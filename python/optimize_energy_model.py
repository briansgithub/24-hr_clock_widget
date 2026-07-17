import pandas as pd
import numpy as np
from scipy.optimize import least_squares
import matplotlib.pyplot as plt
from datetime import datetime
import json
import os

# --- MODEL IMPLEMENTATION (Matches Android/Python Logic) ---

def calculate_energy(t, sleep_duration, sleep_debt, tau_wake, tau_sleep, tau_inertia, circadian_offset):
    # Process S
    s_max = 1.0
    s_min = 0.05
    sleep_start = 24.0 - sleep_duration
    
    e_w = np.exp(-sleep_start / tau_wake)
    e_s = np.exp(-sleep_duration / tau_sleep)
    
    divisor = max(0.0001, 1.0 - e_w * e_s)
    s0 = (s_max * e_s * (1.0 - e_w) + s_min * (1.0 - e_s)) / divisor
    s_bed = s0 * e_w + s_max * (1.0 - e_w)

    if t < sleep_start:
        s_t = s0 + (s_max - s0) * (1.0 - np.exp(-t / tau_wake))
    else:
        s_t = s_min + (s_bed - s_min) * np.exp(-(t - sleep_start) / tau_sleep)

    # Process C
    c_primary = 0.55 * np.cos((np.pi / 12.0) * (t - circadian_offset))
    c_secondary = -0.15 * np.cos((np.pi / 6.0) * (t - 8.0))
    c_t = 1.10 + c_primary + c_secondary

    # Raw Alertness
    raw = c_t - s_t

    # Sleep Inertia
    c_at_wake = 1.10 + 0.55 * np.cos((np.pi / 12.0) * (-circadian_offset)) - 0.15 * np.cos((np.pi / 6.0) * (-8.0))
    raw_at_wake = c_at_wake - s0
    inertia = max(0.0, raw_at_wake) * np.exp(-t / tau_inertia)

    # Debt Penalty
    debt_penalty = min(0.35, (sleep_debt) / 25.0)

    alertness = raw - inertia - debt_penalty
    return np.clip(alertness, 0.0, 1.0) * 100.0

# --- OPTIMIZATION ENGINE ---

def objective_function(params, data):
    tau_wake, tau_sleep, offset = params
    errors = []
    for _, row in data.iterrows():
        # Note: This is a simplified version that assumes a single day's sleep/debt for all points.
        # In a full version, we would correlate each log with its specific date's Fitbit data.
        pred = calculate_energy(row['hours_since_wake'], row['sleep_dur'], row['debt'], 
                                tau_wake, tau_sleep, 1.5, offset)
        errors.append(pred - row['EnergyLevel'])
    return np.array(errors)

def run_optimization(csv_path):
    if not os.path.exists(csv_path):
        print(f"Error: Log file not found at {csv_path}")
        return

    df = pd.read_csv(csv_path)
    # Filter for logged data and remove sleep exclusions
    df = df[df['Status'] == 'LOGGED'].dropna(subset=['EnergyLevel'])
    
    if len(df) < 10:
        print("Not enough data points to optimize. Collect at least 10 logs.")
        return

    # Mocking hours_since_wake, sleep_dur, and debt for demonstration
    # In practice, you'd merge this CSV with a fitbit_history.csv
    df['hours_since_wake'] = (df['Timestamp'] % (24 * 3600 * 1000)) / (3600 * 1000) 
    df['sleep_dur'] = 7.5
    df['debt'] = 2.0

    initial_guess = [18.2, 4.2, 12.0] # Standard defaults
    res = least_squares(objective_function, initial_guess, args=(df,), 
                        bounds=([10.0, 2.0, 6.0], [30.0, 8.0, 18.0]))

    optimized_tau_wake, optimized_tau_sleep, optimized_offset = res.x
    
    print("\n" + "="*40)
    print("OPTIMIZATION COMPLETE")
    print("="*40)
    print(f"Original Tau-Wake: 18.2  -> Optimized: {optimized_tau_wake:.2f}")
    print(f"Original Tau-Sleep: 4.2  -> Optimized: {optimized_tau_sleep:.2f}")
    print(f"Original Offset: 12.0   -> Optimized: {optimized_offset:.2f}")
    print("="*40)
    
    # AI Summary Generation
    residuals = objective_function(res.x, df)
    avg_error = np.mean(np.abs(residuals))
    
    with open("ai_optimization_prompt.txt", "w") as f:
        f.write(f"USER EMPIRICAL DATA SUMMARY:\n")
        f.write(f"- Total Logs: {len(df)}\n")
        f.write(f"- Mean Absolute Error: {avg_error:.2f}%\n")
        f.write(f"- Optimized Parameters: TauWake={optimized_tau_wake:.2f}, TauSleep={optimized_tau_sleep:.2f}, Offset={optimized_offset:.2f}\n\n")
        f.write("PROMPT FOR AI AGENT:\n")
        f.write("The energy curve has been optimized to my ratings. However, there is still a residual error. ")
        f.write("Suggest a modification to the cosine harmonic (C_secondary) or the sleep pressure buildup (Process S) ")
        f.write("to better capture the remaining variance in my subjective energy levels.")

    print("Created 'ai_optimization_prompt.txt' for your next AI iteration.")

if __name__ == "__main__":
    # Adjust path to your synced Google Drive file
    csv_file = "Alertness_Master_Log.csv"
    run_optimization(csv_file)
