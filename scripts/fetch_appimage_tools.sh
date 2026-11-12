#!/usr/bin/env bash
# CI-only pinned AppImage tooling for reproducible packaging. Asset API IDs and
# hashes must be reviewed together when upgrading these third-party binaries.
set -euo pipefail
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'This fetch must run on GitHub Actions' >&2; exit 1; }
: "${GH_TOKEN:?GitHub Actions token is required}"
dest="${1:?output directory required}"
mkdir -p "$dest"
umask 077

fetch() {
  local repo="$1" id="$2" expected="$3" name="$4" actual
  local path="$dest/$name"
  curl --fail --location --silent --show-error --retry 3 --retry-delay 2 \
    --header "Authorization: Bearer $GH_TOKEN" \
    --header 'Accept: application/octet-stream' \
    --header 'X-GitHub-Api-Version: 2022-11-28' \
    "https://api.github.com/repos/$repo/releases/assets/$id" \
    --output "$path"
  test -s "$path"
  actual=$(sha256sum "$path" | awk '{print $1}')
  test "$actual" = "$expected" || { echo "unexpected SHA-256 for $name" >&2; rm -f "$path"; exit 1; }
}

# Pinned public x86_64 asset IDs, not the mutable 'continuous' download URL.
# curl removes Authorization on cross-host redirects; don't use --location-trusted.
fetch AppImage/appimagetool 715388534 e8c1169c4af66c3184d7ee110713d4be04befe671fa358e2c1591ee840553b03 appimagetool-x86_64.AppImage
fetch AppImage/type2-runtime 657374641 617aa77314d57c8b8e4fae9c3f0b35af7b30d5529cb69e98f64f92510ed98a46 runtime-x86_64
chmod 755 "$dest/appimagetool-x86_64.AppImage"
chmod 644 "$dest/runtime-x86_64"
