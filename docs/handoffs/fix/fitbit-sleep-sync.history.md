# History: `fix/fitbit-sleep-sync`

Append-only milestones. Current state and next actions are in [fitbit-sleep-sync.md](fitbit-sleep-sync.md).

### 2026-09-15 — Branch created for reliable sleep delivery

- Base: local `main` at `1c4b1f4`, preserving existing local commits and unrelated work.
- Scope: sequential Android authentication/sync/status repairs, free TypeScript relay, and Pixel validation. Runtime model unknown; single agent. No billing or app-data clearing.

### 2026-09-15 — Android and free relay deployed; live sleep and push verified

- Implemented serialized recovery, explicit sleep outcomes, coordinator/retries, reconnect UI, opacity fading, and account-restricted silent push. Documented Google Health migration for later work.
- Provisioned Workers Free/D1/Queue and dedicated Firebase Spark resources; verified the Fitbit subscriber. Cloudflare device login recovered the failed browser authorization.
- Pixel authorization exposed the inert Fitbit `#_=_` callback fragment, and enrollment exposed millisecond expiry/map-like scopes in introspection. Both compatibility issues were repaired with regression coverage.
- Android 48 tests/build and relay 12 tests/type-check/deployment dry run passed. Real authorization, cache-preserving offline recovery, preview/home/lock redraws and awake/Doze push delivery were validated without clearing data. Awake push verification completed in 5.7–7.1 seconds; visible redraws in 25–119 ms.
- Owner authorization is still required before commit/push/PR/merge. Controlled delivery tests do not establish Fitbit's future upstream detection/processing latency.

### 2026-09-15 — Reconnect process-recreation acceptance completed

- Foreground reconnect reused existing browser login/consent and preserved the Sleep screen. Browser cancellation and a simulated valid-state denial preserved credentials and cached sleep.
- Killing the app while authorization was open exposed Android replaying the original notification intent, creating two authorization attempts. Notification request IDs now have persistent one-time consumption before browser launch.
- Repeated the real cold-start/process-death flow: saved OAuth state/PKCE survived, exactly one authorization completed, duplicate notification intents were ignored, and new request IDs still opened the flow.
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` passed (48 tests; label `android-reconnect-replay`). Upgraded the Pixel without clearing data. Final controlled push verified sleep in 6,020 ms and redrew in 29 ms; connectivity restored. No remaining blocked manual reconnect checks.

## 2026-09-15 — Fitbit checkpoint and Google Health preparation

Owner authorized and received local checkpoint `026782c` of completed Fitbit recovery/status/push work. Google Health API enabled under explicitly approved Developer Terms. Separate OAuth User Data Policy consent pending. Added inactive Python/Kotlin API adapters and conversion/state helpers; 55 Android tests and 20 Python migration tests passed. No provider activated or Google relay deployed. Resume from active handoff and migration note; preserve the working Fitbit installation.

## 2026-09-15 — Google migration implementation and free relay setup

Published personal OAuth application and created native clients. Wired Python/Android auth, provider adapters, coordinator/status/fading and shared fixtures. Deployed provider-aware relay and D1 migration; Google verified automatic sleep subscriber. Dedicated read-only subscriber identity leaves FCM sender privileges unchanged. Android 57 tests/build, Python regression/concurrent renewal tests, and relay 21 tests/typecheck/dry-run passed. First Google data grant and Pixel reconnection pending; no client cutover or migrated live acceptance claimed. Migration remains uncommitted.

## 2026-09-16 — Google cutover and live validation

Recovered expired desktop loopback sign-in and activated Python using the saved DPAPI grant. Sixteen paired sessions matched timing/time-in-bed/main; asleep totals differ (+3..48 minutes), documented without changing formulas. Fixed Android rejection of grants without email hints; Pixel activated and enrolled after data-preserving upgrade. Awake direct FCM verified in 2,848 ms/redrew in 52 ms; offline preservation/recovery and post-Doze delivery passed. Google owner routing and live subscriber health/enrollment verified. Final Python regression and Android 57 tests/build passed. Rejected-token fault-injection helper appended an empty preference entry; byte-verified repair preserved all named preferences/data and restored operation. SDK renewal acceptance remains unclaimed, along with natural upstream webhooks and listed visual/reconnect checks. Migration remains uncommitted.
