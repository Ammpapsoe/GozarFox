#!/usr/bin/env bash
# Exercise Android package-manager behavior with debug fixtures. This checks
# forward installation and persistence, not production recovery correctness.
set -euo pipefail
DEST="${GOZAR_APK_DIR:?set GOZAR_APK_DIR}"
PKG=com.gozar
trap 'adb logcat -d -s AndroidRuntime:E | tail -100 || true' ERR
for apk in old affected forward; do test -s "$DEST/$apk.apk"; done

adb install -r "$DEST/old.apk"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
printf recovery-ci-sentinel > "$DEST/sentinel.txt"
adb push "$DEST/sentinel.txt" /data/local/tmp/gozar-recovery-sentinel >/dev/null
adb shell run-as "$PKG" mkdir -p files
adb shell run-as "$PKG" cp /data/local/tmp/gozar-recovery-sentinel files/recovery-ci-sentinel

adb install -r "$DEST/affected.apk"
# A force-stop can race the process's durable journal write on an emulator.
# Wait for each real launch to appear in the journal before stopping again;
# never manufacture trial state or infer a crash from an unconfirmed launch.
python3 - "$PKG" "$DEST/affected-journal.json" <<'PY'
import json, subprocess, sys, time
pkg, evidence = sys.argv[1:]

def adb(*args):
    return subprocess.run(["adb", *args], text=True, capture_output=True, check=True).stdout

def state():
    try:
        return json.loads(adb("shell", "run-as", pkg, "cat", "files/journal/update.json"))
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        return {}

for launch in range(1, 9):
    adb("shell", "am", "force-stop", pkg)
    before = state()
    output = adb("shell", "am", "start", "-W", "-n", pkg + "/.MainActivity")
    if "Status: ok" not in output:
        raise SystemExit("app did not start: " + output)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        current = state()
        if current.get("installed") == "1.11.1" and (
            before.get("installed") != "1.11.1" or current.get("attempts", 0) > before.get("attempts", 0)
        ):
            break
        time.sleep(0.25)
    else:
        raise SystemExit(f"launch {launch} did not update journal; before={before}, after={current}")
    print(f"Observed affected launch {launch}: attempts={current.get('attempts')}")
    if current.get("attempts", 0) >= 3:
        break
else:
    raise SystemExit("unconfirmed-launch threshold was not reached")

with open(evidence, "w", encoding="utf-8") as handle:
    json.dump(current, handle)
assert current["installed"] == "1.11.1", current
assert current["previous"] == "1.11.0", current
assert current["attempts"] >= 3 and not current["healthy"], current
PY
adb shell dumpsys notification --noredact | tr -d '\r' > "$DEST/notifications.txt"
if ! grep -E '(pkg=com\.gozar.*id=4098|id=4098.*pkg=com\.gozar)' "$DEST/notifications.txt" >/dev/null; then
  echo 'expected actionable recovery notification was not posted' >&2
  exit 1
fi

# Open the actual notice via the system notification shade and confirm that it
# leads to the non-exported recovery activity rather than an inert action.
adb shell cmd statusbar expand-notifications
adb shell uiautomator dump /sdcard/gozar-recovery.xml >/dev/null
adb shell cat /sdcard/gozar-recovery.xml | tr -d '\r' > "$DEST/window.xml"
coords=$(python3 - "$DEST/window.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if "Update health not yet confirmed" not in node.attrib.get("text", ""):
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if match:
        x1, y1, x2, y2 = map(int, match.groups())
        if x2 > x1 and y2 > y1:
            print((x1+x2)//2, (y1+y2)//2)
            break
else:
    raise SystemExit("recovery notice not visible in notification shade")
PY
)
# shellcheck disable=SC2086
adb shell input tap $coords
python3 - <<'PY'
import subprocess, time
end = time.monotonic() + 12
while time.monotonic() < end:
    out = subprocess.run(["adb", "shell", "dumpsys", "activity", "activities"], capture_output=True, text=True, check=True).stdout
    if any("UpdateRecoveryActivity" in line and ("ResumedActivity" in line or "topResumedActivity" in line) for line in out.splitlines()):
        break
    time.sleep(0.3)
else:
    raise SystemExit("recovery notification did not open the recovery activity")
PY

# A normal replace-install must not silently downgrade an installed version.
adb shell am force-stop "$PKG"
if adb install -r "$DEST/old.apk" > "$DEST/downgrade.log" 2>&1; then
  echo 'unexpectedly allowed ordinary downgrade' >&2
  exit 1
fi
grep -F 'INSTALL_FAILED_VERSION_DOWNGRADE' "$DEST/downgrade.log" >/dev/null

# A higher-version, same-key debug build can be installed while keeping data.
adb install -r "$DEST/forward.apk"
sentinel=$(adb shell run-as "$PKG" cat files/recovery-ci-sentinel | tr -d '\r')
test "$sentinel" = recovery-ci-sentinel
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
adb shell run-as "$PKG" cat files/journal/update.json | tr -d '\r' > "$DEST/forward-journal.json"
python3 - "$DEST/forward-journal.json" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["installed"] == "1.11.2", state
assert state["previous"] == "1.11.1", state
PY
printf '%s\n' 'Android emulator: downgrade rejected; forward install kept data and recovery notice opened.'
