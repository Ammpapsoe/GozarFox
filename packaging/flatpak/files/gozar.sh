#!/bin/sh
set -eu

bundle=/app/lib/gozar
root_core="$bundle/gozar-core.real"

export LD_LIBRARY_PATH="$bundle/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export XDG_DATA_DIRS="/app/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"

if [ -n "${GOZAR_CORE:-}" ]; then
	exec "$bundle/gozar" "$@"
fi

host_home=$(flatpak-spawn --host printenv HOME 2>/dev/null | tr -d '\n' || true)
if [ -z "$host_home" ]; then
	host_home=$(getent passwd "$(id -u)" | cut -d: -f6 || true)
fi
if [ -z "$host_home" ]; then
	echo "gozar: cannot locate the host home directory" >&2
	exit 1
fi

host_dir="$host_home/.local/share/gozar-host"
host_core="$host_dir/gozar-core"
if [ ! -f "$host_core" ] || ! cmp -s "$root_core" "$host_core"; then
	if ! mkdir -p "$host_dir" 2>/dev/null; then
		echo "gozar: cannot write to $host_dir" >&2
		exit 1
	fi
	cp -f "$root_core" "$host_core.tmp"
	chmod 755 "$host_core.tmp"
	mv -f "$host_core.tmp" "$host_core"
fi

export GOZAR_HOST_CORE_PATH="$host_core"
export GOZAR_CORE="$bundle/host/gozar-core"
exec "$bundle/gozar" "$@"
