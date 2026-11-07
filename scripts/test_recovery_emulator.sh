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

# Exercise the actual verifier and installer using an ephemeral test key and a
# local transport in an isolated debug-only activity. Production networking and
# the production pinned key are deliberately NOT replaced or tested here.
python3 scripts/create_recovery_fixture_specs.py "$DEST"
for kind in old forward; do
  adb push "$DEST/$kind.apk" "/data/local/tmp/gozar-fixture-$kind.apk" >/dev/null
  adb shell run-as "$PKG" cp "/data/local/tmp/gozar-fixture-$kind.apk" "files/recovery-fixture-$kind.apk"
done
for kind in invalid-signature tampered valid; do
  adb push "$DEST/fixture-$kind.json" "/data/local/tmp/fixture-$kind.json" >/dev/null
  adb shell run-as "$PKG" cp "/data/local/tmp/fixture-$kind.json" "files/fixture-$kind.json"
done

wait_for_fixture() {
  python3 - "$PKG" "$1" <<'PY'
import subprocess, sys, time
pkg, expected = sys.argv[1:]
deadline = time.monotonic() + 30
last = ""
while time.monotonic() < deadline:
    result = subprocess.run(["adb", "shell", "run-as", pkg, "cat", "files/recovery-fixture-result"],
                            capture_output=True, text=True)
    last = result.stdout.strip()
    if last:
        if last != expected:
            raise SystemExit(f"fixture returned {last!r}, expected {expected!r}")
        print("Fixture:", last)
        break
    time.sleep(0.3)
else:
    raise SystemExit(f"fixture timed out waiting for {expected!r}; last={last!r}")
PY
}

run_fixture() {
  local mode="$1" expected="$2"
  # ActivityManager may reuse an existing task while ignoring changed extras.
  # Start each fixture from a stopped process so the new input is consumed.
  adb shell am force-stop "$PKG"
  adb shell run-as "$PKG" rm -f files/recovery-fixture-result
  if [ "$mode" = probe ]; then
    adb shell am start -W -n "$PKG/.RecoveryFixtureActivity" --es mode probe >/dev/null
  else
    adb shell am start -W -n "$PKG/.RecoveryFixtureActivity" --es mode update --es spec "fixture-$mode.json" >/dev/null
  fi
  wait_for_fixture "$expected"
}

# The middle APK deliberately changes only this synthetic behavior probe.
run_fixture probe probe:broken
run_fixture invalid-signature manifest-rejected
run_fixture tampered download-rejected
adb shell dumpsys package "$PKG" | grep -F 'versionName=1.11.1' >/dev/null

# Grant the package's install-source app-op, but still require an actual click
# on the system package installer's confirmation UI.
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
run_fixture valid installer-started
python3 - "$PKG" "$DEST/installer-window.xml" <<'PY'
import re, subprocess, sys, time, xml.etree.ElementTree as ET
pkg, evidence = sys.argv[1:]

def adb(*args):
    return subprocess.run(["adb", *args], text=True, capture_output=True, check=True).stdout

deadline = time.monotonic() + 45
while time.monotonic() < deadline:
    try:
        adb("shell", "uiautomator", "dump", "/sdcard/gozar-installer.xml")
        xml = adb("shell", "cat", "/sdcard/gozar-installer.xml")
        open(evidence, "w", encoding="utf-8").write(xml)
        root = ET.fromstring(xml)
        for node in root.iter("node"):
            text = node.attrib.get("text", "").strip().lower()
            owner = node.attrib.get("package", "")
            if text not in ("install", "update") or "packageinstaller" not in owner:
                continue
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
            if not bounds:
                continue
            x1, y1, x2, y2 = map(int, bounds.groups())
            if x2 <= x1 or y2 <= y1:
                continue
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            break
        else:
            time.sleep(0.5)
            continue
        break
    except (subprocess.CalledProcessError, ET.ParseError):
        time.sleep(0.5)
else:
    raise SystemExit("system installer confirmation was not shown")

deadline = time.monotonic() + 45
while time.monotonic() < deadline:
    info = adb("shell", "dumpsys", "package", pkg)
    if "versionName=1.11.2" in info:
        print("System installer upgraded the app to 1.11.2")
        break
    time.sleep(0.5)
else:
    raise SystemExit("system installer did not complete the forward upgrade")
PY
sentinel=$(adb shell run-as "$PKG" cat files/recovery-ci-sentinel | tr -d '\r')
test "$sentinel" = recovery-ci-sentinel
run_fixture probe probe:healthy
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
adb shell run-as "$PKG" cat files/journal/update.json | tr -d '\r' > "$DEST/forward-journal.json"
python3 - "$DEST/forward-journal.json" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["installed"] == "1.11.2", state
assert state["previous"] == "1.11.1", state
PY
printf '%s\n' 'Android emulator: bad signature and tampered APK rejected; user-confirmed fixture update restored synthetic behavior and kept data.'
