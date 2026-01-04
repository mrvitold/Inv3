# Privacy Policy Setup Guide

## Overview

A Privacy Policy is **REQUIRED** by Google Play Store for apps that collect user data. Since Inv3 collects authentication data, invoice images, and processes user information, you must provide a privacy policy URL.

## What Was Created

1. **`PRIVACY_POLICY.md`** - Complete privacy policy document covering:
   - Data collection (authentication, invoices, images)
   - Third-party services (Supabase, Azure, Google)
   - Data usage and security
   - User rights (GDPR/CCPA compliant)
   - Contact information

## Next Steps

### Step 1: Customize the Privacy Policy

Before publishing, you need to customize the following sections in `PRIVACY_POLICY.md`:

1. **Last Updated Date**: Replace `[Date]` with the actual date
2. **Contact Information**: Add your email, website, and business address:
   ```markdown
   **Email**: your-email@example.com
   **Website**: https://yourwebsite.com
   **Address**: Your Business Address
   ```

3. **Review Third-Party Links**: Verify all third-party privacy policy links are correct:
   - Supabase: https://supabase.com/privacy
   - Azure: https://privacy.microsoft.com/privacystatement
   - Google: https://policies.google.com/privacy

### Step 2: Host the Privacy Policy

You need to host the privacy policy on a publicly accessible URL. Options:

#### Option A: GitHub Pages (Free & Easy)

1. Create a GitHub repository (or use existing one)
2. Create a `docs` folder or use the repository root
3. Copy `PRIVACY_POLICY.md` to the repository
4. Enable GitHub Pages:
   - Go to repository Settings → Pages
   - Select source branch (usually `main` or `master`)
   - Save
5. Your privacy policy URL will be:
   - `https://yourusername.github.io/repository-name/PRIVACY_POLICY.md`
   - Or convert to HTML: `https://yourusername.github.io/repository-name/PRIVACY_POLICY.html`

#### Option B: Your Own Website

1. Upload `PRIVACY_POLICY.md` to your website
2. Convert to HTML if needed
3. Accessible at: `https://yourwebsite.com/privacy-policy`

#### Option C: Privacy Policy Generator Services

- [PrivacyPolicyGenerator.net](https://www.privacypolicygenerator.net/)
- [FreePrivacyPolicy.com](https://www.freeprivacypolicy.com/)
- [Termly](https://termly.io/)

### Step 3: Convert Markdown to HTML (Optional but Recommended)

For better presentation, convert the markdown to HTML:

**Using Pandoc:**
```bash
pandoc PRIVACY_POLICY.md -o privacy-policy.html --standalone --css style.css
```

**Using Online Converters:**
- [Markdown to HTML](https://www.markdowntohtml.com/)
- [Dillinger](https://dillinger.io/)

**Using GitHub:**
- GitHub automatically renders `.md` files, but you can create an HTML version for better formatting

### Step 4: Add Privacy Policy Link to App (Optional)

You can add a link to the privacy policy in your app's settings screen:

```kotlin
// In SettingsScreen.kt
TextButton(
    onClick = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://your-privacy-policy-url.com"))
        context.startActivity(intent)
    }
) {
    Text("Privacy Policy")
}
```

### Step 5: Submit to Google Play Console

When publishing your app:

1. Go to Google Play Console → Your App → Policy → App content
2. Find "Privacy Policy" section
3. Enter your privacy policy URL
4. Save and submit

## Privacy Policy Requirements Checklist

✅ **Data Collection**: Describes what data is collected
✅ **Data Usage**: Explains how data is used
✅ **Third-Party Services**: Lists all third-party services (Supabase, Azure, Google)
✅ **Data Security**: Describes security measures
✅ **User Rights**: Explains user rights (access, deletion, export)
✅ **Contact Information**: Provides contact details
✅ **GDPR Compliance**: Covers EU user rights
✅ **CCPA Compliance**: Covers California user rights
✅ **Permissions**: Explains why permissions are needed

## Important Notes

1. **Keep It Updated**: Update the privacy policy whenever you:
   - Add new data collection
   - Change third-party services
   - Modify data usage practices
   - Update security measures

2. **Legal Review**: Consider having a lawyer review the privacy policy, especially if:
   - You operate in multiple jurisdictions
   - You handle sensitive financial data
   - You have a large user base

3. **Accessibility**: Ensure the privacy policy is:
   - Easily accessible (linked from app and website)
   - Written in clear, understandable language
   - Available in languages your users speak

4. **Version Control**: Keep track of privacy policy versions:
   - Maintain a changelog
   - Archive old versions
   - Notify users of significant changes

## Testing

Before submitting to Google Play:

1. ✅ Verify privacy policy URL is accessible
2. ✅ Check all links work correctly
3. ✅ Ensure contact information is accurate
4. ✅ Review for typos and formatting
5. ✅ Test on mobile devices (if HTML version)

## Resources

- [Google Play Privacy Policy Requirements](https://support.google.com/googleplay/android-developer/answer/10787469)
- [GDPR Compliance Guide](https://gdpr.eu/)
- [CCPA Compliance Guide](https://oag.ca.gov/privacy/ccpa)
- [Supabase Privacy Policy](https://supabase.com/privacy)
- [Azure Privacy Statement](https://privacy.microsoft.com/privacystatement)
- [Google Privacy Policy](https://policies.google.com/privacy)

