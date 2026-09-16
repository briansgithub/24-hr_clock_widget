# Branch: `fix/fitbit-sleep-sync`

## Identity and scope

- Base local `main` at `1c4b1f4`; preserve existing work. Single agent, primary checkout `H:/Desktop/widgets/24-hr_clock_widget`. Runtime model unknown.
- Migrate Python/Android clocks and relay to Google Health, preserving caches/settings/energy and Fitbit reliability repairs. Two-minute visible checks; google-sources includes wearables/manual/Health Connect.
- Cloudflare Free and dedicated Firebase Spark only; no billing/domain purchase/paid assessment. Leave `acquiring-testing` untouched. Never clear phone data or automatically revoke Fitbit credentials.
- History: [fitbit-sleep-sync.history.md](fitbit-sleep-sync.history.md). Details: [GOOGLE_HEALTH_MIGRATION.md](../../GOOGLE_HEALTH_MIGRATION.md).

## Completed baseline

Owner-authorized Fitbit checkpoint: `026782c`. Prior Fitbit test/timing results are not Google acceptance.

## Migration active on both clients

- Google Health enabled in `clock-sleep-relay-20260915`, number `856746798164`, with owner-approved Terms. OAuth **24-Hour Clock** External app published to production; separate Android/desktop clients created. Personal unverified-app notice remains expected.
- Only sleep and health-measurement read scopes. User grant completed. Expired desktop loopback listener caused the refused connection; fresh authorization worked. DPAPI credentials saved; no helper remains running. Old error tab can be closed.
- Python HealthIntegration uses DPAPI/state/PKCE, serialized renewal, shared polling, early sleep publication, status/reconnect and fading. Compatibility outputs retained.
- Pixel upgraded without clearing data and activated after identity/full history verification. Health-only grant returned no email hint: removed mandatory-email rejection; validate identity on renewal too. App shows Google Health, Sleep verified, push Ready, 16 records.
- Shared fixtures cover main/two naps/manual/travel/DST/mixed classification. All pages must complete before deletion/verification. Prefer live `mainSleep`/`dataPointName`; accept old aliases. HR uses UTC-minute averages. No workout import/model refit.
- Python active Google and polling CURRENT; retain heart points across sleep-only checks and compatibility files. Sixteen paired sessions match times/time-in-bed/main. Reported asleep totals differ +3..48 minutes; no formula refit. Live HR 239 UTC-minute buckets, RHR and RMSSD available. Exact energy parity unproven.

## Relay and setup

- Worker `https://clock-sleep-relay.clock-sleep-personal.workers.dev`; Google route deployed alongside Fitbit. D1 `clock-sleep-relay`, ID `88cbbe0d-be11-4301-8ce3-ba4aabd9059c`; migrations 0001/0002 applied; queue `clock-sleep-events`.
- Google subscriber confirmed live: `projects/856746798164/subscribers/clock-sleep`, AUTOMATIC sleep. Endpoint handshake passed. Events require auth + raw-body Tink ECDSA, rotating keyset; queue ack before 204.
- Dedicated SA `clock-health-reader` custom role contains only `health.subscribers.list`; key in Worker secret. FCM sender unchanged (`cloudmessaging.messages.create` only). Setup owner CLI token requires `x-goog-user-project` set to dedicated project.
- Worker Google secrets complete, including verified GOOGLE_ALLOWED_ACCOUNT. Live Firebase-authenticated Google enrollment returned active; temporary device/user removed afterward. Pixel enrollment Ready. Legacy route retained.
- Live subscriber flag is `endpointAuthorization.secretSet`; old guide's `authorizationTokenSet` also accepted. Cloud Billing API disabled; not enabled. No paid settings changed.
- Desktop config ignored `python/google_health_client.json`, credentials `*.dpapi`; never print/commit. Root ignored `local.properties` contains Firebase/Fitbit config. Android signing SHA-1 in migration doc.

## Validation

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` passed: 57 tests (`google-health-android-account-hint`). JAVA_HOME Android Studio jbr.
- `python -m unittest discover -s python -p "test_*.py"` passed after final Python edits (`google-health-desktop-final-regressions`). Targeted health tests passed. Prior eight-caller renewal/DPAPI checks passed.
- Relay 21 tests/typecheck/dry-run passed; D1 migration/deployment passed. Crypto/rotation, account/provider restrictions, quota/outages, handshake and live subscriber shape tested.
- Google direct awake FCM: receive 735 ms, verification 2,848 ms, redraw 52 ms. Preview/home purple visible. Offline preserved logs/verification, NETWORK without reconnect; online restored CURRENT. Forced idle deferred FCM; verified after exit (19,224 ms total). Connectivity/battery/idle restored.
- Rejected-token fault-injection helper appended an empty preference entry and caused startup failure. Removed only that artifact with byte-verified copy; all named preferences/credentials/16 records preserved; app restored CURRENT, push Ready. SDK renewal test remains unclaimed. Do not use that helper's stdin binary-write path.

## Resume

1. Observe natural Google sleep/nap webhook through relay; direct FCM acceptance is not upstream latency.
2. Reliable Android SDK renewal/denial/reconnect replay/process recreation tests; actual locked wallpaper and desktop visual freshness checks. Lock preview passed; wake returned to unlocked launcher. Native automation did not expose borderless desktop widget.
3. Preserve active operation. Already-staged desktop grant can activate via `python python/stage_google_health.py --legacy-account 6D7XXB --activate-verified` only with widget stopped. No second state writer. Fresh setup supports --timeout 3600; never reuse callback codes.

## Git

Owner authorized this migration checkpoint. Remaining validation is listed above. Push/PR/merge still require authorization under REPOSITORY.md. No worktree/branch cleanup performed; never delete branch documentation.
