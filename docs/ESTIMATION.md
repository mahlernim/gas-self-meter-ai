# Local estimation model

The word AI in the product name refers to a local adaptive statistical estimator. It does not call a language model or require a cloud API key.

## Historical baseline

A usage segment is an inclusive date range and its raw meter-volume difference in m³. The daily rate for a historical calendar month is the sum of each segment's proportional volume inside that month divided by the covered days. At least 14 covered days are required. Overlapping segments are rejected to avoid double counting. Calendar-only manual input assumes usage was evenly distributed within that month.

For a target date, use the corresponding month one year earlier. Interpolate its daily rate with the preceding or following historical month's rate, using the 15th as each month's center. This is the 13/12/11-month seasonal idea expressed on actual dates. If a neighboring month is absent, keep the central month's rate. If the central month is absent, the seasonal baseline is unavailable. Leap-year month lengths are respected.

## Recent calibration

Use the latest two physical observations for the active meter that are at least one day and at most 28 days apart. Compute their actual volume difference and the integrated seasonal baseline over that interval. When the baseline exceeds 0.01 m³, their ratio is capped between 0 and 5. Shrink that ratio toward 1 with weight

```
spanDays / (spanDays + 7) * clamp(1 - daysSinceLatestObservation / 28, 0, 1)
```

The seasonal rate is multiplied by `1 + weight * (ratio - 1)`. Decay is evaluated on each integrated date, so later projections do not retroactively reduce the elapsed cumulative consumption. If the seasonal baseline is near zero, blend the daily rates instead while recent observations are fresh. These guardrails are engineering choices and are not calibrated prediction intervals.

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

See the estimator and parser tests for partial-day integration, seasonal adjustment, cold start, stale observations, zero consumption, leap months, meter replacement, corrections, overlap rejection, and year-boundary bill parsing.
