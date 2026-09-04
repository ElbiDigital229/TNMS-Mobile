package com.tnms.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where the WebView points.
 *
 * This was originally ServerConfigActivity — a settings screen that was
 * never finished: it referenced R.layout.activity_server_config (a layout
 * that does not exist), was absent from AndroidManifest.xml, and nothing in
 * the app ever navigated to it. Only the static URL resolution below was
 * ever reachable, so that is all this keeps. Wiring up a real settings
 * screen is a separate piece of work.
 */
public final class ServerConfig {

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

    private ServerConfig() {}

    /**
     * The configured server URL, migrating any known stale default from a
     * previous app version so users who never customized it don't get stuck
     * on a dead endpoint after an update.
     */
    public static String getServerUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL);

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
