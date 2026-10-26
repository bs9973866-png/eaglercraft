package net.novablock.app;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String OFFICIAL_PLAY = "https://dev.eaglercraft.com/play?version=";
    private static final String OFFICIAL_DOWNLOADS = "https://dev.eaglercraft.com/downloads";
    private static final int REQUEST_LOCAL_RUNTIME = 7101;
    private static final int REQUEST_WEB_FILE = 7102;
    private static final long MAX_RUNTIME_BYTES = 150L * 1024L * 1024L;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean inGameMode = false;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        applyImmersiveMode();

        web = new WebView(this);
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
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
        inGameMode = true;
        if (web != null) {
            web.removeJavascriptInterface("NovaBlock");
            web.loadUrl(OFFICIAL_PLAY + Uri.encode(version));
        }
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
