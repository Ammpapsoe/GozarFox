#!/usr/bin/env bash
# Upgrade the actual published Android APK to a freshly built, officially
# signed release candidate on an emulator. adb installation bypasses the UI;
# the separate debug fixture scenario exercises system-installer consent.
set -euo pipefail
DEST="${GOZAR_APK_DIR:?set GOZAR_APK_DIR}"
PKG=com.gozar
trap 'adb logcat -d -s AndroidRuntime:E | tail -100 || true' ERR
for apk in official-previous official-candidate; do test -s "$DEST/$apk.apk"; done
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$DEST/official-previous.apk"
adb shell dumpsys package "$PKG" | tr -d '\r' > "$DEST/official-previous-package.txt"
grep -F 'versionName=1.10.4' "$DEST/official-previous-package.txt" >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" | tee "$DEST/official-previous-launch.txt"
grep -F 'Status: ok' "$DEST/official-previous-launch.txt" >/dev/null

# Google APIs emulator images may expose a root ADB daemon. If available,
# plant a sentinel as root without changing the release APK's debug flag.
# Otherwise report the limitation rather than claiming data-persistence proof.
adb root >/dev/null 2>&1 || true
adb wait-for-device
rooted=$(adb shell id -u | tr -d '\r')
if [ "$rooted" = 0 ]; then
  printf official-ci-persistence > "$DEST/official-sentinel.txt"
  adb push "$DEST/official-sentinel.txt" /data/local/tmp/gozar-official-sentinel >/dev/null
  path="/data/user/0/$PKG/files"
  adb shell mkdir -p "$path"
  owner=$(adb shell stat -c '%u:%g' "$path" | tr -d '\r')
  adb shell cp /data/local/tmp/gozar-official-sentinel "$path/recovery-ci-official"
  adb shell chown "$owner" "$path/recovery-ci-official"
  adb shell chmod 600 "$path/recovery-ci-official"
else
  echo '::warning::ADB root unavailable: official APK data persistence is not tested'
fi

adb install -r "$DEST/official-candidate.apk"
adb shell dumpsys package "$PKG" | tr -d '\r' > "$DEST/official-candidate-package.txt"
candidate_version=$(grep -m1 'versionName' app/build.gradle | sed -E "s/.*'([^']+)'.*/\1/")
grep -F "versionName=$candidate_version" "$DEST/official-candidate-package.txt" >/dev/null
python3 - "$DEST/official-previous-package.txt" "$DEST/official-candidate-package.txt" <<'PY'
import re, sys

def identity(path):
    text = open(path, encoding="utf-8").read()
    uid = re.search(r"\buserId=(\d+)", text)
    first = re.search(r"\bfirstInstallTime=([^\r\n]+)", text)
    if not uid or not first:
        raise SystemExit("missing package identity evidence")
    return uid.group(1), first.group(1).strip()

old, new = map(identity, sys.argv[1:])
if old != new:
    raise SystemExit(f"package identity changed across replace-install: {old!r} -> {new!r}")
print("Same Android package identity and first-install time:", new)
PY
if [ "$rooted" = 0 ]; then
  value=$(adb shell cat "/data/user/0/$PKG/files/recovery-ci-official" | tr -d '\r')
  test "$value" = official-ci-persistence
  echo 'Official APK upgrade preserved the app-private sentinel.'
fi
adb shell am start -W -n "$PKG/.MainActivity" | tee "$DEST/official-candidate-launch.txt"
grep -F 'Status: ok' "$DEST/official-candidate-launch.txt" >/dev/null
adb shell pidof "$PKG" >/dev/null
echo 'Officially signed release candidate installed over the published APK and launched on the emulator.'
# The following fixture scenario uses the same application ID but a debug key.
adb uninstall "$PKG" >/dev/null
