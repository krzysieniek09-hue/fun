package android.webkit;

import android.net.Uri;

/**
 * COMPILE-TIME STUB — never packaged into the APK.
 *
 * The Maven Central android jar we compile against stops at API 16, but
 * onShowFileChooser (needed so the web app's file input opens a picker)
 * arrived in API 21. This stub shadows the real class on the javac
 * classpath with the modern signature; at runtime the device's actual
 * android.webkit.WebChromeClient is used and the override links up.
 */
public class WebChromeClient {

    public boolean onShowFileChooser(WebView webView,
            ValueCallback<Uri[]> filePathCallback,
            FileChooserParams fileChooserParams) {
        return false;
    }

    public static abstract class FileChooserParams {
    }
}
