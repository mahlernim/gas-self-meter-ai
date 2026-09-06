# Alpha protocol assumptions

The Alpha track ships reasonable implementations, collects tester feedback and corrects the affected adapter. Lack of a successful account capture does not by itself prevent an experimental release. Identity matching, at most one mutation transmission, and understandable uncertain outcomes remain required.

| Adapter | Working assumption | Evidence and next feedback |
| --- | --- | --- |
| Gasapp | Missing optional submitted/channel/replacement flags mean false. Missing digit count uses the app's numeric bound. Recognized flags include Y/N, true/false and 1/0. Malformed supplied values block submission while bills remain readable. | Public web flow and synthetic tests. Ask testers whether their contract exposes additional required states. |
| Gasapp bills | Return all rows for a month. Display billing usage as reference data. Import estimator intervals only from matching cumulative meter readings. | Units and split/reissued bill behavior can vary. Compare with the official bill before defining aggregation. |
| SK E&S | Common portal structure supports regional adapters. Submission needs explicit eligibility/submission state and a finite prior reading. | Busan historical account reads, shared public structure elsewhere. Keep region-specific outcomes as separate feedback. |
| Samchully | Try the existing experimental login and bill parser, preserving explicit meter identity and cumulative differences. | Synthetic contract tests and public client expectations. Correct real response differences from tester feedback. |
| Kyungnam | Display current bill fields as corrected m³ and MJ. | Public form semantics. Compare displayed month and amount with the official site. |
| Daesung family | Separate supplier origins and self-reading paths, with contract controls, monthly table labels and a fresh submission form discovered from authenticated HTML. | Synthetic form tests cover contract selection, empty histories, integer submission and receipt readback. Ambiguous forms and payment, enrollment or cancellation actions are rejected. |
| EnergyTalk | Official session and selected service/address drive billing and direct submission across configured tenants. | Shared frontend observations and synthetic tests. Real response variations are tracked separately. |
| Haeyang | Mobile WEB LOGIN bootstrap and legacy JSON envelopes support account discovery, bills and SELF100/SELF101 status/submission. | The public getDecAES WEB branch serializes object values without native decryption. Encrypted or unknown structures are rejected. Amount scaling and billing fields follow public frontend semantics and synthetic tests. |

## September 6, 2026 direct-provider implementation

Haeyang public `common/js/hyBizMOB.js` has SHA-256 `b845f7339b788d6cc124a433befbd065df67f3a394b010e0a1e0e0b9833c6bfd`. Its WEB branch supplies a usable transport without a guessed native AES key. Requests remain supplier-bound. SELF101 is the reading write, while service enrollment and cancellation are excluded.

Daesung Energy uses `/consult/self`; Daesung Clean Energy uses `/service/self_input`. Similar login fields do not establish identical customer layouts. Each adapter discovers the selected contract, meter identity and form controls from that supplier's response.

Both connections preserve a pending record before dispatch and require a matching receipt read before confirmation. Unknown results remain pending or uncertain across restart. Public source observations and synthetic checks do not claim successful real-account submissions.

## Public frontend refresh on September 5, 2026

The [Gasapp public application](https://app.gasapp.co.kr/) referenced common chunk `70526.ab6447b61714c6cdc4d9.chunk.js`, SHA-256 `4a95fb80f1670c72f3ba401c93c77f9af544adb87dd6aa2e2e37cb9bdb82229e`, containing web version 6.10.549. Version 0.4.1 uses that observed header version. This does not establish authenticated compatibility. Public bootstrap assets can remain unchanged while the runtime's chunk mapping changes.

Source observations are used as protocol evidence. Third-party implementation code and raw captures are not redistributed here. See [provider documentation](PROVIDERS.md) for earlier references and attribution.
