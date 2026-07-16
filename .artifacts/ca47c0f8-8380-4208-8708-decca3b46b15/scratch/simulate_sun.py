import matplotlib.pyplot as plt
import numpy as np
import math

def interpolate_sun_color(elevation):
    """Port of the implemented logic to Python for simulation."""
    darkest_elev = -8.0
    
    if elevation >= 0:
        # Transition from Bright Yellow (#FFD700) to Deep Orange (#FF4500)
        t = max(0.0, min(1.0, elevation / 20.0))
        r = 255
        g = int(69 * (1 - t) + 215 * t)
        b = 0
        fill = f"#{r:02x}{g:02x}{b:02x}"
        return fill
    else:
        # Twilight to Night
        ratio = max(0.0, min(1.0, (elevation - darkest_elev) / (0.0 - darkest_elev)))
        # Night: Dim Gold (#8B8000) -> Twilight: Deep Orange (#FF4500)
        r = int(139 * (1 - ratio) + 255 * ratio)
        g = int(128 * (1 - ratio) + 69 * ratio)
        b = 0
        fill = f"#{r:02x}{g:02x}{b:02x}"
        return fill

# Simulation Parameters
hours = np.linspace(0, 24, 144) # 10-minute intervals
max_elev = 65.0  # Max sun elevation in summer
min_elev = -45.0 # Min sun elevation at night

# Mock elevation curve (sine wave)
# Peak at 12:00 (pi/2), trough at 00:00 (-pi/2)
elevations = max_elev * np.sin((hours - 6) * math.pi / 12)

colors = [interpolate_sun_color(e) for e in elevations]

# Plotting
plt.figure(figsize=(12, 6))
plt.rcParams['axes.facecolor'] = '#1a1a1a'
plt.rcParams['savefig.facecolor'] = '#1a1a1a'

# Scatter plot to show colors
for h, e, c in zip(hours, elevations, colors):
    plt.scatter(h, e, color=c, s=100, edgecolors='none')

plt.axhline(0, color='white', linestyle='--', alpha=0.3, label='Horizon')
plt.axhline(-8, color='red', linestyle=':', alpha=0.5, label='Darkness Threshold (-8°)')

plt.title('Sun Icon Color Simulation Throughout the Day', color='white')
plt.xlabel('Hour of Day', color='white')
plt.ylabel('Sun Elevation (Degrees)', color='white')
plt.xticks(np.arange(0, 25, 2), color='white')
plt.yticks(color='white')
plt.grid(True, alpha=0.1)
plt.legend()

save_path = "H:/Desktop/widgets/24-hr_clock_widget/.artifacts/sun_color_simulation.png"
plt.savefig(save_path)
print(f"Simulation saved to {save_path}")
