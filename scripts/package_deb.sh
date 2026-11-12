#!/bin/sh
set -eu
bundle=$1
output=$2
version=$3
case "$version" in *[!0-9.+~a-zA-Z-]*) exit 1;; esac
stage=$(mktemp -d)
chmod 755 "$stage"
trap 'rm -rf "$stage"' EXIT HUP INT TERM
mkdir -p "$stage/DEBIAN" "$stage/usr/lib/gozar" "$stage/usr/bin" "$stage/usr/share/applications" "$stage/usr/share/pixmaps"
cp -a "$bundle/." "$stage/usr/lib/gozar/"
install -m 644 packaging/linux/gozar.desktop "$stage/usr/share/applications/gozar.desktop"
install -m 644 desktop_flutter/assets/gozar.png "$stage/usr/share/pixmaps/gozar.png"
printf '%s\n' '#!/bin/sh' 'exec /usr/lib/gozar/gozar "$@"' > "$stage/usr/bin/gozar"
chmod 755 "$stage/usr/bin/gozar"
size=$(du -sk "$stage/usr" | cut -f1)
cat > "$stage/DEBIAN/control" <<CONTROL
Package: gozar
Version: $version
Section: net
Priority: optional
Architecture: amd64
Maintainer: Gozar contributors <noreply@github.com>
Installed-Size: $size
Depends: libgtk-3-0, libstdc++6, libgcc-s1, libc6, iproute2
Homepage: https://github.com/bardia049b-stack/Gozar1
Description: Firefox VPN desktop client
 Gozar includes a Flutter desktop app and its separate Go tunnel core.
 The tunnel requires the appropriate system privileges.
CONTROL
dpkg-deb --root-owner-group -Zxz -z9 --build "$stage" "$output"
