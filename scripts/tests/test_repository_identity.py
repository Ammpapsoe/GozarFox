import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE = "github.com/Ammpapsoe/GozarFox/go-desktop"


class RepositoryIdentityTest(unittest.TestCase):
    def test_go_module_and_internal_imports_use_current_repository(self):
        desktop = ROOT / "go-desktop"
        module_line = (desktop / "go.mod").read_text(encoding="utf-8").splitlines()[0]
        self.assertEqual("module " + MODULE, module_line)
        imports = 0
        for path in desktop.rglob("*.go"):
            text = path.read_text(encoding="utf-8")
            for match in re.finditer(r'"(github\.com/[^"\s]+/go-desktop/internal/[^"\s]+)"', text):
                imports += 1
                self.assertTrue(match.group(1).startswith(MODULE + "/internal/"), str(path))
        self.assertGreater(imports, 0)

    def test_signing_defaults_match_the_trusted_repository(self):
        for name in ("sign-update-manifest.sh", "sign-desktop-manifest.sh"):
            source = (ROOT / "tools" / name).read_text(encoding="utf-8")
            self.assertIn('os.environ.get("GOZAR_RELEASE_REPOSITORY", "Ammpapsoe/GozarFox")', source)


if __name__ == "__main__":
    unittest.main()
