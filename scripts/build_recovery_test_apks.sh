#!/usr/bin/env bash
# CI-only: build three debug-signed APKs from the same source with different
# versions. These are test fixtures, not release or known-good recovery builds.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
DEST="${GOZAR_APK_DIR:?set GOZAR_APK_DIR}"
mkdir -p "$DEST"
original=$(mktemp)
cp app/build.gradle "$original"
trap 'cp "$original" app/build.gradle; rm -f "$original"' EXIT

build_fixture() {
  local name="$1" version="$2" code="$3"
  python3 - "$original" "$version" "$code" <<'PY'
import pathlib, re, sys
original, version, code = sys.argv[1:]
text = pathlib.Path(original).read_text(encoding="utf-8")
text, n1 = re.subn(r"(?m)^(\s*versionCode\s+)\d+\s*$", lambda m: m.group(1) + code, text, count=1)
text, n2 = re.subn(r"(?m)^(\s*versionName\s+)'[^']+'\s*$", lambda m: m.group(1) + "'" + version + "'", text, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit("could not safely set debug fixture versions")
pathlib.Path("app/build.gradle").write_text(text, encoding="utf-8")
PY
  bash ./gradlew :app:assembleDebug --no-daemon --stacktrace
  shopt -s nullglob
  local candidates=(app/build/outputs/apk/debug/*universal*.apk)
  if [ "${#candidates[@]}" -ne 1 ]; then
    echo "expected one universal debug APK, got ${#candidates[@]}" >&2
    exit 1
  fi
  cp "${candidates[0]}" "$DEST/$name.apk"
}

build_fixture old 1.11.0 36
build_fixture affected 1.11.1 37
build_fixture forward 1.11.2 38
printf '%s\n' 'Debug upgrade fixtures built; no production artifacts were created.'
