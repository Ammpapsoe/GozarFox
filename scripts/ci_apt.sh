#!/usr/bin/env bash
set -euo pipefail
targets=(
  "https://security.ubuntu.com/ubuntu|185.125.190.81"
  "https://security.ubuntu.com/ubuntu|185.125.190.82"
  "https://security.ubuntu.com/ubuntu|185.125.190.83"
  "https://security.ubuntu.com/ubuntu|91.189.90.11"
  "https://security.ubuntu.com/ubuntu|91.189.90.12"
  "https://security.ubuntu.com/ubuntu|91.189.90.13"
  "http://azure.archive.ubuntu.com/ubuntu|52.250.76.244"
)
probe="pool/main/b/bzip2/bzip2-doc_1.0.8-5.1ubuntu0.1_all.deb"
for target in "${targets[@]}"; do
  mirror="${target%%|*}"
  ip="${target##*|}"
  host=$(printf '%s' "$mirror" | sed -E 's#^https?://([^/]+)/.*#\1#')
  case "$mirror" in
    https://*) port=443 ;;
    *) port=80 ;;
  esac
  if ! curl -fsSL --connect-timeout 5 --max-time 15 --range 0-4095 --resolve "$host:$port:$ip" -o /dev/null "$mirror/$probe"; then
    continue
  fi
  sudo python3 - "$mirror" "$host" "$ip" <<'PY'
from pathlib import Path
import re
import sys
mirror, host, ip = sys.argv[1:]
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
hosts = Path('/etc/hosts')
lines = [line for line in hosts.read_text().splitlines() if host not in line.split()[1:]]
lines.append(f'{ip} {host}')
hosts.write_text('\n'.join(lines) + '\n')
PY
  printf 'Using Ubuntu mirror %s at %s\n' "$mirror" "$ip"
  if sudo timeout 180 apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=1 -o Acquire::http::Timeout=15 -o Acquire::https::Timeout=15 "$@"; then
    exit 0
  fi
done
printf '%s\n' 'No responsive Ubuntu mirror completed the requested apt operation' >&2
exit 1
