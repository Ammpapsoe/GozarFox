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
  --output "$DEST/official-stable.apk"
curl --fail --location --silent --show-error --retry 3 \
  https://github.com/bardia049b-stack/Gozar/releases/download/v1.11.1/Gozar_1.11.1_universal.apk \
  --output "$DEST/official-previous.apk"
curl --fail --location --silent --show-error --retry 3 \
  https://github.com/bardia049b-stack/Gozar/releases/download/v1.11.2/Gozar_1.11.2_universal.apk \
  --output "$DEST/official-corrected.apk"
test -s "$DEST/official-stable.apk"
test -s "$DEST/official-previous.apk"
test -s "$DEST/official-corrected.apk"
bash ./gradlew :app:assembleRelease --no-daemon --stacktrace
shopt -s nullglob
candidates=(app/build/outputs/apk/release/*universal*.apk)
if [ "${#candidates[@]}" -ne 1 ]; then
  echo "expected one universal release candidate, got ${#candidates[@]}" >&2
  exit 1
fi
cp "${candidates[0]}" "$DEST/official-candidate.apk"
expected=bc9d3368f2671175f81be047a1ff72bca0cfc58e8b1b8f9f87e24694ba45f5b1
for apk in "$DEST/official-stable.apk" "$DEST/official-previous.apk" "$DEST/official-corrected.apk" "$DEST/official-candidate.apk"; do
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
for name in stable previous corrected candidate; do
  "$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$DEST/official-$name.apk" > "$DEST/official-$name-badging.txt"
done
candidate_version=$(grep -m1 'versionName' app/build.gradle | sed -E "s/.*'([^']+)'.*/\1/")
python3 - "$DEST/official-stable-badging.txt" "$DEST/official-previous-badging.txt" "$DEST/official-corrected-badging.txt" "$DEST/official-candidate-badging.txt" "$candidate_version" <<'PY'
import re, sys

def package(path):
    line = open(path, encoding="utf-8").readline().strip()
    fields = dict(re.findall(r"([A-Za-z0-9]+)='([^']*)'", line))
    return fields.get("name"), fields.get("versionName"), int(fields.get("versionCode", "0"))

stable, previous, corrected, new = map(package, sys.argv[1:5])
assert stable[:2] == ("com.gozar", "1.10.4"), stable
assert previous[:2] == ("com.gozar", "1.11.1"), previous
assert corrected[:2] == ("com.gozar", "1.11.2"), corrected
assert new[:2] == ("com.gozar", sys.argv[5]), new
assert new[2] > corrected[2] > previous[2] > stable[2] > 0, (stable, previous, corrected, new)
print("Official same-signer upgrade candidates:", stable, previous, corrected, "->", new)
PY
