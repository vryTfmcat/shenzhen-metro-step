package local.metrostep.android;

import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

final class AmapApi {
    private static final Pattern KEY = Pattern.compile("[a-fA-F0-9]{32}");
    private static final Pattern COORDINATES = Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d{1,6})?),(-?\\d{1,2}(?:\\.\\d{1,6})?)");
    private volatile String key = "";

    JSONObject handle(String path, JSONObject body) throws JSONException {
        switch (path) {
            case "/api/status":
                return new JSONObject().put("configured", !key.isEmpty());
            case "/api/key":
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
                return new JSONObject().put("configured", true);
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
