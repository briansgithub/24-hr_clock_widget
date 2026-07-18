# Archived: `feature/display-tab-lock-first`

## Identity

- Final tip: `a7f45ac` — docs: sync handoff tip after gray Reset buttons
- Feature tip: `d1f3e29` — feat: default Display tab to Lock Screen first
- Gray tip: `fa2582a` — fix: make Reset to Defaults buttons light gray
- Merge / PR: `0c07664` — [PR #4](https://github.com/briansgithub/24-hr_clock_widget/pull/4); `1f3764e` — [PR #5](https://github.com/briansgithub/24-hr_clock_widget/pull/5)
- Parent/base: `main` at `3b5bb18`
- History: [display-tab-lock-first.history.md](display-tab-lock-first.history.md)
- Disposition: merged into `main`
- Last verified: `2026-07-18`

## Goal and scope

- Lock Screen leftmost + default on Display tab entry.
- Light-gray “Reset to Defaults” buttons (Display + preview).

## What landed

- `DisplaySettingsScreen`: Lock tab index 0; Home second; lock-scoped settings/reset/update.
- Reset buttons: `#BDBDBD` container, dark content (Display + preview overlay).

## Validation recorded

- `python scripts/validate_handoffs.py` passed.
- Owner device visual check confirmed (“it’s all good”).
- CI validate green on PR #4; PR #5 merged after owner-authorized follow-up.

## Cleanup

- Local branch deleted: yes
- Remote branch deleted: yes (with PR #5 merge; prune applied)
