#!/usr/bin/env bash
# CI-only: build three debug-signed APKs with a deliberately broken test probe
# in the middle build. These are fixtures, not release or known-good APKs.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
DEST="${GOZAR_APK_DIR:?set GOZAR_APK_DIR}"
mkdir -p "$DEST"
original=$(mktemp)
cp app/build.gradle "$original"
# The exported fixture activity exists only in these isolated debug builds.
# Never overwrite an existing debug source set.
if [ -e app/src/debug ]; then
  echo 'refusing to overwrite an existing debug source set' >&2
  rm -f "$original"
  exit 1
fi
trap 'cp "$original" app/build.gradle; rm -f "$original"; rm -rf app/src/debug' EXIT
mkdir -p app/src/debug/java/com/gozar
cp scripts/recovery-fixture/AndroidManifest.xml app/src/debug/AndroidManifest.xml

build_fixture() {
  local name="$1" version="$2" code="$3" probe="$4"
  python3 - "$probe" <<'PY'
import pathlib, sys
source = pathlib.Path("scripts/recovery-fixture/RecoveryFixtureActivity.kt").read_text(encoding="utf-8")
if source.count("@RECOVERY_FIXTURE_PROBE@") != 1 or sys.argv[1] not in ("healthy", "broken"):
    raise SystemExit("invalid recovery behavior fixture")
pathlib.Path("app/src/debug/java/com/gozar/RecoveryFixtureActivity.kt").write_text(
    source.replace("@RECOVERY_FIXTURE_PROBE@", sys.argv[1]), encoding="utf-8")
PY
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

build_fixture old 1.11.0 36 healthy
build_fixture affected 1.11.1 37 broken
build_fixture forward 1.11.2 38 healthy
printf '%s\n' 'Debug upgrade fixtures built; no production artifacts were created.'
