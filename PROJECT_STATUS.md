# Inv3 – Project Status

**Last updated:** 2026-02-18

## Current State

### App
- **Package:** com.vitol.inv3
- **Version:** 1.0.4 (versionCode 4)
- **Status:** Internal testing on Google Play Console
- **Build:** Release AAB at `app/release/app-release.aab` (build via Android Studio)

### Website
- **URL:** https://inv3-webpage.netlify.app
- **Repo:** https://github.com/mrvitold/inv3-website
- **Content:** Bilingual (LT/EN), VMI i.SAF screenshot, privacy policy link

### Google Play
- Developer account verified
- Organization website verified (inv3-webpage.netlify.app)
- App created (Free with paid subscriptions)
- Internal testing release uploaded

---

## Completed (2025–2026)

- Azure Document Intelligence for OCR (replaced Google Document AI)
- Local ML Kit fallback
- i.SAF XML export for VMI
- Excel export
- Supabase auth (email, Google Sign-In)
- Firebase Crashlytics
- App signing (keystore)
- Website for Play Console verification
- Credentials moved out of app bundle (`documentai-credentials.json` → `support/credentials/`)
- Guide screen image removed (AAPT fix)

---

## Later Plans

### Before Production
1. **Debug symbols** – Upload native debug symbols in Play Console to improve crash/ANR analysis (warning shown on upload).
2. **Store listing** – Screenshots, descriptions, feature graphic.
3. **Content rating** – Complete questionnaire.
4. **Data safety** – Declare data collection.
5. **Target audience** – Set age groups.
6. ~~**Subscriptions** – Configure in Monetize with Play.~~ ✓ Done (basic_monthly, pro_monthly, accounting_monthly)

### After Publish
- Add Play Store link to website.
- Rotate/revoke exposed Google Document AI key (was in old bundle).

### Optional
- Error handling and user feedback improvements.
- Invoice validation.
- Search and filter.

---

## Reference

| Item | Location |
|------|----------|
| Privacy policy | [PRIVACY_POLICY.md](PRIVACY_POLICY.md) / [GitHub](https://github.com/mrvitold/Inv3/blob/master/PRIVACY_POLICY.md) |
| Website deployment | [website/README.md](website/README.md) |
| i.SAF XML format | [ISAF_XML_FORMAT_REQUIREMENTS.md](ISAF_XML_FORMAT_REQUIREMENTS.md) |
| i.SAF invoice types | [ISAF_INVOICE_TYPE_VALUES.md](ISAF_INVOICE_TYPE_VALUES.md) |
| Keystore setup | [docs/KEYSTORE_SETUP.md](docs/KEYSTORE_SETUP.md) |
| Google Sign-In setup | [docs/GOOGLE_SIGNIN_SETUP.md](docs/GOOGLE_SIGNIN_SETUP.md) |
| Azure setup | [AZURE_SETUP.md](AZURE_SETUP.md) |

---

## Release 1.0.4 (2026-02-18)

**Release notes for Play Console:**
- Google Sign-In: fixed navigation after successful login
- Subscriptions: added offer token for Billing Library 7 compatibility
- Improved billing error messages

## Release 1.0.3 (2026-02-18)

**Release notes for Play Console:**
- Subscription plans screen: improved scrolling on all device sizes
- Subscribe button: fixed billing flow (product details handling)
- Error feedback: purchase errors now shown in Snackbar for better visibility
- Subscriptions ready: Basic, Pro, and Accounting plans available

---

## Build

```bash
# Release AAB (use Android Studio: Build → Generate Signed Bundle / APK)
# Output: app/release/app-release.aab
```

Keystore: `keystore.properties` (see `keystore.properties.example`).
