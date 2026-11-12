#!/usr/bin/env bash
# Upgrade the actual published Android APK to a freshly built, officially
# signed release candidate on an emulator. adb installation bypasses the UI;
# the separate debug fixture scenario exercises system-installer consent.
set -euo pipefail
DEST="${GOZAR_APK_DIR:?set GOZAR_APK_DIR}"
PKG=com.gozar
source_apk="${GOZAR_PREVIOUS_APK:?set GOZAR_PREVIOUS_APK}"
source_version="${GOZAR_PREVIOUS_VERSION:?set GOZAR_PREVIOUS_VERSION}"
label="${GOZAR_EVIDENCE_LABEL:?set GOZAR_EVIDENCE_LABEL}"
case "$source_apk:$source_version:$label" in
  official-stable:1.10.4:stable|official-previous:1.11.1:preview|official-corrected:1.11.2:corrected) ;;
  *) echo 'unexpected official upgrade fixture' >&2; exit 1 ;;
esac
prefix="$DEST/official-$label"
trap 'adb logcat -d -s AndroidRuntime:E | tail -100 || true' ERR
for apk in "$source_apk" official-candidate; do test -s "$DEST/$apk.apk"; done
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$DEST/$source_apk.apk"
adb shell dumpsys package "$PKG" | tr -d '\r' > "$prefix-previous-package.txt"
grep -F "versionName=$source_version" "$prefix-previous-package.txt" >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" | tee "$prefix-previous-launch.txt"
grep -F 'Status: ok' "$prefix-previous-launch.txt" >/dev/null

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
adb shell dumpsys package "$PKG" | tr -d '\r' > "$prefix-candidate-package.txt"
candidate_version=$(grep -m1 'versionName' app/build.gradle | sed -E "s/.*'([^']+)'.*/\1/")
grep -F "versionName=$candidate_version" "$prefix-candidate-package.txt" >/dev/null
python3 - "$prefix-previous-package.txt" "$prefix-candidate-package.txt" <<'PY'
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
adb shell am start -W -n "$PKG/.MainActivity" | tee "$prefix-candidate-launch.txt"
grep -F 'Status: ok' "$prefix-candidate-launch.txt" >/dev/null
adb shell pidof "$PKG" >/dev/null
echo "Officially signed candidate installed over $source_version and launched on the emulator."
# The following fixture scenario uses the same application ID but a debug key.
adb uninstall "$PKG" >/dev/null
