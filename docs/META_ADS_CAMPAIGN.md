# Meta Ads campaign launch (Inv3)

Operational guide for **Advantage+ app** (or **App promotion**) campaigns: **Lithuania**, **Lithuanian**, **~€10/day**, destination **Google Play** with **UTM** tracking.

## Google Play destination URL (with UTM)

Use a single canonical store URL and append UTM parameters so **Google Play Console** can attribute installs by source.

**Base URL**

```text
https://play.google.com/store/apps/details?id=com.vitol.inv3
```

**Example: Meta paid, Lithuania i.SAF angle, 2026 Q1**

```text
https://play.google.com/store/apps/details?id=com.vitol.inv3&utm_source=meta&utm_medium=paid_social&utm_campaign=lt_isaf_2026_q1&utm_content=advantage_plus
```

| Parameter | Example | Purpose |
|-----------|---------|---------|
| `utm_source` | `meta` | Channel |
| `utm_medium` | `paid_social` | Paid vs organic |
| `utm_campaign` | `lt_isaf_2026_q1` | Your campaign name |
| `utm_content` | `variant_a` or `advantage_plus` | Creative or ad set label |

**How to use in Ads Manager**

1. At ad level, set **Website URL** / **Link** to the full URL above (some flows use “App” objective and Play Store link field — paste the same string).
2. Use **different `utm_content`** per creative variant to compare in Play Console.

## Campaign settings (recommended starting point)

| Field | Value |
|-------|--------|
| Objective | **App promotion** (or **Sales** with app if your UI shows it) |
| App | Inv3 — Android — `com.vitol.inv3` |
| Optimization | **App installs** (Phase A; see strategy plan) |
| Daily budget | **€10** EUR |
| Country | **Lithuania** |
| Languages | **Lithuanian** (optional language targeting if available) |
| Age | **25–55** (widen later if needed) |
| Placements | **Advantage+ placements** (or Automatic) |

## Advantage+ app campaign (typical path)

1. **Ads Manager** → **Create** → **App promotion**.
2. Select **App installs** as the performance goal.
3. Choose **Inv3** (Android) from the list — requires Events Manager app setup.
4. Audience: **Lithuania**; avoid over-narrowing interests at €10/day.
5. Upload **3–5** creatives from [marketing/meta-ads/creatives-lt.md](../marketing/meta-ads/creatives-lt.md).
6. Destination: **Google Play** with the **UTM** URL.
7. Publish and let the campaign exit learning phase (**~50** optimization events per week is a rough guide; installs count as events).

## Landing page vs Play Store

- **Direct Play URL (recommended for Phase A):** Fewer taps, simpler attribution.
- **Website then Play:** Use only if [website](../website/README.md) loads fast and has a prominent **Get it on Google Play** button; add the same UTMs on the Play button link.

## Checklist before launch

- [ ] **Facebook Page** (and Instagram if used) connected.
- [ ] **Billing** active on the ad account.
- [ ] **App** registered in Events Manager.
- [ ] **UTM** on every ad link.
- [ ] **Creatives** in Lithuanian; no unverified regulatory claims.

## After launch

- Review **CPI** (cost per install) and **install volume** in Ads Manager.
- In **Play Console** → **User acquisition** → **Traffic sources**, verify **campaign** / **utm** breakdown (may take 24–48 hours).
- Refresh creatives if **frequency** &gt; **3–4** on small audiences.
