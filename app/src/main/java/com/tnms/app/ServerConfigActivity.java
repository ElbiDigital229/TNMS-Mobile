package com.tnms.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

public class ServerConfigActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "tnms_prefs";
    public static final String KEY_SERVER_URL = "server_url";

    /**
     * URLs that were shipped as a DEFAULT_SERVER_URL in previous app versions.
     * If a user has one of these saved (meaning they never manually customized
     * it), we silently migrate them to the current default on next launch.
     */
    private static final String[] STALE_DEFAULTS = new String[] {
            "http://18.234.126.30",
            "http://18.234.126.30/",
            "http://18.234.126.30:5000",
    };

    private TextInputEditText serverUrlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_config);

        serverUrlInput = findViewById(R.id.serverUrlInput);
        Button saveButton = findViewById(R.id.saveButton);
        Button resetButton = findViewById(R.id.resetButton);

        // Load current URL
        String currentUrl = getServerUrl(this);
        serverUrlInput.setText(currentUrl);

        saveButton.setOnClickListener(v -> {
            String url = serverUrlInput.getText() != null
                    ? serverUrlInput.getText().toString().trim()
                    : "";

            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show();
                return;
            }

            // Ensure URL has protocol
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }

            // Remove trailing slash
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            // Save
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(KEY_SERVER_URL, url).apply();

            Toast.makeText(this, "Server updated", Toast.LENGTH_SHORT).show();

            // Launch main activity
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });

        resetButton.setOnClickListener(v -> {
            String defaultUrl = BuildConfig.DEFAULT_SERVER_URL;
            serverUrlInput.setText(defaultUrl);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(KEY_SERVER_URL, defaultUrl).apply();

            Toast.makeText(this, "Reset to default: " + defaultUrl, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Static helper — get the configured server URL from anywhere.
     */
    public static String getServerUrl(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String stored = prefs.getString(KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL);

        // Auto-migrate stale defaults from previous app versions so users who
        // never customized the URL don't get stuck on a dead endpoint after
        // an update.
        if (stored != null) {
            for (String stale : STALE_DEFAULTS) {
                if (stale.equals(stored)) {
                    stored = BuildConfig.DEFAULT_SERVER_URL;
                    prefs.edit().putString(KEY_SERVER_URL, stored).apply();
                    break;
                }
            }
        }

        return stored;
    }
}
