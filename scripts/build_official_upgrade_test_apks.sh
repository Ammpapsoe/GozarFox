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
: "${GH_TOKEN:?GitHub Actions token is required to fetch the pinned release assets}"
mkdir -p "$DEST"
umask 077
key=$(mktemp "${RUNNER_TEMP:?}/official-upgrade-test.XXXXXX")
trap 'rm -f "$key"' EXIT
printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$key"
export KEYSTORE_FILE="$key"

# Fetch fixed release asset IDs through GitHub's API. The stable fixture is
# public; the two deleted preview releases' byte-identical APKs are preserved
# in a CI-only draft release tagged ci-official-upgrade-fixtures (accessible to the
# workflow's repository token). The earlier draft was removed; its APKs were re-uploaded
# byte-for-byte from the retained build artifacts and re-verified against the pins below.
# Hashes and the official signing identity are verified independently below.
# curl strips Authorization on cross-host redirects; never use --location-trusted.
fetch_official() {
  local id="$1" expected="$2" name="$3"
  local path="$DEST/$name.apk" actual
  curl --fail --location --silent --show-error --retry 3 --retry-delay 2 \
    --header "Authorization: Bearer $GH_TOKEN" \
    --header 'Accept: application/octet-stream' \
    --header 'X-GitHub-Api-Version: 2022-11-28' \
    "https://api.github.com/repos/bardia049b-stack/Gozar/releases/assets/$id" \
    --output "$path"
  test -s "$path"
  actual=$(sha256sum "$path" | awk '{print $1}')
  test "$actual" = "$expected" || { echo "unexpected SHA-256 for $name" >&2; exit 1; }
}
fetch_official 713630711 d899da890c82761050e7ebac37be81aedf24aa0d51d45b3452f908f3e3a32a88 official-stable
fetch_official 730844479 7c30356ca885534ee250ec13d0cffe64142049ae6aa7f47ce8e8a90349cda41a official-previous
fetch_official 730844502 01971a8391d1d5f074861996afdbb0e58434ec4b74c8de8f9ad27670964cf797 official-corrected
bash ./gradlew :app:assembleRelease --no-daemon --stacktrace
shopt -s nullglob
candidates=(app/build/outputs/apk/release/*universal*.apk)
if [ "${#candidates[@]}" -ne 1 ]; then
  echo "expected one universal release candidate, got ${#candidates[@]}" >&2
  exit 1
fi
cp "${candidates[0]}" "$DEST/official-candidate.apk"
expected=800d24fa5cc38d2a63c216051d4710b950b0b3c4f457096ccfc11794a2b3bfd3
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
