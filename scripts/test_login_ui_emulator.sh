#!/usr/bin/env bash
set -euo pipefail

# Screen evidence for the WebView sign-in flow, without credentials.
#
# This does not test Mozilla and claims nothing about it. A CI runner's address
# is normally refused at Mozilla's edge, so what this can prove is the part
# that is ours: the screens render, the WebView is configured without crashing,
# and the platform reports support for the two WebMessage features the
# credential path depends on. Screenshots plus that capability report are the
# artifact; the navigation trail is collected from logcat afterwards.

# Hard self-watchdog: this graphical evidence run must never hang a
# pipeline. If the whole script has not finished in 15 minutes, it is
# killed and treated as non-essential. Physical-device testing remains
# the real validation of WebView and sign-in.
if [ -z "${GOZAR_UI_WATCHDOG:-}" ]; then
  export GOZAR_UI_WATCHDOG=1
  exec timeout --kill-after=30 900 bash "$0" "$@"
fi

PKG=com.gozar
TEST_PKG=com.gozar.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
CLASS=com.gozar.LoginScreensInstrumentedTest

app_apk=app/build/outputs/apk/debug/app-x86_64-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

out="${GOZAR_UI_DIR:-${RUNNER_TEMP:-/tmp}/login-ui-evidence}"
mkdir -p "$out"

test -s "$app_apk"
test -s "$test_apk"

adb wait-for-device
for _ in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then break; fi
  sleep 5
done

# Record which WebView the image really ships before depending on its features.
adb shell dumpsys webviewupdate > "$out/webviewupdate.txt" 2>&1 || true
grep -E "Current WebView package|versionCode|Fallback" "$out/webviewupdate.txt" | head -6 || true

adb install -r -t "$app_apk"
adb install -r -t "$test_apk"

adb logcat -c > /dev/null 2>&1 || true
status=0
adb shell am instrument -w -r -e class "$CLASS" "$TEST_PKG/$RUNNER" > "$out/instrument.log" 2>&1 || status=$?

# The emulator image restarts Play services once shortly after boot, and
# Android kills every process holding a binder into the dying one. If that
# external cascade - and only that - is what ended the run, give the screens
# one more chance; any other crash stays a failure.
if ! grep -qE 'OK \(1 test\)' "$out/instrument.log"; then
  cp "$out/instrument.log" "$out/instrument-first-attempt.log"
  if adb logcat -d -v time 2>/dev/null | grep -q "depends on provider .* in dying proc"; then
    echo '::warning::An unrelated system process died and Android cascaded the kill into the app; retrying the instrumentation once'
    sleep 20
    adb logcat -c > /dev/null 2>&1 || true
    status=0
    adb shell am instrument -w -r -e class "$CLASS" "$TEST_PKG/$RUNNER" > "$out/instrument.log" 2>&1 || status=$?
  fi
fi

# MozillaDiagnostics.record logs every navigation fact under this tag; the
# instrumented test prints its own trail to stdout, which lands in System.out.
adb logcat -d -s MozillaWebView:D System.out:I > "$out/diagnostics.log" 2>&1 || true

# Everything, and the crash buffer on its own. A filtered logcat is useless
# exactly when it matters most: if the process died, the reason is in
# AndroidRuntime and in the crash buffer, not in our own tags.
adb logcat -d -v time > "$out/full-logcat.txt" 2>&1 || true
adb logcat -d -b crash -v time > "$out/crash-logcat.txt" 2>&1 || true
adb shell dumpsys activity activities 2>/dev/null | grep -iA3 "gozar" > "$out/activity-stack.txt" || true

adb shell ls -l "/sdcard/Android/data/$PKG/files/screens" > "$out/screens-listing.txt" 2>&1 || true
adb pull "/sdcard/Android/data/$PKG/files/screens/." "$out/screens" > "$out/pull.log" 2>&1 || true

# What was actually on screen at the end, independent of our own screenshots.
adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 || true
adb pull /sdcard/window.xml "$out/window.xml" > /dev/null 2>&1 || true

grep -E '^GOZAR_UI|INSTRUMENTATION_(STATUS|CODE|RESULT)|OK \(|FAILURES|shortMsg|Error' \
  "$out/instrument.log" | tail -60 || true

if grep -qE "FATAL EXCEPTION|Process crashed" "$out/instrument.log" "$out/crash-logcat.txt" 2>/dev/null; then
  echo "----- crash buffer -----"
  cat "$out/crash-logcat.txt" 2>/dev/null | tail -80 || true
  echo "----- fatal exceptions from the main log -----"
  grep -A 40 "FATAL EXCEPTION" "$out/full-logcat.txt" 2>/dev/null | head -80 || true
fi

if [ "$status" -ne 0 ] || ! grep -qE 'OK \(1 test\)' "$out/instrument.log"; then
  echo '::error::Login UI instrumentation did not report a passing run'
  tail -40 "$out/instrument.log" || true
  exit 1
fi

found=$(find "$out/screens" -name '*.png' 2>/dev/null | wc -l)
if [ "$found" -eq 0 ]; then
  echo '::error::The run passed but produced no screenshots'
  exit 1
fi
echo "::notice::Login UI evidence: $found screenshots in $out"
