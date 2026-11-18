import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "tools/validate-forward-recovery.py"


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


class ForwardRecoveryPlanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.name", "CI Fixture")
        git(self.repo, "config", "user.email", "fixture@example.invalid")
        (self.repo / "app").mkdir()
        self.good = self.commit("1.10.0", 36, "good")
        self.bad = self.commit("1.11.0", 37, "affected")
        self.recovery = self.commit("1.12.0", 38, "forward")
        self.plan = self.repo / "plan.json"
        self.write_plan(self.good, self.bad)

    def tearDown(self):
        self.temp.cleanup()

    def commit(self, version, code, message, package="com.gozar"):
        (self.repo / "app/build.gradle").write_text(
            "android {\n    defaultConfig {\n"
            f"        applicationId '{package}'\n"
            f"        versionCode {code}\n"
            f"        versionName '{version}'\n"
            "    }\n}\n", encoding="utf-8")
        git(self.repo, "add", "app/build.gradle")
        git(self.repo, "commit", "-q", "-m", message)
        return git(self.repo, "rev-parse", "HEAD")

    def write_plan(self, good, bad):
        self.plan.write_text(json.dumps({"known_good_commit": good, "affected_commit": bad}), encoding="utf-8")

    def validate(self):
        return subprocess.run(["python3", str(TOOL), "--repo", str(self.repo), "--plan", str(self.plan)],
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_git_ancestry_and_version_codes_generate_exact_signed_fields(self):
        result = self.validate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"affected_version": "1.11.0", "affected_code": 37,
                          "affected_commit": self.bad, "restores_version": "1.10.0",
                          "restores_code": 36, "restores_commit": self.good,
                          "recovery_code": 38}, json.loads(result.stdout))

    def test_reversed_or_unknown_commits_fail(self):
        self.write_plan(self.bad, self.good)
        self.assertNotEqual(0, self.validate().returncode)
        self.write_plan("0" * 40, self.bad)
        self.assertNotEqual(0, self.validate().returncode)

    def test_lower_or_equal_recovery_code_fails(self):
        self.commit("1.12.1", 37, "invalid code")
        self.assertNotEqual(0, self.validate().returncode)

    def test_recovery_must_keep_the_android_package(self):
        self.commit("1.12.1", 39, "wrong package", package="com.other")
        self.assertNotEqual(0, self.validate().returncode)

    def test_duplicate_or_extra_plan_fields_fail(self):
        self.plan.write_text('{"known_good_commit":"' + self.good + '","known_good_commit":"' + self.good + '","affected_commit":"' + self.bad + '"}', encoding="utf-8")
        self.assertNotEqual(0, self.validate().returncode)
        self.plan.write_text(json.dumps({"known_good_commit": self.good, "affected_commit": self.bad, "extra": True}), encoding="utf-8")
        self.assertNotEqual(0, self.validate().returncode)


if __name__ == "__main__":
    unittest.main()
