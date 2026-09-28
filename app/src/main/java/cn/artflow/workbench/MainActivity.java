package cn.artflow.workbench;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final String START_URL = "file:///android_asset/index.html";
    private static final String UPDATE_MANIFEST_URL =
            "https://github.com/xuebing0229/artflow-workbench/releases/latest/download/latest.json";
    private static final String UPDATE_APK_URL_PREFIX =
            "https://github.com/xuebing0229/artflow-workbench/releases/download/";
    private static final String UPDATE_PREFS = "artflow_update";
    private static final String PREF_DOWNLOAD_ID = "download_id";
    private static final String PREF_SHA256 = "sha256";
    private static final String PREF_VERSION = "version";
    private static final String INJECT_SCRIPT =
            "(function(){try{document.documentElement.classList.add('android-app-webview');}catch(e){}" +
            "if(window.__afDl)return;window.__afDl=1;" +
            "document.addEventListener('click',function(e){var t=e.target;" +
            "var a=(t&&t.closest)?t.closest('a[download]'):null;if(!a)return;" +
            "var h=a.href||'';if(h.indexOf('blob:')!==0&&h.indexOf('data:')!==0)return;" +
            "e.preventDefault();e.stopPropagation();var n=a.getAttribute('download')||('artflow-export-'+Date.now());" +
            "fetch(h).then(function(r){return r.blob()}).then(function(b){var fr=new FileReader();" +
            "fr.onload=function(){var s=fr.result+'';var p=s.split(',');" +
            "var m=(p[0].match(/data:([^;]+)/)||[0,'application/octet-stream'])[1];" +
            "try{AndroidBridge.saveFile(n,m,p[1]);}catch(err){}};fr.readAsDataURL(b);}).catch(function(){});" +
            "},true);})();";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private long pendingUpdateDownloadId = -1L;
    private String pendingUpdateSha256 = "";
    private String pendingUpdateVersionName = "";
    private Uri pendingInstallUri;
    private boolean verifyingUpdate = false;
    private boolean downloadReceiverRegistered = false;
    private boolean backDispatchInProgress = false;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id == pendingUpdateDownloadId) {
                checkPendingUpdateDownload();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new DownloadBridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(INJECT_SCRIPT, null);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                try {
                    if (view != null) {
                        view.destroy();
                    }
                } catch (Exception ignored) {
                }
                recreate();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(intent, "选择图片"), FILE_CHOOSER_REQUEST);
                return true;
            }
        });

        registerUpdateDownloadReceiver();
        restorePendingUpdateDownload();
        webView.loadUrl(START_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstallUri != null && canInstallPackages()) {
            installDownloadedApk(pendingInstallUri);
            pendingInstallUri = null;
        } else {
            checkPendingUpdateDownload();
        }
    }

    private void registerUpdateDownloadReceiver() {
        if (downloadReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        downloadReceiverRegistered = true;
    }

    private void restorePendingUpdateDownload() {
        android.content.SharedPreferences p = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE);
        pendingUpdateDownloadId = p.getLong(PREF_DOWNLOAD_ID, -1L);
        pendingUpdateSha256 = p.getString(PREF_SHA256, "");
        pendingUpdateVersionName = p.getString(PREF_VERSION, "");
    }

    private void persistPendingUpdateDownload() {
        getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit()
                .putLong(PREF_DOWNLOAD_ID, pendingUpdateDownloadId)
                .putString(PREF_SHA256, pendingUpdateSha256 == null ? "" : pendingUpdateSha256)
                .putString(PREF_VERSION, pendingUpdateVersionName == null ? "" : pendingUpdateVersionName)
                .apply();
    }

    private void clearPendingUpdateDownload() {
        pendingUpdateDownloadId = -1L;
        pendingUpdateSha256 = "";
        pendingUpdateVersionName = "";
        getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit().clear().apply();
    }

    private int currentVersionCode() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                long code = info.getLongVersionCode();
                return code > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) code;
            }
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String currentVersionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void checkForAppUpdate() {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            HttpURLConnection conn = null;
            try {
                URL url = new URL(UPDATE_MANIFEST_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("更新源返回 HTTP " + status);
                }
                StringBuilder body = new StringBuilder();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        body.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                JSONObject manifest = new JSONObject(body.toString());
                int latestCode = manifest.getInt("versionCode");
                String latestName = manifest.optString("versionName", String.valueOf(latestCode));
                String apkUrl = manifest.getString("apkUrl");
                String sha256 = manifest.getString("sha256").toLowerCase(Locale.ROOT);
                String notes = manifest.optString("notes", "");

                if (!apkUrl.startsWith(UPDATE_APK_URL_PREFIX)) {
                    throw new SecurityException("更新地址不在受信任仓库");
                }
                if (!sha256.matches("^[0-9a-f]{64}$")) {
                    throw new SecurityException("更新包校验值格式错误");
                }

                int currentCode = currentVersionCode();
                result.put("ok", true);
                result.put("hasUpdate", latestCode > currentCode);
                result.put("currentVersionCode", currentCode);
                result.put("currentVersionName", currentVersionName());
                result.put("latestVersionCode", latestCode);
                result.put("latestVersionName", latestName);
                result.put("apkUrl", apkUrl);
                result.put("sha256", sha256);
                result.put("notes", notes);
            } catch (Exception e) {
                try {
                    result.put("ok", false);
                    result.put("message", e.getMessage() == null ? "检查更新失败" : e.getMessage());
                    result.put("currentVersionCode", currentVersionCode());
                    result.put("currentVersionName", currentVersionName());
                } catch (Exception ignored) {
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String payload = result.toString();
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(
                            "window.__artflowOnUpdateResult&&window.__artflowOnUpdateResult(" +
                                    JSONObject.quote(payload) + ");",
                            null);
                }
            });
        }, "artflow-update-check").start();
    }

    private void downloadAppUpdate(String apkUrl, String sha256, String versionName) {
        if (apkUrl == null || !apkUrl.startsWith(UPDATE_APK_URL_PREFIX)) {
            Toast.makeText(this, "更新地址不受信任", Toast.LENGTH_LONG).show();
            return;
        }
        String normalizedSha = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        if (!normalizedSha.matches("^[0-9a-f]{64}$")) {
            Toast.makeText(this, "更新包缺少有效校验值", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("系统下载服务不可用");

            String safeVersion = (versionName == null ? "latest" : versionName)
                    .replaceAll("[^0-9A-Za-z._-]", "_");
            String fileName = "artflow-workbench-" + safeVersion + "-" + System.currentTimeMillis() + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("画师工作台 " + (versionName == null ? "更新" : versionName));
            request.setDescription("下载完成后将自动校验并打开安装器");
            request.setMimeType("application/vnd.android.package-archive");
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

            pendingUpdateDownloadId = manager.enqueue(request);
            pendingUpdateSha256 = normalizedSha;
            pendingUpdateVersionName = versionName == null ? "" : versionName;
            persistPendingUpdateDownload();
            Toast.makeText(this, "开始下载更新，完成后会自动打开安装器", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    "下载更新失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void checkPendingUpdateDownload() {
        if (pendingUpdateDownloadId < 0 || verifyingUpdate) return;
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) return;
        try (Cursor c = manager.query(new DownloadManager.Query().setFilterById(pendingUpdateDownloadId))) {
            if (c == null || !c.moveToFirst()) return;
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_FAILED) {
                clearPendingUpdateDownload();
                Toast.makeText(this, "更新包下载失败，请重新拉取", Toast.LENGTH_LONG).show();
                return;
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) return;
        } catch (Exception e) {
            return;
        }
        Uri uri = manager.getUriForDownloadedFile(pendingUpdateDownloadId);
        if (uri != null) verifyAndInstallUpdate(uri);
    }

    private void verifyAndInstallUpdate(Uri uri) {
        if (verifyingUpdate) return;
        verifyingUpdate = true;
        final String expected = pendingUpdateSha256;
        new Thread(() -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("无法读取已下载更新包");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
                }
                byte[] hash = digest.digest();
                StringBuilder actual = new StringBuilder(hash.length * 2);
                for (byte b : hash) actual.append(String.format(Locale.ROOT, "%02x", b));
                if (!actual.toString().equalsIgnoreCase(expected)) {
                    clearPendingUpdateDownload();
                    throw new SecurityException("更新包 SHA-256 校验失败，已拒绝安装");
                }
                runOnUiThread(() -> {
                    verifyingUpdate = false;
                    openInstallerOrPermission(uri);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    verifyingUpdate = false;
                    Toast.makeText(this,
                            e.getMessage() == null ? "更新包校验失败" : e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "artflow-update-verify").start();
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                getPackageManager().canRequestPackageInstalls();
    }

    private void openInstallerOrPermission(Uri uri) {
        if (!canInstallPackages()) {
            pendingInstallUri = uri;
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            Toast.makeText(this, "请允许“安装未知应用”，返回后会继续安装", Toast.LENGTH_LONG).show();
            return;
        }
        installDownloadedApk(uri);
    }

    private void installDownloadedApk(Uri uri) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
            clearPendingUpdateDownload();
        } catch (Exception e) {
            Toast.makeText(this,
                    "无法打开安装器：" + (e.getMessage() == null ? "未知错误" : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) {
            return;
        }

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                results = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    results[i] = clipData.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (backDispatchInProgress) return;
        if (webView == null) {
            super.onBackPressed();
            return;
        }

        backDispatchInProgress = true;
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__artflowHandleBack&&window.__artflowHandleBack());}catch(e){return false;}})();",
                value -> {
                    backDispatchInProgress = false;
                    boolean handled = "true".equalsIgnoreCase(value);
                    if (handled) return;

                    // 这个 App 是单页 WebView；只有网页明确表示“已经没有上一级”时才退出。
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        MainActivity.super.onBackPressed();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        if (downloadReceiverRegistered) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiverRegistered = false;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    public static class DownloadBridge {
        private final MainActivity activity;
        private final Context context;

        DownloadBridge(MainActivity activity) {
            this.activity = activity;
            this.context = activity.getApplicationContext();
        }

        @JavascriptInterface
        public String getVersionName() {
            return activity.currentVersionName();
        }

        @JavascriptInterface
        public int getVersionCode() {
            return activity.currentVersionCode();
        }

        @JavascriptInterface
        public void checkForUpdate() {
            activity.checkForAppUpdate();
        }

        @JavascriptInterface
        public void downloadUpdate(String apkUrl, String sha256, String versionName) {
            activity.runOnUiThread(() ->
                    activity.downloadAppUpdate(apkUrl, sha256, versionName));
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            final String safeName = (name == null || name.trim().isEmpty()) ? "artflow-export.bin" : name.trim();
            final String safeMime = (mime == null || mime.trim().isEmpty()) ? "application/octet-stream" : mime.trim();
            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    writeDownload(safeName, safeMime, bytes);
                    activity.runOnUiThread(() ->
                            Toast.makeText(context, "已保存到下载目录", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    activity.runOnUiThread(() -> Toast.makeText(
                            context,
                            "保存失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()),
                            Toast.LENGTH_LONG).show());
                }
            }, "artflow-download").start();
        }

        private void writeDownload(String name, String mime, byte[] bytes) throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("无法创建下载文件");
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("无法打开下载文件");
                    out.write(bytes);
                    out.flush();
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建下载目录");
                }
                File file = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                    out.flush();
                }
                Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file));
                context.sendBroadcast(scan);
            }
        }
    }

}
