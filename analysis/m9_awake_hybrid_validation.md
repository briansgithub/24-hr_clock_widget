# M9 Awake Hybrid Validation

- Status: **provisional**
- Qualified wake episodes: **17**
- Common original ratings: **480**
- Selected family: **2 harmonics**
- M8 outer-CV/common-sample MAE/RMSE/bias: **13.90 / 16.94 / 0.57**
- M9 outer-CV MAE/RMSE/bias: **15.10 / 18.36 / 0.49**
- Per-episode win rate: **23.5%**
- Held-out peak–dip–peak reproduction: **25.0%**

## Promotion gates

- FAIL — at least 21 qualified wake episodes
- FAIL — outer cv mae improves by at least 1
- FAIL — outer cv rmse does not regress
- FAIL — wins at least 60 percent of episodes
- FAIL — reference peak dip peak prominence
- FAIL — heldout shape reproduction at least 60 percent

## Fold metrics

| Fold | Held-out episodes | M8 MAE | M9 MAE | M9 RMSE | M9 Bias |
|---:|---:|---:|---:|---:|---:|
| 1 | 4 | 14.84 | 15.43 | 18.60 | 2.67 |
| 2 | 4 | 17.27 | 17.40 | 20.52 | 4.57 |
| 3 | 3 | 13.27 | 15.45 | 19.25 | -12.28 |
| 4 | 3 | 12.53 | 15.24 | 18.71 | 10.23 |
| 5 | 3 | 10.42 | 11.39 | 13.41 | -6.72 |

Production remains on committed M8 unless every gate passes and the separate explicit promotion command is run.
