#!/usr/bin/env bash
set -euo pipefail
result="${RUNNER_TEMP:-/tmp}/embedded-tor-instrumentation.txt"
adb shell am instrument -w -r -e class com.gozar.FoxTorInstrumentedTest com.gozar.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
grep -E 'OK \(1 test\)' "$result" >/dev/null
