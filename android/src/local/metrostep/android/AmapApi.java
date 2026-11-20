package local.metrostep.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONException;
import org.json.JSONObject;

final class AmapApi {
    private static final String ALIAS = "metro-amap-key-v1";
    private static final int IV_BYTES = 12;
    private static final Pattern KEY = Pattern.compile("[a-fA-F0-9]{32}");
    private static final Pattern COORDINATES = Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d{1,6})?),(-?\\d{1,2}(?:\\.\\d{1,6})?)");
    private final SharedPreferences storage;
    private volatile String key;

    AmapApi(Context context) {
        storage = context.getSharedPreferences("metro_amap", Context.MODE_PRIVATE);
        key = restore();
    }

    JSONObject handle(String path, JSONObject body) throws JSONException {
        switch (path) {
            case "/api/status":
                return new JSONObject().put("configured", !key.isEmpty());
            case "/api/key":
                if (body.optBoolean("clear")) {
                    if (!storage.edit().remove("credential").commit()) {
                        throw new IllegalArgumentException("无法清除本机 Key，请重试。");
                    }
                    key = "";
                    return new JSONObject().put("configured", false);
                }
                String candidate = body.optString("key");
                if (!KEY.matcher(candidate).matches()) {
                    throw new IllegalArgumentException("请输入 32 位高德 Web 服务 Key。");
                }
                request(endpoint("v3", "assistant", "inputtips")
                        .appendQueryParameter("key", candidate)
                        .appendQueryParameter("keywords", "会展中心地铁站")
                        .appendQueryParameter("city", "0755")
                        .appendQueryParameter("citylimit", "true"));
                key = candidate;
                return new JSONObject().put("configured", true).put("persisted", persist(candidate));
            case "/api/route":
                String currentKey = key;
                if (currentKey.isEmpty()) {
                    throw new IllegalArgumentException("请先在高德设置中连接 Web 服务 Key。");
                }
                String origin = coordinates(body.optString("origin"));
                String destination = coordinates(body.optString("destination"));
                if (origin.equals(destination)) {
                    throw new IllegalArgumentException("起点和终点相同，请更换其中一个地点。");
                }
                return request(endpoint("v3", "direction", "transit", "integrated")
                        .appendQueryParameter("key", currentKey)
                        .appendQueryParameter("origin", origin)
                        .appendQueryParameter("destination", destination)
                        .appendQueryParameter("city", "0755")
                        .appendQueryParameter("strategy", "2".equals(body.optString("strategy")) ? "2" : "0")
                        .appendQueryParameter("extensions", "all"));
            default:
                throw new IllegalArgumentException("请求地址无效。");
        }
    }

    private static synchronized SecretKey encryptionKey(boolean create)
            throws GeneralSecurityException, IOException {
        KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
        keystore.load(null);
        if (keystore.containsAlias(ALIAS)) return (SecretKey) keystore.getKey(ALIAS, null);
        if (!create) throw new GeneralSecurityException("Key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private String restore() {
        String saved = storage.getString("credential", "");
        if (saved.isEmpty()) return "";
        try {
            byte[] encoded = Base64.decode(saved, Base64.NO_WRAP);
            if (encoded.length != IV_BYTES + 32 + 16) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false),
                    new GCMParameterSpec(128, encoded, 0, IV_BYTES));
            String candidate = new String(cipher.doFinal(encoded, IV_BYTES, encoded.length - IV_BYTES),
                    StandardCharsets.US_ASCII);
            return KEY.matcher(candidate).matches() ? candidate : "";
        } catch (GeneralSecurityException | IOException | IllegalArgumentException error) {
            return "";
        }
    }

    private boolean persist(String candidate) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true));
            byte[] iv = cipher.getIV();
            if (iv.length != IV_BYTES) return false;
            byte[] ciphertext = cipher.doFinal(candidate.getBytes(StandardCharsets.US_ASCII));
            byte[] encoded = Arrays.copyOf(iv, iv.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, encoded, iv.length, ciphertext.length);
            return storage.edit().putString("credential", Base64.encodeToString(encoded, Base64.NO_WRAP)).commit();
        } catch (GeneralSecurityException | IOException error) {
            return false;
        }
    }

    private static Uri.Builder endpoint(String... path) {
        Uri.Builder uri = new Uri.Builder().scheme("https").authority("restapi.amap.com");
        for (String segment : path) uri.appendPath(segment);
        return uri;
    }

    private static String coordinates(String text) {
        Matcher match = COORDINATES.matcher(text);
        if (match.matches()) {
            double lon = Double.parseDouble(match.group(1));
            double lat = Double.parseDouble(match.group(2));
            if (lon >= 113.65 && lon <= 114.7 && lat >= 22.38 && lat <= 22.9) return text;
        }
        throw new IllegalArgumentException("请输入深圳范围内的起点和终点。");
    }

    private static JSONObject request(Uri.Builder uri) {
        HttpsURLConnection connection = null;
        JSONObject data;
        try {
            connection = (HttpsURLConnection) new URL(uri.build().toString()).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != 200) throw new IOException("HTTP error");
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream contents = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (contents.size() + count > 4_000_000) throw new IOException("Response too large");
                    contents.write(buffer, 0, count);
                }
                data = new JSONObject(new String(contents.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException error) {
            throw new IllegalArgumentException("暂时无法连接高德，请检查网络后重试。");
        } finally {
            if (connection != null) connection.disconnect();
        }
        if ("1".equals(data.optString("status"))) return data;
        String code = data.optString("infocode");
        if (code.matches("10001|10005|10006|10007|10009|10012")) {
            throw new IllegalArgumentException("高德拒绝了请求（" + code + "）。请检查 Web 服务 Key 及其调用限制。");
        }
        throw new IllegalArgumentException("高德请求失败（" + (code.matches("\\d+") ? code : "未知错误") + "）。");
    }
}
