# First-release validation

Checked on 2026-09-03 for v0.1.0.

| Check | Result |
| --- | --- |
| JVM tests | 12 passed, covering estimation, meter changes, corrections, stale data, monotonic cumulative forecasts, parsing and portable backups |
| Android lint | No errors. Advisory warnings remain for newer available dependencies/API targets and minor style suggestions |
| Android onboarding and demo | Passed on API 35, including confirmed adjustment, encrypted persistence across activity recreation, history and settings |
| Manual and file workflow | Passed on API 35 through the actual system document picker, including manual reading, historical usage, JSON export and restore |
| Reminder | UI toggle persisted, weekly work enqueued, the same worker delivered a notification in a test-triggered immediate run, and disabling reminders removed the notification |
| Busan account access | Authorized Android read-only probe passed for one contract and all 13 advertised billing months, matching current meter identity and a computable seasonal estimate |
| Release signing | App-specific RSA 4096-bit key. APK Signature Scheme v2 verification passed |
| APK alignment | `zipalign -c -P 16 4` passed |
| Package metadata | `dev.mahlernim.gasselfmeter`, version code 1, version name 0.1.0, min API 26, target API 36 |

The Android live probe caught compact `YYYYMMDD` provider dates that the initial Kotlin parser did not accept. The parser was corrected, a regression check was added and the live probe passed afterward. It never called a submission endpoint. Credentials were delivered through hidden input and stdin to a private temporary debug-app file, removed before networking. They were not stored in the repository, exported, or included in screenshots.

The cumulative model was also checked for a decay edge case. Applying an end-date calibration weight to an entire past interval could make a later cumulative estimate fall. The implementation now integrates each date's own nonnegative rate, and a monotonicity regression test covers that case.

Screenshots in `docs/images` are captured from the app on the emulator. Snackbar transients were dismissed for capture. The launcher uses the generated artwork inside Android's adaptive mask, with a native monochrome vector.

CI runs the JVM tests, lint and a fresh debug build. Instrumentation and the authorized live test are local release checks. The live test skips unless explicitly supplied authorized credentials. See [GitHub Actions](https://github.com/mahlernim/gas-self-meter-ai/actions) and the [release assets](https://github.com/mahlernim/gas-self-meter-ai/releases) for publication evidence.

No non-SK account integration, physical handset, week-long background-delay behavior, or household forecast accuracy was validated. Android 8.0 is the supported build minimum, not a claim that every OS version was exercised. The published release must pass the remaining clean-install and downloaded-asset integrity checks in [the release procedure](RELEASING.md).

The automated test fixture values are synthetic. Tests do not register accounts, request SMS codes, submit readings or pay bills.
