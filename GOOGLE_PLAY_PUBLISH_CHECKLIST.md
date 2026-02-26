# Google Play Publish Checklist – Inv3 v1.08

Use this checklist before publishing to production.

---

## Code & Build (Ready)

| Item | Status |
|------|--------|
| versionCode 8, versionName "1.08" | ✓ |
| targetSdk 35 (required by Play) | ✓ |
| Release signing (keystore.properties) | ✓ |
| ProGuard rules for release | ✓ |
| Privacy policy (PRIVACY_POLICY.md) | ✓ |
| Account deletion in Settings | ✓ |
| Data export (Excel) | ✓ |
| google-services.json | ✓ |
| No ads (no ad SDK) | ✓ |

---

## Play Console – Required Before Production

### 1. Store listing
- [ ] **App name**: Inv3
- [ ] **Short description** (max 80 chars)
- [ ] **Full description** (max 4000 chars)
- [ ] **Screenshots**: At least 2 phone screenshots (16:9 or 9:16)
- [ ] **Feature graphic**: 1024×500 px
- [ ] **App icon**: 512×512 px (from `@mipmap/icon_inv3`)

### 2. Content rating
- [ ] Complete the IARC questionnaire
- [ ] Submit for rating (typically Everyone or similar for business apps)

### 3. Data safety
- [ ] Declare data collection:
  - Account info (email, name)
  - Invoice images and extracted data
  - Company data
  - Device/crash data (Crashlytics)
- [ ] Declare data sharing with: Supabase, Azure, Google
- [ ] State that data is encrypted in transit
- [ ] Mention account deletion and data export options

### 4. Target audience
- [ ] Set age groups (e.g. 18+ for business app)
- [ ] Confirm app is not aimed at children

### 5. App content
- [ ] **Ads declaration**: Declare “No, my app does not contain ads”
- [ ] **News app**: No (if applicable)
- [ ] **COVID-19 apps**: No (if applicable)
- [ ] **Financial features**: Yes – subscriptions; ensure monetization is set up

### 6. Privacy policy
- [ ] Add URL in Play Console: `https://github.com/mrvitold/Inv3/blob/master/PRIVACY_POLICY.md`
- [ ] Or use a hosted page (e.g. Netlify) if you prefer a cleaner URL

### 7. Release setup
- [ ] Upload AAB: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
- [ ] Add release notes for v1.08
- [ ] Promote from Internal testing → Production when ready

### 8. Optional but recommended
- [ ] **Debug symbols**: Upload native debug symbols for better crash reports
- [ ] **Internal testing**: Test with internal testers before production
- [ ] **Staged rollout**: Use staged rollout (e.g. 20%) for first production release

---

## Release Notes for v1.08 (suggested)

```
- Fixed Google Sign-In for users who download the app from Google Play
- Improved authentication reliability for Play Store builds
```

---

## Common Rejection Reasons to Avoid

1. **Crash on launch** – Test release build on a real device
2. **Missing privacy policy** – Must be reachable from the URL you provide
3. **Incomplete Data safety** – Describe all collected data and how it’s used
4. **Misleading metadata** – Descriptions must match app behavior
5. **Broken features** – Subscriptions, sign-in, and core flows must work

---

## Quick Build & Upload

```bash
# Build release AAB
./gradlew bundleRelease

# Output: app/build/outputs/bundle/release/app-release.aab
# Upload this file in Play Console → Your app → Production → Create new release
```
