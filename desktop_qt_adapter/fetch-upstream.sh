#!/bin/sh
# Reproduce the exact upstream Qt source reference without overwriting a tree.
set -eu
dest=${1:?usage: fetch-upstream.sh DESTINATION}
if [ -e "$dest" ]; then
  echo "Refusing existing destination: $dest" >&2
  exit 1
fi
git clone --depth 1 --branch v2.26.2 https://github.com/Windscribe/Desktop-App.git "$dest"
actual=$(git -C "$dest" rev-parse HEAD)
expected=5578945964760be0bf234c506fdbcbcfe07c974d
if [ "$actual" != "$expected" ]; then
  echo "Unexpected upstream commit $actual; expected $expected" >&2
  exit 1
fi
printf 'Verified Windscribe source commit %s\n' "$actual"
