#!/bin/sh
set -eu

out=$1
version=$2
local_bundle=${3:-}
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM

appid=io.github.bardia049b_stack.Gozar
manifest=$root/packaging/flatpak/$appid.yaml

command -v flatpak-builder >/dev/null || {
	echo "flatpak-builder is missing; install it first" >&2
	exit 1
}

runtime=$(sed -n "s/^runtime-version: '\{0,1\}\([^']*\)'\{0,1\}$/\1/p" "$manifest")
supported=org.freedesktop.Platform/x86_64/$runtime
if ! flatpak list --user --columns=ref 2>/dev/null | grep -Fxq "$supported"; then
	flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
	flatpak install --user --noninteractive -y flathub "org.freedesktop.Platform//$runtime" "org.freedesktop.Sdk//$runtime"
fi

mkdir -p "$out"

if [ -n "$local_bundle" ]; then
	bundle=$(CDPATH='' cd -- "$(dirname -- "$local_bundle")" && pwd)/$(basename -- "$local_bundle")
	test -s "$bundle"
	sed -e "s|^        url: .*|        path: $bundle|" -e '/^        sha256: /d' "$manifest" \
		> "$root/packaging/flatpak/_local-$appid.yaml"
	manifest=$root/packaging/flatpak/_local-$appid.yaml
	trap 'rm -rf "$work"; rm -f "$root/packaging/flatpak/_local-$appid.yaml"' EXIT HUP INT TERM
fi

flatpak-builder --user --force-clean \
	--state-dir="$work/state" --repo="$work/repo" "$work/build" "$manifest"

flatpak build-bundle "$work/repo" "$out/Gozar-$version-x86_64.flatpak" "$appid" \
	--runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo

ls -l "$out/Gozar-$version-x86_64.flatpak"
