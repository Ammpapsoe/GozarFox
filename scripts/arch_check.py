import os
import struct
import sys

NAMES = {"jvm.dll", "libjvm.so", "libjvm.dylib", "Gozar.exe", "Gozar"}

ELF = {0x3E: "x86_64", 0xB7: "aarch64", 0x03: "x86", 0x28: "arm"}
MACH = {0x01000007: "x86_64", 0x0100000C: "aarch64", 0x07: "x86", 0x0C: "arm"}
PE = {0x8664: "x86_64", 0xAA64: "aarch64", 0x14C: "x86", 0x1C4: "arm"}

ALIASES = {
    "x64": "x86_64",
    "amd64": "x86_64",
    "x86-64": "x86_64",
    "x86_64": "x86_64",
    "arm64": "aarch64",
    "aarch64": "aarch64",
}


def classify(path):
    try:
        with open(path, "rb") as handle:
            head = handle.read(4096)
    except OSError:
        return None
    if len(head) < 64:
        return None
    if head[:4] == b"\x7fELF":
        machine = struct.unpack_from("<H", head, 18)[0]
        return ELF.get(machine, "unknown")
    magic = struct.unpack_from("<I", head, 0)[0]
    if magic in (0xFEEDFACF, 0xFEEDFACE):
        cpu = struct.unpack_from("<I", head, 4)[0]
        return MACH.get(cpu, "unknown")
    if head[:2] == b"MZ":
        offset = struct.unpack_from("<I", head, 0x3C)[0]
        if offset + 6 > len(head):
            return None
        if head[offset:offset + 4] != b"PE\0\0":
            return None
        machine = struct.unpack_from("<H", head, offset + 4)[0]
        return PE.get(machine, "unknown")
    return None


def main():
    if len(sys.argv) < 3:
        print("usage: arch_check.py <root> <expected>")
        return 2
    root = sys.argv[1]
    expected = ALIASES.get(sys.argv[2].lower(), sys.argv[2].lower())
    seen = 0
    bad = 0
    for base, _, files in os.walk(root):
        for name in files:
            if name not in NAMES:
                continue
            path = os.path.join(base, name)
            found = classify(path)
            if found is None:
                continue
            seen += 1
            state = "ok" if found == expected else "MISMATCH"
            if found != expected:
                bad += 1
            print("%-9s %-12s %s" % (state, found, path))
    if seen == 0:
        print("FAIL no binaries inspected under %s" % root)
        return 1
    print("%d binaries inspected, %d wrong, expected %s" % (seen, bad, expected))
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
