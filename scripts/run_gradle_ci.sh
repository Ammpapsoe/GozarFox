#!/usr/bin/env bash
set -uo pipefail
label="$1"
shift
log="${RUNNER_TEMP:-/tmp}/gozar-${label}.log"
set +e
bash ./gradlew "$@" --no-daemon --stacktrace 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
  python3 - "$log" <<'PY'
import re
import sys
patterns = re.compile(r'(^e:|error:|FAILURE:|What went wrong|Execution failed|Could not|Compilation error|There were failing tests|tests? failed|AssertionError|ComparisonFailure|Expected:|Actual:|Caused by:)', re.I)
count = 0
with open(sys.argv[1], errors='replace') as source:
    for raw in source:
        line = raw.strip()
        if not line or not patterns.search(line):
            continue
        line = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error::' + line[:900])
        count += 1
        if count >= 80:
            break
PY
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
count = 0
for path in Path('.').glob('**/build/test-results/**/*.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter('testcase'):
        failure = case.find('failure')
        if failure is None:
            failure = case.find('error')
        if failure is None:
            continue
        detail = (failure.get('message') or failure.text or '').strip().splitlines()
        message = detail[0] if detail else 'test failed'
        name = case.get('classname', '') + '.' + case.get('name', 'test')
        message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error::' + (name + ': ' + message)[:900])
        count += 1
        if count >= 80:
            raise SystemExit(0)
PY
fi
exit "$status"
