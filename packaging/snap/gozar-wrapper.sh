#!/bin/sh
set -eu
base=${SNAP:-$(dirname "$(readlink -f "$0")")/..}
export LD_LIBRARY_PATH="$base/usr/lib/gozar/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export GOZAR_CORE="$base/usr/lib/gozar/gozar-core"
export XDG_DATA_DIRS="$base/usr/share${XDG_DATA_DIRS:+:$XDG_DATA_DIRS}"
exec "$base/usr/lib/gozar/gozar" "$@"
