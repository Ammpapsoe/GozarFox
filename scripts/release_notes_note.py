#!/usr/bin/env python3
"""Append the account-validation disclosure to a release body.

A stable tag is published either with automated Apps Script evidence or with an
explicit manual approval (the workflow's `manual_approve` input). Either way the
release body has to state which one applied, because the difference matters to
anyone deciding whether to trust the build.
"""
import argparse
import sys

MANUAL = """
---

> **تست خودکار ورود (Apps Script) برای این نسخه اجرا نشد.** این نسخه با تأیید دستی (Manual Approval) به عنوان Stable منتشر شده است.
>
> **The automated Apps Script login test was not run for this release.** This version was published as Stable with manual approval.
>
> This approval does not imply that account sign-in, physical-device networking, or real-world performance was independently verified for this commit. Consult the release validation results for the tests actually performed.
"""

AUTOMATED = """
---

> Automated Apps Script account validation passed for this commit: sign-in with Pass and signup code delivery.
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--manual-approval", action="store_true")
    group.add_argument("--automated", action="store_true")
    args = parser.parse_args()
    sys.stdout.write(MANUAL if args.manual_approval else AUTOMATED)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
