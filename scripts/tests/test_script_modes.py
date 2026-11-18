"""Every script that a workflow or a person runs directly must be executable.

GitHub's runners check out exactly the modes recorded in the tree, so a script
that is 100644 fails with "Permission denied" (exit 126) the moment a workflow
invokes it by path. That happened to the real-TUN test during the 1.18.0
release build; this test keeps it from happening silently again.
"""
import unittest
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SHEBANG = re.compile(r"^#!.*\b(bash|sh|python[0-9.]*)\b")


class ScriptModeTest(unittest.TestCase):
    def test_every_shebanged_script_is_executable(self):
        offenders = []
        for path in ROOT.rglob("*"):
            if not path.is_file() or ".git" in path.parts or "backup" in str(path):
                continue
            if path.suffix not in {".sh", ".py"}:
                continue
            try:
                first = path.read_text(encoding="utf-8", errors="replace").splitlines()[:1]
            except OSError:
                continue
            if not first or not SHEBANG.match(first[0]):
                continue
            if not path.stat().st_mode & 0o111:
                offenders.append(str(path.relative_to(ROOT)))
        self.assertEqual([], sorted(offenders))

    def test_workflows_only_call_scripts_that_exist(self):
        missing = []
        for workflow in (ROOT / ".github/workflows").glob("*.yml"):
            text = workflow.read_text(encoding="utf-8")
            for match in re.finditer(r"(?<![\w/.-])((?:scripts|tools)/[\w./-]+\.(?:sh|py))", text):
                if not (ROOT / match.group(1)).exists():
                    missing.append(f"{workflow.name}: {match.group(1)}")
        self.assertEqual([], sorted(set(missing)))


if __name__ == "__main__":
    unittest.main()
