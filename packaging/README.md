# Packaging

Every directory holds the sources for one distribution channel, and every
channel is built by a script in `scripts/`.

| Directory | Script | Output |
| --- | --- | --- |
| `linux/` | `package_deb.sh` | `Gozar-Linux-amd64.deb` and the shared desktop entry |
| `appimage/` | `package_appimage.sh` | `Gozar-<version>-x86_64.AppImage` |
| `flatpak/` | `package_flatpak.sh` | `Gozar-<version>-x86_64.flatpak` |
| `snap/` | `package_snap.sh` | `Gozar_<version>_amd64.snap` |
| `rpm/` | `package_rpm.sh` | `Gozar-Fedora-x86_64.rpm`, `Gozar-RHEL-x86_64.rpm` |
| `aur/` | `package_arch.sh` | `Gozar-<version>-x86_64.pkg.tar.zst`, plus the AUR `PKGBUILD` |
| `openwrt/` | `package_openwrt.sh` | four `Gozar-OpenWrt-*.ipk` and `LuCI-Gozar-all.ipk` |
| `raspberrypi/` | `package_raspberrypi.sh` | `Gozar-RaspberryPi-{arm64,armv7}.tar.xz` |
| `freebsd/` | `package_freebsd.py` | `Gozar-FreeBSD-{amd64,arm64}.pkg` |
| `windows/` | CI | NSIS installer and portable zip |

The desktop channels ship the Flutter bundle; the router, Raspberry Pi and
FreeBSD packages ship the headless core with a service script, because there is
no graphical session on those systems.
