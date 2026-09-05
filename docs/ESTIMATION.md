# Local estimation model

The word AI in the product name refers to a local adaptive statistical estimator. It does not call a language model or require a cloud API key.

## Historical baseline

A usage segment is an inclusive date range and its raw meter-volume difference in m³. The daily rate for a historical calendar month is the sum of each segment's proportional volume inside that month divided by the covered days. At least 14 covered days are required. Overlapping segments are rejected to avoid double counting. Calendar-only manual input assumes usage was evenly distributed within that month.

For a target date, use the corresponding month one year earlier. Interpolate its daily rate with the preceding or following historical month's rate, using the 15th as each month's center. This is the 13/12/11-month seasonal idea expressed on actual dates. If a neighboring month is absent, keep the central month's rate. If the central month is absent, the seasonal baseline is unavailable. Leap-year month lengths are respected.

## Recent calibration

Use the latest physical observation for the active meter and earlier checks between one and 28 days before it. The recent daily rate is the median of all positive-time pairwise slopes in this window, clamped to zero if negative. This reduces an isolated misreading's influence on both the recent rate and seasonal calibration. The latest cumulative anchor itself still needs a correct physical reading.

Multiply the robust recent daily rate by the window's elapsed days and compare that volume with the integrated seasonal baseline over the same interval. When the baseline exceeds 0.01 m³, their ratio is capped between 0 and 5. Shrink that ratio toward 1 with weight

```
spanDays / (spanDays + 7) * clamp(1 - daysSinceLatestObservation / 28, 0, 1)
```

The seasonal rate is multiplied by `1 + weight * (ratio - 1)`. Decay is evaluated on each integrated date, so later projections do not retroactively reduce the elapsed cumulative consumption. If the seasonal ratio is unavailable, blend the recent rate with the bias-corrected seasonal rate. The recent blend decreases linearly to zero over 14 days rather than stopping at a step. These guardrails are engineering choices and are not calibrated prediction intervals.

When the in-window seasonal ratio is unavailable, stored pre-check forecasts can supply a multiplicative calibration bias. For up to 12 comparable checks, compare actual and predicted increments from the preceding anchor. Each increment must be at least 0.5 m³. Average their log ratios with a 0.7 decay per older usable sample, exponentiate, and clamp the multiplier to [0.5, 2]. Do not apply this correction alongside the seasonal ratio because that would count the same deviation twice. The stored forecasts can come from earlier app versions, and this learning rule is not evidence of improved real-household accuracy. Monthly seasonal rates are memoized within each estimate call.

Without seasonal history, use the recent observed daily rate for no more than 14 days after the last physical observation. A single physical reading is an anchor, not enough evidence to infer a consumption rate. A latest anchor older than 60 days disables estimates even with seasonal history.

## Anchors and presentation

The latest physical reading or matching-meter end-of-bill reading anchors the cumulative display. An inclusive bill end date becomes the start of the following day as its approximate boundary timestamp. Within-day rate integration uses Korean local dates. The actual meter-reader time is unavailable, which adds uncertainty.

Meter replacement starts a new active meter identifier. Prior physical observations are retained for inspection but cannot anchor the replacement. Historical household usage may still inform seasonality. Reset the household data when moving to another household.

The app distinguishes cumulative meter estimate, daily usage rate, and usage since the last bill boundary. A future planned reading date can show a forecast with today's evidence frozen. It never treats a forecast as a measurement.

`계량기 보고 보정하기` opens a physical-confirmation dialog. Enter the observed reading or adjust it with `+0.1` and `−0.1`, then choose `이 숫자로 확인` to save. This records a local physical observation and does not submit it to the supplier. Adjustments within ten minutes replace the previous check and retain the original pre-correction forecast. A decreasing meter reading is rejected against the preceding anchor. Correct an erroneous older observation by deleting it, or start a new meter when appropriate.

## Cost

When available, the latest bill supplies an effective cost per raw m³ from its energy-charge lines, including 10% VAT, plus the bill's VAT-inclusive base charge. Applying this to the estimated current-period usage produces a provisional historical-rate amount. Discounts and adjustments disable it when recognized. It is neither a live tariff calculation nor an amount payable. The first release does not scrape changing nationwide tariffs.

## Accuracy and limits

No household-level forecast-accuracy claim has been validated. Weekly readings are recommended, not required by a demonstrated optimal schedule. Weather, occupancy, hot-water patterns, heating changes, partial month coverage and reading-time uncertainty can all shift the result. The app does not claim that the 0.1 m³ adjustment step implies that level of forecast accuracy.

### Historical prediction differences in 0.4.0

The dashboard reports the mean and maximum absolute difference between a physical check and its stored pre-check forecast. It uses the current meter only, excludes future checks and checks older than 90 days, and keeps up to 12 distinct Korean calendar days. At least three comparable days are required. Exact duplicates count once, conflicting records at the same timestamp are excluded, and only the earliest comparable check on each day is retained so repeated corrections cannot inflate the sample count. Invalid or missing values are omitted. The comparison date range and sample count are shown.

This is a descriptive history, not an error bound around today's estimate, a confidence interval, a same-horizon benchmark or a guarantee of submission safety. Check intervals and model versions can differ. The summary never recomputes historical forecasts using later observations, changes estimates, or changes manual or automatic submission decisions. A calibrated uncertainty interval and its submission-policy implications remain separate work under issue #42.

See the estimator and parser tests for partial-day integration, seasonal adjustment, cold start, stale observations, zero consumption, leap months, meter replacement, corrections, overlap rejection, and year-boundary bill parsing.
