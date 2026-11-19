"""Rewrite official Ubuntu APT mirrors for CI, without touching other sources."""

from pathlib import Path
import re
import sys

# Hosted runners can use regional/EC2 archive hostnames, not just archive,
# azure.archive, or us.archive. Preserve the path and suite after /ubuntu.
UBUNTU_MIRROR = re.compile(
    r"https?://(?:(?:[a-z0-9-]+\.)*archive\.ubuntu\.com|security\.ubuntu\.com)/ubuntu(?=/|\s|$)",
    re.IGNORECASE,
)


def rewrite_sources(text: str, mirror: str) -> str:
    return UBUNTU_MIRROR.sub(lambda _: mirror, text)


def rewrite_hosts(text: str, host: str, ip: str) -> str:
    lines = [line for line in text.splitlines() if host not in line.split()[1:]]
    lines.append(f"{ip} {host}")
    return "\n".join(lines) + "\n"


def main(mirror: str, host: str, ip: str) -> None:
    for path in Path("/etc/apt").rglob("*"):
        if not path.is_file():
            continue
        try:
            text = path.read_text()
        except (UnicodeDecodeError, PermissionError):
            continue
        updated = rewrite_sources(text, mirror)
        if updated != text:
            path.write_text(updated)
    hosts = Path("/etc/hosts")
    hosts.write_text(rewrite_hosts(hosts.read_text(), host, ip))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: ci_apt_sources.py MIRROR HOST IP")
    main(*sys.argv[1:])
