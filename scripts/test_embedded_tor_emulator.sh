#!/usr/bin/env bash
set -uo pipefail
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
exit 1
