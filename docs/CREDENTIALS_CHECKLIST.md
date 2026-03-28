# Inv3 – Credentials Checklist (New PC Setup)

When copying the project to a new machine, copy these credentials from your laptop or `support/credentials/` folder.

## Required in `gradle.properties`

| Property | Source | Status on this PC |
|----------|--------|-------------------|
| `SUPABASE_URL` | Supabase Dashboard URL | ✓ Present |
| `SUPABASE_ANON_KEY` | Supabase Dashboard → Settings → API → anon public | **Add from laptop** |
| `AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT` | Azure Portal | ✓ Present |
| `AZURE_DOCUMENT_INTELLIGENCE_API_KEY` | Azure Portal | ✓ Present |
| `GOOGLE_OAUTH_CLIENT_ID` | Google Cloud Console (Web Client ID) | ✓ Present |

## Optional — Meta (Facebook) App Events (Ads)

Only needed if you use Meta App Events / Ads optimization toward subscriptions. See [META_ADS_MEASUREMENT.md](META_ADS_MEASUREMENT.md).

| Property | Source |
|----------|--------|
| `FACEBOOK_APP_ID` | Meta app dashboard (numeric App ID) |
| `FACEBOOK_CLIENT_TOKEN` | Meta app → Settings → **Client token** |

If omitted, the Meta SDK stays disabled and the app behaves as before.

## Google Sign-In – Debug SHA-1 on New PC

**Each PC has a different debug keystore.** The debug SHA-1 on this desktop must be added to Google Cloud Console.

**This PC's debug SHA-1:** `B0:9D:71:ED:80:39:63:E1:C1:55:36:FF:5A:A4:F4:89:82:ED:CE:2C`

1. To get SHA-1 on another machine:
   ```powershell
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android
   ```
   Copy the **SHA1** fingerprint.

2. Go to [Google Cloud Console](https://console.cloud.google.com/) → project **LT Invoice Scanner**
3. **APIs & Services** → **Credentials**
4. Find the **Android** OAuth client with package `com.vitol.inv3`
5. Add this PC's debug SHA-1 to that client (or create a new Android client with package + SHA-1)
6. Save

## Files to Copy from Laptop

| File | Location |
|------|----------|
| `gradle.properties` | Project root (contains Supabase, Azure, Google keys) |
| `keystore.properties` | Project root (for release signing) |
| `support/credentials/*` | Any credential backups you keep there |
| `app/google-services.json` | Firebase config (if needed) |
| `inv3-release.jks` | Keystore for release builds |

## Quick Fix: Supabase Login Not Working

If you cannot log in (email or Google):

1. **Supabase client null** – Add `SUPABASE_URL` and `SUPABASE_ANON_KEY` to `gradle.properties` (copy from laptop).
2. **Google Sign-In fails** – Add this PC's debug SHA-1 to the Android OAuth client in Google Cloud Console (see above).
