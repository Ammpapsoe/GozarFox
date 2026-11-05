# AUR package

`PKGBUILD` and `.SRCINFO` for `gozar-bin`, which repackages the
`v1.10.4-desktop` Linux bundle from the release page.

```sh
makepkg -si
```

The AUR repository has to be created by the maintainer's account. Until then
these two files are the source of truth and can be pushed as-is:

```sh
git clone ssh://aur@aur.archlinux.org/gozar-bin.git
cp packaging/aur/PKGBUILD packaging/aur/.SRCINFO gozar-bin/
cd gozar-bin && git add PKGBUILD .SRCINFO && git commit -m 'gozar-bin 1.10.4' && git push
```

`sha256sums` is pinned to the published archive. Re-run `makepkg --printsrcinfo`
after a version bump so `.SRCINFO` matches the `PKGBUILD`.
