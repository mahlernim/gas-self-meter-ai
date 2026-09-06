# 개발 검증 기록

이 문서는 개발자를 위한 검증 기록입니다. 앱 화면이나 이용자 안내에 이 기록을 그대로 사용하지 않습니다.

## 0.5.1 대성 및 해양 연결

2026년 9월 6일 versionCode 14의 단위 테스트 179개와 API 35 가상 기기 테스트 32개가 모두 통과했습니다. Android lint와 debug build도 통과했습니다. 실제 계정 조회를 수행하는 LiveBusanTest는 이번 실행에서 제외했습니다.

대성 두 회사의 계약 선택과 HTML 폼 처리, 해양 WEB 인증과 청구 변환, 제출 직전 대상 대조, 전송 후 결과 재조회와 불확실한 전송의 중복 방지를 합성 응답과 모의 전송으로 확인했습니다. 기기에서는 로그인 진입, 두 계약 중 선택, 계량기 정보 반영, 청구 이력 표시와 확인 중인 제출 기록의 보존을 확인했습니다. 실제 공급사에 시험 로그인, SMS 또는 검침 제출 요청을 보내지 않았습니다.

## 2026년 9월 6일 공급사 연결 구현

다음 공급사 연결 업데이트의 로컬 개발본에서는 가스앱 본인인증과 계약 선택, EnergyTalk 공식 WebView 로그인과 주소 선택, 삼천리 로그인, 공급사별 청구 이력 변환, 검침값 입력과 결과 재조회 흐름을 구현했습니다. 테스트는 합성 응답과 모의 전송으로 실행했습니다. 실제 공급사에 더미 로그인, SMS 발송, 토큰 교환 또는 검침값 입력 요청을 보내지 않았습니다.

| Check | Result |
| --- | --- |
| JVM tests | 67 passed. 인증 및 계약 연결, 청구 변환, 제출 조건, 전송 결과 재조회와 백업 경계를 포함합니다. |
| Selected Android tests | 4 passed. 연결 화면과 공급사 흐름을 API 35 가상 기기에서 확인했습니다. |
| Debug build | Passed for the local provider-integration development snapshot on 2026-09-06. |

테스트 fixture는 합성 데이터입니다. 테스트는 계정을 만들거나 실제 인증번호를 요청하지 않으며, 실제 공급사에 검침값을 입력하지 않습니다.

## 이전 기록

아래 내용은 2026년 9월 4일의 초기 구현 기록입니다. 현재 공급사 연결 구현과 별도로 보존합니다.

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

초기 Android 조회에서 공급사 날짜가 압축된 `YYYYMMDD` 형식으로 들어오는 경우를 발견해 파서를 수정하고 회귀 검사를 추가했습니다. 당시의 승인된 읽기 전용 조회는 제출 엔드포인트를 호출하지 않았습니다.

추정 모델에서는 과거 구간 전체에 종료일 보정값을 적용하면 누적 추정값이 낮아질 수 있는 경계를 수정했습니다. 각 날짜의 음수가 아닌 사용률을 적분하도록 변경했고, 단조성 회귀 검사를 추가했습니다.

CI는 JVM 테스트, lint와 새 debug build를 실행합니다. [GitHub Actions](https://github.com/mahlernim/gas-self-meter-ai/actions)와 [release assets](https://github.com/mahlernim/gas-self-meter-ai/releases)는 게시된 소스와 산출물의 기록을 제공합니다.
