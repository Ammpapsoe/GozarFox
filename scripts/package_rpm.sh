#!/bin/sh
set -eu

bundle=$1
out=$2
version=$3
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
case "$version" in *[!0-9.+~a-zA-Z-]*) echo "invalid version" >&2; exit 1;; esac

# RPM versions cannot contain "-". Map the SemVer prerelease separator to "~",
# which RPM sorts before the release, so 1.11.4~rc.1 < 1.11.4.
rpm_version=$(printf '%s' "$version" | tr '-' '~')

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$work/BUILD" "$work/RPMS" "$work/SRPMS" "$work/SOURCES" "$work/SPECS" "$out"

cp -a "$bundle" "$work/SOURCES/bundle"
install -m 644 "$root/packaging/linux/gozar.desktop" "$work/SOURCES/gozar.desktop"
install -m 644 "$root/desktop_flutter/assets/gozar.png" "$work/SOURCES/gozar.png"
install -m 644 "$root/packaging/rpm/gozar.spec" "$work/SPECS/gozar.spec"

build() {
	dist=$1
	target=$2
	rpmbuild -bb "$work/SPECS/gozar.spec" \
		--define "_topdir $work" \
		--define "gozar_version $rpm_version" \
		--define "gozar_dist $dist" \
		--define "dist $dist" \
		--target x86_64 >/dev/null
	built=$(find "$work/RPMS" -name "gozar-$rpm_version-1$dist.x86_64.rpm" -print -quit)
	test -n "$built"
	install -m 644 "$built" "$target"
	rm -rf "$work/RPMS"
}

build ".fc41" "$out/Gozar-Fedora-x86_64.rpm"
build ".el9" "$out/Gozar-RHEL-x86_64.rpm"

for file in "$out/Gozar-Fedora-x86_64.rpm" "$out/Gozar-RHEL-x86_64.rpm"; do
	printf '%s\n' "== $file"
	rpm -qp --queryformat '%{NAME} %{VERSION} %{RELEASE} %{ARCH}\n' "$file"
	rpm -qp --requires "$file" | head -12
	rpm -qpl "$file" | head -6
done
