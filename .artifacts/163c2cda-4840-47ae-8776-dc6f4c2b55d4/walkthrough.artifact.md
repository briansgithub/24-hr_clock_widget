# Walkthrough - Sleep Debt & Efficiency Calculation Streamlining

I have updated the sleep debt calculation to anchor weights strictly to the calendar offset and standardized the efficiency calculation across the app.

## Changes Made

### Core Logic

#### [EnergyCalculator.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/EnergyCalculator.kt)
- Modified `computeSleepDebt` to use the loop index `i` (days ago) as the weight exponent.
- Removed `weightIndex` shifting logic. Now, if a day is excluded, its weight is skipped rather than passed to the next day. This matches the Python widget's "anchored" behavior.

#### [FitbitManager.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/logic/FitbitManager.kt)
- Updated `refreshMetrics` to calculate sleep efficiency as the **mean of session ratios**.
- Implemented the assumption that **naps always have 100% efficiency**.

### UI & Presentation

#### [MainActivity.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/main/java/com/example/a24_hr_clock/MainActivity.kt)
- Updated the Sleep Log table to use the anchored weighting logic for the "Wtd" column.
- Updated the table summary logic to use the same "mean of session ratios" (with 100% naps) for the "AVG" efficiency value.

### Testing

#### [EnergyCalculatorTest.kt](file:///H:/Desktop/widgets/24-hr_clock_widget/android/app/src/test/java/com/example/a24_hr_clock/logic/EnergyCalculatorTest.kt)
- Adjusted `testSleepDebtStabilityWhenTodayExcluded` to reflect the new anchored weighting behavior.

## Verification Results

### Automated Tests
- Ran `:app:testDebugUnitTest`.
- **Result**: All 11 tests passed, including the updated `EnergyCalculatorTest`.

### Manual Verification Path
1. Open the app and navigate to the **Sleep Log** section.
2. Observe the "Wtd" values for the last 14 days.
3. Uncheck "Yesterday" (T-1).
4. Verify that the "Wtd" value for "Day Before Yesterday" (T-2) **remains unchanged**. (Previously it would have increased as it inherited the T-1 weight).
5. Verify that napping sessions (not marked as Main Sleep) contribute 100% to the average efficiency in the footer.
