"""Run only with explicit account-owner authorization. Read-only login and bill queries.

Credentials use hidden input and stdin transport, never command-line arguments.
The temporary input is in the debuggable test app's private directory and is
deleted by the test before networking. A finally block also removes it.
"""
import getpass
import json
from pathlib import Path
import shutil
import subprocess
import os

package = "dev.mahlernim.gasselfmeter"
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "AppData/Local/Android/Sdk"))
adb = shutil.which("adb") or str(sdk / "platform-tools/adb.exe")
username = input("Authorized test username: ")
password = getpass.getpass("Authorized test password: ")
payload = json.dumps({"username": username, "password": password}).encode()
try:
    subprocess.run([adb, "shell", "run-as", package, "mkdir", "-p", "files"], check=True)
    subprocess.run([adb, "shell", "run-as", package, "sh", "-c", "'cat > files/live-probe-input.json'"], input=payload, check=True)
    payload = b""
    password = ""
    result = subprocess.run([adb, "shell", "am", "instrument", "-w", "-e", "class", package + ".LiveBusanTest", package + ".test/androidx.test.runner.AndroidJUnitRunner"], check=True, capture_output=True, text=True)
    print(result.stdout)
    if "OK (1 test)" not in result.stdout:
        raise SystemExit("Live Android probe did not pass. Inspect the test output.")
finally:
    subprocess.run([adb, "shell", "run-as", package, "rm", "-f", "files/live-probe-input.json"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
