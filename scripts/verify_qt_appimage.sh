#!/usr/bin/env bash
# Check the actual image's payload in an isolated extracted layout, no FUSE.
set -euo pipefail
image=$(realpath "$1")
bundle=$(realpath "$2")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
chmod +x "$image"
cd "$tmp"
"$image" --appimage-extract >/dev/null
extracted="$tmp/squashfs-root/usr/lib/gozar"
test -x "$tmp/squashfs-root/AppRun"
test -x "$extracted/gozar"
for file in LICENSE LICENSE-SCOPE.md NOTICE WINDSCRIBE-GPL-2-LICENSE.txt gozar-core gozar-original-ui; do
  cmp "$bundle/$file" "$extracted/$file"
done
mkdir "$tmp/home"
env -u QT_PLUGIN_PATH -u QT_QPA_PLATFORM_PLUGIN_PATH -u LD_LIBRARY_PATH \
  HOME="$tmp/home" XDG_CONFIG_HOME="$tmp/home" QT_QPA_PLATFORM=offscreen \
  timeout 60s "$extracted/gozar-original-ui" --ci-signed-out
