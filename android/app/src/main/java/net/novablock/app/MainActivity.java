package net.novablock.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String OFFICIAL_PLAY = "https://dev.eaglercraft.com/play?version=";
    private static final String OFFICIAL_DOWNLOADS = "https://dev.eaglercraft.com/downloads";
    // The official site publishes this offline client. It is downloaded on first launch and kept private to the app.
    private static final String EAGLERCRAFT_188_DOWNLOAD = "https://cdn.eaglercraft.ru/dl/1.8.8/EaglercraftX_1.8_u53_Offline_Signed.zip";
    private static final String APP_ASSET_ORIGIN = "https://appassets.androidplatform.net";
    private static final long MAX_RUNTIME_ARCHIVE_BYTES = 50L * 1024L * 1024L;
    private static final long MAX_EXPANDED_RUNTIME_BYTES = 250L * 1024L * 1024L;
    private static final int REQUEST_LOCAL_RUNTIME = 7101;
    private static final int REQUEST_WEB_FILE = 7102;
    private static final long MAX_RUNTIME_BYTES = 150L * 1024L * 1024L;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private ProgressDialog runtimeProgress;
    private boolean runtimeDownloadRunning = false;
    private boolean inGameMode = false;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        applyImmersiveMode();

        web = new WebView(this);
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/runtime/", new RuntimePathHandler(new File(getFilesDir(), "runtime")))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (isHomeUrl(url)) {
                    view.addJavascriptInterface(new LauncherBridge(), "NovaBlock");
                } else {
                    // Never expose the native bridge to remote game pages or imported HTML.
                    view.removeJavascriptInterface("NovaBlock");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Other catalog versions stay in this WebView, but skip the official landing screen.
                if (inGameMode && isOfficialPlayUrl(url)) {
                    view.evaluateJavascript(buildAutoLaunchScript(), null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if ("https".equals(scheme) || "http".equals(scheme) || "file".equals(scheme)
                        || "about".equals(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    toast("Не удалось открыть эту ссылку");
                }
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Never bypass certificate errors.
                handler.cancel();
                toast("Ошибка защищённого соединения. Проверь дату и сеть устройства.");
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame() && !isHomeUrl(request.getUrl().toString())) {
                    toast("Страница не загрузилась. Проверь подключение к интернету и нажми «Назад».");
                }
            }
        });

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true); // Needed for a user-selected self-contained offline HTML client.
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFF090812);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "Выбери файл"), REQUEST_WEB_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    toast("На устройстве нет файлового менеджера");
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new LauncherBridge(), "NovaBlock");
        setContentView(web);
        if (state == null) {
            web.loadUrl(HOME_URL);
        } else {
            web.restoreState(state);
            if (web.getUrl() == null) web.loadUrl(HOME_URL);
        }
    }

    private void applyImmersiveMode() {
        getWindow().setFlags(1024, 1024);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    private boolean isHomeUrl(String url) {
        return url != null && (url.equals(HOME_URL) || url.startsWith(HOME_URL + "?"));
    }

    private void launchOnlineVersion(String version) {
        if (!Arrays.asList("1.8.8", "1.12.2", "26.2", "26.3", "1.5.2").contains(version)) {
            toast("Эта версия отсутствует в проверенном каталоге Eaglercraft");
            return;
        }
        if ("1.8.8".equals(version)) {
            launchEmbedded188();
            return;
        }

        // Keep other catalog versions inside this WebView, not in the external browser.
        inGameMode = true;
        if (web != null) {
            web.removeJavascriptInterface("NovaBlock");
            web.loadUrl(OFFICIAL_PLAY + Uri.encode(version));
        }
    }

    private boolean isOfficialPlayUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        String path = uri.getPath();
        return host != null && path != null
                && (host.equalsIgnoreCase("dev.eaglercraft.com") || host.equalsIgnoreCase("eaglercraft.com"))
                && path.toLowerCase(Locale.ROOT).contains("/play");
    }

    private String buildAutoLaunchScript() {
        return "(function(){"
                + "if(window.__novaBlockAutoLaunchInstalled)return;"
                + "window.__novaBlockAutoLaunchInstalled=true;"
                + "var tries=0;"
                + "var timer=setInterval(function(){"
                + "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=button]'));"
                + "var target=nodes.find(function(el){"
                + "var s=(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim().toLowerCase();"
                + "return s==='launch game'||s==='запустить игру'||s==='играть'||s.indexOf('launch game')>=0||s.indexOf('запустить игру')>=0;"
                + "});"
                + "if(target){clearInterval(timer);target.click();}"
                + "else if(++tries>100){clearInterval(timer);}"
                + "},250);"
                + "})();";
    }

    private void launchEmbedded188() {
        File entry = findCachedRuntimeEntry();
        if (entry != null) {
            openEmbeddedRuntime(entry);
            return;
        }
        if (runtimeDownloadRunning) {
            toast("Клиент уже загружается…");
            return;
        }

        runtimeDownloadRunning = true;
        runtimeProgress = new ProgressDialog(this);
        runtimeProgress.setTitle("Подготовка Eaglercraft 1.8.8");
        runtimeProgress.setMessage("Первый запуск: загружаем автономный клиент. Потом он будет открываться без загрузки.");
        runtimeProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        runtimeProgress.setMax(100);
        runtimeProgress.setIndeterminate(true);
        runtimeProgress.setCancelable(false);
        runtimeProgress.show();

        new Thread(() -> {
            File archive = new File(getCacheDir(), "eaglercraft_1.8.8.zip.part");
            File runtimeDir = new File(getFilesDir(), "runtime/eaglercraft/1.8.8");
            File staging = new File(runtimeDir.getParentFile(), "1.8.8.tmp");
            try {
                downloadRuntimeArchive(archive);
                runOnUiThread(() -> {
                    if (runtimeProgress != null) {
                        runtimeProgress.setIndeterminate(true);
                        runtimeProgress.setMessage("Распаковываем и проверяем клиент…");
                    }
                });
                extractRuntimeArchive(archive, runtimeDir, staging);
                archive.delete();
                File installed = findCachedRuntimeEntry();
                if (installed == null) throw new IOException("Offline HTML entry point not found after extraction");
                runOnUiThread(() -> {
                    dismissRuntimeProgress();
                    openEmbeddedRuntime(installed);
                    toast("Eaglercraft 1.8.8 готов. Сенсорное управление включается в игре автоматически.");
                });
            } catch (Exception error) {
                android.util.Log.e("NovaBlock", "Could not prepare Eaglercraft 1.8.8 runtime", error);
                archive.delete();
                deleteRecursively(staging);
                runOnUiThread(() -> {
                    dismissRuntimeProgress();
                    runtimeDownloadRunning = false;
                    toast("Не удалось подготовить клиент. Проверь интернет и повтори запуск.");
                });
            }
        }, "NovaBlock-RuntimeDownload").start();
    }

    private void openEmbeddedRuntime(File entry) {
        try {
            String runtimeRoot = new File(getFilesDir(), "runtime").getCanonicalPath();
            String entryPath = entry.getCanonicalPath();
            if (!entryPath.startsWith(runtimeRoot + File.separator)) {
                throw new IOException("Runtime entry is outside the runtime directory");
            }
            String relative = entryPath.substring(runtimeRoot.length() + 1).replace(File.separatorChar, '/');
            inGameMode = true;
            if (web != null) {
                web.removeJavascriptInterface("NovaBlock");
                web.loadUrl(APP_ASSET_ORIGIN + "/runtime/" + Uri.encode(relative, "/"));
            }
        } catch (IOException error) {
            android.util.Log.e("NovaBlock", "Invalid embedded runtime entry", error);
            toast("Не удалось открыть локальный клиент. Повтори импорт или загрузку.");
        }
    }

    private File findCachedRuntimeEntry() {
        File dir = new File(getFilesDir(), "runtime/eaglercraft/1.8.8");
        File marker = new File(dir, ".novablock-entrypoint");
        if (marker.isFile()) {
            try (InputStream input = new FileInputStream(marker)) {
                byte[] bytes = new byte[(int) Math.min(marker.length(), 4096)];
                int count = input.read(bytes);
                if (count > 0) {
                    String relative = new String(bytes, 0, count, "UTF-8").trim();
                    File candidate = new File(dir, relative).getCanonicalFile();
                    String canonicalDir = dir.getCanonicalPath();
                    if (candidate.getPath().startsWith(canonicalDir + File.separator)
                            && candidate.isFile() && candidate.getName().toLowerCase(Locale.ROOT).endsWith(".html")) {
                        return candidate;
                    }
                }
            } catch (Exception error) {
                android.util.Log.w("NovaBlock", "Could not read cached runtime entry", error);
            }
        }
        return null;
    }

    private void downloadRuntimeArchive(File destination) throws IOException {
        HttpURLConnection connection = null;
        int lastPercent = -1;
        try {
            connection = (HttpURLConnection) new URL(EAGLERCRAFT_188_DOWNLOAD).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(45000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NovaBlock/0.4.0 Android");
            connection.connect();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Runtime download HTTP status " + status);
            long total = connection.getContentLengthLong();
            if (total > MAX_RUNTIME_ARCHIVE_BYTES) throw new IOException("Runtime archive exceeds size limit");

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    downloaded += read;
                    if (downloaded > MAX_RUNTIME_ARCHIVE_BYTES) throw new IOException("Runtime archive exceeds size limit");
                    output.write(buffer, 0, read);
                    if (total > 0) {
                        int percent = (int) Math.min(95L, downloaded * 95L / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int progress = percent;
                            runOnUiThread(() -> {
                                if (runtimeProgress != null) {
                                    runtimeProgress.setIndeterminate(false);
                                    runtimeProgress.setProgress(progress);
                                }
                            });
                        }
                    }
                }
            }
            if (!destination.isFile() || destination.length() < 1024) {
                throw new IOException("Runtime archive is empty or incomplete");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void extractRuntimeArchive(File archive, File runtimeDir, File staging) throws IOException {
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) throw new IOException("Cannot create runtime staging directory");
        String canonicalStaging = staging.getCanonicalPath();
        long expanded = 0;
        int entries = 0;

        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 10000) throw new IOException("Too many archive entries");
                String name = entry.getName().replace('\\', '/');
                if (name.length() == 0 || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
                    throw new IOException("Unsafe archive path");
                }
                for (String part : name.split("/")) {
                    if ("..".equals(part)) throw new IOException("Unsafe archive path");
                }

                File target = new File(staging, name).getCanonicalFile();
                if (!target.getPath().startsWith(canonicalStaging + File.separator)) {
                    throw new IOException("Archive entry escapes the runtime directory");
                }
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Cannot create runtime subdirectory");
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Cannot create runtime subdirectory");
                    }
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            expanded += read;
                            if (expanded > MAX_EXPANDED_RUNTIME_BYTES) throw new IOException("Expanded runtime exceeds size limit");
                            output.write(buffer, 0, read);
                        }
                    }
                }
                input.closeEntry();
            }
        }

        File html = findBestHtml(staging);
        if (html == null) throw new IOException("No HTML client found in runtime archive");
        String relative = html.getCanonicalPath().substring(canonicalStaging.length() + 1)
                .replace(File.separatorChar, '/');

        deleteRecursively(runtimeDir);
        File parent = runtimeDir.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create runtime directory");
        }
        if (!staging.renameTo(runtimeDir)) throw new IOException("Could not install runtime files");
        File marker = new File(runtimeDir, ".novablock-entrypoint");
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(relative.getBytes("UTF-8"));
            output.flush();
        }
    }

    private File findBestHtml(File root) {
        List<File> candidates = new ArrayList<>();
        collectHtmlFiles(root, candidates);
        if (candidates.isEmpty()) return null;
        Collections.sort(candidates, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                int a = htmlEntryScore(left.getName());
                int b = htmlEntryScore(right.getName());
                if (a != b) return Integer.compare(a, b);
                return Long.compare(right.length(), left.length());
            }
        });
        return candidates.get(0);
    }

    private int htmlEntryScore(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("readme") || lower.contains("license") || lower.contains("credits")) return 100;
        if (lower.equals("offline_download_version.html")) return 0;
        if (lower.endsWith(".html") && lower.contains("offline")) return 1;
        if (lower.equals("index.html")) return 2;
        return 3;
    }

    private void collectHtmlFiles(File directory, List<File> candidates) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectHtmlFiles(child, candidates);
            else if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".html")
                    && htmlEntryScore(child.getName()) < 100) candidates.add(child);
        }
    }

    private void dismissRuntimeProgress() {
        if (runtimeProgress != null) {
            try { runtimeProgress.dismiss(); } catch (Exception ignored) {}
            runtimeProgress = null;
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) android.util.Log.w("NovaBlock", "Could not delete " + file.getAbsolutePath());
    }

    private void openOfficialDownloads() {
        inGameMode = true;
        if (web != null) {
            web.removeJavascriptInterface("NovaBlock");
            web.loadUrl(OFFICIAL_DOWNLOADS);
        }
    }

    private void chooseLocalRuntime() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(intent, "Выбери автономный HTML-клиент"),
                    REQUEST_LOCAL_RUNTIME);
        } catch (ActivityNotFoundException e) {
            toast("На устройстве нет файлового менеджера");
        }
    }

    private void importLocalRuntime(Uri source) {
        if (source == null) {
            toast("Файл не выбран");
            return;
        }

        String displayName = getDisplayName(source);
        String mime = getContentResolver().getType(source);
        boolean htmlName = displayName != null
                && (displayName.toLowerCase().endsWith(".html") || displayName.toLowerCase().endsWith(".htm"));
        boolean htmlMime = mime != null && mime.toLowerCase().contains("html");
        if (!htmlName && !htmlMime) {
            toast("Выбери файл HTML. ZIP сначала нужно распаковать.");
            return;
        }

        File temp = new File(getFilesDir(), "offline-client.html.tmp");
        File target = new File(getFilesDir(), "offline-client.html");
        long copied = 0;
        try (InputStream raw = getContentResolver().openInputStream(source)) {
            if (raw == null) throw new IOException("Empty input stream");
            try (BufferedInputStream input = new BufferedInputStream(raw);
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copied += read;
                    if (copied > MAX_RUNTIME_BYTES) {
                        throw new IOException("HTML client is over the 150 MB limit");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (copied < 128) throw new IOException("File is too small to be a playable HTML client");
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace old runtime");
            if (!temp.renameTo(target)) throw new IOException("Cannot save runtime");
            inGameMode = true;
            web.removeJavascriptInterface("NovaBlock");
            web.loadUrl(Uri.fromFile(target).toString());
            toast("Офлайн-клиент импортирован");
        } catch (Exception e) {
            // Keep the original error out of the UI, but leave a useful message in logcat.
            android.util.Log.e("NovaBlock", "Could not import offline HTML client", e);
            if (temp.exists()) temp.delete();
            toast("Не удалось импортировать HTML. Проверь файл и свободную память.");
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOCAL_RUNTIME) {
            if (resultCode == RESULT_OK && data != null) {
                importLocalRuntime(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_WEB_FILE) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        result = new Uri[count];
                        for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    }
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (inGameMode && web != null) {
            inGameMode = false;
            web.addJavascriptInterface(new LauncherBridge(), "NovaBlock");
            web.loadUrl(HOME_URL);
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        applyImmersiveMode();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (web != null) web.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    private final class RuntimePathHandler implements WebViewAssetLoader.PathHandler {
        private final File root;

        RuntimePathHandler(File root) {
            this.root = root;
        }

        @Override
        public WebResourceResponse handle(String path) {
            try {
                File canonicalRoot = root.getCanonicalFile();
                File file = new File(root, path).getCanonicalFile();
                if (!file.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                    return textResponse(403, "Forbidden");
                }
                if (!file.isFile()) return textResponse(404, "Not Found");

                String lower = file.getName().toLowerCase(Locale.ROOT);
                String mime = "application/octet-stream";
                if (lower.endsWith(".wasm")) mime = "application/wasm";
                else if (lower.endsWith(".js") || lower.endsWith(".mjs")) mime = "application/javascript";
                else {
                    String extension = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : "";
                    String detected = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (detected != null) mime = detected;
                }

                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-cache");
                if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                    // Chromium requires these headers for SharedArrayBuffer and integrated single-player.
                    headers.put("Cross-Origin-Opener-Policy", "same-origin");
                    headers.put("Cross-Origin-Embedder-Policy", "require-corp");
                }
                String encoding = (mime.startsWith("text/") || mime.contains("javascript")
                        || mime.contains("json") || mime.contains("xml")) ? "UTF-8" : null;
                return new WebResourceResponse(mime, encoding, 200, "OK", headers, new FileInputStream(file));
            } catch (Exception error) {
                android.util.Log.e("NovaBlock", "Runtime asset error: " + path, error);
                return textResponse(404, "Not Found");
            }
        }

        private WebResourceResponse textResponse(int status, String message) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/plain", "UTF-8", status,
                    status == 403 ? "Forbidden" : "Not Found", headers,
                    new ByteArrayInputStream(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    private final class LauncherBridge {
        @JavascriptInterface
        public void openOnlineVersion(String version) {
            runOnUiThread(() -> launchOnlineVersion(version));
        }

        @JavascriptInterface
        public void openDownloads() {
            runOnUiThread(MainActivity.this::openOfficialDownloads);
        }

        @JavascriptInterface
        public void pickLocalRuntime() {
            runOnUiThread(MainActivity.this::chooseLocalRuntime);
        }
    }
}
