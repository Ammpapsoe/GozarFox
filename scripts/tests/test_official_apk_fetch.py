import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "build_official_upgrade_test_apks.sh"


class OfficialApkFetchTest(unittest.TestCase):
    def run_with_fake_curl(self, token):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bindir = root / "bin"
            bindir.mkdir()
            curl = bindir / "curl"
            curl.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, pathlib, sys\n"
                "args=sys.argv[1:]\n"
                "pathlib.Path(os.environ['MOCK_ARGS']).write_text(json.dumps(args))\n"
                "pathlib.Path(args[args.index('--output')+1]).write_bytes(b'tampered APK')\n"
            )
            curl.chmod(0o755)
            env = os.environ.copy()
            env.update({
                "PATH": str(bindir) + os.pathsep + env.get("PATH", ""),
                "GITHUB_ACTIONS": "true",
                "ANDROID_HOME": str(root),
                "KEYSTORE_BASE64": base64.b64encode(b"synthetic keystore").decode(),
                "KEYSTORE_PASSWORD": "synthetic-password",
                "KEY_ALIAS": "synthetic-alias",
                "KEY_PASSWORD": "synthetic-key-password",
                "GH_TOKEN": token,
                "RUNNER_TEMP": str(root),
                "GOZAR_APK_DIR": str(root / "apks"),
                "MOCK_ARGS": str(root / "curl-args.json"),
            })
            result = subprocess.run(["bash", str(SCRIPT)], cwd=ROOT, env=env,
                                    text=True, capture_output=True, timeout=15)
            args_file = root / "curl-args.json"
            args = json.loads(args_file.read_text()) if args_file.exists() else None
            temporary_keys = list(root.glob("official-upgrade-test.*"))
            return result.returncode, result.stdout, result.stderr, args, temporary_keys

    def test_rejects_corrupted_asset_and_never_prints_the_token(self):
        code, out, err, args, keys = self.run_with_fake_curl("synthetic-gh-token")
        self.assertNotEqual(0, code)
        self.assertIn("unexpected SHA-256 for official-stable", err)
        self.assertNotIn("synthetic-gh-token", out + err)
        self.assertIsNotNone(args)
        self.assertIn("https://api.github.com/repos/bardia049b-stack/Gozar/releases/assets/713630711", args)
        self.assertIn("Authorization: Bearer synthetic-gh-token", args)
        self.assertIn("Accept: application/octet-stream", args)
        self.assertNotIn("--location-trusted", args)
        self.assertEqual([], keys)

    def test_requires_a_token_before_fetching(self):
        code, out, err, args, keys = self.run_with_fake_curl("")
        self.assertNotEqual(0, code)
        self.assertIn("GitHub Actions token is required", err)
        self.assertIsNone(args)
        self.assertEqual([], keys)


if __name__ == "__main__":
    unittest.main()
