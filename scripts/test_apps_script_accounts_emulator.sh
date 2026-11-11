#!/usr/bin/env bash
set -euo pipefail
export GOZAR_LOGIN_EMAIL GOZAR_LOGIN_PASSWORD GOZAR_SIGNUP_EMAIL GOZAR_SIGNUP_PASSWORD
readarray -t GOZAR_LOGIN_VALUES < <(python3 -c 'import json,os; d=json.loads(os.environ["GOZAR_LIVE_INPUT"]); assert d["email"] and d["password"]; print(d["email"]); print(d["password"])')
readarray -t GOZAR_SIGNUP_VALUES < <(python3 -c 'import json,os; d=json.loads(os.environ["GOZAR_CI_SIGNUP_INPUT"]); assert d["email"] and d["password"]; print(d["email"]); print(d["password"])')
GOZAR_LOGIN_EMAIL="${GOZAR_LOGIN_VALUES[0]}"
GOZAR_LOGIN_PASSWORD="${GOZAR_LOGIN_VALUES[1]}"
GOZAR_SIGNUP_EMAIL="${GOZAR_SIGNUP_VALUES[0]}"
GOZAR_SIGNUP_PASSWORD="${GOZAR_SIGNUP_VALUES[1]}"
for value in "$GOZAR_LOGIN_EMAIL" "$GOZAR_LOGIN_PASSWORD" "$GOZAR_SIGNUP_EMAIL" "$GOZAR_SIGNUP_PASSWORD"; do
  printf '::add-mask::%s\n' "$value"
done
app_apk=app/build/outputs/apk/debug/app-x86_64-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-x86_64-debug-androidTest.apk
test -s "$app_apk"
test -s "$test_apk"
adb wait-for-device
adb install -r "$app_apk"
adb install -r "$test_apk"
result="${RUNNER_TEMP:-/tmp}/apps-script-account-instrumentation.txt"
umask 077
trap 'rm -f "$result"' EXIT
run_test() {
  local method="$1" email="$2" password="$3" marker="$4"
  set +e
  adb shell am instrument -w -r -e class "com.gozar.FoxAccountInstrumentedTest#$method" -e email "$email" -e password "$password" com.gozar.test/androidx.test.runner.AndroidJUnitRunner > "$result" 2>&1
  local status=$?
  set -e
  if [ "$status" -eq 0 ] && grep -E 'OK \(1 test\)' "$result" >/dev/null && grep -F "$marker" "$result" >/dev/null; then
    printf '::notice::%s\n' "$marker"
    return 0
  fi
  python3 - "$result" <<'PY'
import os,re,sys
text=open(sys.argv[1],errors='replace').read()
for key in ('GOZAR_LOGIN_EMAIL','GOZAR_LOGIN_PASSWORD','GOZAR_SIGNUP_EMAIL','GOZAR_SIGNUP_PASSWORD'):
 value=os.environ.get(key,'')
 if value:text=text.replace(value,'[redacted]')
text=re.sub(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}','[email]',text)
text=re.sub(r'(?i)(bearer\s+)[A-Za-z0-9._~+/-]+',r'\1[redacted]',text)
lines=[line for line in text.splitlines() if re.search(r'(STATE=|CODE_REQUIRED|AssertionError|FoxError|Exception|INSTRUMENTATION_CODE|shortMsg|FAILURE|Error:)',line,re.I)]
print('\n'.join(lines[-50:]) if lines else 'Account instrumentation did not report its required passing test')
PY
  return 1
}
run_test realLoginReceivesPassThroughAppsScript "$GOZAR_LOGIN_EMAIL" "$GOZAR_LOGIN_PASSWORD" 'STATE=LOGIN_PASS_OK'
run_test realSignupReceivesCodeThroughAppsScript "$GOZAR_SIGNUP_EMAIL" "$GOZAR_SIGNUP_PASSWORD" 'STATE=SIGNUP_CODE_REQUIRED'
