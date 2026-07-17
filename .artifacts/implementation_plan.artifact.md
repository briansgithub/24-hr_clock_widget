# Implementation Plan - Dynamic Sun Color and Brightness

The goal is to implement dynamic sun color and brightness adjustment in both the Android app and the Python widget. The sun's appearance should reflect its altitude in the sky, simulating atmospheric effects. It should transition from bright yellow at noon to orange/red at sunset, and remain visible as a faint yellow icon at night.

## User Review Required

> [!IMPORTANT]
> The threshold for the darkest point is set to an elevation of -8.0 degrees, which corresponds roughly to 30-45 minutes after sunset at mid-latitudes. This aligns with the "30+ minutes below the horizon" requirement.

## Proposed Changes

### [Component Name] Android App

#### [MODIFY] [CelestialManager.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/CelestialManager.kt)
- Update `getCelestialPositions` to return sun elevation in addition to angles and moon phase.
- Update `getSolarIrradiance` to not return 0 at night, but instead a minimum threshold if desired, or keep it as is if it only affects the center circle.

#### [MODIFY] [ClockWallpaperService.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/wallpaper/ClockWallpaperService.kt)
- Store `sunElevation` and pass it to `renderer.draw`.

#### [MODIFY] [MainActivity.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/MainActivity.kt)
- Update `ClockPreviewScreen` and its callers to handle and pass `sunElevation`.

#### [MODIFY] [ClockRenderer.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/wallpaper/ClockRenderer.kt)
- Update `draw` signature to accept `sunElevation`.
- Update `drawSunAndMoon` to calculate sun color and alpha based on `sunElevation`.
- Implement `interpolateSunColor(elevation: Double)` helper.

### [Component Name] Python Widget

#### [MODIFY] [clock_widget.py](file:///H:/Desktop/widgets/24-hr_clock_widget/python/clock_widget.py)
- Update `_get_celestial_positions` to return sun elevation.
- Update `draw_sun_and_moon` to calculate sun color and alpha based on elevation.
- Implement color interpolation logic for the sun icon.

## Verification Plan

### Automated Tests
- N/A for UI color changes, but I will ensure the build passes.

### Manual Verification
- Deploy the Android app to a device/emulator and observe the sun color changes (may require temporary time manipulation in code for testing).
- Run the Python widget and observe the sun color changes.

Please review the plan above. Reply with 'Proceed' to begin implementation or provide feedback.
