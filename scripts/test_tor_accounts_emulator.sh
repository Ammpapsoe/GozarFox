#!/usr/bin/env bash
set -euo pipefail
export GOZAR_LOGIN_EMAIL GOZAR_LOGIN_PASSWORD GOZAR_SIGNUP_EMAIL GOZAR_SIGNUP_PASSWORD GOZAR_SIGNUP_CODE
readarray -t GOZAR_LOGIN_VALUES < <(python3 -c 'import json,os; d=json.loads(os.environ["GOZAR_LIVE_INPUT"]); assert d["email"] and d["password"]; print(d["email"]); print(d["password"])')
readarray -t GOZAR_SIGNUP_VALUES < <(python3 -c 'import json,os; d=json.loads(os.environ["GOZAR_CI_SIGNUP_INPUT"]); assert d["email"] and d["password"]; print(d["email"]); print(d["password"]); print(d.get("code", ""))')
GOZAR_LOGIN_EMAIL="${GOZAR_LOGIN_VALUES[0]}"
GOZAR_LOGIN_PASSWORD="${GOZAR_LOGIN_VALUES[1]}"
GOZAR_SIGNUP_EMAIL="${GOZAR_SIGNUP_VALUES[0]}"
GOZAR_SIGNUP_PASSWORD="${GOZAR_SIGNUP_VALUES[1]}"
GOZAR_SIGNUP_CODE="${GOZAR_SIGNUP_VALUES[2]:-}"
for value in "$GOZAR_LOGIN_EMAIL" "$GOZAR_LOGIN_PASSWORD" "$GOZAR_SIGNUP_EMAIL" "$GOZAR_SIGNUP_PASSWORD" "$GOZAR_SIGNUP_CODE"; do
  if [ -n "$value" ]; then printf '::add-mask::%s\n' "$value"; fi
done
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
  printf '%s\n' '::warning::Root is active but this emulator image refused permissive SELinux; continuing account validation'
fi
app_apk=app/build/outputs/apk/debug/app-x86_64-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-x86_64-debug-androidTest.apk
test -s "$app_apk"
test -s "$test_apk"
printf '%s\n' '::notice::STATE=ACCOUNT_APK_LAYOUT_OK'
adb install -r "$app_apk"
printf '%s\n' '::notice::STATE=ACCOUNT_APP_APK_INSTALLED'
adb install -r "$test_apk"
adb shell pm list instrumentation | grep -F 'instrumentation:com.gozar.test/androidx.test.runner.AndroidJUnitRunner' >/dev/null
printf '%s\n' '::notice::STATE=ACCOUNT_APKS_INSTALLED'
result="${RUNNER_TEMP:-/tmp}/tor-account-instrumentation.txt"
umask 077
trap 'rm -f "$result" "${RUNNER_TEMP:-/tmp}/tor-account-logcat-raw.txt"' EXIT
sanitize() {
  python3 -c 'import os,re,sys
text=sys.stdin.read()
for key in ("GOZAR_LOGIN_EMAIL", "GOZAR_LOGIN_PASSWORD", "GOZAR_SIGNUP_EMAIL", "GOZAR_SIGNUP_PASSWORD", "GOZAR_SIGNUP_CODE"):
 value=os.environ.get(key, "")
 if value: text=text.replace(value, "[redacted]")
text=re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "[email]", text)
text=re.sub(r"(?i)(bearer\s+)[A-Za-z0-9._~+/-]+", r"\1[redacted]", text)
print(text)'
}
annotate_errors() {
  python3 -c 'import sys
for raw in sys.stdin:
 line=raw.strip()
 if line:
  line=line.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
  print("::error::" + line[:1400])'
}
run_account_test() {
  local method="$1" email="$2" password="$3" code="$4" marker="$5"
  adb logcat -c
  local arguments=(shell am instrument -w -r -e class "com.gozar.FoxAccountInstrumentedTest#$method" -e email "$email" -e password "$password")
  if [ -n "$code" ]; then arguments+=(-e code "$code"); fi
  arguments+=(com.gozar.test/androidx.test.runner.AndroidJUnitRunner)
  set +e
  adb "${arguments[@]}" > "$result" 2>&1
  local status=$?
  set -e
  if [ "$status" -eq 0 ] && grep -E 'OK \(1 test\)' "$result" >/dev/null; then
    printf '::notice::%s\n' "$marker"
    return 0
  fi
  python3 - "$result" <<'PY' | sanitize | annotate_errors
import re
import sys
pattern = re.compile(r'(STATE=|CODE_REQUIRED|LINK_REQUIRED|TUNNEL_|AssertionError|FoxError|Exception|INSTRUMENTATION_CODE|INSTRUMENTATION_RESULT|shortMsg|Process crashed|FAILURE|Error:|bootstrap|TorService)', re.I)
count = 0
with open(sys.argv[1], errors='replace') as source:
    for raw in source:
        line = raw.strip()
        if not line or not pattern.search(line):
            continue
        print(line[:1400])
        count += 1
        if count >= 80:
            break
if count == 0:
    print('Account instrumentation did not report its required passing test')
PY
  adb logcat -d -v brief -t 1200 'GozarTor:V' 'TorService:V' '*:S' > "${RUNNER_TEMP:-/tmp}/tor-account-logcat-raw.txt" || true
  tail -80 "${RUNNER_TEMP:-/tmp}/tor-account-logcat-raw.txt" | sanitize
  if grep -F 'SIGNUP_CODE_REQUIRED' "$result" >/dev/null; then
    printf '%s\n' 'STATE=SIGNUP_CODE_REQUIRED'
  fi
  return 1
}
overall=0
run_account_test realLoginTwiceAndOpenDirectTunnel "$GOZAR_LOGIN_EMAIL" "$GOZAR_LOGIN_PASSWORD" '' 'STATE=LOGIN_PASS_AND_DIRECT_TUNNEL_TWICE_OK' || overall=1
run_account_test realSignupReceivesCodeAndOpensTunnel "$GOZAR_SIGNUP_EMAIL" "$GOZAR_SIGNUP_PASSWORD" "$GOZAR_SIGNUP_CODE" 'STATE=SIGNUP_LOGIN_PASS_AND_TUNNEL_OK' || overall=1
exit "$overall"
