Gozar for Raspberry Pi
======================

This archive contains the headless Go core and a systemd unit. It runs on
64-bit Raspberry Pi OS (arm64) or 32-bit Raspberry Pi OS (armv7l).

Install
-------

  sudo ./install.sh

The installer copies the core to /opt/gozar, installs gozar.service, and
enables it. The service does nothing useful until an account is signed in:

  sudo XDG_CONFIG_HOME=/opt/gozar/config \
       /opt/gozar/gozar-core --email name@example.com --password 'the password'

Then:

  sudo systemctl start gozar
  journalctl -u gozar -f

State and diagnostics
---------------------

  sudo XDG_CONFIG_HOME=/opt/gozar/config /opt/gozar/gozar-core --selftest --headless
  sudo XDG_CONFIG_HOME=/opt/gozar/config /opt/gozar/gozar-core --servers

Notes
-----

The tunnel takes over the default route while it runs and restores the previous
routes and DNS servers when the service stops. The core is statically linked, so
no extra libraries are needed. It needs the TUN device, which Raspberry Pi OS
provides through /dev/net/tun.

This archive does not contain the Flutter desktop application; it is a headless
build for servers and for a Pi used as a router.
