#!/usr/bin/env bash
# Sign desktop update metadata using the same pinned Ed25519 release key.
# GOZAR_RELEASE_KEY=/secure/key.pem GOZAR_RELEASE_TAG=v1.12.0 \
#   tools/sign-desktop-manifest.sh <desktop-version> <artifact-dir> [notes]
set -euo pipefail
umask 077
VERSION="${1:?usage: sign-desktop-manifest.sh <desktop-version> <artifact-dir> [notes]}"
ARTIFACT_DIR="${2:?missing artifact dir}"
NOTES="${3:-}"
TAG="${GOZAR_RELEASE_TAG:?set GOZAR_RELEASE_TAG to the actual GitHub release tag}"
KEY="${GOZAR_RELEASE_KEY:?set GOZAR_RELEASE_KEY to the release private key pem}"
EXPECTED_PUB="14cf2b6901d20a703c241674b8d2f1cdbb3fe87cba85529af82f004115575903"
ACTUAL_DER=$(openssl pkey -in "$KEY" -pubout -outform DER | od -An -tx1 | tr -d ' \n')
if [ "$ACTUAL_DER" != "302a300506032b6570032100$EXPECTED_PUB" ]; then
    echo 'refusing to sign: the key is not the pinned Ed25519 release key' >&2
    exit 1
fi
WORK=$(mktemp -d "$ARTIFACT_DIR/.desktop-manifest.XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT
python3 - "$VERSION" "$TAG" "$ARTIFACT_DIR" "$NOTES" "$WORK/desktop-update.json" <<'PY'
import hashlib, json, os, re, sys, time
version, tag, directory, notes, output = sys.argv[1:]
repository = os.environ.get("GOZAR_RELEASE_REPOSITORY", "bardia049b-stack/Gozar")
if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
    raise SystemExit("invalid release repository")
if not re.fullmatch(r"[0-9]{1,4}(\.[0-9]{1,4}){2}(-rc\.[0-9]{1,3})?", version):
    raise SystemExit("invalid desktop version")
if not re.fullmatch(r"v[0-9]{1,4}(\.[0-9]{1,4}){1,3}(-rc\.[0-9]{1,3}|-desktop)?", tag):
    raise SystemExit("invalid release tag")
if len(notes.encode("utf-8")) > 16_000:
    raise SystemExit("release notes too long")
entries = [
    ("windows-amd64-installer", "windows", "amd64", "exe", "Gozar-Windows-x64-Setup.exe"),
    ("windows-amd64-portable", "windows", "amd64", "zip", "Gozar-Windows-x64.zip"),
    ("linux-amd64-deb", "linux", "amd64", "deb", "Gozar-Linux-amd64.deb"),
    ("linux-amd64-portable", "linux", "amd64", "tar.xz", "Gozar-Linux-amd64.tar.xz"),
    ("linux-amd64-appimage", "linux", "amd64", "appimage", f"Gozar-{version}-x86_64.AppImage"),
    ("linux-amd64-flatpak", "linux", "amd64", "flatpak", f"Gozar-{version}-x86_64.flatpak"),
    ("linux-amd64-snap", "linux", "amd64", "snap", f"Gozar_{version}_amd64.snap"),
    ("linux-amd64-fedora", "linux", "amd64", "rpm", "Gozar-Fedora-x86_64.rpm"),
    ("linux-amd64-rhel", "linux", "amd64", "rpm", "Gozar-RHEL-x86_64.rpm"),
    ("linux-amd64-arch", "linux", "amd64", "pkg.tar.zst", f"Gozar-{version}-x86_64.pkg.tar.zst"),
    ("darwin-arm64-bundle", "darwin", "arm64", "tar.xz", "Gozar-macOS-ARM.tar.xz"),
    ("darwin-amd64-bundle", "darwin", "amd64", "tar.xz", "Gozar-macOS-Intel.tar.xz"),
]
artifacts = []
for ident, os_name, arch, kind, name in entries:
    path = os.path.join(directory, name)
    digest = hashlib.sha256()
    try:
        with open(path, "rb") as handle:
            size = os.fstat(handle.fileno()).st_size
            if size <= 0 or size > 512 * 1024 * 1024:
                raise SystemExit(f"invalid artifact size: {name}")
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
    except FileNotFoundError:
        raise SystemExit(f"missing desktop artifact: {name}")
    artifacts.append({"id": ident, "os": os_name, "arch": arch, "kind": kind,
        "name": name, "sha256": digest.hexdigest(), "size": size,
        "url": f"https://github.com/{repository}/releases/download/{tag}/{name}"})
manifest = {"version": version, "release_tag": tag, "issued_at": int(time.time()),
    "notes": notes, "artifacts": artifacts}
raw = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
if len(raw) > 65_536:
    raise SystemExit("manifest exceeds client limit")
with open(output, "wb") as out:
    out.write(raw)
print(f"desktop manifest prepared for {len(artifacts)} artifact(s)")
PY
openssl pkeyutl -sign -rawin -inkey "$KEY" -in "$WORK/desktop-update.json" -out "$WORK/signature.raw"
[ "$(wc -c < "$WORK/signature.raw")" -eq 64 ] || { echo 'invalid Ed25519 signature size' >&2; exit 1; }
openssl pkey -in "$KEY" -pubout -out "$WORK/public.pem"
openssl pkeyutl -verify -rawin -pubin -inkey "$WORK/public.pem" \
    -in "$WORK/desktop-update.json" -sigfile "$WORK/signature.raw" >/dev/null
base64 -w0 "$WORK/signature.raw" > "$WORK/desktop-update.json.sig"
mv -f "$WORK/desktop-update.json" "$ARTIFACT_DIR/desktop-update.json"
mv -f "$WORK/desktop-update.json.sig" "$ARTIFACT_DIR/desktop-update.json.sig"
echo 'signed desktop-update.json and desktop-update.json.sig ready'
