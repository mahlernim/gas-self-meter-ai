"""Public frontend and tariff inspection. No customer auth, SMS or writes."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parent


def gasapp():
    page_url = "https://app.gasapp.co.kr/"
    response = requests.get(page_url, timeout=25)
    soup = BeautifulSoup(response.content, "html.parser")
    scripts = [urljoin(page_url, x["src"]) for x in soup.select("script[src]")]
    main_url = next((x for x in scripts if "/static/js/main." in x), None)
    result = {"name": "gasapp_public_frontend", "url": page_url, "status": response.status_code, "main_script_url": main_url}
    if main_url:
        response = requests.get(main_url, timeout=40)
        response.raise_for_status()
        content = response.content.decode("utf-8")
        result["script_bytes"] = len(response.content)
        result["script_sha256"] = hashlib.sha256(response.content).hexdigest()
        snippets = {}
        for term in ("extern/auth/nice/sms/request", "extern/auth/nice/sms/confirm", "bills/summary", "bills/history", "usageQty", "useContractNum", "X-COMPANY", "X-Company", "companyName", "서울도시가스", "예스코", "삼천리"):
            pos = content.find(term)
            snippets[term] = content[max(0, pos-100):pos+230] if pos >= 0 else None
        result["source_snippets"] = snippets
        (ROOT / "gasapp-public-main.js").write_text(content, encoding="utf-8")
    return result


def yesco():
    url = "https://www.lsyesco.com/Common/connApiServer.do"
    response = requests.post(url, json={"id": "E0006", "I_DATAB": "20260901"}, timeout=25)
    result = {"name": "yesco_public_tariff", "url": url, "status": response.status_code}
    try:
        data = response.json()
        result["success"] = data.get("success")
        rows = data.get("data", {}).get("Tables", {}).get("ITAB", {}).get("tableMap", [])
        result["row_count"] = len(rows)
        result["residential_rates"] = [{k: row.get(k) for k in ("CITYCD", "TYPENAME", "AMOUNT_PERC")} for row in rows if row.get("TYPENAME") in ("주택취사", "주택난방")]
    except (ValueError, AttributeError):
        result["expected_json_schema"] = False
    return result


def seoul():
    page = "https://www.seoulgas.co.kr/front/payment/gasPayTable"
    with requests.Session() as session:
        response = session.get(page, timeout=25)
        result = {"name": "seoul_public_tariff", "url": page, "status": response.status_code}
        soup = BeautifulSoup(response.content, "html.parser")
        token = soup.select_one('meta[name="_csrf"]')
        header = soup.select_one('meta[name="_csrf_header"]')
        result["csrf_metadata_present"] = bool(token and header)
        if token and header:
            response = session.post("https://www.seoulgas.co.kr/ajax/front/payment/gasPayTable", json={"gaspayArea": "01"}, headers={header["content"]: token["content"], "X-Requested-With": "XMLHttpRequest"}, timeout=25)
            result["tariff_response_status"] = response.status_code
            text = BeautifulSoup(response.content, "html.parser").get_text(" ", strip=True)
            result["has_residential_labels"] = "주택" in text
            result["decimal_value_count"] = len(re.findall(r"\d+\.\d{4}", text))
            result["body_bytes"] = len(response.content)
    return result


def incheon():
    url = "https://icgas.co.kr:8443/recruit/dwr/exec/ICGAS.getChargecost.dwr"
    payload = {"callCount": "1", "c0-scriptName": "ICGAS", "c0-methodName": "getChargecost", "c0-id": "0", "c0-param0": "string:1", "c0-param1": "string:주택취사", "c0-param2": "string:2026-09-01", "c0-param3": "string:주택취사", "xml": "true"}
    response = requests.post(url, data=payload, timeout=25)
    match = re.search(r'var s5="(\d+\.\d+)"', response.text)
    return {"name": "incheon_public_tariff", "url": url, "status": response.status_code, "expected_rate_field_found": bool(match), "rate": match[1] if match else None}


def safe_call(fn):
    try:
        return fn()
    except Exception as error:
        return {"name": fn.__name__, "error_type": type(error).__name__}


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(safe_call, (gasapp, yesco, seoul, incheon)))
    payload = {"checked_at_utc": datetime.now(timezone.utc).isoformat(), "scope": "Public static frontend and public tariff queries only. No customer account or SMS calls.", "results": results}
    (ROOT / "non-sk-public-results.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for result in results:
        print(json.dumps(result, ensure_ascii=False))
