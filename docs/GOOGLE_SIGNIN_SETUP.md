# Google Sign-In – Complete Setup Checklist

## Current Configuration (Already Done)

| Item | Value | Status |
|------|-------|--------|
| **App package** | `com.vitol.inv3` | ✓ Set in build.gradle.kts |
| **gradle.properties** | `GOOGLE_OAUTH_CLIENT_ID` (Web Client ID for both debug and release) | ✓ Configured |
| **Supabase** | Client ID and Client Secret configured | ✓ From your screenshot |
| **Supabase callback URL** | `https://azbyzwdthelztfuybxmg.supabase.co/auth/v1/callback` | ✓ |

---

## Critical: Play Store Builds (Google Sign-In Not Working After Download)

**If users cannot sign in with Google after downloading from Play Store**, the most common cause is a **missing Play App Signing SHA-1** in Google Cloud Console.

When you use Google Play App Signing, Google re-signs your app with their key. The SHA-1 of that key **must** be registered in an Android OAuth client.

### Add Play App Signing SHA-1

1. Go to [Google Play Console](https://play.google.com/console/) → your app **Inv3**
2. **Release** → **Setup** → **App Integrity**
3. Under **App signing key certificate**, copy the **SHA-1 certificate fingerprint**
4. Go to [Google Cloud Console](https://console.cloud.google.com/) → project **LT Invoice Scanner**
5. **APIs & Services** → **Credentials**
6. Find or create an **Android** OAuth 2.0 client with package `com.vitol.inv3`
7. Add the Play App Signing SHA-1 to that client (or create a new Android client with package + SHA-1)
8. Click **Save**

**Note:** The app uses the **Web Client ID** for `requestIdToken()`. The Android client(s) are only for app verification (package + SHA-1). You need one Android client with debug SHA-1 and one with Play App Signing SHA-1, or a single client with both SHA-1 fingerprints.

---

## What You Must Configure in Google Cloud Console

### 1. Web Client 1 – Add Redirect URI

1. Go to [Google Cloud Console](https://console.cloud.google.com/) → project **LT Invoice Scanner**
2. **APIs & Services** → **Credentials**
3. Open **Web client 1** (click the pencil icon)
4. Under **Authorized redirect URIs**, click **Add URI**
5. Add: `https://azbyzwdthelztfuybxmg.supabase.co/auth/v1/callback`
6. Click **Save**

### 2. Android Clients – SHA-1 Fingerprints

You need an **Android** OAuth 2.0 client with package `com.vitol.inv3` and **all** of these SHA-1 fingerprints:

| Build type | Where to get SHA-1 |
|------------|--------------------|
| **Debug** | `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` |
| **Release (upload key)** | From your release keystore |
| **Play Store (App Signing)** | Play Console → Release → Setup → App Integrity → App signing key certificate |

Add all relevant SHA-1 values to the same Android client (or use separate clients if preferred).

---

## Summary

| Where | What to do |
|-------|------------|
| **Google Cloud Console** | Add redirect URI to Web client 1 |
| **Google Cloud Console** | Android client: package `com.vitol.inv3` + debug SHA-1 + **Play App Signing SHA-1** |
| **Supabase** | Already configured ✓ |
| **App (gradle.properties)** | `GOOGLE_OAUTH_CLIENT_ID` = Web Client ID ✓ |

---

## After Configuration

1. Save all changes in Google Cloud Console
2. Wait a few minutes for changes to propagate
3. Fully close the Inv3 app
4. Try **Sign in with Google** (debug: rebuild and run; Play Store: reinstall or wait for update)

---

## If You Still See "Another project contains OAuth client"

The Firebase project (inv3-affc9) may show a warning because the package + SHA-1 is already in the **LT Invoice Scanner** Google Cloud project. That is expected. The app uses the OAuth client from **LT Invoice Scanner** (project 592279498858), not from Firebase. You can ignore the Firebase warning or remove the SHA-1 from Firebase if you prefer.
