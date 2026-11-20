package local.metrostep.android;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Rational;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final List<String> EXTERNAL = Arrays.asList(
            "www.szmc.net", "www.amap.com", "console.amap.com", "www.mtrsz.com.cn",
            "jtys.sz.gov.cn", "www.lg.gov.cn", "www.sz.gov.cn");
    private static final int PICK_FILE = 1001;
    private static final int CAPTURE_PHOTO = 1002;
    private static final int SAVE_EXPORT = 1003;
    private static final int NOTIFICATION_REQUEST = 1004;

    private AmapApi amap;
    private WebView browser;
    private ValueCallback<Uri[]> fileCallback;
    private Uri capture;
    private String pendingExport;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        amap = new AmapApi(this);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        CaptureProvider.clearExpired(this);
        browser = new WebView(this);
        browser.setBackgroundColor(Color.rgb(237, 242, 238));
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.addJavascriptInterface(new ExportBridge(), "MetroAndroid");
        browser.setWebViewClient(new LocalClient());
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                completeFile(null);
                fileCallback = callback;
                if (params.isCaptureEnabled() && Arrays.stream(params.getAcceptTypes())
                        .anyMatch(type -> type.startsWith("image/"))) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setItems(new String[]{"拍摄照片", "选择图片"}, (dialog, selected) -> {
                                if (selected == 0) openCamera(params);
                                else openPicker(params);
                            })
                            .setOnCancelListener(dialog -> completeFile(null))
                            .show();
                } else {
                    openPicker(params);
                }
                return true;
            }
        });
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(237, 242, 238));
        frame.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 30) {
            frame.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(frame);
        browser.loadUrl(ORIGIN + "/");
    }

    private static boolean local(Uri url) {
        return "https".equals(url.getScheme()) && HOST.equals(url.getHost())
                && url.getPort() == -1 && url.getUserInfo() == null;
    }

    private final class LocalClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri address = req.getUrl();
            if (!local(address) || !req.getMethod().equals("GET") || address.getQuery() != null) {
                return jsonError(403, "Forbidden", "请求来源无效。");
            }
            String path = address.getPath();
            if (path != null && path.startsWith("/api/") && !req.isForMainFrame()) {
                return apiResponse(path, req.getRequestHeaders());
            }
            try {
                if ("/".equals(path)) return asset("index.html", "text/html");
                if ("/app.js".equals(path)) return asset("app.js", "text/javascript");
                if ("/style.css".equals(path)) return asset("style.css", "text/css");
            } catch (IOException error) {
                return jsonError(500, "Internal Server Error", "应用文件无法读取。");
            }
            return jsonError(404, "Not Found", "页面不存在。");
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (local(url) && "/".equals(url.getPath())) return false;
            if (request.isForMainFrame() && "https".equals(url.getScheme()) && EXTERNAL.contains(url.getHost())) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException error) {
                    Toast.makeText(MainActivity.this, "没有可打开链接的浏览器。", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
    }

    private WebResourceResponse asset(String name, String mime) throws IOException {
        return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers(), getAssets().open(name));
    }

    private WebResourceResponse apiResponse(String path, Map<String, String> requestHeaders) {
        try {
            JSONObject payload = new JSONObject();
            if (!"/api/status".equals(path)) {
                String encoded = requestHeaders.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase("X-Metro-Request"))
                        .map(Map.Entry::getValue).findFirst().orElse("");
                if (encoded.isEmpty() || encoded.length() > 4096) {
                    return jsonError(400, "Bad Request", "请求长度无效。");
                }
                payload = new JSONObject(new String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8));
            }
            return json(200, "OK", amap.handle(path, payload));
        } catch (JSONException error) {
            return jsonError(400, "Bad Request", "请求格式无效。");
        } catch (IllegalArgumentException error) {
            return jsonError(400, "Bad Request", error.getMessage());
        }
    }

    private static Map<String, String> headers() {
        Map<String, String> values = new HashMap<>();
        values.put("Cache-Control", "no-store");
        values.put("X-Content-Type-Options", "nosniff");
        values.put("Referrer-Policy", "no-referrer");
        values.put("Content-Security-Policy", "default-src 'self'; img-src 'self' blob:; "
                + "object-src 'none'; frame-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        return values;
    }

    private static WebResourceResponse jsonError(int code, String reason, String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("error", message);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
        return json(code, reason, payload);
    }

    private static WebResourceResponse json(int code, String reason, JSONObject body) {
        return new WebResourceResponse("application/json", "UTF-8", code, reason, headers(),
                new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private void openPicker(WebChromeClient.FileChooserParams params) {
        try {
            startActivityForResult(params.createIntent(), PICK_FILE);
        } catch (ActivityNotFoundException error) {
            completeFile(null);
            Toast.makeText(this, "没有可用的系统文件选择器。", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCamera(WebChromeClient.FileChooserParams params) {
        try {
            capture = CaptureProvider.create(this);
            Intent photo = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, capture)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            photo.setClipData(ClipData.newRawUri("站牌照片", capture));
            startActivityForResult(photo, CAPTURE_PHOTO);
        } catch (IOException | ActivityNotFoundException error) {
            if (capture != null) CaptureProvider.remove(this, capture);
            capture = null;
            Toast.makeText(this, "相机暂不可用，请选择已有照片。", Toast.LENGTH_SHORT).show();
            openPicker(params);
        }
    }

    private void completeFile(Uri[] files) {
        if (fileCallback == null) return;
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        callback.onReceiveValue(files);
    }

    public final class ExportBridge {
        @JavascriptInterface public void syncTrip(String raw) {
            if (raw != null && raw.length() <= 1024) {
                runOnUiThread(() -> TripReminder.update(MainActivity.this, raw));
            }
        }

        @JavascriptInterface public boolean exactAlarmEnabled() {
            return TripReminder.exactAvailable(MainActivity.this);
        }

        @JavascriptInterface public void previewAlarm() {
            runOnUiThread(() -> {
                if (TripReminder.canNotify(MainActivity.this) && TripReminder.alertChannelEnabled(MainActivity.this)) {
                    TripReminder.preview(MainActivity.this);
                } else {
                    enableRideNotifications(true);
                }
            });
        }

        @JavascriptInterface public void stopAlarm() {
            runOnUiThread(() -> TripReminder.stopRinging(MainActivity.this));
        }

        @JavascriptInterface public boolean notificationsEnabled() {
            return TripReminder.canNotify(MainActivity.this) && TripReminder.alertChannelEnabled(MainActivity.this);
        }

        @JavascriptInterface public void requestRideNotifications() {
            runOnUiThread(() -> enableRideNotifications(false));
        }

        @JavascriptInterface public void openNotificationSettings() {
            runOnUiThread(() -> enableRideNotifications(true));
        }

        @JavascriptInterface public void openExactAlarmSettings() {
            if (Build.VERSION.SDK_INT < 31) return;
            runOnUiThread(() -> openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", getPackageName(), null))));
        }

        @JavascriptInterface public boolean supportsMiniWindow() {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        }

        @JavascriptInterface public void openMiniWindow() {
            runOnUiThread(() -> {
                try {
                    PictureInPictureParams params = new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(16, 9)).build();
                    if (enterPictureInPictureMode(params)) return;
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                    // Disabled by the device or unavailable in the current window mode.
                }
                Toast.makeText(MainActivity.this, "小窗未开启，仍可在通知栏查看行程。", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface public void saveOffline(String html) {
            runOnUiThread(() -> {
                if (pendingExport != null || html == null || html.length() > 1_000_000) {
                    finishExport("error");
                    return;
                }
                pendingExport = html;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/html")
                        .putExtra(Intent.EXTRA_TITLE, "深圳地铁单步导航-离线行程.html");
                try {
                    startActivityForResult(intent, SAVE_EXPORT);
                } catch (ActivityNotFoundException error) {
                    pendingExport = null;
                    finishExport("error");
                }
            });
        }
    }

    private void enableRideNotifications(boolean explicit) {
        if (TripReminder.canNotify(this) && TripReminder.alertChannelEnabled(this)) return;
        boolean asked = getPreferences(MODE_PRIVATE).getBoolean("askedNotifications", false);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && (!asked ||
                (explicit && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        } else if (explicit) {
            openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void openSettings(Intent settings) {
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "请在系统设置里为地铁单步导航调整权限。", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATION_REQUEST) refreshTripPresentation();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshTripPresentation();
    }

    private void refreshTripPresentation() {
        if (browser != null) {
            browser.evaluateJavascript("window.refreshAndroidReminder?.()", null);
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean mini, Configuration configuration) {
        super.onPictureInPictureModeChanged(mini, configuration);
        if (browser != null) {
            browser.evaluateJavascript("window.setMetroMiniMode?.(" + mini + ")", null);
        }
    }

    private void finishExport(String outcome) {
        if (!isDestroyed()) {
            browser.evaluateJavascript("window.finishAndroidExport?.(" + JSONObject.quote(outcome) + ")", null);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILE) {
            Uri[] picked = WebChromeClient.FileChooserParams.parseResult(result, data);
            if (picked != null) {
                // Keep the picker limited to read-granted files from Android's document providers.
                for (Uri uri : picked) {
                    if (!"content".equals(uri.getScheme()) || CaptureProvider.AUTHORITY.equals(uri.getAuthority())) {
                        picked = null;
                        break;
                    }
                }
            }
            completeFile(picked);
        } else if (request == CAPTURE_PHOTO) {
            Uri photo = capture;
            capture = null;
            if (photo == null) return;
            revokeUriPermission(photo, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (result == RESULT_OK && CaptureProvider.hasContent(this, photo)) {
                completeFile(new Uri[]{photo});
            } else {
                CaptureProvider.remove(this, photo);
                completeFile(null);
            }
        } else if (request == SAVE_EXPORT) {
            String html = pendingExport;
            pendingExport = null;
            if (result != RESULT_OK || data == null || data.getData() == null || html == null) {
                finishExport("cancelled");
                return;
            }
            Uri target = data.getData();
            new Thread(() -> writeExport(target, html), "metro-export").start();
        }
    }

    private void writeExport(Uri target, String html) {
        String result = "saved";
        try (OutputStream stream = getContentResolver().openOutputStream(target, "wt")) {
            if (stream == null) throw new IOException("File unavailable");
            stream.write(html.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | SecurityException error) {
            result = "error";
        }
        String outcome = result;
        runOnUiThread(() -> finishExport(outcome));
    }

    @Override public void onBackPressed() {
        browser.evaluateJavascript("window.handleAndroidBack?.() || false", handled -> {
            if (!"true".equals(handled)) super.onBackPressed();
        });
    }

    @Override protected void onDestroy() {
        completeFile(null);
        browser.removeJavascriptInterface("MetroAndroid");
        browser.destroy();
        super.onDestroy();
    }
}
