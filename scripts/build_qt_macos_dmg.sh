#!/usr/bin/env bash
# Create and verify an internal disk image from the already verified app.
set -euo pipefail
app=$1
image=$2
test -d "$app"
work=$(mktemp -d)
mounted=0
cleanup() {
  if [[ $mounted == 1 ]]; then hdiutil detach "$work/mount" >/dev/null || :; fi
  rm -rf "$work"
}
trap cleanup EXIT
mkdir "$work/root" "$work/mount"
ditto "$app" "$work/root/Gozar.app"
ln -s /Applications "$work/root/Applications"
hdiutil create -ov -format UDZO -volname 'Gozar' -srcfolder "$work/root" "$image"
hdiutil verify "$image"
hdiutil attach -readonly -nobrowse -mountpoint "$work/mount" "$image" >/dev/null
mounted=1
installed="$work/mount/Gozar.app"
test "$(readlink "$work/mount/Applications")" = /Applications
codesign --verify --deep --strict "$installed"
for file in LICENSE LICENSE-SCOPE.md NOTICE WINDSCRIBE-GPL-2-LICENSE.txt; do
  cmp "$app/Contents/Resources/$file" "$installed/Contents/Resources/$file"
done
for file in Gozar gozar-original-ui gozar-core; do
  cmp "$app/Contents/MacOS/$file" "$installed/Contents/MacOS/$file"
done
hdiutil detach "$work/mount" >/dev/null
mounted=0
