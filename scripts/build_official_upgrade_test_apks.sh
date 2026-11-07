#!/usr/bin/env bash
# CI only: compare a real published APK to the signed release candidate without
# publishing either. Never expose the official keystore or use debug signing.
set -euo pipefail
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'This test must run on GitHub Actions' >&2; exit 1; }
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
DEST="${GOZAR_APK_DIR:?set GOZAR_APK_DIR}"
: "${ANDROID_HOME:?}"
: "${KEYSTORE_BASE64:?}"
: "${KEYSTORE_PASSWORD:?}"
: "${KEY_ALIAS:?}"
: "${KEY_PASSWORD:?}"
mkdir -p "$DEST"
umask 077
key=$(mktemp "${RUNNER_TEMP:?}/official-upgrade-test.XXXXXX")
trap 'rm -f "$key"' EXIT
printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$key"
export KEYSTORE_FILE="$key"

curl --fail --location --silent --show-error --retry 3 \
  https://github.com/bardia049b-stack/Gozar/releases/download/v1.10.4-desktop/Gozar_1.10.4_universal.apk \
  --output "$DEST/official-previous.apk"
test -s "$DEST/official-previous.apk"
bash ./gradlew :app:assembleRelease --no-daemon --stacktrace
shopt -s nullglob
candidates=(app/build/outputs/apk/release/*universal*.apk)
if [ "${#candidates[@]}" -ne 1 ]; then
  echo "expected one universal release candidate, got ${#candidates[@]}" >&2
  exit 1
fi
cp "${candidates[0]}" "$DEST/official-candidate.apk"
expected=bc9d3368f2671175f81be047a1ff72bca0cfc58e8b1b8f9f87e24694ba45f5b1
for apk in "$DEST/official-previous.apk" "$DEST/official-candidate.apk"; do
  info=$("$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$apk")
  printf '%s\n' "$info" | grep -Fx 'Number of signers: 1' >/dev/null
  actual=$(printf '%s\n' "$info" | awk '/Signer #1 certificate SHA-256 digest:/ {print $NF}')
  test "$actual" = "$expected" || { echo 'unexpected official APK signer' >&2; exit 1; }
  "$ANDROID_HOME/build-tools/36.0.0/aapt" dump xmltree "$apk" AndroidManifest.xml > "$DEST/official-manifest.txt"
  if grep -F 'RecoveryFixtureActivity' "$DEST/official-manifest.txt" >/dev/null; then
    echo 'test activity appeared in an official APK' >&2
    exit 1
  fi
done
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$DEST/official-previous.apk" > "$DEST/official-previous-badging.txt"
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$DEST/official-candidate.apk" > "$DEST/official-candidate-badging.txt"
candidate_version=$(grep -m1 'versionName' app/build.gradle | sed -E "s/.*'([^']+)'.*/\1/")
python3 - "$DEST/official-previous-badging.txt" "$DEST/official-candidate-badging.txt" "$candidate_version" <<'PY'
import re, sys

def package(path):
    line = open(path, encoding="utf-8").readline().strip()
    fields = dict(re.findall(r"([A-Za-z0-9]+)='([^']*)'", line))
    return fields.get("name"), fields.get("versionName"), int(fields.get("versionCode", "0"))

old, new = map(package, sys.argv[1:3])
assert old[:2] == ("com.gozar", "1.10.4"), old
assert new[:2] == ("com.gozar", sys.argv[3]), new
assert new[2] > old[2] > 0, (old, new)
print("Official same-signer upgrade:", old, "->", new)
PY
