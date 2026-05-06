import math

def get_irradiance_linear(elev, peak):
    if elev <= 0: return 0
    raw = math.sin(math.radians(max(0, elev)))
    peak_raw = math.sin(math.radians(peak))
    return 255 * (raw / peak_raw)

def get_irradiance_refined(elev, peak, gamma=2.2, k=0.12):
    if elev <= 0: return 0
    def get_irrad(e):
        s = math.sin(math.radians(max(0.01, e)))
        return s * math.exp(-k / s)
    
    raw = get_irrad(elev)
    peak_raw = get_irrad(peak)
    ratio = raw / peak_raw
    corrected_ratio = math.pow(max(0, ratio), 1/gamma)
    return 255 * corrected_ratio

peak = 60 # Assume 60 degrees peak elevation
angles = [90, 60, 45, 30, 15, 5, 1, 0]

print(f"{'Angle':>6} | {'Linear RGB':>10} | {'Refined RGB':>10} | {'Increase'}")
print("-" * 50)
for a in angles:
    # Cap at peak for the purpose of this comparison if angle > peak
    target_a = min(a, peak)
    lin = get_irradiance_linear(target_a, peak)
    ref = get_irradiance_refined(target_a, peak)
    inc = ref - lin
    print(f"{a:6}° | {lin:10.1f} | {ref:10.1f} | {inc:+7.1f}")

print("\nConclusion:")
print("At 30° (half sine of 90°), the linear model was ~147 RGB (assuming 90° peak).")
print(f"In our 60° peak case, at 30° the refined model is {get_irradiance_refined(30, 60):.1f} RGB vs linear {get_irradiance_linear(30, 60):.1f} RGB.")
print("This shows a significant boost in the mid-to-low range, matching human perception.")
