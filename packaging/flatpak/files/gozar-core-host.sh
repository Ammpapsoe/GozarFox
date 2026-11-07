#!/bin/sh
set -eu

if [ -z "${GOZAR_HOST_CORE_PATH:-}" ] || [ ! -f "${GOZAR_HOST_CORE_PATH:-/nonexistent}" ]; then
	echo "gozar: the host tunnel core is not staged; start the app through its launcher" >&2
	exit 1
fi

exec flatpak-spawn --host -- pkexec "$GOZAR_HOST_CORE_PATH" "$@"
