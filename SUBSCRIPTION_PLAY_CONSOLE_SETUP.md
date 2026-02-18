# Google Play Subscription Setup for Plan Changes

## Problem: "Plan change failed" (Billing Code 7)

When users try to change from one plan to another (e.g. pro_monthly → basic_monthly), they may see:
**"Plan change failed. To switch plans: open Google Play, cancel your current subscription, then subscribe to the new plan here."**

This happens when **basic_monthly**, **pro_monthly**, and **accounting_monthly** are not in the same **subscription group** in Google Play Console.

## Fix: Put All Plans in the Same Subscription Group

For in-app plan changes (upgrade/downgrade) to work, all subscription products must be in the **same subscription group**.

### Steps in Google Play Console

1. Go to **Monetize** → **Products** → **Subscriptions**
2. Create or select a **Subscription group** (e.g. "Inv3 Plans")
3. Add all three products to this group:
   - `basic_monthly`
   - `pro_monthly`
   - `accounting_monthly`

### Alternative: Single Product with Multiple Base Plans

You can also use one subscription product with multiple base plans (basic, pro, accounting). This requires more restructuring and product ID changes in the app.

### After Configuration

- Changes may take a few hours to propagate
- Test with an internal testing track
- In-app plan changes should then work without Code 7

## Cancel Subscription

The "Manage subscription in Google Play" button opens the Play Store subscription management page. Users can:
- Change plans (if in same group)
- Cancel (subscription stays active until end of billing period)
- Resume a cancelled subscription
