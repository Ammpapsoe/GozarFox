#!/usr/bin/env bash
# Shared official Build preflight. Validation-only desktop CI does not use it.
set -euo pipefail
layout=flutter
if [[ -f packaging/desktop-release-ui ]]; then layout=$(cat packaging/desktop-release-ui); fi
case "$layout" in
  flutter) exit 0 ;;
  qt) ;;
  *) echo 'Unknown desktop package layout; refusing fallback.' >&2; exit 1 ;;
esac
version=$(python3 scripts/check_qt_release_prerequisites.py --metadata-only)
if [[ "${GITHUB_REF:-}" == refs/tags/* ]]; then
  if [[ "$GITHUB_REF" != "refs/tags/v$version" ]]; then
    echo "Qt release tag must exactly match v$version." >&2
    exit 1
  fi
  python3 scripts/release_channel.py "docs/release-notes/$version.md"
fi
python3 scripts/check_qt_release_prerequisites.py
