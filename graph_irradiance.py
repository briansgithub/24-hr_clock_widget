import math
import os

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

try:
    import matplotlib.pyplot as plt
    
    peak = 90
    elevations = [x for x in range(0, 91)]
    y_linear = [get_irradiance_linear(e, peak) for e in elevations]
    y_refined = [get_irradiance_refined(e, peak) for e in elevations]
    
    plt.figure(figsize=(10, 6))
    plt.plot(elevations, y_linear, label='Original Linear Model', linestyle='--')
    plt.plot(elevations, y_refined, label='Refined Model (Gamma 2.2 + Beer-Lambert)', linewidth=2)
    
    plt.title('Solar Irradiance Indicator: Linear vs Perceptual Mapping')
    plt.xlabel('Sun Elevation (degrees)')
    plt.ylabel('Indicator Brightness (0-255 RGB)')
    plt.grid(True, alpha=0.3)
    plt.legend()
    plt.xlim(0, 90)
    plt.ylim(0, 260)
    
    # Add annotations for key points
    for angle in [15, 30, 45]:
        val_lin = get_irradiance_linear(angle, peak)
        val_ref = get_irradiance_refined(angle, peak)
        plt.annotate(f'+{int(val_ref-val_lin)}', 
                     xy=(angle, val_ref), 
                     xytext=(angle, val_ref + 10),
                     ha='center',
                     arrowprops=dict(arrowstyle='->', alpha=0.5))

    output_path = os.path.join(os.path.dirname(__file__), "irradiance_comparison.png")
    plt.savefig(output_path)
    print(f"Graph saved to: {output_path}")
    # plt.show() # Uncomment if running in an interactive environment

except ImportError:
    print("matplotlib not found. Printing data points for manual graphing:")
    print(f"{'Angle':>6} | {'Linear':>10} | {'Refined':>10}")
    print("-" * 30)
    for a in [0, 5, 10, 15, 20, 30, 45, 60, 90]:
        lin = get_irradiance_linear(a, 90)
        ref = get_irradiance_refined(a, 90)
        print(f"{a:6}° | {lin:10.1f} | {ref:10.1f}")
