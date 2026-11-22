# File purposes — AI agent guides in this repository

Installed from `H:\Desktop\ai_agent_guides\git-handoff-system` (2026-07-22). Application-specific paths are noted where they extend the kit.

## Agent entry points

| File | Purpose |
|---|---|
| `AGENTS.md` | Vendor-neutral startup: read handoffs, verify git, update docs after changes |
| `GEMINI.md` | Gemini/Google agents: handoff protocol (§0) plus coding/TDD/git hygiene directives |
| `.cursor/rules/handoff-maintenance.mdc` | Cursor always-on rule mirroring handoff lifecycle |

## Handoff framework

| File | Purpose |
|---|---|
| `docs/handoffs/MASTER_HANDOFF.md` | Protocol: layout, startup, archive, PR checks, size limits |
| `docs/handoffs/REPOSITORY.md` | This repo's git workflow, PR-check baseline, Android/Python commands |
| `docs/handoffs/MULTI_AGENT.md` | Parallel agents via Git worktrees |
| `docs/handoffs/README.md` | Active branch index and merge order |
| `docs/handoffs/HISTORY.md` | Append-only global ledger of closed branches |
| `docs/handoffs/main.md` | Live handoff for `main` |
| `docs/handoffs/main.history.md` | Append-only milestones for `main` |
| `docs/handoffs/archive/` | Frozen closed branch handoffs |
| `docs/handoffs/templates/` | Starters for new branch handoffs and archives |

## Validation and CI

| File | Purpose |
|---|---|
| `scripts/validate_handoffs.py` | Checks required files, links, size limits, append-only history |
| `.github/workflows/handoff-validation.yml` | Runs validator on PRs and pushes to `main` |

## Application extensions (not part of upstream kit)

| File | Purpose |
|---|---|
| `python/analyze_energy_fit.py` | Empirical energy-curve M1–M6 CV benchmark |
| `python/energy_models.py` | Candidate model implementations |
| `python/sleep_dynamics.py` | Process-S sleep/nap restoration timeline |
| `python/data_review.py` | Gap audit, outlier walkthrough, peak/trough export |
| `docs/energy_model_refinement.md` | Playbook for re-fit as more empirical days accrue |
| `docs/energy_model_glossary.md` | Abbreviations (MAE, LOO-CV, M1–M6, Process S, outlier classes, etc.) |
| `python/personal_energy_fit.json` | CV winner params (M4 personal model) |
| `python/sleep_settings.json` | UI model params + `personal_model` block |
| `analysis/energy_fit_report.json` | Latest fit report output |
