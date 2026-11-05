# Gozar Flatpak

`io.github.bardia049b_stack.Gozar.yaml` builds the desktop application from the
`v1.10.3-desktop` Linux bundle published on the release page.

## Build

```sh
sudo apt-get install flatpak flatpak-builder
scripts/package_flatpak.sh out 1.10.3
```

The script installs the freedesktop 25.08 runtime and SDK the first time it
runs, then produces `Gozar-1.10.3-x86_64.flatpak`. Pass a local tarball as the
third argument to build without downloading the release asset:

```sh
scripts/package_flatpak.sh "$PWD/out" 1.10.3 /path/to/Gozar-Linux-amd64.tar.xz
```

## Install

```sh
flatpak install --user ./Gozar-1.10.3-x86_64.flatpak
flatpak run io.github.bardia049b_stack.Gozar
```

## How the tunnel runs

A Flatpak sandbox cannot create the TUN interface or change the routing table,
so the launcher copies the core to `~/.local/share/gozar-host/gozar-core` on the
host and starts it through `pkexec`. Your desktop's authentication dialog asks
for the administrator password once per session; the application window itself
stays inside the sandbox.

The session is stored where the privileged core writes it, `/root/.config/Gozar`,
because the core always runs as root.
