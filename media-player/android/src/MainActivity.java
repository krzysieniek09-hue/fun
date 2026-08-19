package com.playwave.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;

/**
 * Full-screen WebView shell around the Playwave web app bundled in
 * assets/www. The app itself handles its music sources: bundled demo
 * tracks, files picked from this device, or streaming from a home
 * server (ZimaBoard) over the local network.
 */
public class MainActivity extends Activity {

    private static final int PICK_AUDIO = 1;

    private WebView web;
    private ValueCallback<Uri[]> pendingFileChooser;

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
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback,
                    WebChromeClient.FileChooserParams params) {
                if (pendingFileChooser != null) {
                    pendingFileChooser.onReceiveValue(null);
                }
                pendingFileChooser = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                        .setType("audio/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
                try {
                    startActivityForResult(
                            Intent.createChooser(pick, "Select songs"), PICK_AUDIO);
                } catch (Exception e) {
                    pendingFileChooser = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_AUDIO) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (pendingFileChooser == null) return;
        Uri[] uris = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                ArrayList<Uri> list = new ArrayList<Uri>();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    list.add(clip.getItemAt(i).getUri());
                }
                uris = list.toArray(new Uri[0]);
            } else if (data.getData() != null) {
                uris = new Uri[] { data.getData() };
            }
        }
        pendingFileChooser.onReceiveValue(uris);
        pendingFileChooser = null;
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
