# Branch: `feature/android-task-planner`

## Objective and scope

Add an Android Plan tab with editable task names, 24-hour start times, estimated durations, persistent random colors, and task wedges that rotate around the fixed dial center. Home and Lock Screen task visibility is independent. Python is excluded by the owner's platform clarification.

## Identity and decisions

- Base: `main` / `origin/main` at `7cc8d39`; fetched and equal at branch creation on 2026-11-16, with a clean checkout.
- Checkout: primary `H:\Desktop\widgets\24-hr_clock_widget`; no concurrent agents/worktrees.
- Owned paths: Android planner UI/model/storage/renderer/service, targeted tests, the Espresso test dependency, Android README, and this branch's handoff/index records.
- History: [android-task-planner.history.md](android-task-planner.history.md).
- Tasks are a local daily plan, with integer-minute starts and durations of 1–1440 minutes. Midnight wrapping and overlapping wedges are supported; the last drawn task receives a drag.
- Colors remain stable across edits/restarts. Drag commits change only the start time inside a DataStore transaction, preserving other edits and avoiding resurrection of deleted tasks.
- Plan always shows tasks; Display controls Home/Lock visibility. Wedges cover clock/sleep/grogginess/wind-down layers, below the now hand. Wallpaper touch forwarding depends on the launcher.
- Shared dial geometry keeps rendering and hit testing aligned. Embedded native Canvas rendering is clipped so it cannot erase the surrounding Compose controls.
- Follow-up UI: removed the editor-visibility and empty-list paragraphs. An always-present matrix below the clock has inline Task / Start / Dur. cells, a new-task row, per-row Save/Cancel, validation, and delete controls. Adding advances the next start to the prior task's end. Clock updates preserve drafts in other columns. Start uses a 12-hour AM/PM clock-dial dialog; duration uses 24-hour/minute clock dials with a separate full-day option and displays h:mm. The drag hint is removed.

## Validation checkpoint — 2026-11-16

- `python -X utf8 scripts/compact_check.py --name plan-clock-dials-build -- .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest` — passed. Earlier foundation checkpoint: 64 JVM tests and 3 storage instrumentation tests per phone passed; unchanged domain/storage code was not retested for the table-only follow-up.
- Direct `adb -s <device> install -r <apk>` for app/test APKs, then `shell am instrument -w -e class com.example.a24_hr_clock.PlanScreenTest com.example.a24_hr_clock.test/androidx.test.runner.AndroidJUnitRunner` — all 6 UI tests passed per phone across targeted runs. The final add/edit flow was rerun with `-e class com.example.a24_hr_clock.PlanScreenTest#addEditValidateAndDeleteTask` on both phones after synchronizing the test with the hour-to-minute animation. Coverage includes dial taps, AM/PM, noon/midnight, zero-duration rejection, full-day selection, cancellation, row edits, drafts, dragging, layering, and clipping.
- Inspected populated matrix screenshots from both phones; aligned, readable cells and add/delete controls. Diagnostic images/logs stay ignored in `.artifacts/planner/`.
- Final debug APK SHA-256: `33151de09954688621cbe235a24c704c524b894549359bb20ea17ac670c01838`. Both installed APKs match; production clock-settings hashes were unchanged by final installs/tests.
- Espresso upgraded from 3.5.1 to stable 3.7.0 because device UI tests encountered removed `InputManager.getInstance` reflection. Runtime dependencies unchanged.

## Risks and recovery

Owner explicitly authorized installation/testing on both phones. The first Gradle `connectedDebugAndroidTest` run uninstalled the target app during cleanup, potentially clearing device-local configuration. Disclosed this to the owner and reinstalled both phones. Pixel 9 Pro restored cached data and requested Google Health reconnect; Pixel 7a had no saved preferences immediately after reinstall. Permissions/connections and wallpaper selection may need owner setup. Do not copy credentials between phones or clear app data.

Use direct ADB instrumentation for further tests on these personal devices; do not repeat Gradle connected-test cleanup. The final direct runs left the app installed and did not modify the real plan; storage tests use temporary files.

## Next action

Owner review/setup. Commit and push were authorized on 2026-11-18. No PR or merge performed. Keep this branch until authorized integration, then follow the repository's archival/reachability process.
