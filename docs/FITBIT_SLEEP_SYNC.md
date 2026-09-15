# Fitbit sleep recovery and delivery

## Behavior

Sleep synchronization uses one process-wide coordinator shared by the activity, wallpaper engines, WorkManager, and FCM. Visibility requests check when the last attempt is over 30 seconds old; one two-minute loop runs while any clock surface is visible. WorkManager supplies a 15-minute network-connected fallback. Manual refresh, reconnect, and push request full two-week history; visible checks request yesterday and today. Requests arriving during a fetch are retained in a following batch.

The token manager serializes refresh, rechecks credentials under its lock, records expiry and identity atomically, and retries an unauthorized request once. Legacy credentials with unknown expiry are renewed on first use. Revoked credentials create one persistent reconnect episode. Network, server, rate-limit, and app-configuration errors do not ask for consent. Callback state, PKCE, exact URI, a ten-minute expiry, and one-time consumption protect authorization, including after process recreation.

Live Fitbit authorization on the Pixel appends the inert `#_=_` browser fragment. The validator accepts only that known fragment (or no fragment); it continues to reject other fragments, wrong endpoints, missing/duplicate codes, and mismatched state. Authorization values are read only from the query string.

Complete responses replace the requested date range, including deletions. Pending responses merge partial records by provider ID/session identity. Errors preserve the cache. A single DataStore transaction publishes sleep and status before derived calculations or other network requests. Fitbit's pending delay is milliseconds: a burst lasts up to 60 seconds, then retries after 1, 5, and 15 minutes. Rate-limit and pending server deadlines also constrain push/manual requests; transient failures use bounded backoff.

Exercise and backups retain their 15-minute maintenance schedule. Exercise history is reconciled daily, with yesterday/today refreshed between reconciliations, so repeated history downloads do not consume sleep's API budget. Sleep success alone refreshes sleep verification. Heart-rate, HRV, or backup success cannot make stale sleep appear current.

## User recovery and fading

Ordinary access-token expiry is silent. When refresh cannot restore authorization, the notification and dismissible **Reconnect Fitbit** modal open the existing browser authorization flow directly. **Later** keeps a reconnect button on the Sleep screen. Notification intents preserve navigation and are consumed once. Notification settings are linked when posting is disabled. Reconnect cancels the notification, checks sleep immediately, and enrolls push again. Browser login or renewed consent may still require the user; the app never launches consent from background work.

Android can restore an activity using its original notification intent after killing the process while the browser is open. Each notification carries an episode/installation request ID, whose consumption is persisted before opening authorization. Removing an extra from the in-memory intent alone does not prevent this replay. A repeated notification request is ignored; the Sleep-screen reconnect button remains available after cancellation.

Main sleep and naps use their existing purple colors, with opacity based on successful sleep verification:

| Verification | Opacity |
|---|---:|
| Under one hour | 100% |
| One to under four hours | 60% |
| Four hours or more | 30% |
| Reconnect required, disconnected, or never verified | 30% |

Opacity is recomputed during drawing and visibility changes. An unchanged successful response restores full opacity. The clock retains its existing fallback sleep section; the Sleep screen displays the actual session date, attempt, verification and data-change times, and separate push status. No glyph, dotted line, or permanent clock-face status text is added.

## Free relay deployment

- Cloudflare Worker: `clock-sleep-relay`; account subdomain `clock-sleep-personal.workers.dev`.
- Endpoint: `https://clock-sleep-relay.clock-sleep-personal.workers.dev/fitbit/webhook`.
- D1: `clock-sleep-relay`; queue: `clock-sleep-events`, 24-hour retention.
- Dedicated Firebase Spark project: `clock-sleep-relay-20260915`. Existing `acquiring-testing` is unrelated and unchanged.
- Fitbit subscriber: `clock-sleep-relay`; sleep subscription ID: `clock-sleep`.

