# Sun Color Simulation Results

I have generated a simulation of the sun icon's color transitions throughout a 24-hour cycle.

The graph below shows:
- **X-Axis**: Hour of the day.
- **Y-Axis**: Sun Elevation in degrees.
- **Color**: The color of the point represents the calculated sun icon color for that specific elevation.

![Sun Color Simulation](file:///H:/Desktop/widgets/24-hr_clock_widget/.artifacts/sun_color_simulation.png)

## Observations
- **Daytime (Yellow)**: When elevation > 20°, the sun remains a bright yellow.
- **Golden Hour (Orange)**: Between 0° and 20°, you can see the smooth transition from yellow to deep orange.
- **Twilight (Reddish/Dim Gold)**: As the sun sets (0° to -8°), it transitions towards the "night sun" color.
- **Night (Dim Gold)**: Below -8°, the color stays at the darkest threshold (a dim gold/brownish yellow) representing the sun as seen through a thick atmosphere/night.

The simulation script used for this is located at:
[simulate_sun.py](file:///H:/Desktop/widgets/24-hr_clock_widget/.artifacts/ca47c0f8-8380-4208-8708-decca3b46b15/scratch/simulate_sun.py)
