import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts/create_recovery_fixture_specs.py"


class RecoveryFixtureSpecsTest(unittest.TestCase):
    def test_ephemeral_signature_and_negative_fixtures(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "forward.apk").write_bytes(b"fixture-forward-apk")
            subprocess.run(["python3", str(TOOL), str(directory)], check=True)
            valid = json.loads((directory / "fixture-valid.json").read_text())
            invalid = json.loads((directory / "fixture-invalid-signature.json").read_text())
            tampered = json.loads((directory / "fixture-tampered.json").read_text())
            manifest = json.loads(valid["manifest"])
            self.assertEqual("1.11.2", manifest["version"])
            self.assertEqual(hashlib.sha256(b"fixture-forward-apk").hexdigest(), manifest["artifacts"][0]["sha256"])
            self.assertEqual(37, manifest["recovery"]["affected_code"])
            self.assertEqual(valid["signature"], tampered["signature"])
            self.assertNotEqual(valid["payload"], tampered["payload"])
            self.assertNotEqual(valid["signature"], invalid["signature"])
            self.assertFalse(list(directory.glob("*.pem")))
            (directory / "pub.der").write_bytes(bytes.fromhex("302a300506032b6570032100") + bytes.fromhex(valid["public_key"]))
            (directory / "body").write_text(valid["manifest"], encoding="utf-8")
            for spec, expected in ((valid, 0), (invalid, 1)):
                (directory / "sig").write_bytes(base64.b64decode(spec["signature"]))
                result = subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(directory / "pub.der"),
                                         "-keyform", "DER", "-rawin", "-in", str(directory / "body"),
                                         "-sigfile", str(directory / "sig")], capture_output=True)
                self.assertEqual(expected == 0, result.returncode == 0)

    def test_missing_apk_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(["python3", str(TOOL), tmp], capture_output=True)
            self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
