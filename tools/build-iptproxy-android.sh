#!/usr/bin/env bash
set -euo pipefail
output="${1:?usage: build-iptproxy-android.sh <output.aar>}"
source_commit="5b33fa42bed1d7b16ee0d61402178a35822eb115"
gomobile_version="v0.0.0-20261011034528-197770444867"
ndk_version="28.0.13004108"
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -f "$ANDROID_NDK_HOME/source.properties" ]; then
    echo 'ANDROID_NDK_HOME must point to the pinned Android NDK' >&2
    exit 1
fi
actual_ndk=$(awk -F= '/Pkg.Revision/ {gsub(/[[:space:]]/, "", $2); print $2}' "$ANDROID_NDK_HOME/source.properties")
if [ "$actual_ndk" != "$ndk_version" ]; then
    echo "Expected NDK $ndk_version, found $actual_ndk" >&2
    exit 1
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git clone --quiet --filter=blob:none --no-checkout https://github.com/tladesignz/IPtProxy.git "$work/source"
git -C "$work/source" checkout --quiet "$source_commit"
git -C "$work/source" submodule update --init --recursive --depth 1
export GOPRIVATE=gitlab.torproject.org
cd "$work/source/IPtProxy.go"
go run "golang.org/x/mobile/cmd/gomobile@$gomobile_version" init
go run "golang.org/x/mobile/cmd/gomobile@$gomobile_version" bind -target=android/arm64,android/arm,android/amd64,android/386 -ldflags='-s -w -checklinkname=0' -o "$work/IPtProxy.aar" -androidapi=24 -tags=netcgo -trimpath
python3 - "$work/IPtProxy.aar" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as aar:
    abis = sorted({entry.filename.split('/')[1] for entry in aar.infolist() if entry.filename.startswith('jni/') and entry.filename.endswith('/libgojni.so')})
    expected = ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']
    if abis != expected:
        raise SystemExit(f'Unexpected IPtProxy ABIs: {abis}')
    for abi in expected:
        if not aar.getinfo(f'jni/{abi}/libgojni.so').file_size:
            raise SystemExit(f'Empty IPtProxy native library: {abi}')
PY
mkdir -p "$(dirname "$output")"
cp "$work/IPtProxy.aar" "$output"
test -s "$output"
printf 'IPtProxy 5.8.0 source %s built with all four Android ABIs\n' "$source_commit"
