import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "package_rpm.sh"


class PackageRpmVersionTest(unittest.TestCase):
    def test_prerelease_version_is_mapped_to_rpm_tilde(self):
        # RPM forbids "-" in Version; the script must map the prerelease separator to "~".
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("tr '-' '~'", text)
        self.assertIn('gozar_version $rpm_version', text)
        self.assertIn('gozar-$rpm_version-1$dist.x86_64.rpm', text)

    @unittest.skipUnless(shutil.which("rpmbuild"), "rpmbuild not installed")
    def test_rc_build_produces_rpm_with_tilde_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            bundle = Path(tmp) / "bundle"
            bundle.mkdir()
            (bundle / "gozar").write_text("#!/bin/sh\n", encoding="utf-8")
            os.chmod(bundle / "gozar", 0o755)
            out = Path(tmp) / "out"
            out.mkdir()
            result = subprocess.run(
                ["sh", str(SCRIPT), str(bundle), str(out), "1.11.4-rc.1"],
                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            for name in ("Gozar-Fedora-x86_64.rpm", "Gozar-RHEL-x86_64.rpm"):
                self.assertTrue((out / name).is_file(), name)
            # The RPM header must carry the tilde form, which sorts below 1.11.4.
            query = subprocess.run(
                ["rpm", "-qp", "--nodb", "--queryformat", "%{VERSION}", str(out / "Gozar-Fedora-x86_64.rpm")],
                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            self.assertEqual(query.stdout.strip(), "1.11.4~rc.1", query.stderr)


if __name__ == "__main__":
    unittest.main()
