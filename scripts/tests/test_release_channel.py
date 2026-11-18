from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/release_channel.py"


class ReleaseChannelTest(unittest.TestCase):
    def channel(self, contents, *flags):
        with tempfile.TemporaryDirectory() as tmp:
            notes = Path(tmp) / "notes.md"
            notes.write_text(contents, encoding="utf-8")
            return subprocess.run(["python3", str(SCRIPT), str(notes), *flags], capture_output=True, text=True)

    def test_explicit_stable_and_prerelease(self):
        for marker, expected in (("STABLE", "stable"), ("PRERELEASE", "prerelease")):
            result = self.channel(f"<!-- {marker} -->\nRelease notes\n")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(expected, result.stdout.strip())

    def test_draft_never_publishes_but_may_be_validated(self):
        text = "<!-- DRAFT -->\nNot yet\n"
        self.assertNotEqual(0, self.channel(text).returncode)
        result = self.channel(text, "--allow-draft")
        self.assertEqual(0, result.returncode)
        self.assertEqual("draft", result.stdout.strip())

    def test_missing_or_misplaced_marker_fails_closed(self):
        for text in ("", "Release notes\n", "Release notes\n<!-- PRERELEASE -->\n", "<!-- beta -->\n"):
            self.assertNotEqual(0, self.channel(text).returncode)


if __name__ == "__main__":
    unittest.main()
