# Gozar on FreeBSD

The release ships a ready package: `Gozar-FreeBSD-amd64.pkg` and
`Gozar-FreeBSD-arm64.pkg`. It installs the headless Go core and an rc.d script;
the Flutter desktop application is a Linux/macOS/Windows build and is not part of
this package.

## Install

```sh
sudo pkg add -f ./Gozar-FreeBSD-amd64.pkg
```

The package installs:

| Path | What it is |
| --- | --- |
| `/usr/local/bin/gozar-core` | the tunnel core |
| `/usr/local/etc/rc.d/gozar` | the rc.d service |
| `/usr/local/share/gozar/README` | the notes below |
| `/usr/local/share/licenses/gozar/` | the licenses |

## Sign in once

```sh
sudo env XDG_CONFIG_HOME=/var/db/gozar gozar-core --email name@example.com --password 'the password'
```

The session is written to `/var/db/gozar/Gozar/config.json`, and every later
start reuses it.

## Run it

```sh
sysrc gozar_enable=YES
service gozar start
service gozar status
```

The rc script loads `if_tun` when it is missing, because the tunnel needs a TUN
interface, and it clears the system proxy state when the service stops. The
tunnel takes the default route while it runs and restores the routes and the
DNS servers it changed on the way out.

## Diagnostics

```sh
sudo env XDG_CONFIG_HOME=/var/db/gozar gozar-core --selftest --headless
sudo env XDG_CONFIG_HOME=/var/db/gozar gozar-core --servers
```

The core is statically linked, so no extra libraries are needed. The package is
built for FreeBSD 14 and newer on amd64 and arm64.
