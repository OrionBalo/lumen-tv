package tv.lumen.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.media3.ui.PlayerView;

/**
 * Lumen TV for Fire TV and Android.
 * A full-screen WebView that runs the Lumen app (assets/index.html) with the
 * permissions an IPTV player needs, and forwards the remote's special keys.
 */
public class MainActivity extends Activity {
    private static final int FILE_REQUEST = 42;
    private static final String APP_VERSION = "1.0.0";
    private WebView web;
    private NativePlayer nativePlayer;
    private ValueCallback<Uri[]> fileCallback;
    private String defaultUserAgent;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Layout: the native video player at the back, the Lumen interface (WebView) on top.
        // The WebView is transparent, so in full screen the video shows through it.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 7, 12));
        PlayerView playerView = new PlayerView(this);
        root.addView(playerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // The app is loaded from the APK itself, so it may talk to any IPTV server
        // (browsers normally block this for security; here the app is trusted).
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        defaultUserAgent = s.getUserAgentString() + " LumenTV/" + APP_VERSION;
        s.setUserAgentString(defaultUserAgent);

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                // Hides the grey "play" placeholder Android draws on videos.
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("*/*");
                    startActivityForResult(Intent.createChooser(pick, "Choose a file"), FILE_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        nativePlayer = new NativePlayer(this, playerView, defaultUserAgent, new NativePlayer.Emitter() {
            @Override
            public void emit(String type, String json) {
                if (web != null) web.evaluateJavascript("window.__lumenNative && window.__lumenNative('" + type + "', " + json + ")", null);
            }
        });
        web.addJavascriptInterface(new Bridge(), "LumenAndroid");
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    /** Functions the web app can call: window.LumenAndroid.xxx() */
    private class Bridge {
        @JavascriptInterface
        public boolean isTV() {
            UiModeManager ui = (UiModeManager) getSystemService(UI_MODE_SERVICE);
            if (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
            PackageManager pm = getPackageManager();
            return pm.hasSystemFeature("amazon.hardware.fire_tv")
                    || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finishAndRemoveTask();
                }
            });
        }

        @JavascriptInterface
        public void setOrientation(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setRequestedOrientation("landscape".equals(mode)
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                }
            });
        }

        @JavascriptInterface
        public void setUserAgent(final String ua) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    web.getSettings().setUserAgentString(ua == null || ua.trim().isEmpty() ? defaultUserAgent : ua.trim());
                }
            });
        }

        @JavascriptInterface
        public String version() {
            return APP_VERSION;
        }

        // ---- native (Android) video player for full screen ----
        @JavascriptInterface
        public boolean nativeAvailable() {
            return true;
        }

        @JavascriptInterface
        public void nativePlay(final String json) {
            onUi(new Runnable() { @Override public void run() { nativePlayer.play(json); web.requestFocus(); } });
        }

        @JavascriptInterface
        public void nativeStop() {
            onUi(new Runnable() { @Override public void run() { nativePlayer.stop(); } });
        }

        @JavascriptInterface
        public void nativePause(final boolean paused) {
            onUi(new Runnable() { @Override public void run() { nativePlayer.pause(paused); } });
        }

        @JavascriptInterface
        public void nativeSeek(final double seconds) {
            onUi(new Runnable() { @Override public void run() { nativePlayer.seek(seconds); } });
        }

        @JavascriptInterface
        public void nativeVolume(final double volume) {
            onUi(new Runnable() { @Override public void run() { nativePlayer.setVolume((float) volume); } });
        }

        @JavascriptInterface
        public void nativeSetAudio(final int index) {
            onUi(new Runnable() { @Override public void run() { nativePlayer.setAudioTrack(index); } });
        }

        @JavascriptInterface
        public void nativeSetSub(final int index) {
            onUi(new Runnable() { @Override public void run() { nativePlayer.setTextTrack(index); } });
        }

        @JavascriptInterface
        public void nativeLayout(final int x, final int y, final int w, final int h) {
            onUi(new Runnable() { @Override public void run() { nativePlayer.layout(x, y, w, h); } });
        }

        @JavascriptInterface
        public void nativeSetFit(final String mode) {
            onUi(new Runnable() { @Override public void run() { nativePlayer.setFit(mode); } });
        }
    }

    private void onUi(final Runnable r) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null && nativePlayer != null) r.run();
            }
        });
    }

    /** Remote buttons that a web page can't see on its own are passed to the app here. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String name = null;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                name = "back";
                break;
            case KeyEvent.KEYCODE_MENU:
                name = "menu";
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                name = "playpause";
                break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                name = "ff";
                break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                name = "rw";
                break;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                name = "chup";
                break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                name = "chdown";
                break;
            case KeyEvent.KEYCODE_GUIDE:
                name = "kg";
                break;
            case KeyEvent.KEYCODE_INFO:
                name = "ki";
                break;
            default:
                break;
        }
        if (name == null) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && (event.getRepeatCount() == 0 || !"back".equals(name))) {
            web.evaluateJavascript("window.__lumenKey && window.__lumenKey('" + name + "')", null);
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.evaluateJavascript("window.__lumenLifecycle && window.__lumenLifecycle('pause')", null);
            web.onPause();
        }
        if (nativePlayer != null) {
            nativePlayer.pause(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.__lumenLifecycle && window.__lumenLifecycle('resume')", null);
        }
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        if (nativePlayer != null) {
            nativePlayer.release();
            nativePlayer = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (web == null) return;
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
