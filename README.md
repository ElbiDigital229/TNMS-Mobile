# TNMS Mobile App

Android WebView wrapper for the TNMS web application.

## Features
- Native Android app wrapping the TNMS web interface
- Splash screen with TNMS branding
- Pull-to-refresh
- Back button navigation through web history
- File upload support (for ticket attachments)
- Offline detection with retry screen
- Progress bar for page loading

## Build

### Automatic (GitHub Actions)
Push to `main` branch triggers automatic APK build.
Download from: Actions tab > Latest run > Artifacts.

### Manual (Android Studio)
1. Open this project in Android Studio
2. Build > Build APK(s)
3. APK will be in `app/build/outputs/apk/`

## Configuration

Change the server URL in `app/build.gradle`:
```
buildConfigField "String", "SERVER_URL", "\"https://your-domain.com\""
```

## When switching to HTTPS:
1. Update `SERVER_URL` in `app/build.gradle`
2. Remove `android:usesCleartextTraffic="true"` from `AndroidManifest.xml`
3. Remove `network_security_config.xml`
