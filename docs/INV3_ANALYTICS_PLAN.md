# Inv3 product analytics plan (GA4 + Firebase)

This document is the execution plan to answer:

- Where users spend time
- Where users struggle
- Why users leave

It includes both **Google Analytics setup** and **in-app instrumentation**.

## 1) Success metrics (north-star and supporting)

Primary business outcomes:

1. `first_invoice_saved_rate` = users who save at least one invoice in first 24h / new users
2. `activation_rate_7d` = users who reach core activation event in 7 days / new users
3. `subscription_start_rate` = users who start paid plan / active users
4. `retention_d1`, `retention_d7`, `retention_d30`

Supporting diagnostics:

- `onboarding_completion_rate`
- `scan_to_save_success_rate`
- `import_to_save_success_rate`
- `paywall_view_to_subscribe_rate`
- Crash/ANR rate by app version and device

## 2) GA4 / Firebase setup checklist

1. Confirm Firebase project is linked to app and data stream is healthy.
2. Enable **Google Analytics (GA4)** and verify debug events from device.
3. Link **BigQuery export** (daily + intraday) for deep path analysis.
4. In GA4:
   - Create conversions for key success events.
   - Build funnel explorations (activation and subscription).
   - Build path explorations (events before churn).
   - Build retention/cohort explorations.
5. Add audiences:
   - New users (first 7 days)
   - Users with repeated failures
   - Users who viewed paywall but did not subscribe
6. Add custom dimensions for key parameters (for example `result`, `failure_reason`, `screen_name`).

## 3) Event taxonomy (recommended canonical names)

Use short, stable names and typed params.

### Core lifecycle

- `app_open`
- `session_start` (auto)
- `screen_view` (auto/custom)
- `screen_time_spent` (custom)

Params:
- `screen_name`
- `duration_ms`

### Onboarding/auth

- `login_started`
- `login_succeeded`
- `login_failed`
- `deep_link_auth_received`
- `deep_link_auth_failed`

Params:
- `provider`
- `result`
- `error_code`

### Home and navigation actions

- `home_action_clicked`
- `feedback_opened`

Params:
- `action` (`scan_camera`, `import_files`, `open_exports`, `open_guide`, `open_settings`, ...)
- `allowed` (`true`/`false`)
- `failure_reason` (`own_company_missing`, `scan_limit_reached`, ...)
- `source`

### Scanning/import funnel

- `scan_started`
- `scan_image_captured`
- `ocr_started`
- `ocr_completed`
- `ocr_failed`
- `invoice_save_started`
- `invoice_save_success`
- `invoice_save_failed`
- `import_started`
- `import_file_selected`
- `import_parsed`
- `import_save_success`
- `import_save_failed`

Params:
- `invoice_type`
- `duration_ms`
- `result`
- `error_code`
- `file_count`

### Subscription/paywall/billing

- `paywall_viewed`
- `subscription_flow_started`
- `subscription_flow_result`
- `billing_error`
- `subscription_started`
- `subscription_canceled` (if cancellation signal is available in-app/server)

Params:
- `source`
- `plan_id`
- `response_code`
- `result`
- `debug_message` (trimmed)

### Reliability/performance

- `api_request_failed`
- `api_request_slow`
- `screen_load_slow`
- `sync_failed`

Params:
- `endpoint`
- `duration_ms`
- `network_type`
- `error_code`

## 4) Funnel definitions

## Funnel A: activation

1. `app_open`
2. `login_succeeded`
3. `home_action_clicked` where `action in (scan_camera, import_files)` and `allowed=true`
4. `invoice_save_success`

Track drop-off by app version and country.

## Funnel B: subscription

1. `paywall_viewed`
2. `subscription_flow_started`
3. `subscription_flow_result` where `result=ok`
4. `subscription_started`

Track failures by `response_code` and `plan_id`.

## Funnel C: struggle diagnosis

1. `home_action_clicked` where `allowed=false`
2. `paywall_viewed` (if source is limit-related)
3. no `subscription_flow_result(result=ok)` within same session/day

This reveals revenue friction and unmet expectations.

## 5) Churn analysis design

Define "at risk":

- No app open for 7 days (for active cohort), or
- New user fails to reach `invoice_save_success` in first 24h.

Query leading indicators:

- Count of `home_action_clicked` with `allowed=false`
- Any `ocr_failed`, `invoice_save_failed`, `sync_failed`
- High `screen_time_spent` on complex screens without success event
- Crashes/ANR before last active session

Compare retained vs churned cohorts weekly.

## 6) Dashboard layout (Looker Studio or GA Explorations)

Page 1: Executive
- Active users, retention (D1/D7/D30), first invoice saved rate, subscription start rate

Page 2: Product funnel
- Activation funnel, subscription funnel, conversion by version/source

Page 3: Friction
- Top failure reasons, blocked actions (`allowed=false`), billing error codes

Page 4: Performance
- Slow screens, API slow/fail rates, crash-free users

Page 5: Cohorts
- Retained vs churned behavior comparison

## 7) What is already instrumented in code

Baseline tracking now includes:

- `screen_view` and `screen_time_spent`
- `home_action_clicked` for key home actions
- `paywall_viewed` from usage indicator/upgrade dialog/limit blocks
- `feedback_opened` from home
- `subscription_flow_started`, `subscription_flow_result`, `billing_error`

These are implemented via `app/src/main/java/com/vitol/inv3/analytics/AppAnalytics.kt` and integrated into navigation/home/billing code paths.

## 8) Next implementation tasks (high priority)

1. Add scan/import lifecycle events in scan/import viewmodels.
2. Add invoice save success/failure events in repository boundary.
3. Add auth result events in auth manager/login flow.
4. Add API latency/failure event wrappers around network calls.
5. Add churn batch queries in BigQuery and schedule weekly report.

## 9) Privacy and data minimization

- Do not send personal data in event params (names, emails, invoice content).
- Use IDs/enums and coarse categories only.
- Keep `debug_message` trimmed/sanitized.
- Update privacy policy when adding material analytics scope.

