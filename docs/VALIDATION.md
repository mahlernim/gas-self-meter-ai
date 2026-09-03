# First-release validation

## 0.2.0 개발 검증

2026년 9월 4일 현재 검침값 직접 입력과 조건부 자동 입력 구현을 검토 중입니다.

- 합성 데이터 단위검사 15개가 통과했습니다. 자동 입력의 마지막 날 제한, 최근 실측 기간, 불확실 전송 재시도 금지와 백업 복원 시 자동 입력 해제를 포함합니다.
- API 35 가상 기기에서 가상 데이터 흐름을 확인했습니다. `검침`, `제출`, `기록`, `설정` 탐색과 암호화 저장 후 화면 반영이 통과했습니다.
- SK E&S 공통 포털의 제출 필드와 가능 기간 판정은 공식 페이지 코드에서 확인했습니다. 부산은 허가받은 계정으로 조회했으며 다른 지역은 공통 포털 구조와 공급사 설정을 검증했습니다.
- 실제 고객 계정으로 제출 요청을 보내지는 않았습니다. 최초 실제 검증은 정확한 계량기 값, 입력 가능 기간과 사용자의 별도 승인을 확인한 뒤 한 번만 수행해야 합니다.
- 실제 제출이 검증되기 전에는 0.2.0을 Play 비공개 테스트에 배포하지 않습니다.

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
