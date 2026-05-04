import math
import datetime
from energy_logic import compute_sleep_debt

def test_compute_sleep_debt():
    # Setup mock data
    today = datetime.datetime.now()
    t_minus_1 = (today - datetime.timedelta(days=1)).strftime("%Y-%m-%d")
    t_minus_2 = (today - datetime.timedelta(days=2)).strftime("%Y-%m-%d")
    t_minus_3 = (today - datetime.timedelta(days=3)).strftime("%Y-%m-%d")

    sleep_logs = [
        {"dateOfSleep": t_minus_1, "minutesAsleep": 480, "isMainSleep": True}, # 8h
        {"dateOfSleep": t_minus_2, "minutesAsleep": 360, "isMainSleep": True}, # 6h
        # T-3 is missing (0h)
    ]

    sleep_need = 8.0

    print("--- Test 1: All included (T-0 excluded by default) ---")
    # T-0 excluded by default for now in UI, but let's test with empty list
    debt = compute_sleep_debt(sleep_logs, sleep_need, include_naps=True, excluded_dates=[today.strftime("%Y-%m-%d")])
    # T-1: 8h - 8h = 0h debt
    # T-2: 8h - 6h = 2h debt * 0.9^2 = 1.62h
    # T-3: 8h - 0h = 8h debt * 0.9^3 = 5.832h
    # ... and so on for other 14 days
    print(f"Total debt: {debt:.2f}h")

    print("\n--- Test 2: Exclude T-2 ---")
    debt_excluded = compute_sleep_debt(sleep_logs, sleep_need, include_naps=True, excluded_dates=[today.strftime("%Y-%m-%d"), t_minus_2])
    print(f"Total debt (T-2 excluded): {debt_excluded:.2f}h")

    assert debt_excluded < debt
    print("\nSUCCESS: Exclusion reduced debt as expected.")

if __name__ == "__main__":
    test_compute_sleep_debt()
