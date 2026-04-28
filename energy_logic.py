import math
import tkinter as tk

class EnergyCurve:
    def __init__(self, canvas, color="#00A8FF"):
        self.canvas = canvas
        self.color = color

    def interpolate_color(self, val):
        """Interpolates between Blue (low energy) and Red (high energy)."""
        # Low: Bright Cyan/Blue (#00D2FF) -> (0, 210, 255)
        # High: Bright Red-Orange (#FF4B2B) -> (255, 75, 43)
        # val is clamped 0.0 to 1.0
        val = max(0.0, min(1.0, val))
        # Swap colors: Higher energy is redder, lower is bluer
        r = int(0 * (1 - val) + 255 * val)
        g = int(210 * (1 - val) + 75 * val)
        b = int(255 * (1 - val) + 43 * val)
        return f'#{r:02x}{g:02x}{b:02x}'

    def get_energy_level(self, hour, wake_hour):
        """Mathematically approximates the RISE energy curve."""
        if wake_hour is None:
            return 0.5
        
        # Normalize time to "hours since wake"
        t = (hour - wake_hour) % 24
        
        # RISE Curve Formula Approximation:
        # 1. Grogginess (first 1.5h)
        if t < 1.5:
            val = 0.15 + (t / 1.5) * 0.35
        else:
            # 2. Morning Peak (~3.5h after wake)
            # 3. Afternoon Dip (~8h after wake)
            # 4. Evening Second Wind (~13h after wake)
            # Using a slightly more dramatic composite sine wave
            circadian = 0.55 + 0.45 * math.sin((math.pi / 6) * (t - 2)) 
            dip = 0.3 * math.exp(-((t - 8)**2) / 1.5) # Sharper dip around 8h
            val = circadian - dip
        
        return max(0.0, min(1.0, val))

    def draw(self, cx, cy, radius, wake_hour):
        """Draws the energy curve as a colorful path mapped to the clock radius."""
        # Use a higher resolution for smooth color transitions
        steps = 144 # Every 10 minutes is usually enough
        
        last_px, last_py = None, None
        
        for i in range(steps + 1):
            h = (i / float(steps)) * 24
            energy = self.get_energy_level(h, wake_hour)
            
            # Map energy to a radius extension
            # Lowest energy (0.0) -> 10% radius
            # Highest energy (1.0) -> 90% radius
            current_r = (0.10 + 0.80 * energy) * radius
            
            # Convert hour to angle (matching 24h clock rotation)
            angle = (18 - h) * 15
            rad = math.radians(angle)
            
            px = cx + current_r * math.cos(rad)
            py = cy - current_r * math.sin(rad)
            
            if last_px is not None:
                # Calculate color for this segment
                color = self.interpolate_color(energy)
                
                # Draw the segment
                self.canvas.create_line(
                    last_px, last_py, px, py, 
                    fill=color, width=4, 
                    capstyle=tk.ROUND, tags="energy_curve"
                )
            
            last_px, last_py = px, py
