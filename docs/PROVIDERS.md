# 공급사 지원 범위

2026년 9월 6일 소스 버전 0.4.2를 기준으로 정리했습니다. 배포 채널의 앱 버전은 다를 수 있습니다. 구현된 기능은 지원으로 표시하며, 실계정 검증 여부는 별도로 기록합니다.

## 지원 기능

모든 공급사에서 앱 내 사용량·실측 기록, 추정, 알림과 백업을 지원합니다. **앱 내 수동 기록**은 기기에 값을 저장하는 기능이며 **공급사 제출**은 공급사 서버에 검침값을 보내는 기능입니다.

| 공급사 또는 채널 | 구현 기준 지원 기능 | 앱 화면과 기록 반영 | 실계정 검증 |
| --- | --- | --- | --- |
| 부산도시가스 | 로그인, 계약·청구·검침 상태 조회, 사용자 확인 제출, 조건부 마지막 날 자동 제출 | 연결 및 이력 가져오기 지원 | 로그인·이력 조회 검증 기록 있음. 제출 성공 미검증 |
| 나머지 SK E&S 7개 공급사 | 로그인, 계약·청구·검침 상태 조회, 사용자 확인 제출 | 연결 및 이력 가져오기 지원. 백그라운드 자동 제출 비활성 | 공급사별 검증 미완료 |
| 가스앱 14개 공급사 | 본인인증, 계약·청구·계량기·검침 상태 조회, 사용자 확인 제출, 조건부 마지막 날 자동 제출 | 연결 및 이력 가져오기 지원 | 실계정 인증·제출 성공 미검증 |
| 삼천리 | 개인 계정 로그인, 계약 선택, 청구 이력 조회 | 알파 조회 전용 연결 및 이력 가져오기 지원. 제출 미지원 | 계정별 호환성 미검증 |
| 경남에너지 고객번호 조회 | 고지월·금액·보정사용량·사용열량 조회 | 실험실에서 참고 표시. 이력 가져오기·제출 미지원 | 미검증 |
| 대성에너지·대성청정에너지 | 공식 로그인 폼과 월별 요금 페이지 구조 점검 | 실험실에서 구조 점검 결과 표시. 청구 이력 가져오기·제출 미지원 | 실제 로그인 성공을 판정하는 기능은 아님 |
| EnergyTalk | 공식 웹 로그인·주소 선택 후 사용량·자가검침 상태 조회 | 실험실에서 참고 표시. 이력 가져오기·앱 자체 제출 미지원 | 미검증 |
| 그 밖의 공급사 | 앱 내 수동 기록과 추정 | 수동 기록 화면 지원 | 공급사 통신 없음 |

실계정 미검증은 구현된 기능을 미지원으로 분류하는 기준이 아닙니다. 합성 응답 검사와 실제 계정 성공은 서로 다른 검증 상태이며, 공급사 메뉴가 있다는 이유만으로 앱의 이력 가져오기나 제출 기능까지 지원한다고 표시하지 않습니다. 이번 문서 갱신에서는 실제 로그인·고객 조회·제출을 실행하지 않았습니다.

## 전체 공급사 목록

앱에는 이름이 있는 공급사 31개 항목과 `다른 공급사 / 직접 입력`이 있습니다. 아래 채널에는 중복 공급사가 있으므로 행별 개수를 더해 공급사 수로 세지 않습니다.

| 채널 | 공급사 |
| --- | --- |
| SK E&S | 부산도시가스, 코원에너지서비스, 충청에너지서비스, 영남에너지서비스 구미, 영남에너지서비스 포항, 전남도시가스, 강원도시가스, 전북에너지서비스 |
| 가스앱 | 서울도시가스, 예스코, 인천도시가스, 대륜E&S, 귀뚜라미에너지, JB, 전북도시가스, 군산도시가스, 제주도시가스, 참빛도시가스, 경동도시가스, MC에너지(구 목포도시가스), 미래엔서해에너지, 대화도시가스 |
| 삼천리 | 삼천리 |
| 고객번호 조회 실험 | 경남에너지 |
| 로그인·페이지 구조 점검 실험 | 대성에너지, 대성청정에너지 |
| EnergyTalk 실험 | CNCITY에너지, 경남에너지, 귀뚜라미에너지, 미래엔서해에너지, 서라벌도시가스, GSE, 참빛도시가스 |
| 수동 기록만 지원 | 해양에너지, 명성파워그린 |

