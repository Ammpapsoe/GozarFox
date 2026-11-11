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
mkdir -p "$(dirname "$output")"
app_dir=$(cd "$(dirname "$output")/.." && pwd)
jni_output="$app_dir/build/generated/iptproxy/jniLibs"
rm -rf "$jni_output"
mkdir -p "$jni_output"
python3 - "$work/IPtProxy.aar" "$output" "$jni_output" <<'PY'
import os
import struct
import sys
import zipfile
source_path, output_path, jni_output = sys.argv[1:]
expected = {
    'arm64-v8a': (2, 183),
    'armeabi-v7a': (1, 40),
    'x86': (1, 3),
    'x86_64': (2, 62),
}
with zipfile.ZipFile(source_path) as source:
    names = {entry.filename for entry in source.infolist()}
    for abi, (elf_class, machine) in expected.items():
        name = f'jni/{abi}/libgojni.so'
        if name not in names:
            raise SystemExit(f'Missing IPtProxy native library: {abi}')
        data = source.read(name)
        if len(data) < 20 or data[:4] != b'\x7fELF':
            raise SystemExit(f'Invalid IPtProxy ELF library: {abi}')
        if data[4] != elf_class or struct.unpack_from('<H', data, 18)[0] != machine:
            raise SystemExit(f'Wrong IPtProxy ELF architecture: {abi}')
        destination = os.path.join(jni_output, abi, 'libgojni.so')
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        with open(destination, 'wb') as output:
            output.write(data)
        os.chmod(destination, 0o755)
    temporary = output_path + '.tmp'
    with zipfile.ZipFile(temporary, 'w') as output:
        for entry in source.infolist():
            if not entry.filename.startswith('jni/'):
                output.writestr(entry, source.read(entry))
    os.replace(temporary, output_path)
PY
test -s "$output"
printf 'IPtProxy 5.8.0 source %s built with all four Android ABIs\n' "$source_commit"
