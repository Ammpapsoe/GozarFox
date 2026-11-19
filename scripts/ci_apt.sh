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
  sudo python3 scripts/ci_apt_sources.py "$mirror" "$host" "$ip"
  printf 'Using Ubuntu mirror %s at %s\n' "$mirror" "$ip"
  if sudo timeout 180 apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=1 -o Acquire::http::Timeout=15 -o Acquire::https::Timeout=15 "$@"; then
    exit 0
  fi
done
printf '%s\n' 'No responsive Ubuntu mirror completed the requested apt operation' >&2
exit 1
