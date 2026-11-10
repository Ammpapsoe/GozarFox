#!/usr/bin/env bash
set -euo pipefail
adb wait-for-device
adb root || true
adb wait-for-device
rooted=false
for attempt in $(seq 1 30); do
  identity=$(adb shell id 2>/dev/null | tr -d '\r' || true)
  if printf '%s' "$identity" | grep -F 'uid=0(root)' >/dev/null; then rooted=true; break; fi
  sleep 1
done
if [ "$rooted" != true ]; then
  printf '%s\n' '::error::Emulator adbd did not enter root mode'
  exit 1
fi
printf '%s\n' '::notice::STATE=EMULATOR_ADB_ROOT_OK'
if adb shell setenforce 0; then
  enforcement=$(adb shell getenforce | tr -d '\r')
  printf '::notice::STATE=EMULATOR_SELINUX_%s\n' "$enforcement"
else
  printf '%s\n' '::warning::Root is active but this emulator image refused permissive SELinux; continuing native validation'
fi
app_apk=app/build/outputs/apk/debug/app-x86_64-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-x86_64-debug-androidTest.apk
test -s "$app_apk"
test -s "$test_apk"
printf '%s\n' '::notice::STATE=NATIVE_APK_LAYOUT_OK'
adb install -r "$app_apk"
printf '%s\n' '::notice::STATE=NATIVE_APP_APK_INSTALLED'
adb install -r "$test_apk"
adb shell pm list instrumentation | grep -F 'instrumentation:com.gozar.test/androidx.test.runner.AndroidJUnitRunner' >/dev/null
printf '%s\n' '::notice::STATE=NATIVE_APKS_INSTALLED'
result="${RUNNER_TEMP:-/tmp}/embedded-tor-instrumentation.txt"
set +e
adb shell am instrument -w -r -e class com.gozar.FoxTorInstrumentedTest com.gozar.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
status=${PIPESTATUS[0]}
set -e
if [ "$status" -eq 0 ] && grep -E 'OK \(1 test\)' "$result" >/dev/null; then
  exit 0
fi
python3 - "$result" <<'PY'
import re
import sys
pattern = re.compile(r'(shortmsg|AssertionError|ComparisonFailure|FoxError|Exception|expected|actual|Process crashed|INSTRUMENTATION_CODE|FAILURE|Error:)', re.I)
count = 0
with open(sys.argv[1], errors='replace') as source:
    for raw in source:
        line = raw.strip()
        if not line or not pattern.search(line):
            continue
        line = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error::' + line[:900])
        count += 1
        if count >= 60:
            break
if count == 0:
    print('::error::Embedded Tor instrumentation did not report its required single passing test')
PY
adb logcat -d -v brief -t 1200 > "${RUNNER_TEMP:-/tmp}/embedded-tor-logcat-raw.txt" || true
python3 - "${RUNNER_TEMP:-/tmp}/embedded-tor-logcat-raw.txt" "${RUNNER_TEMP:-/tmp}/embedded-tor-native-failure.txt" <<'PY'
import re
import sys
pattern = re.compile(r'(AndroidRuntime|FATAL|Fatal signal|UnsatisfiedLinkError|dlopen|SIGSEGV|SIGABRT|TorService|org\.torproject|IPtProxy|GoLog|Go log|gozar)', re.I)
with open(sys.argv[1], errors='replace') as source:
    lines = [line.strip() for line in source if pattern.search(line)]
lines = lines[-120:]
with open(sys.argv[2], 'w') as output:
    for line in lines:
        output.write(line + '\n')
        clean = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error::' + clean[:900])
PY
exit 1
