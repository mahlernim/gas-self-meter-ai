# First-release validation

## 0.3.0 통합 검증

- 2026년 9월 4일 Windows JDK 21과 Android API 35 에뮬레이터에서 검증했습니다.
- 단위 테스트와 Android lint가 통과했습니다. 보정 재알림 0~6회, 공급사 제출 상태 반영, 마지막 날 안내, 정보 갱신과 제출 분리, 세션 없는 백업, 계정·계량기 변경 차단을 포함합니다.
- Gasapp HTTP 합성 응답 테스트에서 제출 전 대상 변경 차단, 정수 지침, 응답 손실 후 조회 확인과 POST 재시도 금지를 확인했습니다.
- 에뮬레이터의 검침·보정·추이·설정 흐름, 시스템 파일 선택기를 통한 백업 내보내기와 복원, 실제 SMS 없는 Gasapp 인증 화면 진입 및 취소의 3개 흐름이 통과했습니다.
- README와 Play 소개용 이미지는 가상 데이터로 촬영했습니다. 계약자번호 0000000000은 데모 번호입니다.
- 실제 고객 계정의 SMS 인증이나 공급사 검침값 전송은 실행하지 않았습니다. 실제 계정 검증 완료를 의미하지 않습니다.
- Android 절전 및 네트워크 상황에 따라 WorkManager 작업은 지정 시각보다 늦어질 수 있습니다.


## 0.2.3 제출 안전 검사 보완

- 제출 직전 조회한 계약과 계량기가 기기에 저장된 대상과 다르면 직접 입력과 자동 입력을 모두 차단합니다.
- 제출 후에는 같은 계약, 계량기, 검침 기간과 제출값이 확인되어야 완료로 표시합니다. 조회값이 누락되거나 대상이 달라지면 결과 불확실 상태로 남기며 자동 재전송하지 않습니다.
- 합성 데이터 단위검사 23개가 통과했습니다. 계약 및 계량기 변경 차단과 제출 결과의 값 누락, 값 불일치 및 검침 기간 변경을 포함합니다.
- 실제 공급사 제출 검증은 아직 수행하지 않았습니다. #4와 상위 작업 목록 #7은 실제 검증 완료 전까지 열린 상태로 유지합니다.
- 최초 실제 검증에는 현재 계약과 계량기, 입력 가능 기간, 정확한 실측값을 확인한 뒤 한 번만 제출하고 같은 값의 접수 결과를 조회해야 합니다.
- 이 패치는 Closed Alpha용이며 제출 기능의 기본값은 꺼짐입니다.

## 0.2.2 개발 검증

2026년 9월 4일 현재 기록 탭의 24개월 조회와 월별 상세 표시를 포함한 Closed Alpha 빌드를 검증합니다.

- 합성 데이터 단위검사 21개가 통과했습니다. 24개월 요약, 정확히 일치하는 청구월 금액만 표시하는 조건, 자동 입력의 안전 조건과 백업 복원을 포함합니다.
- API 35 가상 기기에서 가상 데이터 흐름을 확인했습니다. `검침`, `제출`, `기록`, `설정` 탐색, 24개월 차트 선택, 청구월 가스비 표시와 암호화 저장 후 화면 반영이 통과했습니다.
- SK E&S 공통 포털의 제출 필드와 가능 기간 판정은 공식 페이지 코드에서 확인했습니다. 부산은 허가받은 계정으로 조회했으며 다른 지역은 공통 포털 구조와 공급사 설정을 검증했습니다.
- 실제 고객 계정으로 제출 요청을 보내지는 않았습니다. 최초 실제 검증은 정확한 계량기 값, 입력 가능 기간과 사용자의 별도 승인을 확인한 뒤 한 번만 수행해야 합니다.
- 실제 제출은 아직 검증되지 않았습니다. 0.2.2는 제출 기능이 기본적으로 꺼진 Closed Alpha로만 배포하며, 테스터에게 이 제한을 명시합니다.

Checked on 2026-09-04 for v0.2.2.

| Check | Result |
| --- | --- |
| JVM tests | 21 passed, covering history summaries, estimation, meter changes, corrections, stale data, submission policy, parsing and portable backups |
| Android lint | No errors. Advisory warnings remain for newer available dependencies/API targets and minor style suggestions |
| Android onboarding and demo | Passed on API 35, including confirmed adjustment, encrypted persistence, 24-month history selection, exact bill-month amount and settings |
| Manual and file workflow | Passed on API 35 through the actual system document picker, including manual reading, historical usage, JSON export and restore |
| Reminder | UI toggle persisted, weekly work enqueued, the same worker delivered a notification in a test-triggered immediate run, and disabling reminders removed the notification |
| Busan account access | Authorized Android read-only probe passed for one contract and all 13 advertised billing months, matching current meter identity and a computable seasonal estimate |
| Release signing | App-specific RSA 4096-bit key. APK Signature Scheme v2 verification passed |
| APK alignment | `zipalign -c -P 16 4` passed |
| Package metadata | `dev.mahlernim.gasselfmeter`, version code 5, version name 0.2.2, min API 26, target API 36 |

The Android live probe caught compact `YYYYMMDD` provider dates that the initial Kotlin parser did not accept. The parser was corrected, a regression check was added and the live probe passed afterward. It never called a submission endpoint. Credentials were delivered through hidden input and stdin to a private temporary debug-app file, removed before networking. They were not stored in the repository, exported, or included in screenshots.

The cumulative model was also checked for a decay edge case. Applying an end-date calibration weight to an entire past interval could make a later cumulative estimate fall. The implementation now integrates each date's own nonnegative rate, and a monotonicity regression test covers that case.

Screenshots in `docs/images` are captured from the app on the emulator. Snackbar transients were dismissed for capture. The launcher uses the generated artwork inside Android's adaptive mask, with a native monochrome vector.

CI runs the JVM tests, lint and a fresh debug build. Instrumentation and the authorized live test are local release checks. The live test skips unless explicitly supplied authorized credentials. See [GitHub Actions](https://github.com/mahlernim/gas-self-meter-ai/actions) and the [release assets](https://github.com/mahlernim/gas-self-meter-ai/releases) for publication evidence.

No non-SK account integration, physical handset, week-long background-delay behavior, or household forecast accuracy was validated. Android 8.0 is the supported build minimum, not a claim that every OS version was exercised. The published release must pass the remaining clean-install and downloaded-asset integrity checks in [the release procedure](RELEASING.md).

The automated test fixture values are synthetic. Tests do not register accounts, request SMS codes, submit readings or pay bills.
