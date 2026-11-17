#!/bin/sh
set -eu

if [ "$(id -u)" != 0 ]; then
	echo "run this installer as root" >&2
	exit 1
fi

here=$(dirname "$(readlink -f "$0")")

install -dm755 /opt/gozar /opt/gozar/config /usr/local/bin
install -m755 "$here/gozar-core" /opt/gozar/gozar-core
ln -sf /opt/gozar/gozar-core /usr/local/bin/gozar-core
install -m644 "$here/gozar.service" /etc/systemd/system/gozar.service
install -m644 "$here/README.txt" /opt/gozar/README.txt

systemctl daemon-reload
systemctl enable gozar.service

cat <<TEXT
Gozar is installed.

Sign in once so the service has a session:

  sudo XDG_CONFIG_HOME=/opt/gozar/config /opt/gozar/gozar-core --email name@example.com --password 'the password'

Then start the tunnel:

  sudo systemctl start gozar

Read the state at any time with:

  sudo XDG_CONFIG_HOME=/opt/gozar/config /opt/gozar/gozar-core --selftest --headless
TEXT
