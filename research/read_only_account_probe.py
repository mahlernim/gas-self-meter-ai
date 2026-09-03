"""Bounded, in-memory Busan account probe. Saves structural results only."""
import asyncio
from datetime import date, datetime, timezone
from getpass import getpass
import importlib
import json
from pathlib import Path
import sys
import types

import aiohttp

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parent
package = types.ModuleType("busan_probe")
package.__path__ = [str(ROOT / "ha-busan-city-gas/custom_components/busan_city_gas")]
sys.modules[package.__name__] = package
portal = importlib.import_module("busan_probe.portal")

ALLOWED = {
    "/busan/login/login.do", "/busan/login/loginProcess.do",
    "/busan/read/selfRead.do", "/busan/read/call_EBPP_018.do",
    "/busan/charge/askDetail.do", "/busan/rate/guide.do",
}


class ReadOnlyClient(portal.PortalClient):
    async def _request(self, path, data=None):
        if path not in ALLOWED:
            raise RuntimeError("path_not_allowlisted")
        return await super()._request(path, data)

    async def submit(self, *args, **kwargs):
        raise RuntimeError("submission_disabled_in_research")


async def probe(username, password):
    summary = {
        "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_commit": "320513798301d491e3984fae8bb1a1cede22e8c0",
        "scope": "User-authorized read-only test. Credentials and response bodies kept in memory only. At most 24 bills per contract and 3 contracts.",
        "authenticated": False,
        "contracts": [],
    }
    async with aiohttp.ClientSession(cookie_jar=aiohttp.CookieJar()) as session:
        client = ReadOnlyClient(session, username, password)
        try:
            await client.login()
            summary["authenticated"] = True
            contracts = await client.contracts()
            summary["contract_count"] = len(contracts)
            print("Login and contract discovery succeeded.", flush=True)
            for index, contract in enumerate(contracts[:3]):
                row = {"contract_index": index + 1, "history_errors": []}
                try:
                    window = await client.meter(contract)
                    row["meter_information_parsed"] = True
                    row["reading_window_has_dates"] = bool(window.start and window.end)
                    row["planned_reading_date_available"] = bool(window.planned)
                except portal.GasError as error:
                    row["meter_error"] = str(error)
                    window = None
                html = await client.bill_page(contract)
                latest = portal.bill_from_html(html)
                months = portal.available_months(html)
                row["advertised_history_month_count"] = len(months)
                selected = sorted(set(months) | {latest.month})[-24:]
                bills = [latest]
                for month in selected:
                    if month == latest.month:
                        continue
                    try:
                        bills.append(portal.bill_from_html(await client.bill_page(contract, month), month))
                    except portal.GasError as error:
                        row["history_errors"].append({"month": month, "error": str(error)})
                        break
                    await asyncio.sleep(0.3)
                bills.sort(key=lambda b: b.month)
                today = date.today()
                targets = []
                for offset in (13, 12, 11):
                    ordinal = today.year * 12 + today.month - 1 - offset
                    year, month0 = divmod(ordinal, 12)
                    first = date(year, month0 + 1, 1)
                    next_first = date(year + (month0 == 11), (month0 + 1) % 12 + 1, 1)
                    matches = [b.month for b in bills if b.start < next_first and b.end >= first]
                    targets.append({"months_ago": offset, "calendar_month": first.strftime("%Y-%m"), "overlapping_bills": matches})
                row.update(
                    parsed_bill_count=len(bills),
                    earliest_bill_month=bills[0].month,
                    latest_bill_month=bills[-1].month,
                    earliest_usage_date=min(b.start for b in bills).isoformat(),
                    latest_usage_date=max(b.end for b in bills).isoformat(),
                    all_bills_have_dated_meter_segments=all(b.segments and all(s.meter for s in b.segments) for b in bills),
                    bills_with_due_date=sum(bool(b.due_date) for b in bills),
                    bills_with_unsupported_adjustments=sum(b.unsupported_adjustments for b in bills),
                    latest_meter_matches_bill=bool(window and latest.segments[-1].meter == window.meter),
                    latest_bill_has_coefficients=all(s.coefficient and s.heat_factor for s in latest.segments),
                    seasonal_target_coverage=targets,
                    credential_or_customer_identifiers_saved=False,
                )
                summary["contracts"].append(row)
                print(f"Contract {index + 1}: {len(bills)} bills parsed.", flush=True)
            try:
                tariff = await client.tariff()
                summary["tariff_parsed"] = True
                summary["tariff_effective_date"] = tariff.effective
            except portal.GasError as error:
                summary["tariff_error"] = str(error)
        except portal.GasError as error:
            summary["error"] = str(error)
        except Exception as error:
            summary["error_type"] = type(error).__name__
        finally:
            client.username = client.password = ""
            session.cookie_jar.clear()
    (ROOT / "busan-read-only-results.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    username = getpass("Test username (hidden): ")
    password = getpass("Test password (hidden): ")
    asyncio.run(probe(username, password))
