# Inv3 App Website

Bilingual (Lithuanian/English) landing page for the Inv3 invoice-scanning app, for Google Play Console organization verification.

## Before Deploying

1. **Play Store link** (when app is published): Add a download section with your Play Store URL (e.g. `https://play.google.com/store/apps/details?id=com.vitol.inv3`) in `index.html`. The site currently shows "Coming soon" until then.
2. **Icons**: App icons from IconKitchen are already in place (`favicon.ico`, `icon-192.png`, `icon-512.png`, `apple-touch-icon.png`).
3. **Privacy Policy**: The website links to the full policy at [GitHub](https://github.com/mrvitold/Inv3/blob/master/PRIVACY_POLICY.md). Update `PRIVACY_POLICY.md` in the main Inv3 repo when needed.

## Local Preview

Open `index.html` in a browser, or run a simple server:

```bash
cd website
python -m http.server 8000
```

Then visit http://localhost:8000

## Deploy to GitHub Pages

1. Push the contents of the `website` folder to your `inv3-website` repo.
2. Go to **Settings → Pages**.
3. Under "Source", select **Deploy from a branch**.
4. Choose the `main` branch and `/ (root)` folder.
5. Save. Your site will be at `https://<username>.github.io/inv3-website/`

## Deploy to Netlify

1. Sign up at [netlify.com](https://netlify.com) (free).
2. Drag and drop the `website` folder onto the Netlify deploy area, or connect your Git repo.
3. Your site will get a URL like `https://random-name.netlify.app`. You can add a custom domain in settings.

## Google Play Verification

After deploying:

1. Add and verify your site in [Google Search Console](https://search.google.com/search-console).
2. On the Play Console **Account details** page, enter your website URL.
3. Click **Verify website** and approve the request in Search Console.
