# Firebase Crashlytics Setup Guide

## Overview

Firebase Crashlytics has been integrated into the Inv3 app to provide production crash reporting and analytics. This guide explains how to complete the setup.

## What Was Added

1. **Firebase Dependencies**: Added to `app/build.gradle.kts`
2. **Firebase Plugins**: Added Google Services and Crashlytics plugins
3. **CrashlyticsTree**: Custom Timber tree that sends logs to Crashlytics
4. **Application Configuration**: Updated `Inv3App.kt` to initialize Firebase
5. **ProGuard Rules**: Added rules to preserve crash report information

## Setup Steps

### Step 1: Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click **Add project** or select an existing project
3. Follow the setup wizard:
   - Enter project name (e.g., "Inv3")
   - Enable Google Analytics (recommended)
   - Accept terms and create project

### Step 2: Add Android App to Firebase

1. In Firebase Console, click **Add app** → **Android**
2. Enter your app details:
   - **Android package name**: `com.vitol.inv3`
   - **App nickname**: Inv3 (optional)
   - **Debug signing certificate SHA-1**: (optional, for Google Sign-In)
3. Click **Register app**

### Step 3: Download google-services.json

1. After registering, download `google-services.json`
2. Place it in: `app/` directory (same level as `build.gradle.kts`)
3. **Important**: The file should be at `app/google-services.json`

### Step 4: Verify File Location

Your project structure should look like:
```
Inv3/
├── app/
│   ├── google-services.json  ← Place here
│   ├── build.gradle.kts
│   └── src/
├── build.gradle.kts
└── ...
```

### Step 5: Build and Test

1. Sync Gradle files in Android Studio
2. Build the app: `./gradlew build`
3. Run the app on a device or emulator
4. Trigger a test crash (see Testing section below)

## Testing Crashlytics

### Test Crash Reporting

Add this code temporarily to test crash reporting:

```kotlin
// In any Activity or ViewModel
button.setOnClickListener {
    throw RuntimeException("Test crash for Crashlytics")
}
```

Or use Timber to log an error:

```kotlin
Timber.e(Exception("Test error"), "This is a test error")
```

### Verify in Firebase Console

1. Go to Firebase Console → Crashlytics
2. Wait 5-10 minutes for the crash to appear
3. You should see crash reports with stack traces

## How It Works

### Debug vs Release Builds

- **Debug Builds**: 
  - Uses `Timber.DebugTree()` for Logcat output
  - Crashlytics collection is **disabled** (to avoid cluttering reports during development)
  
- **Release Builds**:
  - Uses `CrashlyticsTree()` for crash reporting
  - Crashlytics collection is **enabled**
  - Logs are sent to Firebase Crashlytics

### Logging Levels

- **VERBOSE/DEBUG**: Only sent to Crashlytics if there's an exception
- **INFO/WARNING**: Sent to Crashlytics as logs
- **ERROR**: Sent to Crashlytics as logs + exception (if provided)

### Custom Keys

The `CrashlyticsTree` automatically sets:
- `log_priority`: Log priority level
- `log_tag`: Log tag (if available)

You can add custom keys in your code:

```kotlin
FirebaseCrashlytics.getInstance().setCustomKey("user_id", userId)
FirebaseCrashlytics.getInstance().setCustomKey("invoice_count", invoiceCount)
```

## Privacy Considerations

Crashlytics collects:
- Crash reports and stack traces
- Log messages (INFO, WARNING, ERROR)
- Device information (OS version, device model)
- App version information

**Important**: Do not log sensitive information (passwords, API keys, personal data) using Timber in production builds, as it will be sent to Crashlytics.

## Troubleshooting

### Issue: Build fails with "google-services.json not found"

**Solution**: 
- Ensure `google-services.json` is in `app/` directory
- Sync Gradle files
- Clean and rebuild project

### Issue: No crash reports appearing

**Solutions**:
1. Wait 5-10 minutes (reports may take time to appear)
2. Check that Crashlytics is enabled in Firebase Console
3. Verify you're testing on a release build (or enable manually in debug)
4. Check Firebase Console → Project Settings → General → Your apps

### Issue: "Crashlytics collection is disabled"

**Solution**: This is normal in debug builds. To test in debug:
```kotlin
FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
```

### Issue: ProGuard obfuscation breaking stack traces

**Solution**: The ProGuard rules have been configured to preserve:
- Line numbers
- Source file names
- Exception classes

If issues persist, check `app/proguard-rules.pro`

## Best Practices

1. **Use Timber for Logging**: All logging should go through Timber
   ```kotlin
   Timber.d("Debug message")
   Timber.i("Info message")
   Timber.w("Warning message")
   Timber.e(exception, "Error message")
   ```

2. **Add Context**: Include relevant context in error messages
   ```kotlin
   Timber.e(e, "Failed to process invoice: ${invoiceId}")
   ```

3. **Set User Identifiers**: Set user ID for better crash tracking
   ```kotlin
   FirebaseCrashlytics.getInstance().setUserId(userId)
   ```

4. **Test Regularly**: Test crash reporting before major releases

## Next Steps

After setup:
1. ✅ Add `google-services.json` to your project
2. ✅ Build and test the app
3. ✅ Verify crash reports in Firebase Console
4. ✅ Monitor crashes after releasing to users

## Resources

- [Firebase Crashlytics Documentation](https://firebase.google.com/docs/crashlytics)
- [Firebase Console](https://console.firebase.google.com/)
- [Timber Documentation](https://github.com/JakeWharton/timber)

