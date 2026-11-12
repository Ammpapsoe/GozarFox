#!/bin/sh
set -eu

bundle=$1
output=$2
version=$3
arch=${4:-x86_64}
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
case "$version" in *[!0-9.+~a-zA-Z-]*) echo "invalid version" >&2; exit 1;; esac

tool=${APPIMAGETOOL:-}
if [ -z "$tool" ]; then
	tool="$root/.cache/appimagetool-$arch.AppImage"
	mkdir -p "$root/.cache"
	if [ ! -x "$tool" ]; then
		curl -fsSL -o "$tool" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$arch.AppImage"
		chmod 755 "$tool"
	fi
fi

appdir=$(mktemp -d)
trap 'rm -rf "$appdir"' EXIT HUP INT TERM
mkdir -p "$appdir/usr/lib/gozar" "$appdir/usr/share/applications" "$appdir/usr/share/icons/hicolor/1024x1024/apps"
cp -a "$bundle/." "$appdir/usr/lib/gozar/"

install -m 755 "$root/packaging/appimage/AppRun" "$appdir/AppRun"
install -m 644 "$root/packaging/linux/gozar.desktop" "$appdir/gozar.desktop"
install -m 644 "$root/packaging/linux/gozar.desktop" "$appdir/usr/share/applications/gozar.desktop"
icon="$root/desktop_flutter/assets/gozar.png"
install -m 644 "$icon" "$appdir/gozar.png"
install -m 644 "$icon" "$appdir/.DirIcon"
install -m 644 "$icon" "$appdir/usr/share/icons/hicolor/1024x1024/apps/gozar.png"

chmod -R a+rX "$appdir"
export APPIMAGE_EXTRACT_AND_RUN=1
export ARCH="$arch"
export VERSION="$version"
mkdir -p "$(dirname "$output")"
if [ -n "${APPIMAGE_RUNTIME_FILE:-}" ]; then
	test -f "$APPIMAGE_RUNTIME_FILE"
	"$tool" --no-appstream --comp zstd --runtime-file "$APPIMAGE_RUNTIME_FILE" "$appdir" "$output"
else
	"$tool" --no-appstream --comp zstd "$appdir" "$output"
fi
ls -l "$output"
