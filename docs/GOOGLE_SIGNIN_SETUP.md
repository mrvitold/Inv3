# Google Sign-In – Complete Setup Checklist

## Current Configuration (Already Done)

| Item | Value | Status |
|------|-------|--------|
| **App package** | `com.vitol.inv3` | ✓ Set in build.gradle.kts |
| **gradle.properties** | `GOOGLE_OAUTH_CLIENT_ID=592279498858-rhi4cfmq7hkddfqjkfalbb7b72g8s5ee.apps.googleusercontent.com` | ✓ Configured |
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

### 2. Android Client "LT Invoice Scanner" – Verify Package and SHA-1

1. In **Credentials**, open **LT Invoice Scanner** (Android type)
2. Confirm:
   - **Package name:** `com.vitol.inv3`
   - **SHA-1 certificate fingerprint:** `9E:32:EF:8A:BB:42:8F:49:BD:D9:84:DD:34:29:4B:84:FE:62:87:57`
3. If SHA-1 is missing or different, add/update it and save

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
