#!/bin/sh
set -eu

out=$1
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$out"

build() {
	goarch=$1
	goarm=$2
	name=$3
	stage="$work/$name"
	mkdir -p "$stage"
	(cd "$root/go-desktop" && CGO_ENABLED=0 GOOS=linux GOARCH="$goarch" GOARM="$goarm" GONOSUMCHECK=1 \
		go build -trimpath -buildvcs=false -ldflags='-s -w' -o "$stage/gozar-core" ./cmd/gozar)
	install -m 644 "$root/packaging/raspberrypi/gozar.service" "$stage/gozar.service"
	install -m 755 "$root/packaging/raspberrypi/install.sh" "$stage/install.sh"
	install -m 644 "$root/packaging/raspberrypi/README.txt" "$stage/README.txt"
	for license in LICENSE NOTICE QUIC-GO-LICENSE.txt QPACK-LICENSE.txt GO-DEPS-LICENSES.txt; do
		install -m 644 "$root/$license" "$stage/$license"
	done
	(cd "$work" && tar --owner=0 --group=0 -cJf "$out/$name.tar.xz" "$name")
}

build arm64 "" Gozar-RaspberryPi-arm64
build arm 7 Gozar-RaspberryPi-armv7

ls -l "$out"/Gozar-RaspberryPi-*.tar.xz