참빛도시가스는 여러 지역 회사를 한 선택 항목으로 묶습니다. EnergyTalk의 11개 서비스 구분은 지역·지점을 포함하며 11개 독립 공급사를 뜻하지 않습니다. 청구서에서 실제 공급사 이름을 확인해 주세요. 전북에너지서비스와 전북도시가스, 대성에너지와 대성청정에너지는 별개 공급사입니다.

## 제출 범위

부산도시가스와 가스앱은 사용자 확인 제출과 선택적인 마지막 날 자동 제출을 지원합니다. 다른 SK E&S 공급사는 사용자 확인 제출만 지원합니다. 삼천리와 실험실 조회에는 앱 자체 제출 기능이 없습니다.

자동 제출은 기본적으로 꺼져 있습니다. 전송 전에 계약·계량기, 대상 여부, 검침 기간, 기존 제출, 이전 지침, 실측 설정과 전송 기록을 확인합니다. 결과가 불확실하면 자동으로 다시 보내지 않습니다. 가스앱의 서비스 등록이나 채널 변경은 별도 동의를 받습니다. 자세한 구현 범위는 [가스앱 연동](GASAPP.md)에 기록합니다.

## 알파 연결과 검증 상세

아래 내용은 현재 제공되는 연결과 실험의 경계 및 개인정보 처리 설명입니다. [알파 가정](PROTOCOL_ASSUMPTIONS.md), [검증 기록](VALIDATION.md), [개인정보 안내](../PRIVACY.md)를 함께 참고하세요.

## Alpha iteration policy from 0.4.1

Experimental connections ship from documented protocol observations and reasonable assumptions, then improve through tester feedback. Missing account-level validation alone does not block an Alpha release. Keep uncertainty visible and collect successful as well as failed outcomes. Malformed mutation prerequisites, mismatched identities and duplicate transmission are implementation defects to fix, while optional missing fields can use documented defaults. See [current assumptions](PROTOCOL_ASSUMPTIONS.md) and [the 0.4.1 feedback guide](ALPHA_0.4.1.md). Earlier account acceptance lists remain follow-up work, not a blanket prerequisite for exposing Alpha connections.

## 0.4.0 catalog expansion and evidence boundaries

