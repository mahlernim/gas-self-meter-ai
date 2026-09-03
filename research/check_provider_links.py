"""Check only public links used by the provider picker."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json
import re
import requests

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/dev/mahlernim/gasselfmeter/Providers.kt"
urls = re.findall(r'https://[^"\s]+', source.read_text(encoding="utf-8"))

def check(url):
    try:
        response = requests.get(url, timeout=15)
        return {"url": url, "status": response.status_code, "final": response.url}
    except requests.RequestException as exc:
        return {"url": url, "error": type(exc).__name__}

if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(check, urls))
    (root / "research/provider-links.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for result in results:
        print(json.dumps(result))
