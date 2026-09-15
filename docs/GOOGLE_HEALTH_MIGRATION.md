# Google Health migration follow-up

Research date: 2026-09-15. This document records later work; the current repair continues using Fitbit. The legacy shutdown notice names **September 2026** without a precise day here. The repair cannot extend the legacy service's availability. [Official notice](https://developers.google.com/health/about)

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

Treat the official pages as the source of truth when this follow-up begins. The current code has a `provider` credential field and string record IDs as preparation; it does not implement Google Health authorization or endpoints.
