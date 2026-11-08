import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class AppImageToolsTest(unittest.TestCase):
    def test_fetch_rejects_tampered_tool_without_printing_token(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bindir = root / "bin"
            bindir.mkdir()
            fake = bindir / "curl"
            fake.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, pathlib, sys\n"
                "args=sys.argv[1:]\n"
                "pathlib.Path(os.environ['MOCK_ARGS']).write_text(json.dumps(args))\n"
                "pathlib.Path(args[args.index('--output')+1]).write_bytes(b'tampered tool')\n"
            )
            fake.chmod(0o755)
            env = os.environ.copy()
            env.update({"GITHUB_ACTIONS": "true", "GH_TOKEN": "synthetic-gh-token",
                        "PATH": str(bindir) + os.pathsep + env.get("PATH", ""),
                        "MOCK_ARGS": str(root / "curl-args.json")})
            dest = root / "tools"
            result = subprocess.run(["bash", str(ROOT / "scripts/fetch_appimage_tools.sh"), str(dest)],
                                    cwd=ROOT, env=env, text=True, capture_output=True, timeout=15)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unexpected SHA-256 for appimagetool", result.stderr)
            self.assertNotIn("synthetic-gh-token", result.stdout + result.stderr)
            args = json.loads((root / "curl-args.json").read_text())
            self.assertIn("https://api.github.com/repos/AppImage/appimagetool/releases/assets/715388534", args)
            self.assertNotIn("--location-trusted", args)
            self.assertFalse((dest / "appimagetool-x86_64.AppImage").exists())

    def test_package_uses_the_preverified_runtime_without_downloading_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bundle = root / "bundle"
            bundle.mkdir()
            (bundle / "gozar").write_text("synthetic binary")
            tool = root / "appimagetool"
            tool.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, pathlib, sys\n"
                "pathlib.Path(os.environ['MOCK_ARGS']).write_text(json.dumps(sys.argv[1:]))\n"
                "pathlib.Path(sys.argv[-1]).write_bytes(b'synthetic image')\n"
            )
            tool.chmod(0o755)
            runtime = root / "runtime"
            runtime.write_bytes(b"synthetic runtime")
            output = root / "result.AppImage"
            env = os.environ.copy()
            env.update({"APPIMAGETOOL": str(tool), "APPIMAGE_RUNTIME_FILE": str(runtime),
                        "MOCK_ARGS": str(root / "tool-args.json")})
            result = subprocess.run(["sh", str(ROOT / "scripts/package_appimage.sh"), str(bundle),
                                     str(output), "1.11.3"], cwd=ROOT, env=env,
                                    text=True, capture_output=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            args = json.loads((root / "tool-args.json").read_text())
            self.assertIn("--runtime-file", args)
            self.assertEqual(str(runtime), args[args.index("--runtime-file") + 1])
            self.assertEqual(b"synthetic image", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
