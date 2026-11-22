# Archived branch: `fix/fitbit-sleep-sync`

## Identity

- Final tip: `68b6ff8` — Replace Android launcher icon with centered circadian helix
- Merge commit / PR: fast-forward of local `main` from `e03fa48` to `68b6ff8` (not pushed)
- History: [fitbit-sleep-sync.history.md](fitbit-sleep-sync.history.md)
- Closed: `2026-11-05`

## Goal and scope

Reliable Fitbit sleep delivery, then a Google Health cutover for the Python and Android clocks and the free relay, without clearing phone data.

## What landed

- Android sleep sync recovery, reconnect handling, fading, and silent push.
- Google Health read path for sleep and heart measurements on Android and desktop, with the provider-aware Cloudflare relay.
- Centered circadian-helix launcher icon.

## Validation

- Prior branch validation: Android 57 unit tests and debug assemble, Python health regressions, relay 21 tests. Live Pixel enrollment and direct FCM were recorded in the history.
- This closure did not re-run that suite. SDK renewal and a natural upstream webhook were still open at close.

## Disposition

- Status: merged into `main` (fast-forward)
- Remote branch deleted: not applicable (never pushed)
- Local branch deleted: yes
- Cleanup completed: yes
