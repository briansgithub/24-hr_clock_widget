import matplotlib.pyplot as plt
import numpy as np
import math

def interpolate_sun_color(elevation):
    """Port of the refined logic to Python for simulation."""
    darkest_elev = -8.0
    
    if elevation >= 0:
        # Daytime: Fully opaque
        t = max(0.0, min(1.0, elevation / 20.0))
        r = 255
        g = int(69 * (1 - t) + 215 * t)
        b = 0
        fill = f"#{r:02x}{g:02x}{b:02x}"
        
        # Outline logic
        og = int(40 * (1 - t) + 165 * t)
        outline = f"#{r:02x}{og:02x}{b:02x}"
        
        return fill, 1.0, outline
    else:
        # Night/Twilight: Fade in as it approaches horizon
        ratio = max(0.0, min(1.0, (elevation - darkest_elev) / (0.0 - darkest_elev)))
        alpha = (60 + (195 * ratio)) / 255.0
        
        r = int(139 * (1 - ratio) + 255 * ratio)
        g = int(128 * (1 - ratio) + 69 * ratio)
        b = 0
        fill = f"#{r:02x}{g:02x}{b:02x}"
        
        # At night, outline matches fill
        return fill, alpha, fill

# Simulation Parameters
hours = np.linspace(0, 24, 144) # 10-minute intervals
max_elev = 65.0  # Max sun elevation in summer
min_elev = -45.0 # Min sun elevation at night

# Mock elevation curve (sine wave)
# Peak at 12:00 (pi/2), trough at 00:00 (-pi/2)
elevations = max_elev * np.sin((hours - 6) * math.pi / 12)

results = [interpolate_sun_color(e) for e in elevations]
colors = [r[0] for r in results]
alphas = [r[1] for r in results]
outlines = [r[2] for r in results]

# Plotting
plt.figure(figsize=(12, 6))
plt.rcParams['axes.facecolor'] = '#1a1a1a'
plt.rcParams['savefig.facecolor'] = '#1a1a1a'

# Scatter plot to show colors, alpha, and outlines
for h, e, c, a, o in zip(hours, elevations, colors, alphas, outlines):
    plt.scatter(h, e, color=c, alpha=a, s=150, edgecolors=o, linewidths=2)

plt.axhline(0, color='white', linestyle='--', alpha=0.3, label='Horizon')
plt.axhline(-8, color='red', linestyle=':', alpha=0.5, label='Darkness Threshold (-8°)')

plt.title('Refined Sun Icon Simulation (Fill + Outlines)', color='white')
plt.xlabel('Hour of Day', color='white')
plt.ylabel('Sun Elevation (Degrees)', color='white')
plt.xticks(np.arange(0, 25, 2), color='white')
plt.yticks(color='white')
plt.grid(True, alpha=0.1)
plt.legend()

save_path = "H:/Desktop/widgets/24-hr_clock_widget/.artifacts/sun_color_simulation.png"
plt.savefig(save_path)
print(f"Simulation saved to {save_path}")
