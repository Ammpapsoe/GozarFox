#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "core/src/main/kotlin/com/gozar/FoxPinnedTls.kt"
GO = ROOT / "go-desktop/internal/relay/relay.go"

def valid(pin):
    return re.fullmatch(r"[0-9a-f]{64}", pin) is not None

def replace(path, pattern, live, formatter):
    text = path.read_text(encoding="utf-8")
    match = re.search(pattern, text, re.DOTALL)
    if not match:
        raise SystemExit(f"pin block not found in {path}")
    existing = re.findall(r'"([0-9a-f]{64})"', match.group(1))
    pins = list(dict.fromkeys([*existing, *live]))[-6:]
    block = formatter(pins)
    path.write_text(text[:match.start()] + block + text[match.end():], encoding="utf-8")

def main():
    live = list(dict.fromkeys(pin.lower() for pin in sys.argv[1:]))
    if not live or any(not valid(pin) for pin in live):
        raise SystemExit("valid live SPKI hashes are required")
    replace(
        KOTLIN,
        r"val WORKER_SPKI_SHA256_PINS = setOf\((.*?)\n    \)",
        live,
        lambda pins: "val WORKER_SPKI_SHA256_PINS = setOf(\n        " + ",\n        ".join(f'\"{pin}\"' for pin in pins) + "\n    )",
    )
    replace(
        GO,
        r"var WorkerSPKISHA256Pins = \[\]string\{(.*?)\n\}",
        live,
        lambda pins: "var WorkerSPKISHA256Pins = []string{\n" + "".join(f'\t\"{pin}\",\n' for pin in pins) + "}",
    )

if __name__ == "__main__":
    main()
