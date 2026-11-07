import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "sign-update-manifest.sh"


def command(*args, **kwargs):
    return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)


class ReleaseToolsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory()
        cls.root = Path(cls.workspace.name)
        cls.key = cls.root / "test-ed25519.pem"
        cls.public = cls.root / "test-public.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(cls.key)], check=True)
        subprocess.run(["openssl", "pkey", "-in", str(cls.key), "-pubout", "-out", str(cls.public)], check=True)
        der = subprocess.check_output(["openssl", "pkey", "-in", str(cls.key), "-pubout", "-outform", "DER"])
        assert der[:12].hex() == "302a300506032b6570032100" and len(der) == 44
        cls.pub_hex = der[-32:].hex()
        original = SCRIPT.read_text(encoding="utf-8")
        cls.prod_hex = re.search(r'EXPECTED_PUB="([0-9a-f]{64})"', original).group(1)
        cls.test_script = cls.root / "test-sign.sh"
        cls.test_script.write_text(original.replace(cls.prod_hex, cls.pub_hex), encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=self.root)
        self.apkdir = Path(self.directory.name)
        self.env = os.environ.copy()
        self.env.update(GOZAR_RELEASE_KEY=str(self.key), GOZAR_RELEASE_TAG="v1.11.1")
        (self.apkdir / "Gozar_1.11.1_universal.apk").write_bytes(b"a verified APK fixture")

    def tearDown(self):
        self.directory.cleanup()

    def sign(self, script=None):
        return command("bash", str(script or self.test_script), "1.11.1", "1.8.0", str(self.apkdir), "test notes", env=self.env)

    def test_production_key_matches_the_client_and_wrong_key_is_rejected(self):
        source = (ROOT / "core/src/main/kotlin/com/gozar/FoxUpdate.kt").read_text(encoding="utf-8")
        self.assertIn(f'KEY_HEX = "{self.prod_hex}"', source)
        result = self.sign(SCRIPT)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.apkdir / "update.json").exists())

    def test_generated_manifest_and_signature_match_the_exact_release_assets(self):
        (self.apkdir / "Gozar_1.11.1_arm64-v8a.apk").write_bytes(b"arm64 fixture")
        result = self.sign()
        self.assertEqual(0, result.returncode, result.stderr)
        raw = (self.apkdir / "update.json").read_bytes()
        signature = base64.b64decode((self.apkdir / "update.json.sig").read_bytes(), validate=True)
        self.assertEqual(64, len(signature))
        sigfile = self.apkdir / "sig.raw"
        sigfile.write_bytes(signature)
        verified = command("openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(self.public),
                           "-in", str(self.apkdir / "update.json"), "-sigfile", str(sigfile))
        self.assertEqual(0, verified.returncode, verified.stderr)
        manifest = json.loads(raw)
        self.assertEqual("1.11.1", manifest["version"])
        self.assertFalse(manifest["rollback"])
        self.assertEqual(2, len(manifest["artifacts"]))
        for item in manifest["artifacts"]:
            data = (self.apkdir / item["name"]).read_bytes()
            self.assertEqual(len(data), item["size"])
            self.assertEqual(hashlib.sha256(data).hexdigest(), item["sha256"])
            self.assertEqual(f'https://github.com/bardia049b-stack/Gozar/releases/download/v1.11.1/{item["name"]}', item["url"])
        (self.apkdir / "update.json").write_bytes(raw + b" ")
        self.assertNotEqual(0, command("openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(self.public),
                                       "-in", str(self.apkdir / "update.json"), "-sigfile", str(sigfile)).returncode)

    def test_desktop_suffixed_tag_points_to_the_actual_release(self):
        self.env["GOZAR_RELEASE_TAG"] = "v1.11.1-desktop"
        result = self.sign()
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads((self.apkdir / "update.json").read_text())
        self.assertIn("/v1.11.1-desktop/", manifest["artifacts"][0]["url"])

    def test_wrong_tag_or_unexpected_apk_name_are_rejected(self):
        self.env["GOZAR_RELEASE_TAG"] = "v1.10.4"
        self.assertNotEqual(0, self.sign().returncode)
        self.env["GOZAR_RELEASE_TAG"] = "v1.11.1"
        (self.apkdir / "Gozar_9.9.9_universal.apk").write_bytes(b"wrong version")
        self.assertNotEqual(0, self.sign().returncode)
        self.assertFalse((self.apkdir / "update.json").exists())

    def test_release_notes_fail_closed_for_drafts_and_missing_versions(self):
        notes = ROOT / "scripts/notes.py"
        env = self.env.copy()
        env.pop("GOZAR_ALLOW_DRAFT_NOTES", None)
        self.assertNotEqual(0, command("python3", str(notes), "1.10.4", "bardia049b-stack/Gozar", "1.11.1", "v1.11.1", env=env).returncode)
        self.assertNotEqual(0, command("python3", str(notes), "9.9.9", "bardia049b-stack/Gozar", "9.9.9", "v9.9.9", env=env).returncode)
        env["GOZAR_ALLOW_DRAFT_NOTES"] = "1"
        dry = command("python3", str(notes), "1.10.4", "bardia049b-stack/Gozar", "1.11.1", "v1.11.1", env=env)
        self.assertEqual(0, dry.returncode, dry.stderr)
        self.assertIn("Gozar_1.11.1_universal.apk", dry.stdout)
        self.assertIn("/v1.11.1/", dry.stdout)
        self.assertNotIn("## Gozar 1.10.4", dry.stdout)


if __name__ == "__main__":
    unittest.main()