The catalog includes 31 named entries representing the 34 company rows in the [KOGAS directory](https://www.kogas.or.kr/site/koGas/1020408040000), checked September 4, 2026. Four Chambit companies remain grouped under one stable catalog ID. This is catalog coverage, not verified account or submission coverage.

Daesung Clean Energy, Kyungnam Energy, Seorabeol City Gas, GSE and Myungsung PowerGreen are added with manual household recording. The separate experiments below do not enable automatic history imports. Chungcheong Energy Service also appears under Sejong. MC Energy includes its former Mokpo City Gas name. Supplier website links are separate from Gasapp connection routing. Grouped Chambit and Myungsung use the KOGAS directory where a single verified HTTPS supplier destination would be misleading or unavailable.

Provider expansion issues remain open until their respective account-level acceptance criteria are met. Similar login forms do not establish identical billing adapters. Public frontend fields are observed client expectations, not authenticated success captures. Missing-token responses do not characterize expired tokens, and an empty bill response does not establish an invalid customer. A failed or 404 mutation probe does not establish successful authorization, endpoint absence or a replacement cancellation route.

The onboarding and settings screens include a separate alpha connection laboratory. Existing automatic connection, background refresh and submission flags remain unchanged. Laboratory results are not imported into household history or used for submission. New HaeYang authentication remains disabled. Exact native HaeYang decryption behavior and Samchully cancellation behavior remain unverified.

## 0.4.0 experimental connection laboratory

| Route | Implemented experiment | Evidence boundary |
| --- | --- | --- |
| Kyungnam Energy | Authorized customer-number lookup displaying billing month, amount, corrected m³ and MJ | Frontend-derived fields, not a successful account capture. Corrected volume is not raw meter usage. No period reconstruction or estimator import. Empty responses do not establish customer validity. |
| Daesung Energy and Daesung Clean Energy | Exact official login forms followed by the monthly page, with bounded same-origin reads and no credential replay | A logout-looking link and monthly heading establish only session-like structure, not confirmed authentication. Returns table count and allowlisted labels, not customer rows or a billing schema. |
| EnergyTalk | Official WebView login and address selection followed by native display-only usage and self-reading-state queries | Eleven service tenants include Chambit regions and a branch, not eleven verified independent suppliers. Real-account compatibility and successful retrieval remain unverified. No import or native submission. |

EnergyTalk tenants are `cncity`, `kne`, `ktrm`, `miraense`, `srb`, `gse`, `cwjgas`, `ccbgas`, `cydgas`, `cdhgas` and `cscgas`. Users select their actual service and address. Authentication is handed to the official EnergyTalk/Kakao page. The app does not read passwords or exchange Kakao authorization codes itself.

The official WebView is not read-only. It can offer registration, consent, payment and meter submission. Testers must not use payment or submission during the experiment. Native read-only code does not disable the website's own write actions. Native queries use session data in memory. Cleanup of EnergyTalk storage and some cookies is best effort on normal close. Kakao cookies are retained, and abnormal termination or cookie paths can leave additional web data behind.

Direct-probe inputs and displayed results are not saved to household records or backups. Diagnostic summaries contain allowlisted stages and classifications, not customer identifiers or raw responses. The app adds no analytics or automatic diagnostic upload. Third-party web services apply their own privacy policies.

Synthetic tests are separate from authorized account acceptance. This document does not claim final test-suite success or a successful live customer login. See [the alpha test guide](ALPHA_0.4.0.md) and [privacy policy](../PRIVACY.md).

## 0.3.1 experimental Samchully reads

Samchully is available as an explicitly experimental read-only connection for alpha testers. Personal-account login, contract selection and bill history are wired into the app. Account-specific compatibility is not yet confirmed. Submission remains disabled. Local diagnostic metadata can be reviewed and copied by the tester, without automatic upload. See [the alpha patch notes](ALPHA_0.3.1.md).

## 0.3.0 Gasapp integration

The app now includes an independently implemented Gasapp SMS authentication and contract workflow for 14 configured supplier brands. It retrieves bills, meter information and submission windows, supports explicit direct submission and optional last-day submission, and preserves uncertain outcomes without repeating the POST. Required terms and service/channel changes need in-app user consent. Marketing consent defaults to no. See [GASAPP.md](GASAPP.md) for current endpoint coverage and synthetic validation. The research notes below describe earlier observations and do not claim live customer authentication or submission verification.


## 출처와 라이선스

아래 공개 참고 프로젝트의 기능을 이 앱의 지원 기능으로 간주하지 않습니다. 검사한 revision과 당시의 라이선스 관찰을 보존합니다. 라이선스가 확인되지 않은 가스앱 소스는 재배포하지 않으며 이 앱의 연동은 독립 구현입니다.

| Reference | What it demonstrates | Limits |
| --- | --- | --- |
| [af950833/korea_gasapp](https://github.com/af950833/korea_gasapp/tree/f9809867c89779378feb8b5e3880b49abae2589e) | A Home Assistant Gasapp client with SMS authentication, member/session handling, contract discovery and bill/indication cards | No license found at the inspected commit. No authorized non-SK customer test in this research |
| [hwajin-me/home-assistant-korea-components](https://github.com/hwajin-me/home-assistant-korea-components/tree/6b5d17e10411054f45a6fbe904f7c152d818c307/custom_components/korea_incubator/gasapp) | A smaller Gasapp reader using token, member, company and contract values to read home/bill data | No applicable license found for this module. Manual token setup and uncertain session lifetime |
| [dugurs/ha-city-gas-bill](https://github.com/dugurs/ha-city-gas-bill/tree/48940284b101a00d60673545bd13e261c128243a) | MIT-licensed public tariff/caloric scrapers for several SK and non-SK providers | Tariff access is not customer billing-history access. Some adapters provide only heat factors or require manual prices |

사용 라이브러리의 출처와 라이선스는 [NOTICE](../NOTICE.md)에 기록합니다.
