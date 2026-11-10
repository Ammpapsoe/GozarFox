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
fi
exit "$status"
