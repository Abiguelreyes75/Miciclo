package com.cerquita.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 7;
    private WebView web;
    private LanCore core;
    private ValueCallback<Uri[]> pendingFile;
    private PermissionRequest pendingMedia;
    private static final String HOST = "cerquita.local";

    /** Lo que la pantalla puede pedirle a la app. */
    public class Bridge {
        @JavascriptInterface public String id() { return core.id(); }
        @JavascriptInterface public String pub() { return core.pub(); }
        @JavascriptInterface public String name() { return core.name(); }
        @JavascriptInterface public void setName(String n) { core.setName(n); }
        @JavascriptInterface public String peers() { return core.peersJson(); }
        @JavascriptInterface public String peerPub(String id) { return core.peerPub(id); }
        @JavascriptInterface public String drain() { return core.drain(); }
        @JavascriptInterface public void send(String peer, String id, int kind, String payload) { core.send(peer, id, kind, payload); }
        @JavascriptInterface public String kvGet(String k) { return core.kvGet(k); }
        @JavascriptInterface public boolean kvSet(String k, String v) { return core.kvSet(k, v); }
        @JavascriptInterface public boolean addManual(String ip) { return core.addManual(ip); }
        @JavascriptInterface public String safetyCode(String id) { return core.safetyCode(id); }
        @JavascriptInterface public boolean introduce(String id, String pub, String n) { return core.introduce(id, pub, n); }

        @JavascriptInterface
        public String localIps() {
            List<String> ips = core.localIps();
            StringBuilder s = new StringBuilder("[");
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) s.append(',');
                s.append(LanCore.json(ips.get(i)));
            }
            return s.append(']').toString();
        }

        @JavascriptInterface
        public void callAudio(final boolean active, final boolean speaker) {
            runOnUiThread(() -> {
                AudioManager a = (AudioManager) getSystemService(AUDIO_SERVICE);
                a.setMode(active ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
                a.setSpeakerphoneOn(active && speaker);
            });
        }

        @JavascriptInterface
        public void requestNotifications() {
            if (Hub.tiramisuOrLater()) {
                runOnUiThread(() -> requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1));
            }
        }

        @JavascriptInterface
        public void setBars(final String top, final String bottom, final boolean light) {
            runOnUiThread(() -> {
                Window w = getWindow();
                w.setStatusBarColor(Color.parseColor(top));
                w.setNavigationBarColor(Color.parseColor(bottom));
                w.getDecorView().setSystemUiVisibility(light
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            core = Hub.get(this);
            core.start();
        } catch (Exception e) {
            finish();
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(0);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // la app nunca navega fuera de su propia página
            }

            /** Sirve la página desde la app con dirección https: el micrófono y la cámara solo funcionan así. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost()) && "/index.html".equals(u.getPath())) {
                    try {
                        return new WebResourceResponse("text/html", "utf-8", getAssets().open("index.html"));
                    } catch (IOException e) {
                        return null;
                    }
                }
                return new WebResourceResponse("text/plain", "utf-8", null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> askMedia(request));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFile != null) pendingFile.onReceiveValue(null);
                pendingFile = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                startActivityForResult(pick, PICK_IMAGE);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("https://" + HOST + "/index.html");
        startForegroundService(new Intent(this, LanService.class));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IMAGE && pendingFile != null) {
            Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
            pendingFile.onReceiveValue(uri == null ? null : new Uri[] {uri});
            pendingFile = null;
        }
    }

    private void askMedia(PermissionRequest request) {
        List<String> need = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) need.add(Manifest.permission.RECORD_AUDIO);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) need.add(Manifest.permission.CAMERA);
        }
        List<String> missing = new ArrayList<>();
        for (String n : need) {
            if (checkSelfPermission(n) != PackageManager.PERMISSION_GRANTED) missing.add(n);
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
        } else {
            pendingMedia = request;
            requestPermissions(missing.toArray(new String[0]), 2);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != 2 || pendingMedia == null) return;
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (ok) pendingMedia.grant(pendingMedia.getResources());
        else pendingMedia.deny();
        pendingMedia = null;
    }

    private void callJs(final String js) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Hub.visible = true;
        Hub.onChange = () -> callJs("window.onLan&&window.onLan()");
        callJs("window.onAppResume&&window.onAppResume()");
    }

    @Override
    protected void onPause() {
        Hub.visible = false;
        callJs("window.onAppPause&&window.onAppPause()");
        Hub.onChange = null;
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack()", handled -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
        });
    }
}
