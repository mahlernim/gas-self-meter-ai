# Korean gas-provider integration research

Checked on 2026-09-03. Source inspection, public endpoint tests and authenticated tests are different evidence levels. Only Busan account retrieval is enabled in v0.1.0.

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

The raw public probe script is [inspect_non_sk_public.py](../research/inspect_non_sk_public.py). Numeric tariffs are observations at the research date and are deliberately not hardcoded into the app. No customer history access was established by these tariff queries.

## SK E&S findings

The eight public login pages for Busan, Cowon, Chungcheong, Yeongnam Gumi, Yeongnam Pohang, Jeonnam, Gangwon and Jeonbuk Energy Service share the same normalized login function. Public caloric pages share a request shape. Both `www.skens.com` and `ebpp.skens.com` variants returned HTTP 200 during the audit.

The Busan adapter uses `C000` as a company identifier. Changing only `/busan/` in the URL would leave contract identities, billing queries and caloric validation wrong for other companies. Each company needs its own verified code and authenticated response fixtures. Other SK companies therefore use the manual workflow in this release.

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
