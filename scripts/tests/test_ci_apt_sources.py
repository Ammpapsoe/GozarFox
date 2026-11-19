import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("ci_apt_sources", ROOT / "scripts/ci_apt_sources.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AptMirrorRewriteTest(unittest.TestCase):
    def test_regional_mirrors_and_security_are_rewritten(self):
        source = """Types: deb
URIs: http://us-east-2.ec2.archive.ubuntu.com/ubuntu
Suites: noble noble-updates

deb http://azure.archive.ubuntu.com/ubuntu noble main
deb https://security.ubuntu.com/ubuntu noble-security main
deb http://archive.ubuntu.com/ubuntu noble main
"""
        target = "https://security.ubuntu.com/ubuntu"
        rewritten = MODULE.rewrite_sources(source, target)
        self.assertEqual(4, rewritten.count(target))
        self.assertNotIn("us-east-2.ec2.archive.ubuntu.com", rewritten)
        self.assertIn("Suites: noble noble-updates", rewritten)
        self.assertEqual(rewritten, MODULE.rewrite_sources(rewritten, target))

    def test_other_sources_and_similar_names_are_not_modified(self):
        source = """deb https://packages.microsoft.com/ubuntu noble main
deb https://archive.ubuntu.com.example.net/ubuntu noble main
deb https://archive.ubuntu.com/ubuntu-extra noble main
"""
        self.assertEqual(source, MODULE.rewrite_sources(source, "https://security.ubuntu.com/ubuntu"))

    def test_hosts_replaces_only_exact_hostname(self):
        source = "127.0.0.1 localhost\n1.2.3.4 security.ubuntu.com\n9.9.9.9 notsecurity.ubuntu.com\n"
        result = MODULE.rewrite_hosts(source, "security.ubuntu.com", "185.125.190.81")
        self.assertNotIn("1.2.3.4", result)
        self.assertIn("9.9.9.9 notsecurity.ubuntu.com", result)
        self.assertIn("185.125.190.81 security.ubuntu.com\n", result)


if __name__ == "__main__":
    unittest.main()
