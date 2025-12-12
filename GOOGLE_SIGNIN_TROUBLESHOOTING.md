# Google Sign-In Troubleshooting Guide

## Common Issues and Solutions

### 1. SHA-1 Fingerprint Not Registered (Most Common)

**Problem:** Google Sign-In fails silently or shows "DEVELOPER_ERROR" because your app's SHA-1 fingerprint is not registered in Google Cloud Console.

**Solution:**

1. **Get your app's SHA-1 fingerprint:**

   For debug builds:
   ```bash
   # Windows (PowerShell)
   cd android
   .\gradlew signingReport
   
   # Or using keytool directly
   keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   ```

   For release builds:
   ```bash
   keytool -list -v -keystore your-release-key.keystore -alias your-key-alias
   ```

2. **Register SHA-1 in Google Cloud Console:**
   - Go to [Google Cloud Console](https://console.cloud.google.com/)
   - Navigate to **APIs & Services** > **Credentials**
   - Find your OAuth 2.0 Client ID (Android app)
   - Click **Edit**
   - Under **SHA-1 certificate fingerprints**, click **+ ADD FINGERPRINT**
   - Paste your SHA-1 fingerprint
   - Click **Save**

3. **Wait a few minutes** for changes to propagate

### 2. Wrong OAuth Client ID

**Problem:** The client ID in your code doesn't match the one in Google Cloud Console.

**Check:**
- Your client ID: `592279498858-b3o6p8j8jaqu32ok4ggc4fvkgj8idqc2.apps.googleusercontent.com`
- Verify this matches the Android OAuth client ID in Google Cloud Console
- Make sure you're using the **Android** client ID, not the Web client ID

### 3. Package Name Mismatch

**Problem:** The package name in Google Cloud Console doesn't match your app's package name.

**Check:**
- Your app's package name: `com.vitol.inv3`
- Verify this matches the package name in your OAuth client configuration

### 4. Supabase Google OAuth Configuration

**Problem:** Supabase might not be configured to accept Google Sign-In.

**Check:**
1. Go to your Supabase dashboard
2. Navigate to **Authentication** > **Providers**
3. Enable **Google** provider
4. Add your Google OAuth client ID and secret
5. Set the redirect URL to: `https://azbyzwdthelztfuybxmg.supabase.co/auth/v1/callback`

### 5. Testing Steps

After fixing the configuration:

1. **Clear app data** or reinstall the app
2. **Wait 5-10 minutes** after updating Google Cloud Console
3. **Check logcat** for detailed error messages (now with improved logging)
4. Look for these error codes:
   - `10` (DEVELOPER_ERROR) - Usually means SHA-1 not registered
   - `7` (NETWORK_ERROR) - Network connectivity issue
   - `8` (INTERNAL_ERROR) - Google service error

### 6. Quick Debug Checklist

- [ ] SHA-1 fingerprint is registered in Google Cloud Console
- [ ] OAuth client ID matches in code and console
- [ ] Package name matches (`com.vitol.inv3`)
- [ ] Google Sign-In API is enabled in Google Cloud Console
- [ ] Supabase Google provider is enabled
- [ ] Wait time after configuration changes (5-10 minutes)
- [ ] Check logcat for detailed error messages

### 7. Getting SHA-1 from Android Studio

1. Open Android Studio
2. Click **Gradle** tab (right side)
3. Navigate to: `app` > `Tasks` > `android` > `signingReport`
4. Double-click `signingReport`
5. Check the **Run** tab at the bottom
6. Look for `SHA1:` under `Variant: debug`

### 8. Alternative: Get SHA-1 from Running App

If you have the app installed, you can also get the SHA-1 from the running app using:
```bash
adb shell dumpsys package com.vitol.inv3 | grep -A 1 "signatures"
```

## Current Configuration

- **Package Name:** `com.vitol.inv3`
- **OAuth Client ID:** `592279498858-b3o6p8j8jaqu32ok4ggc4fvkgj8idqc2.apps.googleusercontent.com`
- **Supabase Callback URL:** `https://azbyzwdthelztfuybxmg.supabase.co/auth/v1/callback`

## Next Steps

1. Get your SHA-1 fingerprint using one of the methods above
2. Add it to Google Cloud Console
3. Wait 5-10 minutes
4. Test Google Sign-In again
5. Check logcat for detailed error messages (improved logging is now in place)

