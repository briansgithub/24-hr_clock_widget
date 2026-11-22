# History: `feature/android-task-planner`

Current scope and next action: [android-task-planner.md](android-task-planner.md).

### 2026-11-16 — Branch created

- Base: fetched `main` / `origin/main` at `7cc8d39`, equal and clean; reused the primary checkout without concurrency.
- Owner clarified Android-only scope. Created the narrow branch for a persistent clock planner; Python remains unchanged.

### 2026-11-16 — Planner implemented and installed on both Pixels

- Added Plan task editing, saved random colors, fixed-center wedge dragging, shared dial geometry, transactional start-time updates, and independent Home/Lock display switches.
- Validation: 64 JVM tests and 8 direct instrumentation tests per phone passed; inspected populated layouts on Pixel 7a and Pixel 9 Pro. Installed APK hashes match the artifact recorded in the active handoff.
- Device testing required an Espresso test-only update and native Canvas clipping. Initial Gradle connected-test cleanup uninstalled apps; disclosed the configuration risk, reinstalled both, and switched to direct ADB tests. Owner may need to reconnect Google Health, regrant permissions, or reselect wallpaper.
- Work remains uncommitted pending explicit owner authorization under repository policy.

### 2026-11-16 — Replaced dialogs/list with an inline task matrix

- Owner requested removal of the visibility/empty-list paragraphs and an editable table below the clock. Added inline cells, a permanent new-task row, Save/Cancel, validation, and automatic next-start time. Clock dragging preserves drafts in other columns; saved values normalize cleanly.
- App/test APK builds passed, 5 UI tests passed per phone across targeted runs, and final edit-flow checks passed on both. Inspected populated screenshots and verified installed APK hashes plus unchanged production clock settings. Direct ADB installation/testing preserved app data; no Gradle connected-test cleanup used.

### 2026-11-16 — Added time and duration selectors

- Removed the drag instruction; renamed headers Start and Dur. Start uses a 12-hour AM/PM selector, and duration uses hours/minutes wheels with h:mm display. Existing minute-based storage remains compatible.
- App/test APK build and all 5 UI tests passed on each Pixel. Inspected both matrix screenshots and verified installed APK hashes and unchanged production clock settings.

### 2026-11-16 — Changed selectors to circular clock dials

- Replaced start-time text input and duration wheels with Material clock dials: hour selection followed by minute selection. Start retains AM/PM; duration supports 0–23 elapsed hours plus a 24-hour shortcut and rejects zero.
- Built app/test APKs; all 6 UI checks passed per phone across targeted runs. Inspected dial layouts and verified final installed APK hashes on both Pixels. No data/storage model changes.

### 2026-11-18 — Owner authorized commit and push

- Owner authorized committing and pushing the Android planner, the Espresso test dependency, and this branch's handoff records. No pull request or merge.
