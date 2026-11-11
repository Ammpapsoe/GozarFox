#!/usr/bin/env bash
# Build an Ed25519-signed Android update manifest for the actual release tag.
# GOZAR_RELEASE_KEY=/secure/key.pem [GOZAR_RELEASE_TAG=v1.12.0] \
#   tools/sign-update-manifest.sh <version> <channel_min> <apk-dir> [notes]
set -euo pipefail
umask 077

VERSION="${1:?usage: sign-update-manifest.sh <version> <channel_min> <apk-dir> [notes]}"
CHANNEL_MIN="${2:?missing channel_min}"
APK_DIR="${3:?missing apk dir}"
NOTES="${4:-}"
KEY="${GOZAR_RELEASE_KEY:?set GOZAR_RELEASE_KEY to the release private key pem}"
TAG="${GOZAR_RELEASE_TAG:-v$VERSION}"
if [[ "$TAG" != "v$VERSION" && "$TAG" != "v$VERSION-desktop" ]]; then
    echo 'refusing to sign: tag must match the Android version' >&2
    exit 1
fi

# SubjectPublicKeyInfo for Ed25519 is exactly this prefix followed by 32 bytes.
EXPECTED_PUB="75ec9a23ecb173ddb9683e1c574515b3d4676453836804f327e86492896184e8"
ACTUAL_DER=$(openssl pkey -in "$KEY" -pubout -outform DER | od -An -tx1 | tr -d ' \n')
if [ "$ACTUAL_DER" != "302a300506032b6570032100$EXPECTED_PUB" ]; then
    echo 'refusing to sign: the key is not the Ed25519 key pinned in FoxUpdate.KEY_HEX' >&2
    exit 1
fi

WORK=$(mktemp -d "$APK_DIR/.manifest.XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT
python3 - "$VERSION" "$CHANNEL_MIN" "$APK_DIR" "$NOTES" "$TAG" "$WORK/update.json" <<'PY'
import hashlib, json, os, re, sys, time
version, channel_min, apk_dir, notes, tag, output = sys.argv[1:]
repository = os.environ.get("GOZAR_RELEASE_REPOSITORY", "bardia049b-stack/Gozar")
if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
    raise SystemExit("invalid release repository")
pattern = re.compile(r"^\d{1,4}(\.\d{1,4}){1,3}(-[0-9A-Za-z.]{1,16})?$")
def valid(value):
    return pattern.fullmatch(value) and ("-" not in value or all(value.split("-", 1)[1].split(".")))
if not valid(version) or not valid(channel_min) or len(notes.encode("utf-8")) > 16_000:
    raise SystemExit("invalid version, channel floor or notes")
allowed = {f"Gozar_{version}_{abi}.apk" for abi in ("universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64")}
artifacts = []
for name in sorted(os.listdir(apk_dir)):
    if not name.endswith(".apk"):
        continue
    if name not in allowed:
        raise SystemExit(f"unexpected APK name: {name}")
    path = os.path.join(apk_dir, name)
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        size = os.fstat(handle.fileno()).st_size
        if size <= 0 or size > 512 * 1024 * 1024:
            raise SystemExit(f"invalid APK size: {name}")
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    artifacts.append({"name": name, "sha256": digest.hexdigest(), "size": size,
        "url": f"https://github.com/{repository}/releases/download/{tag}/{name}"})
if not artifacts:
    raise SystemExit(f"no APK files found in {apk_dir}")
manifest = {"version": version, "channel_min": channel_min, "rollback": False,
    "issued_at": int(time.time()), "notes": notes, "artifacts": artifacts}
plan_path = os.environ.get("GOZAR_RECOVERY_PLAN")
if plan_path:
    def unique(pairs):
        out = {}
        for key, value in pairs:
            if key in out:
                raise ValueError("duplicate recovery field")
            out[key] = value
        return out
    with open(plan_path, encoding="utf-8") as handle:
        recovery = json.load(handle, object_pairs_hook=unique)
    fields = {"affected_version", "affected_code", "affected_commit", "restores_version",
              "restores_code", "restores_commit", "recovery_code"}
    if not isinstance(recovery, dict) or set(recovery) != fields:
        raise SystemExit("invalid recovery metadata fields")
    stable = re.compile(r"[0-9]{1,4}(?:\.[0-9]{1,4}){2}\Z")
    versions = [recovery["restores_version"], recovery["affected_version"], version]
    if not all(isinstance(v, str) and stable.fullmatch(v) for v in versions):
        raise SystemExit("invalid recovery version")
    ordered = [tuple(map(int, v.split("."))) for v in versions]
    codes = [recovery["restores_code"], recovery["affected_code"], recovery["recovery_code"]]
    commits = [recovery["restores_commit"], recovery["affected_commit"]]
    if not (ordered[0] < ordered[1] < ordered[2]) or not all(type(c) is int and 0 < c <= 2_100_000_000 for c in codes) or not (codes[0] < codes[1] < codes[2]):
        raise SystemExit("recovery versions or codes are not increasing")
    if not all(isinstance(c, str) and re.fullmatch(r"[0-9a-f]{40}", c) for c in commits) or commits[0] == commits[1]:
        raise SystemExit("invalid recovery commits")
    manifest["recovery"] = recovery
raw = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
if len(raw) > 65_536:
    raise SystemExit("manifest exceeds client limit")
with open(output, "wb") as out:
    out.write(raw)
print(f"manifest prepared for {len(artifacts)} artifact(s)")
PY

openssl pkeyutl -sign -rawin -inkey "$KEY" -in "$WORK/update.json" -out "$WORK/signature.raw"
[ "$(wc -c < "$WORK/signature.raw")" -eq 64 ] || { echo 'invalid Ed25519 signature size' >&2; exit 1; }
openssl pkey -in "$KEY" -pubout -out "$WORK/public.pem"
openssl pkeyutl -verify -rawin -pubin -inkey "$WORK/public.pem" \
    -in "$WORK/update.json" -sigfile "$WORK/signature.raw" >/dev/null
base64 -w0 "$WORK/signature.raw" > "$WORK/update.json.sig"
mv -f "$WORK/update.json" "$APK_DIR/update.json"
mv -f "$WORK/update.json.sig" "$APK_DIR/update.json.sig"
echo 'signed update.json and update.json.sig ready'
