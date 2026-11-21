#!/usr/bin/env bash
# Archive the exact project checkout, not an all-dependencies source bundle.
set -euo pipefail
version=${1:?version required}
out=${2:?output directory required}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
sha=$(git rev-parse HEAD)
if [[ -n ${GITHUB_SHA:-} ]]; then test "$GITHUB_SHA" = "$sha"; fi
mkdir -p "$out"
archive="$out/GozarFox-$version-project-source.tar.xz"
tmp=$(mktemp "$out/.gozar-source.XXXXXX")
trap 'rm -f "$tmp"' EXIT
# Use git archive: only tracked data at the immutable commit is packaged.
git archive --format=tar --prefix="GozarFox-$sha/" "$sha" | xz -T2 -6 > "$tmp"
xz -t "$tmp"
embedded=$(xz -dc "$tmp" | { git get-tar-commit-id; cat >/dev/null; })
test "$embedded" = "$sha"
chmod 644 "$tmp"
mv "$tmp" "$archive"
printf 'Verified project source snapshot for %s: %s\n' "$sha" "$archive"
