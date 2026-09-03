package com.tnms.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private ValueCallback<Uri[]> fileUploadCallback;
    /** Chooser params held across a runtime CAMERA permission prompt. */
    private WebChromeClient.FileChooserParams pendingChooserParams;
    /** Where the camera app was told to write its capture, if it was offered. */
    private Uri pendingCameraUri;
    private String serverUrl;

    /** Hosts that serve the TNMS app itself — see isAppUrl(). */
    private static final String[] APP_HOSTS = {
            "feoms.vercel.app",
            "ticket.truenorthpk.com",
            "localhost",
    };
    private String pendingDeepLink = null;

    private static final int RC_CAMERA = 101;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Uri cameraUri = pendingCameraUri;
            pendingCameraUri = null;

            if (fileUploadCallback == null) {
                return;
            }

            Uri[] results = null;
            if (result.getResultCode() == RESULT_OK) {
                results = extractUris(result.getData());
                // A successful ACTION_IMAGE_CAPTURE returns a null Intent — the
                // photo is already at the EXTRA_OUTPUT uri we handed it.
                if (results == null && cameraUri != null) {
                    results = new Uri[]{ cameraUri };
                }
            }

            // The callback MUST be resolved on every path, including cancel.
            // Leaving it pending wedges the <input type="file"> for the rest of
            // the session — every later tap opens nothing.
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        });

    /**
     * Pull every selected image out of a chooser result.
     *
     * A single pick arrives in getData(). A multi-pick arrives in getClipData()
     * and leaves getData() null. The previous code read only getDataString(),
     * so selecting two or more photos handed the WebView a null array: the
     * picker closed, nothing uploaded, and no error surfaced anywhere. Every
     * photo input in the web app is `multiple`, so this was the normal path,
     * not an edge case.
     */
    private static Uri[] extractUris(Intent data) {
        if (data == null) {
            return null;
        }
        ClipData clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            Uri[] uris = new Uri[clip.getItemCount()];
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris[i] = clip.getItemAt(i).getUri();
            }
            return uris;
        }
        Uri single = data.getData();
        return single != null ? new Uri[]{ single } : null;
    }

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
                pendingChooserParams = fileChooserParams;

                // CAMERA is declared in the manifest, which means the OS requires
                // it to be granted before ACTION_IMAGE_CAPTURE will run. Ask, then
                // build the chooser from onRequestPermissionsResult.
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA}, RC_CAMERA);
                    return true;
                }

                launchChooser(fileChooserParams, true);
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

    /**
     * Show the picker technicians actually need: the gallery/document intent
     * Android hands us, plus a camera capture option beside it.
     *
     * fileChooserParams.createIntent() only ever produces a content picker.
     * Chrome bolts the camera on itself inside its own WebChromeClient, so a
     * page that offers "take a photo" in the browser offers only "browse
     * files" once it is wrapped in this app — which is why a technician
     * standing in front of the equipment had no way to photograph it.
     */
    private void launchChooser(WebChromeClient.FileChooserParams params, boolean withCamera) {
        pendingChooserParams = null;

        Intent contentIntent = params.createIntent();
        Intent chooser = Intent.createChooser(contentIntent, getString(R.string.file_chooser_title));

        if (withCamera) {
            Intent cameraIntent = buildCameraIntent();
            if (cameraIntent != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{ cameraIntent });
            }
        }

        try {
            fileChooserLauncher.launch(chooser);
        } catch (ActivityNotFoundException e) {
            pendingCameraUri = null;
            if (fileUploadCallback != null) {
                fileUploadCallback.onReceiveValue(null);
                fileUploadCallback = null;
            }
        }
    }

    /**
     * Camera intent writing to a FileProvider uri, or null if the device has
     * no camera app or the temp file can't be created — in which case the
     * chooser is still shown, just without the camera entry.
     */
    private Intent buildCameraIntent() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            return null;
        }

        File photo;
        try {
            photo = createCaptureFile();
        } catch (IOException e) {
            return null;
        }

        pendingCameraUri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", photo);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return intent;
    }

    /**
     * Captures land in cache/, not external storage, which keeps the app clear
     * of READ_EXTERNAL_STORAGE and the Android 13 media permissions entirely
     * and lets the OS reclaim the files.
     *
     * The .jpg suffix is load-bearing: the server validates uploads on the
     * file extension (server/middleware/upload.ts), so an extensionless name
     * is rejected.
     */
    private File createCaptureFile() throws IOException {
        File dir = new File(getCacheDir(), "camera");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return File.createTempFile("TNMS_" + stamp + "_", ".jpg", dir);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != RC_CAMERA) {
            return;
        }

        WebChromeClient.FileChooserParams params = pendingChooserParams;
        if (params == null) {
            return;
        }

        // Denying the camera should not cost them the gallery — show the
        // chooser either way, just without the camera entry.
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        launchChooser(params, granted);
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
