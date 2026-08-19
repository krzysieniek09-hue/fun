package com.playwave.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Full-screen WebView shell around the Playwave web app bundled in
 * assets/www. The app itself handles connecting to the music server
 * (ZimaBoard) over the local network.
 */
public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage: saved server URL, likes
        s.setAllowFileAccess(true);
        // The page lives on file:// but streams from http://<server> on the
        // LAN; universal access keeps those fetches from being blocked.
        s.setAllowUniversalAccessFromFileURLs(true);

        // Let audio started by the page keep controlling playback (API 17+).
        try {
            WebSettings.class
                .getMethod("setMediaPlaybackRequiresUserGesture", boolean.class)
                .invoke(s, false);
        } catch (Exception ignored) {
        }

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());

        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
