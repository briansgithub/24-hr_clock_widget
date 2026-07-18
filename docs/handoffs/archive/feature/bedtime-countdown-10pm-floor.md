# Archived: eature/bedtime-countdown-10pm-floor

## Identity

- Final tip: 94ed3ce — Add optional home/lock bedtime countdown and keep 10pm floor.
- Merge commit / PR: 52444d9 (local merge; not pushed)
- History: [bedtime-countdown-10pm-floor.history.md](bedtime-countdown-10pm-floor.history.md)
- Closed: 2026-07-18

## Goal and scope

Clamp bedtime targets to no earlier than 10:00 PM and optionally show a wallpaper countdown on home/lock via Display → Sleep.

## What landed

- 22:00 floor after 90-minute advance + 5-minute rounding
- showBedtimeCountdown (default off) for home and lock
- Wallpaper/preview countdown with small-clock sizing/alignment polish
- Shared 
esolveBedtimeMillis; 10s wallpaper refresh (not 1Hz)

## Validation

- python scripts/validate_handoffs.py passed
- Android compile/test deferred (no Gradle run this session)
- Owner visual iteration on home placement before merge request

## Disposition

- Status: merged into main
- Remote branch deleted: not applicable (never pushed)
- Local branch deleted: yes
- Cleanup completed: yes (archival committed; push of main pending owner request)
