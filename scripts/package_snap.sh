#!/bin/sh
set -eu

bundle=$1
output=$2
version=$3
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
case "$version" in *[!0-9.+~a-zA-Z-]*) echo "invalid version" >&2; exit 1;; esac

snapdir=$(mktemp -d)
trap 'rm -rf "$snapdir"' EXIT HUP INT TERM
mkdir -p "$snapdir/meta/gui" "$snapdir/bin" "$snapdir/usr/lib/gozar" \
	"$snapdir/usr/share/applications" "$snapdir/usr/share/pixmaps"

cp -a "$bundle/." "$snapdir/usr/lib/gozar/"
install -m 755 "$root/packaging/snap/gozar-wrapper.sh" "$snapdir/bin/gozar"
install -m 644 "$root/packaging/snap/gozar.desktop" "$snapdir/meta/gui/gozar.desktop"
install -m 644 "$root/packaging/snap/gozar.desktop" "$snapdir/usr/share/applications/gozar.desktop"
install -m 644 "$root/desktop_flutter/assets/gozar.png" "$snapdir/meta/gui/gozar.png"
install -m 644 "$root/desktop_flutter/assets/gozar.png" "$snapdir/usr/share/pixmaps/gozar.png"

cat > "$snapdir/meta/snap.yaml" <<SNAP
name: gozar
version: "$version"
summary: Firefox Private Network client
description: |
  Gozar connects a Linux desktop to the Firefox Private Network proxy and keeps
  the whole system behind it. It ships a Flutter window and a separate Go core
  that speaks the same tunnel to the same servers.

  The tunnel creates a TUN interface and rewrites the routing table, which needs
  administrator rights, so start Gozar with sudo: sudo gozar
base: core22
grade: stable
confinement: classic
architectures:
  - amd64
apps:
  gozar:
    command: bin/gozar
SNAP

mkdir -p "$(dirname "$output")"
rm -f "$output"
mksquashfs "$snapdir" "$output" -noappend -comp xz -no-xattrs -no-fragments -all-root -no-progress >/dev/null
ls -l "$output"
