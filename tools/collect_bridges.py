#!/usr/bin/env python3
import argparse
import datetime
import hashlib
import ipaddress
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

MOAT = "https://bridges.torproject.org/moat/circumvention/builtin"
COMMUNITIES = {
    "community-delta-kronecker": "https://raw.githubusercontent.com/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/{}_tested.txt",
    "community-onionhop": "https://raw.githubusercontent.com/center2055/OnionHop-Bridges-Collector/main/bridge/{}_tested.txt",
}
CERT = re.compile(r"[A-Za-z0-9+/]{86}(?:==)?\Z")
FINGERPRINT = re.compile(r"[0-9A-Fa-f]{40}\Z")
VERSION = re.compile(r"[A-Za-z0-9.+_-]{1,16}\Z")
DOMAIN = re.compile(r"[a-z0-9-]+\Z")
PATH = re.compile(r"[A-Za-z0-9\-._~/!$&'()*+,;=:@%]+\Z")


def public_ipv4(value):
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError:
        return False
    return address.is_global and not any((address.is_private, address.is_loopback, address.is_link_local, address.is_multicast, address.is_reserved, address.is_unspecified))


def endpoint(value):
    if value.count(":") != 1:
        return None
    host, port_text = value.rsplit(":", 1)
    if not public_ipv4(host) or not port_text.isdigit():
        return None
    port = int(port_text)
    if not 1 <= port <= 65535:
        return None
    return f"{host}:{port}"


def valid_webtunnel_url(value):
    if not 1 <= len(value) <= 1024 or any(ord(c) < 33 or ord(c) > 126 or c in '"\\' for c in value):
        return False
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError:
        return False
    if parsed.scheme != "https" or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment:
        return False
    if port == 0 or not parsed.path or len(parsed.path) > 768 or not PATH.fullmatch(parsed.path):
        return False
    if "%2f" in parsed.path.lower() or "%5c" in parsed.path.lower():
        return False
    host = (parsed.hostname or "").lower().rstrip(".")
    if not host or len(host) > 253 or host == "localhost":
        return False
    if re.fullmatch(r"[0-9.]+", host):
        return public_ipv4(host)
    if ":" in host:
        return False
    labels = host.split(".")
    return len(labels) >= 2 and all(1 <= len(label) <= 63 and not label.startswith("-") and not label.endswith("-") and DOMAIN.fullmatch(label) for label in labels)


def canonical_bridge(raw):
    text = raw.strip()
    if not text or len(text) > 2048 or any(ord(c) < 32 or ord(c) == 127 for c in text):
        return None
    parts = text.split()
    if len(parts) == 5 and parts[0] == "obfs4":
        end = endpoint(parts[1])
        fingerprint = parts[2].upper() if FINGERPRINT.fullmatch(parts[2]) else None
        certificate = parts[3][5:] if parts[3].startswith("cert=") else ""
        mode = parts[4][9:] if parts[4].startswith("iat-mode=") else ""
        if end and fingerprint and CERT.fullmatch(certificate) and mode in ("0", "1", "2"):
            return f"obfs4 {end} {fingerprint} cert={certificate} iat-mode={mode}"
    if len(parts) in (4, 5) and parts[0] == "webtunnel":
        end = endpoint(parts[1])
        fingerprint = parts[2].upper() if FINGERPRINT.fullmatch(parts[2]) else None
        url = parts[3][4:] if parts[3].startswith("url=") else ""
        version = parts[4][4:] if len(parts) == 5 and parts[4].startswith("ver=") else ""
        if len(parts) == 5 and not version:
            return None
        if end and fingerprint and valid_webtunnel_url(url) and (len(parts) == 4 or VERSION.fullmatch(version)):
            suffix = f" ver={version}" if version else ""
            return f"webtunnel {end} {fingerprint} url={url}{suffix}"
    return None


def download(url, limit=2_097_152):
    request = urllib.request.Request(url, headers={"User-Agent": "Gozar signed bridge collector", "Accept": "application/json,text/plain", "Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=25) as response:
        if response.status != 200 or response.geturl() != url:
            raise ValueError("unexpected source response")
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("source exceeds size limit")
    return data


def collect(fetch=download, now=None):
    moment = datetime.datetime.now(datetime.timezone.utc) if now is None else now
    official = {"obfs4": set(), "webtunnel": set()}
    community = {"obfs4": set(), "webtunnel": set()}
    sources = []
    counts = {}
    try:
        document = json.loads(fetch(MOAT).decode("utf-8"))
        if not isinstance(document, dict):
            raise ValueError("invalid MOAT document")
        accepted = 0
        for transport in official:
            values = document.get(transport, [])
            if not isinstance(values, list):
                continue
            for raw in values[:500]:
                if not isinstance(raw, str):
                    continue
                line = canonical_bridge(raw)
                if line:
                    official[transport].add(line)
                    accepted += 1
        if accepted:
            sources.append("tor-moat-builtin")
        counts["tor-moat-builtin"] = accepted
    except Exception as error:
        counts["tor-moat-builtin"] = 0
        print(f"source unavailable: tor-moat-builtin ({type(error).__name__})")
    for source, template in COMMUNITIES.items():
        accepted = 0
        for transport in community:
            try:
                text = fetch(template.format(transport)).decode("utf-8")
                for raw in text.splitlines()[:5000]:
                    line = canonical_bridge(raw)
                    if line:
                        community[transport].add(line)
                        accepted += 1
            except Exception as error:
                print(f"source unavailable: {source}/{transport} ({type(error).__name__})")
        if accepted:
            sources.append(source)
        counts[source] = accepted
    day = moment.date().isoformat()
    selected = []
    for transport, limit in (("obfs4", 60), ("webtunnel", 20)):
        pool = sorted(official[transport])
        rotating = sorted(community[transport] - official[transport], key=lambda line: hashlib.sha256((day + "\n" + line).encode()).digest())
        selected.extend((pool + rotating)[:limit])
    if not selected:
        raise ValueError("no valid public bridges were collected")
    issued = int(moment.timestamp())
    document = {
        "schema": 1,
        "issued_at": issued,
        "expires_at": issued + 36 * 3600,
        "sources": sources,
        "bridges": selected,
    }
    return document, counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("packaging/bridges.json"))
    args = parser.parse_args()
    document, counts = collect()
    encoded = (json.dumps(document, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n").encode()
    if len(encoded) > 1_048_576:
        raise ValueError("signed feed exceeds application limit")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)
    print("accepted counts: " + ", ".join(f"{name}={counts[name]}" for name in sorted(counts)))
    print(f"signed-feed candidates: {len(document['bridges'])}")


if __name__ == "__main__":
    main()