Workers Free and Firebase Spark were verified in their consoles on 2026-09-15; Google billing is disabled. Never enable a paid plan, attach billing, or purchase a domain for this deployment. Free quota errors fall back to polling. Service limits can change: check the official [Workers](https://developers.cloudflare.com/workers/platform/limits/), [Queues](https://developers.cloudflare.com/queues/platform/pricing/), [D1](https://developers.cloudflare.com/d1/platform/pricing/), and [Firebase plan](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans) documentation before changing provisioning.

The relay validates Firebase anonymous ID tokens and introspects a supplied short-lived Fitbit access token before binding the configured account to an installation. It stores device routing, subscription health and delivery outcomes, never refresh tokens or sleep records. Enrollment is idempotent; FCM token rotation updates routing. Fitbit subscription conflicts are reported without deleting existing subscriptions. Daily FCM maintenance and a daily Android worker renew enrollment and check subscription health using credentials held on the phone.

The live Fitbit v1.1 introspection response observed on 2026-09-15 uses epoch **milliseconds** for `exp` and a map-like scope string such as `{ACTIVITY=READ, HEARTRATE=READ, SLEEP=READ}`. The relay normalizes this representation and RFC-style seconds/space-delimited scopes before applying its eight-hour lifetime bound and exact account/application restriction. Regression fixtures include the observed shape.

Webhook authentication uses Fitbit's HMAC-SHA1 signature over the raw body, keyed by the client secret plus `&`. Acceptance awaits queue storage before HTTP 204. Each received delivery gets a distinct opaque revision: identical webhook bodies can represent successive naps. Normal-priority, collapsible FCM messages carry only invalidation type and revision. Delivery retries skip already acknowledged device/revision pairs and remove explicitly unregistered FCM tokens. Delivery diagnostics expire after seven days.

### Setup / recovery

1. Use `wrangler login --device --browser=false` if a browser callback expires or fails. Device codes expire after five minutes.
2. From `relay`, run `npm ci`, `npm test`, `npm run check`, and `npm run dry-run`.
3. Create the free D1/Queue resources, apply `migrations/0001_devices.sql`, and update `wrangler.toml` for another account. Keep `preview_urls = false`.
4. Create a dedicated Firebase Spark Android app with the clock's package name. Enable **Anonymous** in Firebase Authentication. Initializing Authentication through the Firebase console works on Spark; the public Google Identity Platform initialization API requires billing and must not be used as a reason to upgrade.
5. Create an FCM service account with only `cloudmessaging.messages.create` in that dedicated project. Store its JSON in the Worker secret `FCM_SERVICE_ACCOUNT`. Keep private keys out of Git and app resources.
6. Set Worker secrets `FITBIT_CLIENT_ID`, `FITBIT_CLIENT_SECRET`, `FITBIT_ALLOWED_ACCOUNT`, and `FITBIT_VERIFY_CODE`. The last value must be the code **assigned by Fitbit** when adding the subscriber. After updating it, click Fitbit's **Verify** and confirm the timestamp. Never bulk-upload an older verification placeholder over the assigned value.
7. Set the Firebase public Android configuration and relay URL in ignored root `local.properties`, using `local.properties.example`. Blank relay configuration leaves sleep polling functional.
8. Deploy the Worker, build the app from the repository root, and upgrade using `adb install -r`. Do not clear app data. Reconnect Fitbit if refresh is revoked; successful authorization performs enrollment and creates/checks the sleep subscription.

`GET /health` is a basic reachability check. `/devices` (POST/DELETE) and `/status` (GET) require Firebase ID authentication. Use an ordinary app HTTP client for smoke tests: Python's default urllib user agent was rejected by Cloudflare with error 1010 during rollout; Node fetch and Android OkHttp were accepted without weakening Cloudflare protection.

## Validation and practical limits

Root checks: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`. Relay: `npm test`, `npm run check`, `npm run dry-run`. Deterministic tests cover token concurrency/recovery, typed responses, record corrections/deletions, same-day naps, midnight/civil-date changes, opacity boundaries, OAuth validation, queued overlapping requests, signatures, enrollment restrictions, token rotation, quota/outage handling, and delivery retries. Local D1 migration is checked separately.

On-device logs use `SleepSync` for sanitized save outcomes and `SleepRender` for time from persistence to drawing. No token or raw OAuth response is logged. Measure push receipt separately from upstream Fitbit availability. A push only indicates changed data; Fitbit may still be processing it. [Fitbit processing responses](https://dev.fitbit.com/build/reference/web-api/sleep/get-sleep-log-by-date-range/)

Normal-priority FCM and periodic work can be delayed in Doze. Visible polling and refresh-on-use remain necessary. These limits prevent an immediate-delivery guarantee while Android suspends background work. [FCM priority guidance](https://firebase.google.com/docs/cloud-messaging/android-message-priority)

Exact rollout results and outstanding device acceptance checks belong in the branch handoff; passing unit tests alone do not prove the two-second redraw or 30-second awake-phone push targets.
