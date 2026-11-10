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
        desktop_original = (ROOT / "tools/sign-desktop-manifest.sh").read_text(encoding="utf-8")
        if f'EXPECTED_PUB="{cls.prod_hex}"' not in desktop_original:
            raise AssertionError("desktop and Android signing tools use different keys")
        cls.desktop_script = cls.root / "test-sign-desktop.sh"
        cls.desktop_script.write_text(desktop_original.replace(cls.prod_hex, cls.pub_hex), encoding="utf-8")

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
        desktop = (ROOT / "go-desktop/internal/update/manifest.go").read_text(encoding="utf-8")
        self.assertIn(f'const KeyHex = "{self.prod_hex}"', desktop)
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

    def test_forward_recovery_metadata_is_signed_and_invalid_code_is_rejected(self):
        recovery = {"affected_version": "1.11.0", "affected_code": 37, "affected_commit": "a" * 40,
                    "restores_version": "1.10.0", "restores_code": 36, "restores_commit": "b" * 40,
                    "recovery_code": 38}
        plan = self.apkdir / "recovery.json"
        plan.write_text(json.dumps(recovery), encoding="utf-8")
        self.env["GOZAR_RECOVERY_PLAN"] = str(plan)
        result = self.sign()
        self.assertEqual(0, result.returncode, result.stderr)
        raw = (self.apkdir / "update.json").read_bytes()
        self.assertEqual(recovery, json.loads(raw)["recovery"])
        signature = base64.b64decode((self.apkdir / "update.json.sig").read_bytes(), validate=True)
        sigfile = self.apkdir / "recovery.sig.raw"
        sigfile.write_bytes(signature)
        verified = command("openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(self.public),
                           "-in", str(self.apkdir / "update.json"), "-sigfile", str(sigfile))
        self.assertEqual(0, verified.returncode, verified.stderr)
        recovery["recovery_code"] = 37
        plan.write_text(json.dumps(recovery), encoding="utf-8")
        self.assertNotEqual(0, self.sign().returncode)
        self.assertEqual(raw, (self.apkdir / "update.json").read_bytes())

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

    def test_desktop_manifest_is_signed_and_includes_every_platform_artifact(self):
        names = ["Gozar-Windows-x64-Setup.exe", "Gozar-Windows-x64.zip", "Gozar-Linux-amd64.deb",
                 "Gozar-Linux-amd64.tar.xz", "Gozar-1.10.4-x86_64.AppImage", "Gozar-1.10.4-x86_64.flatpak",
                 "Gozar_1.10.4_amd64.snap", "Gozar-Fedora-x86_64.rpm", "Gozar-RHEL-x86_64.rpm",
                 "Gozar-1.10.4-x86_64.pkg.tar.zst", "Gozar-macOS-ARM.tar.xz", "Gozar-macOS-Intel.tar.xz"]
        for name in names:
            (self.apkdir / name).write_bytes(("fixture for " + name).encode())
        result = command("bash", str(self.desktop_script), "1.10.4", str(self.apkdir), "desktop notes", env=self.env)
        self.assertEqual(0, result.returncode, result.stderr)
        path = self.apkdir / "desktop-update.json"
        manifest = json.loads(path.read_bytes())
        self.assertEqual("1.10.4", manifest["version"])
        self.assertEqual("v1.11.1", manifest["release_tag"])
        self.assertEqual(set(names), {item["name"] for item in manifest["artifacts"]})
        for item in manifest["artifacts"]:
            data = (self.apkdir / item["name"]).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), item["sha256"])
            self.assertEqual(len(data), item["size"])
            self.assertIn("/v1.11.1/", item["url"])
        signature = base64.b64decode((self.apkdir / "desktop-update.json.sig").read_bytes(), validate=True)
        sigfile = self.apkdir / "desktop-sig.raw"
        sigfile.write_bytes(signature)
        verified = command("openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(self.public),
                           "-in", str(path), "-sigfile", str(sigfile))
        self.assertEqual(0, verified.returncode, verified.stderr)

    def test_desktop_manifest_accepts_release_candidate_tag(self):
        names = ["Gozar-Windows-x64-Setup.exe", "Gozar-Windows-x64.zip", "Gozar-Linux-amd64.deb",
                 "Gozar-Linux-amd64.tar.xz", "Gozar-1.11.4-rc.1-x86_64.AppImage",
                 "Gozar-1.11.4-rc.1-x86_64.flatpak", "Gozar_1.11.4-rc.1_amd64.snap",
                 "Gozar-Fedora-x86_64.rpm", "Gozar-RHEL-x86_64.rpm",
                 "Gozar-1.11.4-rc.1-x86_64.pkg.tar.zst", "Gozar-macOS-ARM.tar.xz", "Gozar-macOS-Intel.tar.xz"]
        for name in names:
            (self.apkdir / name).write_bytes(("rc fixture " + name).encode())
        env = dict(self.env, GOZAR_RELEASE_TAG="v1.11.4-rc.1")
        result = command("bash", str(self.desktop_script), "1.11.4-rc.1", str(self.apkdir), "rc notes", env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads((self.apkdir / "desktop-update.json").read_bytes())
        self.assertEqual("1.11.4-rc.1", manifest["version"])
        self.assertEqual("v1.11.4-rc.1", manifest["release_tag"])
        self.assertIn("/v1.11.4-rc.1/", manifest["artifacts"][0]["url"])

    def test_desktop_manifest_rejects_other_prerelease_forms(self):
        for version, tag in (("1.11.4-beta.1", "v1.11.4-beta.1"), ("1.11.4-rc.1.2", "v1.11.4-rc.1"),
                             ("1.11.4", "v1.11.4-rc.x")):
            env = dict(self.env, GOZAR_RELEASE_TAG=tag)
            result = command("bash", str(self.desktop_script), version, str(self.apkdir), env=env)
            self.assertNotEqual(0, result.returncode, (version, tag))

    def test_desktop_signing_fails_closed_when_artifacts_are_missing(self):
        result = command("bash", str(self.desktop_script), "1.10.4", str(self.apkdir), env=self.env)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.apkdir / "desktop-update.json").exists())

    def test_desktop_core_and_package_versions_match(self):
        pubspec = (ROOT / "desktop_flutter/pubspec.yaml").read_text(encoding="utf-8")
        core = (ROOT / "go-desktop/cmd/gozar/main.go").read_text(encoding="utf-8")
        ui_version = re.search(r"(?m)^version:\s*([^+\s]+)", pubspec).group(1)
        core_version = re.search(r'(?m)^const version = "([^"]+)"', core).group(1)
        self.assertEqual(ui_version, core_version)

    def test_dry_run_does_not_silently_skip_packaging_or_validation(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        packages = workflow.split("  packages:\n", 1)[1].split("\n  publish:\n", 1)[0]
        self.assertIn("inputs.validate_release", packages)
        sentinel = workflow.split("  release-validation-complete:\n", 1)[1]
        self.assertIn("needs: [publish]", sentinel)
        self.assertIn("VALIDATION_RESULT", sentinel)
        self.assertIn('!= success', sentinel)

    def test_publication_is_draft_first_and_respects_release_channel(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        publish = workflow.split("  publish:\n", 1)[1].split("\n  release-validation-complete:\n", 1)[0]
        self.assertIn("scripts/release_channel.py", publish)
        self.assertIn("draft: true", publish)
        self.assertIn("Require same-commit unit and packaging evidence", publish)
        self.assertIn('select(.name == "Test" and .conclusion == "success")', publish)
        self.assertIn('select(.name == "Autonomous Android" and .conclusion == "success")', publish)
        self.assertIn('.name == "compile" or .name == "unit-tests" or .name == "artifact"', publish)
        self.assertNotIn("android-recovery-emulator", publish)
        self.assertIn("Require same-commit root and real-account evidence for Stable", publish)
        self.assertIn('!contains(github.ref_name, \'-\')', publish)
        self.assertIn('select(.name == "Tor Device Validation" and .event == "workflow_dispatch" and .conclusion == "success")', publish)
        self.assertIn('.name == "native" or .name == "account"', publish)
        self.assertIn("prerelease: ${{ steps.version.outputs.prerelease }}", publish)
        self.assertIn("--draft=false --prerelease --latest=false", publish)
        self.assertLess(publish.index("Verify draft release files"), publish.index("Publish verified release"))

    def test_release_notes_fail_closed_for_drafts_and_missing_versions(self):
        # Keep this test independent of whether the real release notes later
        # move from draft to an explicitly labeled prerelease or stable state.
        scratch = self.root / "notes-fixture"
        (scratch / "scripts").mkdir(parents=True, exist_ok=True)
        (scratch / "docs/release-notes").mkdir(parents=True, exist_ok=True)
        notes = scratch / "scripts/notes.py"
        notes.write_text((ROOT / "scripts/notes.py").read_text(encoding="utf-8"), encoding="utf-8")
        (scratch / "docs/release-notes/1.11.1.md").write_text("<!-- DRAFT -->\nDraft fixture\n", encoding="utf-8")
        env = self.env.copy()
        env.pop("GOZAR_ALLOW_DRAFT_NOTES", None)
        self.assertNotEqual(0, command("python3", str(notes), "1.10.4", "bardia049b-stack/Gozar", "1.11.1", "v1.11.1", env=env).returncode)
        self.assertNotEqual(0, command("python3", str(notes), "9.9.9", "bardia049b-stack/Gozar", "9.9.9", "v9.9.9", env=env).returncode)
        env["GOZAR_ALLOW_DRAFT_NOTES"] = "1"
        dry = command("python3", str(notes), "1.10.4", "bardia049b-stack/Gozar", "1.11.1", "v1.11.1", env=env)
        self.assertEqual(0, dry.returncode, dry.stderr)
        self.assertIn("Gozar_1.11.1_arm64-v8a.apk", dry.stdout)
        self.assertIn("/v1.11.1/", dry.stdout)
        self.assertNotIn("## Gozar 1.10.4", dry.stdout)


if __name__ == "__main__":
    unittest.main()
