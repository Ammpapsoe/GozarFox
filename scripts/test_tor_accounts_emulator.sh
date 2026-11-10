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
adb root
adb wait-for-device
adb shell id | grep -F 'uid=0(root)' >/dev/null
adb shell setenforce 0
adb shell getenforce | grep -F 'Permissive' >/dev/null
app_apk=app/build/outputs/apk/debug/app-x86_64-debug.apk
mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/debug -maxdepth 1 -type f -name '*.apk' | sort)
test -s "$app_apk"
test "${#test_apks[@]}" -eq 1
test -s "${test_apks[0]}"
adb install -r "$app_apk"
adb install -r "${test_apks[0]}"
adb shell pm list instrumentation | grep -F 'instrumentation:com.gozar.test/androidx.test.runner.AndroidJUnitRunner' >/dev/null
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
run_account_test() {
  local method="$1" email="$2" password="$3" code="$4" marker="$5"
  adb logcat -c
  set +e
  adb shell am instrument -w -r -e class "com.gozar.FoxAccountInstrumentedTest#$method" \
    -e email "$email" -e password "$password" -e code "$code" \
    com.gozar.test/androidx.test.runner.AndroidJUnitRunner > "$result" 2>&1
  local status=$?
  set -e
  if [ "$status" -eq 0 ] && grep -E 'OK \(1 test\)' "$result" >/dev/null; then
    printf '%s\n' "$marker"
    return 0
  fi
  python3 - "$result" <<'PY' | sanitize
import re
import sys
pattern = re.compile(r'(STATE=|CODE_REQUIRED|LINK_REQUIRED|TUNNEL_|AssertionError|FoxError|Exception|INSTRUMENTATION_CODE|FAILURE|Error:|bootstrap|TorService)', re.I)
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
run_account_test realLoginTwiceAndOpenDirectTunnel "$GOZAR_LOGIN_EMAIL" "$GOZAR_LOGIN_PASSWORD" '' 'STATE=LOGIN_PASS_AND_DIRECT_TUNNEL_TWICE_OK'
run_account_test realSignupReceivesCodeAndOpensTunnel "$GOZAR_SIGNUP_EMAIL" "$GOZAR_SIGNUP_PASSWORD" "$GOZAR_SIGNUP_CODE" 'STATE=SIGNUP_LOGIN_PASS_AND_TUNNEL_OK'
