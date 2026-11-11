#!/usr/bin/env bash
set -euo pipefail
mirrors=(
  "https://us.archive.ubuntu.com/ubuntu"
  "https://archive.ubuntu.com/ubuntu"
  "https://security.ubuntu.com/ubuntu"
  "http://archive.ubuntu.com/ubuntu"
  "http://azure.archive.ubuntu.com/ubuntu"
)
probe="pool/main/b/bzip2/bzip2-doc_1.0.8-5.1ubuntu0.1_all.deb"
for mirror in "${mirrors[@]}"; do
  if ! curl -fsSL --connect-timeout 5 --max-time 15 --range 0-4095 -o /dev/null "$mirror/$probe"; then
    continue
  fi
  sudo python3 - "$mirror" <<'PY'
from pathlib import Path
import re
import sys
mirror = sys.argv[1]
pattern = re.compile(r'https?://(?:(?:azure|us)\.archive\.ubuntu\.com|archive\.ubuntu\.com|security\.ubuntu\.com)/ubuntu')
for path in Path('/etc/apt').rglob('*'):
    if not path.is_file():
        continue
    try:
        text = path.read_text()
    except (UnicodeDecodeError, PermissionError):
        continue
    updated = pattern.sub(mirror, text)
    if updated != text:
        path.write_text(updated)
PY
  printf 'Using Ubuntu mirror %s\n' "$mirror"
  if sudo timeout 180 apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=1 -o Acquire::http::Timeout=15 -o Acquire::https::Timeout=15 "$@"; then
    exit 0
  fi
done
printf '%s\n' 'No responsive Ubuntu mirror completed the requested apt operation' >&2
exit 1
