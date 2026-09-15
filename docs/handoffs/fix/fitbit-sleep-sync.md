# Branch: `fix/fitbit-sleep-sync`

## Identity

- Base: local `main` at `1c4b1f4`; preserve its existing local commits.
- Role: Fitbit sleep reliability and free push delivery.
- Worktree: `H:/Desktop/widgets/24-hr_clock_widget` (single agent, primary checkout).
- Runtime model: unknown. Sequential Android, TypeScript relay, then local/Pixel validation.
- Owned paths: Android sleep/auth/render integration, `relay/`, configuration example and related docs.
- History: [fitbit-sleep-sync.history.md](fitbit-sleep-sync.history.md)
- Last verified: 2026-09-15.

## Goal and scope

Deliver main sleep/naps promptly after Fitbit exposes them, silently renew ordinary expiry, preserve/fade stale sleep, and support personal devices with silent push. Workers Free and dedicated Firebase Spark only; no billing, domain purchase, app-data clearing, or changes to `acquiring-testing`.

Implementation/recovery: [FITBIT_SLEEP_SYNC.md](../../FITBIT_SLEEP_SYNC.md). Deferred Google Health work: [GOOGLE_HEALTH_MIGRATION.md](../../GOOGLE_HEALTH_MIGRATION.md). Existing preview-label overlap and unrelated energy behavior are excluded.

## Changes since branch creation

Shared token renewal and atomic credentials; typed sleep results and authoritative range reconciliation; one coordinator for visibility/manual/push/background with retries; immediate sleep/status publication before other work; reconnect modal/notification with state/PKCE; opacity-only freshness; account-restricted queued FCM relay and subscription health.

Live Fitbit callbacks append `#_=_`; only this inert fragment or no fragment is accepted. Live introspection uses millisecond expiry and map-like permissions. Both compatibility fixes have regression coverage.

## Current status

Android and relay are deployed and working on the Pixel 7a. Fresh authorization reused its browser login, recorded expiry, cleared the reconnect episode/notification, and downloaded September 15 sleep (old cache ended September 10). Push is Ready, with an active subscription. All upgrades used `adb install -r` and preserved data.

- Worker: `https://clock-sleep-relay.clock-sleep-personal.workers.dev`; version `b9f9f176-6ebb-49ad-a28b-599338db011d`.
- D1 `clock-sleep-relay`, ID `88cbbe0d-be11-4301-8ce3-ba4aabd9059c`; queue `clock-sleep-events`.
- Firebase Spark `clock-sleep-relay-20260915`; Android app `1:856746798164:android:f1d2ae677f56b6b9a251c1`; billing disabled.
- FCM sender has only `cloudmessaging.messages.create`. Secrets are in Worker secrets; Android configuration is in ignored root `local.properties`. Do not print either. Temporary provisioning secret files were removed.
- Fitbit subscriber `clock-sleep-relay` verified; sleep subscription `clock-sleep`. One active device; controlled push delivery is healthy.
- Wrangler device login resolved timed-out Cloudflare browser OAuth.

### Validation

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` — PASS, 48 tests/seven suites, no failures (wrapper label `android-reconnect-replay`). Root wrapper; Android Studio `jbr`.
- In `relay`: `npm test`, `npm run check`, `npm run dry-run` — PASS, 12 tests (label `relay-live-shape-tests`). D1 migration passed; `npm audit --omit=dev --json`: zero production vulnerabilities.
- `npx wrangler deploy` — PASS. Live Firebase authentication/status, rejection of unauthenticated/bad-verification requests, and Fitbit subscriber verification passed.
- Pixel real authorization: recovered invalid refresh credentials; fresh sleep restored purple opacity. Preview Complete-response redraws: 40–119 ms.
- Awake preview push: webhook accepted 820 ms, received 5,808 ms, verified 7,069 ms, redrawn 54 ms after persistence.
- Home wallpaper/app background: identical second webhook got a distinct revision; accepted 598 ms, received 4,492 ms, verified 5,706 ms, redrawn 26 ms.
- Offline refresh: NETWORK, cache/verification time unchanged, no reauth. Automatic retry recovered after connectivity returned.
- Forced Doze: accepted 1,369 ms; delivery deferred until wake, then received 2,923 ms/verified 4,568 ms. Lock redraws 25–80 ms. Forced idle/battery simulation reset; original Wi-Fi/mobile-data settings restored.
- Preview/home/lock visually checked.
- `python scripts/validate_handoffs.py --quiet` and `git diff --check` — PASS. The local validator does not support `--require-current-handoff`.

### Limits and remaining acceptance

Controlled pushes fetched existing Fitbit records; they do not measure future Fitbit detection/processing delay. Natural Android background scheduling remains subject to Doze. Multi-device routing, corrections/deletions, pending/rate/quota failures, token concurrency and fading boundaries use deterministic/local tests without changing the user's health history.

Live ADB notification-intent tests passed: foreground/cold starts, browser cancellation, simulated valid-state `access_denied`, and process death during authorization. Cancellation/denial preserve credentials, cache and navigation. A replay bug was fixed by persisting notification request consumption; recreation now authorizes exactly once and duplicate intents are ignored. Final installed-build push verified in 6,020 ms and redrew in 29 ms. Serial `3C081JEHN14930`; ADB under `C:/Users/user1/AppData/Local/Android/Sdk/platform-tools/adb.exe`. Private recovery backup: `%TEMP%/clock-sleep-preupgrade.preferences_pb`; never print/commit.

## Next actions

1. Observe a future real sleep/nap, separating Fitbit publication time from delivery latency.
2. Implement Google Health migration separately using the migration note.

## Merge and cleanup

PR base `main`. Owner authorized a local checkpoint commit of the completed Fitbit work on 2026-09-15. Push/PR/merge still require authorization. No sibling worktree created/removed. After authorized merge, verify remote-default reachability and archive handoff/history; obtain missing cleanup authorization. Never delete branch documentation.
