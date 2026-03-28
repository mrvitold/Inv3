# Meta Ads setup (Inv3)

Step-by-step checklist to create **Meta Business**, **ad account**, register the **Android app** for app events, and set a **€10/day** budget cap. Dashboard steps change over time; names below match Meta’s UI as of early 2026.

## Prerequisites

- **Facebook account** (personal is fine to start; you can add a business identity later).
- **Google Play** listing URL for Inv3:  
  `https://play.google.com/store/apps/details?id=com.vitol.inv3`
- **Package name:** `com.vitol.inv3`

## 1. Meta Business Suite / Business Manager

1. Go to [business.facebook.com](https://business.facebook.com) and create a **Business Portfolio** (or use an existing one).
2. Add **People** and **Assets** as needed (you can be the only admin at first).
3. Create or connect a **Facebook Page** for Inv3 (used for some ad placements and trust).

## 2. Ad account and billing

1. In Business settings, open **Accounts → Ad accounts** → **Add** → **Create a new ad account**.
2. Set **Currency** to **EUR** and **Time zone** (e.g. Vilnius).
3. Add a **payment method** (card or other).
4. **Spending limit (recommended):** In **Ad account settings** → **Billing** / **Payment settings**, set an **account spending limit** if available (e.g. **€300/month** as a safety net alongside daily budget).

## 3. Register the Android app (Events Manager)

1. Open [Events Manager](https://business.facebook.com/events_manager2).
2. Click **Connect data sources** → **App** → **Connect**.
3. Choose **Android**, enter app name **Inv3** and **Package name** `com.vitol.inv3`.
4. Complete the wizard. You will get:
   - **App ID**
   - **Client token** (Settings → Basic → **App secret** is different; use **Client token** from the app dashboard for the SDK).
5. In the app dashboard ([developers.facebook.com](https://developers.facebook.com) → your app), note:
   - **App ID**
   - **Client token** (under **Settings → Advanced** or **App settings** depending on UI)

Add these to your **local** Gradle config (do not commit secrets):

```properties
# In ~/.gradle/gradle.properties or project gradle.properties (gitignored locally)
FACEBOOK_APP_ID=your_numeric_app_id
FACEBOOK_CLIENT_TOKEN=your_client_token
```

See [META_ADS_MEASUREMENT.md](META_ADS_MEASUREMENT.md) for wiring into the Android build.

## 4. Link Instagram (optional but useful)

1. In **Business settings** → **Accounts** → **Instagram accounts**, connect a professional/creator account for Inv3.
2. This unlocks Instagram placements for the same campaigns.

## 5. Roles and access

1. **Business settings** → **Users** → ensure you have **Admin** on the ad account and app.
2. If you work with an agency later, add them under **Partners** with least privilege.

## 6. Daily budget €10

When you **create a campaign** (see [META_ADS_CAMPAIGN.md](META_ADS_CAMPAIGN.md)):

1. Set **Campaign budget** or **Ad set budget** to **€10** per day (not lifetime, unless you intentionally choose lifetime).
2. Confirm **Currency: EUR**.

## 7. Before going live

- [ ] Play Store URL opens correctly on mobile.
- [ ] Privacy policy URL is valid (see repo `PRIVACY_POLICY.md` / website).
- [ ] Ad copy avoids unverified claims about VMI/i.SAF (see [marketing creatives](../marketing/meta-ads/creatives-lt.md)).

## Troubleshooting

- **App not verified:** For app install ads, Meta may require app association; follow **Events Manager** prompts for **Google Play** linking.
- **No installs:** Check country (**Lithuania**), age, placements, and creative fatigue (refresh creatives every 1–2 weeks).
