# Google Sign-In – Complete Setup Checklist

## Current Configuration (Already Done)

| Item | Value | Status |
|------|-------|--------|
| **App package** | `com.vitol.inv3` | ✓ Set in build.gradle.kts |
| **gradle.properties** | `GOOGLE_OAUTH_CLIENT_ID` (debug) + `GOOGLE_OAUTH_CLIENT_ID_RELEASE` (Play Store) | ✓ Configured |
| **Supabase** | Client ID and Client Secret configured | ✓ From your screenshot |
| **Supabase callback URL** | `https://azbyzwdthelztfuybxmg.supabase.co/auth/v1/callback` | ✓ |

---

## What You Must Configure in Google Cloud Console

### 1. Web Client 1 – Add Redirect URI

1. Go to [Google Cloud Console](https://console.cloud.google.com/) → project **LT Invoice Scanner**
2. **APIs & Services** → **Credentials**
3. Open **Web client 1** (click the pencil icon)
4. Under **Authorized redirect URIs**, click **Add URI**
5. Add: `https://azbyzwdthelztfuybxmg.supabase.co/auth/v1/callback`
6. Click **Save**

### 2. Android Clients – Two clients for debug vs Play Store

**Client 1 (debug/upload keystore):** Package `com.vitol.inv3`, SHA-1 for debug/upload keystore  
**Client 2 (Play App Signing):** Package `com.vitol.inv3`, SHA-1 from Play Console → App signing → App signing key certificate

- Debug builds (Android Studio) use `GOOGLE_OAUTH_CLIENT_ID` (Client 1)
- Release builds (Play Store) use `GOOGLE_OAUTH_CLIENT_ID_RELEASE` (Client 2)

---

## Summary

| Where | What to do |
|-------|------------|
| **Google Cloud Console** | Add redirect URI to Web client 1 |
| **Google Cloud Console** | Ensure Android client has correct package + SHA-1 |
| **Supabase** | Already configured ✓ |
| **App (gradle.properties)** | Already configured ✓ |

---

## After Configuration

1. Save all changes in Google Cloud Console
2. Fully close the Inv3 app
3. Rebuild and run from Android Studio
4. Try **Sign in with Google**

---

## If You Still See "Another project contains OAuth client"

The Firebase project (inv3-affc9) may show a warning because the package + SHA-1 is already in the **LT Invoice Scanner** Google Cloud project. That is expected. The app uses the OAuth client from **LT Invoice Scanner** (project 592279498858), not from Firebase. You can ignore the Firebase warning or remove the SHA-1 from Firebase if you prefer.
