#!/usr/bin/env python3
"""Produce ephemeral signed data for the debug-only Android recovery fixture.

The manifest names an official-looking release URL so the real URL rules are
exercised, but the fixture intercepts its transport locally. The private key
is temporary and is never the production update-signing key.
"""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def generate(directory: Path) -> None:
    apk = directory / "forward.apk"
    if not apk.is_file() or apk.stat().st_size <= 0:
        raise ValueError("forward fixture APK missing")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    name = "Gozar_1.11.2_universal.apk"
    manifest = json.dumps({
        "version": "1.11.2", "channel_min": "1.8.0", "rollback": False,
        "issued_at": int(time.time()), "notes": "CI-only recovery fixture",
        "artifacts": [{"name": name, "sha256": digest.hexdigest(),
                       "size": apk.stat().st_size,
                       "url": "https://github.com/bardia049b-stack/Gozar/releases/download/v1.11.2/" + name}],
        "recovery": {"affected_version": "1.11.1", "affected_code": 37,
                     "affected_commit": "2" * 40, "restores_version": "1.11.0",
                     "restores_code": 36, "restores_commit": "1" * 40, "recovery_code": 38},
    }, sort_keys=True, separators=(",", ":"))
    with tempfile.TemporaryDirectory(prefix="gozar-fixture-key-") as temporary:
        work = Path(temporary)
        key, body, signature_path = work / "key.pem", work / "manifest.json", work / "signature.bin"
        body.write_text(manifest, encoding="utf-8")
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        pub = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"],
                             check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
        if len(pub) != 44 or pub[:12] != bytes.fromhex("302a300506032b6570032100"):
            raise ValueError("unexpected Ed25519 public-key encoding")
        subprocess.run(["openssl", "pkeyutl", "-sign", "-inkey", str(key), "-rawin", "-in", str(body),
                        "-out", str(signature_path)], check=True, stdout=subprocess.DEVNULL)
        signature = signature_path.read_bytes()
    if len(signature) != 64:
        raise ValueError("unexpected Ed25519 signature length")
    broken = bytes([signature[0] ^ 1]) + signature[1:]
    for suffix, signature_bytes, payload in (
        ("invalid-signature", broken, "recovery-fixture-forward.apk"),
        ("tampered", signature, "recovery-fixture-old.apk"),
        ("valid", signature, "recovery-fixture-forward.apk"),
    ):
        spec = {"public_key": pub[-32:].hex(), "manifest": manifest,
                "signature": base64.b64encode(signature_bytes).decode("ascii"), "payload": payload}
        (directory / f"fixture-{suffix}.json").write_text(json.dumps(spec), encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: create_recovery_fixture_specs.py <fixture-apk-directory>")
    generate(Path(sys.argv[1]))
