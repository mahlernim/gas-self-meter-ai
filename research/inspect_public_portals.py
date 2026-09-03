"""Inspect public pages only. No credentials, login attempts or customer queries."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import re
import sys

import requests
from bs4 import BeautifulSoup

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parent
PROVIDERS = {
    "busan": "부산도시가스",
    "koone": "코원에너지서비스",
    "cheongju": "충청에너지서비스",
    "gumi": "영남에너지서비스 구미",
    "pohang": "영남에너지서비스 포항",
    "jeonnam": "전남도시가스",
    "gangwon": "강원도시가스",
    "jeonbuk": "전북에너지서비스",
}


def inspect(item):
    slug, name = item
    result = {"slug": slug, "name": name, "pages": []}
    for host in ("www.skens.com", "ebpp.skens.com"):
        url = f"https://{host}/{slug}/login/login.do"
        page = {"url": url}
        try:
            response = requests.get(url, timeout=20)
            response.encoding = "utf-8"
            html = response.text
            soup = BeautifulSoup(html, "html.parser")
            login = re.search(r"function checkLoginForm\(btn\).*?(?=function showAlert)", html, re.S)
            normalized = re.sub(r"\s+", " ", login[0]).strip() if login else None
            links = []
            for a in soup.select("a[href]"):
                label = a.get_text(" ", strip=True)
                if "찾기" in label or "회원가입" == label:
                    links.append({"label": label, "href": a.get("href"), "onclick": a.get("onclick")})
            page.update(
                status=response.status_code,
                final_url=response.url,
                title=soup.title.get_text(strip=True) if soup.title else None,
                credential_fields=sorted({n.get("name") for n in soup.select("input[name]") if n.get("name") in ("id", "pw", "returnURL")}),
                login_endpoint="loginProcess.do" if 'url:"loginProcess.do"' in html else None,
                success_code_S='data.errCd =="S"' in html,
                login_function_sha256=hashlib.sha256(normalized.encode()).hexdigest() if normalized else None,
                shared_scripts=[n.get("src") for n in soup.select("script[src]") if n.get("src") in ("/js/common.js", "/js/ebpp_common.js")],
                recovery_links=links,
                credentialed_flow_verified=False,
            )
        except requests.RequestException as error:
            page["error"] = type(error).__name__
        result["pages"].append(page)
    return result


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(inspect, PROVIDERS.items()))
    payload = {"checked_at_utc": datetime.now(timezone.utc).isoformat(), "scope": "Public GET requests only. Matching login source is not proof of shared authenticated billing APIs.", "providers": rows}
    (ROOT / "provider-public-audit.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for row in rows:
        print(row["slug"], [(p.get("status", p.get("error")), (p.get("login_function_sha256") or "")[:12], p.get("credential_fields")) for p in row["pages"]])
