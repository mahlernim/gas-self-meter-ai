# Gasapp connection

The Android client implements phone verification, required terms, member session creation, contract selection, billing and meter queries, self-reading registration, channel changes, and reading submission. It communicates directly with Gasapp. No developer backend receives credentials or identity data.

## Protocol sources

Independently implemented from protocol observations, including the [official frontend](https://app.gasapp.co.kr/). Third-party implementation source is not redistributed.

On 2026-09-04, the official `70526.28f463236afe2ff0a022.chunk.js` used `GET contracts`, `home`, `meters`, `bills/summary`, `indications` and `indications/history`. The history page requests six rows, displays five, and uses the sixth row ID as the next inclusive cursor. Annual bills use `f=annual` and `onlyUnpay=N`. Registration posts customer and contract numbers. Channel changes put the contract number to `indications/channel`.

Public, anonymous `documents/search/0` requests were verified on the same date. Required terms are selected by the server's `necessary` field. Optional benefit marketing consent is excluded and member creation sends `marketingAcceptance=N`.

SMS request and confirmation, `POST members`, and `POST relay/indications/input` follow the public reference protocol. Member birth dates use YYYYMMDD. The current public frontend passes final meter input to its native bridge, so authenticated submission compatibility must be exercised by the user with their real account. Unit tests do not establish successful account authentication or submission for every company.

## Integration contract

Version 0.4.1 retains experimental access while recording [Alpha assumptions](PROTOCOL_ASSUMPTIONS.md). Missing optional flags use documented defaults. Malformed supplied submission fields produce a submission-specific explanation without rejecting readable bills. Mutation bodies are one-shot, including HTTP 503 follow-ups. Duplicate-month bills remain separate through refresh and backup, and billed usage stays visible without becoming an unverified raw meter interval.

- `GasappConnectScreen` returns a session and selected account. The app stores them in its existing encrypted store and starts normal synchronization. Identity input and OTP are held only in transient UI state.
- `GasappApi.snapshot` returns typed bill summaries, reading history, and a current target. Missing amounts or usage remain null. The adapter must not replace them with zero or invent a meter identity. AMI comes from the selected contract.
- Registration and channel change require an explicit user action. The app explains their effect before calling the API.
- Automatic submission is opt-in. Both direct and automatic submission persist a pending record before the call. The client refreshes target identity, dates, eligibility, and previous reading before sending exactly one POST.
- Gasapp input is an integer. The client rejects fractions rather than silently rounding a confirmed value. The submission UI and automatic policy must use a provider-specific integer value before confirmation.
- `reconcile` only performs reads. HTTP success alone is not receipt confirmation. Confirmation requires the same account, meter, period and value. Pending or uncertain records must not be automatically resent.
- Session expiry requires phone reauthentication. It does not trigger silent membership mutation or a repeated submission.

## Verification

MockWebServer tests cover consent, phone and member request fields, session expiry, company isolation, AMI, pagination, numeric parsing, changed meter rejection, integer validation, period restrictions, and receipt confirmation. Build and unit tests run without customer credentials. The integrated app is then tested with the user's account, including a submission during the provider's reading period and independent receipt confirmation.

For user acceptance, check login, contract selection, bill and meter comparisons, service registration if needed, one confirmed direct submission, then automatic submission settings. Test an ordinary company, a Chambit regional code, and a company requiring meter identification as accounts become available. Keep account details out of public issues and screenshots.
