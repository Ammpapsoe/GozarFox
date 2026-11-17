#!/usr/bin/env python3
"""Validate release-history provenance for a proposed Android forward recovery.

A successful check proves version ordering and Git ancestry only. It does NOT
prove that code behavior was restored, that migrations are compatible, or that
an APK can be installed on every device.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

SHA = re.compile(r"[0-9a-f]{40}\Z")
VERSION = re.compile(r"[0-9]{1,4}(?:\.[0-9]{1,4}){2}\Z")


def run(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError("Git validation failed: " + " ".join(args[:2]))
    return result.stdout.strip()


def unique_object(pairs):
    output = {}
    for key, value in pairs:
        if key in output:
            raise ValueError("duplicate plan field")
        output[key] = value
    return output


def read_build(repo, commit):
    text = run(repo, "show", f"{commit}:app/build.gradle")
    def one(pattern, label):
        matches = re.findall(pattern, text, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError(f"cannot determine one {label} at {commit}")
        return matches[0]
    version = one(r"^\s*versionName\s+'([^']+)'\s*$", "versionName")
    code = int(one(r"^\s*versionCode\s+(\d+)\s*$", "versionCode"))
    package = one(r"^\s*applicationId\s+'([^']+)'\s*$", "applicationId")
    if not VERSION.fullmatch(version) or not 0 < code <= 2_100_000_000:
        raise ValueError("invalid version or versionCode in build")
    return version, code, package


def validate(repo, plan_path):
    with open(plan_path, encoding="utf-8") as handle:
        plan = json.load(handle, object_pairs_hook=unique_object)
    if not isinstance(plan, dict) or set(plan) != {"known_good_commit", "affected_commit"}:
        raise ValueError("plan must contain exactly known_good_commit and affected_commit")
    good, bad = plan["known_good_commit"], plan["affected_commit"]
    if not isinstance(good, str) or not isinstance(bad, str) or not SHA.fullmatch(good) or not SHA.fullmatch(bad) or good == bad:
        raise ValueError("plan commits must be distinct full lowercase SHA-1s")
    for commit in (good, bad):
        if run(repo, "rev-parse", "--verify", f"{commit}^{{commit}}") != commit:
            raise ValueError("invalid commit")
    head = run(repo, "rev-parse", "--verify", "HEAD^{commit}")
    if head == bad:
        raise ValueError("a recovery must be a new commit")
    run(repo, "merge-base", "--is-ancestor", good, bad)
    run(repo, "merge-base", "--is-ancestor", bad, head)
    good_version, good_code, good_package = read_build(repo, good)
    bad_version, bad_code, bad_package = read_build(repo, bad)
    new_version, new_code, new_package = read_build(repo, head)
    numeric = lambda value: tuple(map(int, value.split(".")))
    if not (numeric(good_version) < numeric(bad_version) < numeric(new_version)):
        raise ValueError("recovery versionName must exceed affected and known-good versions")
    if not (good_code < bad_code < new_code):
        raise ValueError("recovery versionCode must exceed affected and known-good codes")
    if good_package != "com.gozar" or bad_package != good_package or new_package != good_package:
        raise ValueError("applicationId changed across recovery history")
    return {"affected_version": bad_version, "affected_code": bad_code, "affected_commit": bad,
            "restores_version": good_version, "restores_code": good_code, "restores_commit": good,
            "recovery_code": new_code}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=".")
    parser.add_argument("--plan", required=True)
    args = parser.parse_args()
    repo = Path(args.repo).resolve()
    path = Path(args.plan)
    if not path.is_absolute():
        path = repo / path
    try:
        print(json.dumps(validate(repo, path), sort_keys=True, separators=(",", ":")))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"refusing forward recovery: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
