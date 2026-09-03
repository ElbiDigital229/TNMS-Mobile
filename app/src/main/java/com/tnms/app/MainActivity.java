package com.tnms.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private ValueCallback<Uri[]> fileUploadCallback;
    private String serverUrl;

    /** Hosts that serve the TNMS app itself — see isAppUrl(). */
    private static final String[] APP_HOSTS = {
            "feoms.vercel.app",
            "ticket.truenorthpk.com",
            "localhost",
    };
    private String pendingDeepLink = null;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String dataString = result.getData().getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);

        serverUrl = ServerConfig.getServerUrl(this);

        // Check for deep link from notification
        handleIntent(getIntent());

        // Request notification permission (Android 13+)
        requestNotificationPermission();

        // ── WebView Settings ──
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " TNMSApp/1.0");

        // ── JavaScript Interface for FCM token ──
        webView.addJavascriptInterface(new WebAppInterface(), "TNMSNative");

        // ── Cookies ──
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // ── WebView Client (navigation + errors) ──
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                injectMobileCSS(view);

                // After page loads, inject FCM token registration
                injectFCMTokenRegistration(view);

                // Handle pending deep link
                if (pendingDeepLink != null) {
                    String deepLink = pendingDeepLink;
                    pendingDeepLink = null;
                    view.evaluateJavascript(
                        "window.location.href = '" + deepLink + "';", null
                    );
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showNoConnection();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (isAppUrl(url)) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, url);
                startActivity(intent);
                return true;
            }
        });

        // ── Chrome Client (file upload, progress) ──
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                fileChooserLauncher.launch(intent);
                return true;
            }
        });

        // ── Disable pull to refresh (app handles its own scrolling) ──
        swipeRefresh.setEnabled(false);

        // ── Load app ──
        if (isNetworkAvailable()) {
            webView.loadUrl(serverUrl);
        } else {
            showNoConnection();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        // If WebView is already loaded, navigate to deep link
        if (pendingDeepLink != null && webView != null) {
            String deepLink = pendingDeepLink;
            pendingDeepLink = null;
            webView.evaluateJavascript(
                "window.location.href = '" + deepLink + "';", null
            );
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("deep_link")) {
            pendingDeepLink = intent.getStringExtra("deep_link");
        }
    }

    /**
     * JavaScript interface — allows the web app to get the FCM token
     */
    private class WebAppInterface {
        @JavascriptInterface
        public String getFCMToken() {
            SharedPreferences prefs = getSharedPreferences("tnms_prefs", MODE_PRIVATE);
            return prefs.getString("fcm_token", "");
        }
    }

    /**
     * After page finishes loading, inject JS that registers the FCM token
     * with the server if the user is logged in.
     */
    private void injectFCMTokenRegistration(WebView view) {
        // First ensure we have the latest FCM token
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            // Store it
            SharedPreferences prefs = getSharedPreferences("tnms_prefs", MODE_PRIVATE);
            prefs.edit().putString("fcm_token", token).apply();

            // Inject JS to register token with the server
            String js = "(function() {" +
                "try {" +
                "  var authToken = localStorage.getItem('token');" +
                "  if (!authToken) return;" +
                "  var fcmToken = '" + token + "';" +
                "  fetch('/api/notifications/device-token', {" +
                "    method: 'POST'," +
                "    headers: {" +
                "      'Content-Type': 'application/json'," +
                "      'Authorization': 'Bearer ' + authToken" +
                "    }," +
                "    body: JSON.stringify({ token: fcmToken, platform: 'android' })" +
                "  });" +
                "} catch(e) {}" +
                "})();";

            view.evaluateJavascript(js, null);
        });
    }

    /**
     * Is this URL part of the TNMS app, or should it open in a browser?
     *
     * The configured server URL's host is always ours. The extra hosts cover
     * the redirect chain: feoms.vercel.app answers every path with a 301 to
     * ticket.truenorthpk.com, so the host the WebView actually lands on is
     * not the host we asked for. Matching on a string prefix (the old check)
     * treated that redirect as an external link and kicked the user out to
     * Chrome on the very first page load.
     */
    private boolean isAppUrl(Uri url) {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String configuredHost = Uri.parse(serverUrl).getHost();
        if (configuredHost != null && host.equalsIgnoreCase(configuredHost)) {
            return true;
        }
        for (String appHost : APP_HOSTS) {
            if (host.equalsIgnoreCase(appHost)) {
                return true;
            }
        }
        return false;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void injectMobileCSS(WebView view) {
        String css = "document.body.style.overscrollBehavior='none';" +
                     "document.body.style.webkitUserSelect='none';";
        view.evaluateJavascript(css, null);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void showNoConnection() {
        Intent intent = new Intent(this, NoConnectionActivity.class);
        startActivity(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }
}
