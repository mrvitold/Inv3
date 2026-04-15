# Meta Ads measurement (Inv3)

How to track **CPI**, **installs → subscriptions**, and **Meta App Events** (Phase B) for optimization beyond installs.

## 1. Google Play Console (no code)

### Installs and subscriptions by traffic source

1. **Play Console** → **Grow** → **User acquisition** → **Traffic sources** (or **Acquisition reports** depending on UI version).
2. Filter by **campaign** / **UTM** if reported.
3. Compare **Store listing visitors** → **Installers** → **Buyers** (subscription reporting depends on Play billing setup).

### UTM parameters

Use the URLs documented in [META_ADS_CAMPAIGN.md](META_ADS_CAMPAIGN.md). Consistent naming makes month-over-month comparison easier.

### Uninstalls

**Play Console** → **Quality** → **Android vitals** / **Ratings and reviews** — monitor uninstall trends after campaign spikes.

For full in-app product behavior analytics (screen time, struggle points, churn diagnostics), see [INV3_ANALYTICS_PLAN.md](INV3_ANALYTICS_PLAN.md).

## 2. KPIs at €10/day (realistic)

| Metric | Where | Note |
|--------|--------|------|
| **Impressions / Reach** | Meta Ads Manager | Awareness |
| **CTR** | Meta | Creative quality |
| **CPI** | Meta (cost / installs) | Primary Phase A KPI |
| **Installs** | Meta + Play | Should roughly align |
| **Subscription revenue** | Play Console | Phase B business KPI |

## 3. Meta App Events (Phase B) — Android app

The Inv3 app includes optional **Meta (Facebook) SDK** integration:

- When `FACEBOOK_APP_ID` and `FACEBOOK_CLIENT_TOKEN` are set in Gradle (see below), the SDK initializes and sends **Subscribe** (`fb_mobile_subscribe`) with **EUR** value when a **new** Google Play subscription purchase is completed (paid plans: Basic / Pro / Accounting).

### Gradle configuration (local, do not commit secrets)

1. Open [Meta app dashboard](https://developers.facebook.com/apps) → your Inv3 app → **Settings**.
2. Copy **App ID** (numeric) and **Client token** (under **Advanced** or **App settings**, depending on UI).
3. Add to **root** [`gradle.properties`](../gradle.properties) (already contains placeholders):

```properties
FACEBOOK_APP_ID=1234567890123456
FACEBOOK_CLIENT_TOKEN=your_client_token_here
```

Leave both empty to keep the Meta SDK disabled. See also [`gradle.properties.example`](../gradle.properties.example).

Rebuild the app (any variant). Events appear in **Events Manager** → **Test events** (when testing on a device) and in reporting after delay.

### Standard event logged

| Event | When | Value |
|-------|------|--------|
| `Subscribe` | After a successful new subscription purchase (not FREE) | **EUR**: 7 / 17 / 39 by plan |

### Optimization in Ads Manager (later)

Once **Subscribe** events accumulate volume:

1. Duplicate or create a new **App promotion** ad set.
2. Optimization goal: **App events** → **Subscribe** (or **Value** if value optimization is available and data supports it).

Low daily spend may not generate enough events for stable optimization; keep a parallel **installs** campaign if needed.

## 4. Testing events

1. Add your device as a **test device** in Meta app settings if required.
2. Use **Events Manager** → **Test events** while performing a **test purchase** (Play billing test track).
3. Confirm **Subscribe** fires once per successful purchase.

## 5. Privacy

- Disclose analytics/attribution in **Privacy Policy** if you collect app events (already aligned with app store requirements; update text if you add new SDKs materially).

## 6. Troubleshooting

| Issue | What to check |
|-------|----------------|
| No events | `FACEBOOK_APP_ID` / `FACEBOOK_CLIENT_TOKEN` empty → SDK disabled |
| Duplicate Subscribe | Should only fire on new purchase acknowledgment path |
| Meta vs Play revenue mismatch | Cancellations, trials, currency, reporting delay |
