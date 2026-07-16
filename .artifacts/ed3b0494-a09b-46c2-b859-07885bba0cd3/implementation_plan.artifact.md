# Fix Coroutine Calling Error for `uploadToGoogleDrive`

The `uploadToGoogleDrive` function was changed to a `suspend` function returning a `Pair<Boolean, String>`, but several call sites are still using it with a callback or calling it from a non-suspending context. This prevents the project from building.

## Proposed Changes

### UI Component

#### [MODIFY] [EmpiricalLogScreens.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/ui/EmpiricalLogScreens.kt)
- Add `import kotlinx.coroutines.launch`.
- Initialize a `CoroutineScope` using `rememberCoroutineScope()` inside the `EmpiricalLogHistoryScreen` composable.
- Wrap the `manager.uploadToGoogleDrive` call inside the "Export Now" button's `onClick` lambda with `scope.launch`.
- Update the call to handle the returned `Pair<Boolean, String>` instead of passing a callback.

### Background Worker

#### [MODIFY] [MissedDataCheckWorker.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/MissedDataCheckWorker.kt)
- Update the `manager.uploadToGoogleDrive` call within the `suspend fun doWork()` to use the returned `Pair<Boolean, String>` directly.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:compileDebugKotlin` to verify the build error is resolved.

### Manual Verification
- Deploy the app and trigger a manual "Export Now" from the Empirical Energy History screen.
- Observe the status message updates (e.g., "Exporting...", then success or failure).
