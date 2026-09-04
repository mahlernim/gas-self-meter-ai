# Korean gas-provider integration research

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


## SK E&S 검침값 입력

0.2.1은 단순 기록을 넘어 공급사의 자가검침 가능 기간과 기존 입력 상태를 확인하고, 계산한 누적 지침을 직접 또는 조건부로 자동 입력하도록 설계했습니다. 자동 입력은 기본적으로 꺼져 있으며 검침 기간 마지막 날에만 실행합니다.

공식 자가검침 페이지에서 입력 주소와 필드 구조를 확인했습니다. 대상 조회 응답의 기간, 자가검침 대상 여부, 이전 지침, 계량기 식별값과 내부 식별값을 그대로 사용합니다. 제출 전후에 공급사 상태를 조회하고 결과가 불확실하면 다시 보내지 않습니다.

2026년 9월 4일 현재 실제 제출은 수행하지 않았습니다. 정확한 계량기 값과 입력 가능 기간을 확인한 사용자 계정에서 단 한 번의 직접 입력을 검증하기 전까지 이 기능이 실제 공급사에서 정상 완료된다고 단정하지 않습니다.

아래 내용은 2026년 9월 3일에 수행한 공급사 연동 조사 기록입니다. 소스 확인, 공개 주소 조회와 인증 계정 검증은 서로 다른 수준의 근거입니다.

## Useful code beyond SK E&S

| Reference | What it demonstrates | Limits |
| --- | --- | --- |
| [af950833/korea_gasapp](https://github.com/af950833/korea_gasapp/tree/f9809867c89779378feb8b5e3880b49abae2589e) | A Home Assistant Gasapp client with SMS authentication, member/session handling, contract discovery and bill/indication cards | No license found at the inspected commit. No authorized non-SK customer test in this research |
| [hwajin-me/home-assistant-korea-components](https://github.com/hwajin-me/home-assistant-korea-components/tree/6b5d17e10411054f45a6fbe904f7c152d818c307/custom_components/korea_incubator/gasapp) | A smaller Gasapp reader using token, member, company and contract values to read home/bill data | No applicable license found for this module. Manual token setup and uncertain session lifetime |
| [dugurs/ha-city-gas-bill](https://github.com/dugurs/ha-city-gas-bill/tree/48940284b101a00d60673545bd13e261c128243a) | MIT-licensed public tariff/caloric scrapers for several SK and non-SK providers | Tariff access is not customer billing-history access. Some adapters provide only heat factors or require manual prices |

The [official Gasapp website](https://www.gasapp.co.kr/) lists Seoul City Gas, Yesco, Incheon City Gas, Daeryun E&S, JB, Gunsan, Jeju, Kiturami Energy, Chambit, MC Energy, Kyungdong, Miraen Seohae, Daehwa and Jeonbuk among its service areas. That is promising shared infrastructure for future expansion. It does not establish that every company's full billing history uses the same schema or that the app can obtain enough dated history for this estimator.

No directly useful customer-history connector was found in the bounded GitHub searches for Samchully, CNCITY and Gyeongnam Energy domains. This is a search result, not a claim that no such code exists.

## Gasapp protocol observations

The inspected source uses `https://app.gasapp.co.kr/api/`. Its request sequence includes SMS request and confirmation, member handling, `init` for contracts, `home` for cards, `meters`, and `bills/summary`. Headers include member, token, company, platform and application-version values. These are internal app endpoints, not an advertised public developer API.

`home` can expose bill and indication history, but actual coverage, date boundaries, volume units, pagination and ordering need an authorized account test. The two references make different ordering assumptions, so any new adapter should sort explicit dates and validate units. An old endpoint name alone is not enough to claim compatibility.

The SMS/member flow may create or update a member and accept service agreements. Research did not call these endpoints, send SMS, register accounts, refresh customer sessions or submit meter readings. A future user-facing connection must explain the account action and follow the legitimate authentication/consent flow. No Busan credential was sent to a different provider.

Because no applicable license was found for the two Gasapp implementations, their code is not redistributed in this project. A future implementation can use documented protocol observations and independently written code, or obtain an appropriate license from the author.

## Public non-SK queries verified

All queries below were read-only and used no customer account.

| Provider | Observed public mechanism | Result |
| --- | --- | --- |
| Seoul City Gas | CSRF metadata from `/front/payment/gasPayTable`, then JSON POST to `/ajax/front/payment/gasPayTable` with `gaspayArea` `01` | HTTP 200 with residential tariff rows. The initial probe incorrectly used `1` and was corrected to `01` |
| Yesco | POST `/Common/connApiServer.do` with public tariff operation `E0006` and effective-date input | Success, 105 rows, residential rates returned |
| Incheon City Gas | Public DWR `ICGAS.getChargecost.dwr` with region, usage class and effective date | HTTP 200 with the expected numeric rate field |
| Gasapp frontend | Public landing page and its referenced JavaScript | HTTP 200. Split frontend bundles mean the bootstrap alone does not reveal the full API |

Numeric tariffs are observations at the research date and are deliberately not hardcoded into the app. No customer history access was established by these tariff queries. Raw investigation scripts and internal capture records are not part of the public specification.

## SK E&S findings

The eight public login pages for Busan, Cowon, Chungcheong, Yeongnam Gumi, Yeongnam Pohang, Jeonnam, Gangwon and Jeonbuk Energy Service share the same normalized login function. Public caloric pages share a request shape. Both `www.skens.com` and `ebpp.skens.com` variants returned HTTP 200 during the audit.

The shared SK E&S adapter selects both the portal path and company identifier. The configured pairs are `busan`/`C000`, `koone`/`B000`, `cheongju`/`D000`, `gumi`/`E000`, `pohang`/`F000`, `jeonnam`/`G000`, `gangwon`/`J000` and `jeonbuk`/`K000`. These providers use the automatic account workflow and user-confirmed direct submission. Busan retains the existing opt-in background submission. The other regions rely on the verified common portal structure and keep background submission disabled until region-specific account fixtures are available.

An authorized live account test through [ha-busan-city-gas](https://github.com/mahlernim/ha-busan-city-gas/tree/320513798301d491e3984fae8bb1a1cede22e8c0) succeeded for login, one contract, meter metadata and all 13 advertised billing months. The histories included dated usage segments and matching current-meter identity. Due dates were not extracted by that parser. No daily forecast-accuracy study was performed. No submission took place.

## Regional selection

A region does not uniquely determine the provider. Seoul and Gyeonggi have several. Jeonbuk Energy Service and Jeonbuk City Gas are separate companies, and Incheon City Gas is not Cowon Energy Service. The app asks for the provider after the region and offers a manual fallback. The [KOGAS supplier directory](https://www.kogas.or.kr/site/koGas/1020408040000) is linked when a supplier is missing.

## Next connector acceptance criteria

1. An authorized account can sign in through its legitimate flow without bypassing verification.
2. Multiple contracts can be identified and selected, with no data mixing between households.
3. At least the available historical periods, units and meter identities can be parsed and verified. Missing history must remain missing.
4. Session expiry and changed response schemas produce understandable errors and preserve existing records.
5. Local credential handling, export exclusion and the read-only request boundary have been verified on Android.
6. Source licensing and attribution are resolved before redistributing borrowed code.
