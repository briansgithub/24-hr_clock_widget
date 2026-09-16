# Google Health migration follow-up

Research date: 2026-09-15; live rollout updated 2026-09-16. Both clocks now use Google Health; Fitbit credentials and recovery caches are retained. The legacy shutdown notice names **September 2026** without a precise day here. The repair cannot extend the legacy service's availability. [Official notice](https://developers.google.com/health/about)

## Verified requirements

| Area | Required accommodation | Official reference |
|---|---|---|
| Authorization | Existing Fitbit access and refresh tokens cannot transfer. Add Google OAuth, offline access and user re-consent. Keep cached data while reconnecting. | [Migration](https://developers.google.com/health/migration) |
| Refresh lifetime | Google OAuth projects in Testing issue refresh tokens expiring after seven days for these scopes. Resolve publishing and test-user requirements before relying on unattended recovery. | [Setup](https://developers.google.com/health/setup) |
| Permissions | Register Google Health access and request minimal read scopes, including `googlehealth.sleep.readonly`. Confirm review/verification requirements for this personal deployment. | [Scopes](https://developers.google.com/health/scopes), [Setup](https://developers.google.com/health/setup) |
| Identity | Use `getIdentity` to map legacy Fitbit identity to `healthUserId`. Do not assume identical identifier formats or lengths. | [Identity migration](https://developers.google.com/health/migration) |
| Sleep API | Adapt v4 sleep data points, pagination, stable record IDs, interval timestamps, UTC offsets and civil dates. Main sleep comes from metadata; duration/processing fields differ. | [Endpoint examples](https://developers.google.com/health/endpoints) |
| Webhooks | Replace Fitbit subscriber setup and HMAC-SHA1 verification with Google subscriber APIs and Tink signatures. Handle rotating public verification keys; Google documents a different retry policy. | [Webhooks](https://developers.google.com/health/webhooks) |

## Compatibility tests still required

- Map sleep `metadata.main`, processing state, `summary.minutesAsleep` and `summary.minutesInSleepPeriod` to the clock model. Confirm numeric/string representation, units, naps, manual records, corrected/deleted sessions and time-in-bed parity. Do not equate similarly named fields without checking sample responses.
- Reconcile paginated data across midnight, daylight-saving changes, phone/provider timezone differences and UTC offsets. Preserve stable IDs as strings. Select and document the appropriate source family; Google wearables versus combined Google sources can differ in manual records and reconciliation.
- Compare historical coverage and backfill gaps before switching the provider. Verify both successful empty responses and partially processed records against the existing Complete/Pending/Failed contract.
- Map heart rate, HRV and exercise separately, including units, missing data, source selection and historical coverage. Verify resulting energy calculations against Fitbit before enabling the new provider.
- Reuse credential/status/reconnect interfaces, sleep coordinator, queue and FCM routing, while keeping provider-specific OAuth, parsing and signature verification separate. Switch a device only after the new provider successfully verifies sleep. Preserve cached Fitbit data during consent and backfill.
- Test Tink key fetching, cache lifetime, rotation overlap, algorithm/key selection, signature rejection and replay/duplicate delivery handling using Google's official examples. The current HMAC verifier must not be reused for Google signatures.
- Confirm publishing, consent-screen verification, API access, webhook delivery and all other dependencies remain compatible with **zero running cost**. Do not enable billing or upgrade Firebase/Cloudflare automatically. A documented feature existing does not prove personal-project eligibility or a free quota.

Treat the official pages and live discovery schema as the source of truth. See the implementation status below before resuming.

## Implementation and rollout status — 2026-09-16

The previous Fitbit repairs are committed as `026782c`. This checkpoint includes the Google migration and its recorded validation limits. Python and the Pixel have switched independently after Google identity and complete sleep-history verification. No app data was cleared.

### Account and infrastructure setup completed

- Google Health enabled in `clock-sleep-relay-20260915` (project number `856746798164`), with owner-approved Developer Terms. OAuth application **24-Hour Clock** is External and published to production for personal use. No billing/paid plan enabled; `acquiring-testing` untouched.
- Separate desktop and Android OAuth clients created. Android package `com.example.a24_hr_clock`, debug SHA-1 `02:9B:7C:C1:17:72:1A:56:DF:1C:30:A4:9D:34:6E:29:07:6C:AB:66`. Desktop configuration is ignored `python/google_health_client.json`; never commit or print it.
- Only the sleep and health-metrics read scopes are requested. The user grant completed; live identity, sleep, heart-rate, resting-heart-rate and HRV requests succeeded. Published personal apps still show Google's unverified-app notice.
- Homepage and privacy policy are served by the existing free Worker. D1 migration `0002_provider.sql` applied, provider-aware relay deployed, and Google verified its endpoint handshake.
- Automatic sleep subscriber confirmed through the live list API: `projects/856746798164/subscribers/clock-sleep`. Endpoint is `/google-health/webhook` on the existing Worker.
- Dedicated `clock-health-reader` service account has custom role `clockHealthSubscriberReader`, containing only `health.subscribers.list`. Its key is stored only in Worker secrets. Subscriber administration uses the owner's setup session; the FCM sender remains limited to `cloudmessaging.messages.create`.
- Worker secrets added: `GOOGLE_WEBHOOK_AUTH`, `GOOGLE_SUBSCRIBER_READER`, `GOOGLE_SUBSCRIBER_NAME`. `GOOGLE_ALLOWED_ACCOUNT` is set to the verified healthUserId. Live Firebase-authenticated Google enrollment returned subscription `active`; its temporary device and anonymous user were removed afterward. The Pixel subsequently enrolled and shows push `Ready`.

### Client implementation

- Android uses AuthorizationClient, silent token acquisition/one rejected-token retry, foreground resolution, identity validation and atomic provider/cache cutover. Existing reconnect/status/coordinator/fading flows are reused. Legacy responses cannot overwrite Google credentials or sleep; push registration and delivery are provider-scoped.
- Python uses InstalledAppFlow with state/PKCE and offline access, DPAPI credentials and serialized renewal. The widget is wired to the new coordinator, two-minute visible polling, refresh on use, 15-minute hidden polling, persistent status/reconnect action and opacity-only purple fading. Saved sleep publishes before metrics/backups.
- Both adapters read all reconciled pages from `google-sources`, retain true UTC instants/recorded offsets and expose legacy-compatible civil projections. Pending/failed responses preserve unconfirmed cache records. Google credentials and Fitbit recovery caches remain separate.
- Shared synthetic fixtures cover main sleep, two naps, manual entries, travel, DST and mixed classification. Metrics use deduplicated UTC minute buckets; no workout import or energy-model refit was added.

### Verified schema differences from guide examples

- Reconciled record ID is `dataPointName`; old `name` is accepted. Main classification is `metadata.mainSleep`; old `main` is accepted, with the live field preferred. `nap` remains separate. If no explicit main exists, infer among unclassified records without promoting explicitly non-main sessions.
- Subscriber list responses use `endpointAuthorization.secretSet`; accept older documented `authorizationTokenSet` too. Subscriber health uses the list API (there is no subscriber-get operation in the published API).
- Endpoint setup sends authorized and unauthorized unsigned `{"type":"verification"}` requests, requiring 200 and 401 respectively. Health notifications require both the configured Authorization value and the raw-body Tink ECDSA signature. Key selection, protobuf coordinates and DER-to-Web-Crypto signature conversion are tested.
- `{}` is a valid complete empty protobuf response only after successful HTTP and complete pagination.
- Session elapsed time is currently projected as time-in-bed, separately from reported asleep minutes. Paired comparison matched elapsed duration/time-in-bed exactly for 16 shared sessions. See the provider differences below.

### Checks completed

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` — passed, 57 tests; latest build includes optional Android account hints and identity validation on renewal (`google-health-android-account-hint`).
- `python -m unittest discover -s python -p "test_*.py"` — passed after saved-grant activation and heart-cache projection fixes (`google-health-desktop-final-regressions`). Targeted `test_health*.py` discovery also passed.
- `python python/test_google_health_client.py` — passed, including eight overlapping callers performing one renewal (`google-health-credential-concurrency`).
- `python -m compileall -q python` — passed before the staging helper was added; staging helper syntax checked separately at rollout preparation.
- Relay `npm test` — 21 tests passed; `npm run check`, `npm run dry-run` and deployment passed. Tests include real ECDSA fixtures, key rotation, tampering, signature failures, setup handshake, batching/deletions, identity restrictions, provider routing, FCM/queue/D1 failures and live-shape subscriber health.
- Google subscriber list confirmed AUTOMATIC sleep configuration after creation. This proves endpoint setup, not live health-event or FCM delivery.

### Live rollout and provider comparison

- The original desktop loopback listener expired before its callback arrived. A new foreground authorization completed, saved DPAPI-encrypted credentials, and staged 17 records. A fresh identity/history check activated Python using the saved grant; 16 records remain in the current range after midnight. The restarted widget continues successful Google checks and retains 239 heart-minute points in its compatibility projection.
- Compared 16 shared Fitbit/Google sessions: start/end, elapsed duration, time-in-bed and main classification matched exactly. Google's reported asleep totals were **3–48 minutes higher per session** (310 minutes total). Keep Google's reported asleep minutes as requested; no formula refit or subtraction was applied. Short awakenings explain some differences but do not establish exact parity. Energy outputs can differ when provider inputs differ.
- Live metrics: 239 unique UTC-minute heart-rate buckets for the latest main session; daily resting-heart-rate and daily RMSSD each returned one record. This validates access and mapping, not full historical metric equivalence.
- Pixel upgrade used `adb install -r`. Health-only AuthorizationClient returned tokens/scopes but **no email/account hint**. The previous mandatory email check rejected that valid grant. Account hints are now optional; `/identity` binds both initial and renewed tokens to the configured owner. No email/profile scope was added. First Google activation, complete history, app preview and home wallpaper, and real device enrollment passed.
- Controlled normal-priority FCM invalidation on the awake Pixel: received in **735 ms**, Google sleep verified in **2,848 ms**, renderer reported **52 ms** from persistence to redraw. This is FCM-to-phone acceptance, not a natural Google-webhook latency measurement.
- Offline manual refresh preserved cached records and successful-verification time, reported NETWORK, and did not request reconnection. Restoring connectivity and refreshing returned CURRENT on Google. An earlier offline attempt raced network shutdown and was inconclusive.
- Forced deep idle deferred normal-priority FCM during a 15-second observation; verification completed after leaving idle, **19,224 ms** after sending. Forced idle, simulated battery state, Wi-Fi and mobile data were restored.
- A final rejected-token fault-injection helper added an empty entry to the phone's preferences file through its stdin transfer and caused a temporary startup failure. Removed only that empty artifact using a byte-verified file copy; all named preferences, real credentials and 16 sleep records were preserved. The app is again CURRENT, Google-connected and push Ready. **Do not count that fault-injection attempt as a successful SDK renewal test.** Avoid binary preference writes through that helper's stdin path.

### Recovery and remaining validation

- Desktop credentials already work; do not request another grant just because an old localhost error tab remains. That tab can be closed. For a genuinely expired/canceled setup flow, restart `python python/stage_google_health.py --legacy-account 6D7XXB --timeout 3600` and use the new URL. Never reuse or log callback codes.
- For a staged grant not yet activated, stop the widget first and run `python python/stage_google_health.py --legacy-account 6D7XXB --activate-verified`, then restart it. This revalidates identity and complete history without another browser grant. Never run a second state writer alongside the widget.
- Still required: observe a **natural new Google sleep/nap webhook** through the relay; verify migrated Android renewal with a reliable test harness, permission denial/reconnect replay/process recreation; verify actual locked wallpaper and desktop visual freshness boundaries. Lock preview passed, but the wake test returned to the unlocked launcher. Desktop polling/state were verified; the borderless widget was unavailable in the native automation inventory.
- Synthetic pagination, deletion, naps, timezone/DST, pending and opacity-boundary tests passed. Those do not replace live new-nap/deletion/processing or long-term token-lifetime observations. Do not claim the deferred checks passed.

The Cloud Billing API is disabled in this project; no attempt was made to enable it. Existing Firebase Spark/Workers Free configuration is retained. The setup's Firebase CLI owner token needs `x-goog-user-project: clock-sleep-relay-20260915` for Google Health administration so quota attribution uses the intended enabled project.
