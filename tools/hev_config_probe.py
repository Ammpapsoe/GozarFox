#!/usr/bin/env python3
"""End-to-end proof for the Android tunnel defect, run against the real engine.

Reproduces what the Android app does:

  1. write the hev-socks5-tunnel document,
  2. (defect) chmod it so its owner can no longer read it,
  3. run the real hev-socks5-tunnel binary against a real tun fd and a fake
     SOCKS5 proxy listening on loopback,
  4. push a TCP flow into the tun device and see whether it reaches the proxy.

Run inside an isolated network namespace so no host route is touched:

    sudo unshare -n python3 tools/hev_config_probe.py
"""
import fcntl
import os
import socket
import struct
import subprocess
import sys
import threading
import time

TUNSETIFF = 0x400454CA
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000
HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.environ.get("HEV_HARNESS") or os.path.normpath(os.path.join(
    HERE, "..", "app/src/main/jni/hev-socks5-tunnel/bin/hev-socks5-tunnel"))
DESTINATION = "198.18.0.80"
DEST_PORT = 443
UDP_DESTINATION = "198.18.0.81"
UDP_DEST_PORT = 443


def run(*argv, check=True):
    result = subprocess.run(argv, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise SystemExit("%s failed: %s%s" % (" ".join(argv), result.stdout, result.stderr))
    return result


def write_config(path, mtu, port, passphrase):
    document = (
        "tunnel:\n"
        "  mtu: %d\n"
        "socks5:\n"
        "  address: 127.0.0.1\n"
        "  port: %d\n"
        "  username: '%s'\n"
        "  password: '%s'\n"
        "  udp: 'udp'\n"
        "misc:\n"
        "  log-level: warn\n" % (mtu, port, passphrase, passphrase)
    )
    with open(path, "w") as handle:
        handle.write(document)
    return document


def revoke_owner_read(path):
    """Exactly Java's File.setReadable(false, false) on Android."""
    current = os.stat(path).st_mode & 0o777
    os.chmod(path, current & ~0o444)
    return oct(os.stat(path).st_mode & 0o777)


def decode_udp_request(datagram):
    """Return the payload of a SOCKS5 UDP request header, or None."""
    if len(datagram) < 4 or datagram[2] != 0:
        return None
    atyp = datagram[3]
    if atyp == 1:
        offset = 4 + 4 + 2
    elif atyp == 3:
        offset = 4 + 1 + datagram[4] + 2
    elif atyp == 4:
        offset = 4 + 16 + 2
    else:
        return None
    return datagram[offset:]


def echo_udp_reply(request, payload):
    """Reply from the exact address the request named."""
    if request[3] == 1:
        header = request[:10]
    elif request[3] == 3:
        header = request[:7 + request[4]]
    else:
        header = request[:22]
    return header + payload


def _peer_of(datagram):
    atyp = datagram[3]
    if atyp == 1:
        return struct.unpack("!H", datagram[8:10])[0]
    if atyp == 3:
        return struct.unpack("!H", datagram[5 + datagram[4]:7 + datagram[4]])[0]
    return struct.unpack("!H", datagram[20:22])[0]


class FakeSocks(threading.Thread):
    """Minimal SOCKS5 server that records the CONNECT target and echoes."""

    daemon = True

    def __init__(self):
        super().__init__()
        self.listener = socket.socket()
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(8)
        self.port = self.listener.getsockname()[1]
        self.target = None
        self.payloads = []
        self.udp_payloads = []
        self.udp_target = None
        self.raw = []
        self.done = threading.Event()

    def run(self):
        while not self.done.is_set():
            try:
                conn, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def serve(self, conn):
        try:
            conn.settimeout(10)
            greeting = conn.recv(2)
            if len(greeting) < 2:
                return
            conn.recv(greeting[1])
            conn.sendall(b"\x05\x00")
            head = conn.recv(4)
            if len(head) < 4:
                return
            atyp = head[3]
            if atyp == 1:
                host = socket.inet_ntoa(conn.recv(4))
            elif atyp == 3:
                length = conn.recv(1)[0]
                host = conn.recv(length).decode()
            else:
                host = "?"
            port = struct.unpack("!H", conn.recv(2))[0]
            if head[1] == 3:
                # UDP ASSOCIATE: name the relay socket, then drain it so the
                # datagrams hev sends are recorded as received.
                relay = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                relay.bind(("127.0.0.1", 0))
                relay.settimeout(15)
                bound = relay.getsockname()[1]
                conn.sendall(b"\x05\x00\x00\x01" + socket.inet_aton("127.0.0.1")
                             + struct.pack("!H", bound))
                try:
                    while not self.done.is_set():
                        datagram, source = relay.recvfrom(65535)
                        self.raw.append(datagram)
                        payload = decode_udp_request(datagram)
                        if payload is None:
                            continue
                        self.udp_payloads.append(payload)
                        # The reply header must name the address the app sent
                        # to: that pair becomes the packet's source, and it is
                        # what the app's socket is waiting for.
                        relay.sendto(echo_udp_reply(datagram, payload), source)
                except OSError:
                    pass
                finally:
                    relay.close()
                return
            self.target = (host, port)
            conn.sendall(b"\x05\x00\x00\x01" + socket.inet_aton("0.0.0.0") + b"\x00\x00")
            while True:
                chunk = conn.recv(65535)
                if not chunk:
                    break
                self.payloads.append(chunk)
                conn.sendall(chunk)
        except OSError:
            pass
        finally:
            conn.close()

    def stop(self):
        self.done.set()
        self.listener.close()


def open_tun(name="hevtun0"):
    fd = os.open("/dev/net/tun", os.O_RDWR)
    request = struct.pack("16sH", name.encode(), IFF_TUN | IFF_NO_PI)
    fcntl.ioctl(fd, TUNSETIFF, request)
    return fd


def main():
    if os.geteuid() != 0:
        raise SystemExit("run as root inside an isolated netns")
    run("ip", "link", "set", "lo", "up")
    socks = FakeSocks()
    socks.start()
    tun_fd = open_tun()
    run("ip", "addr", "add", "10.111.0.2/32", "dev", "hevtun0")
    run("ip", "link", "set", "hevtun0", "up")
    run("ip", "route", "add", DESTINATION + "/32", "dev", "hevtun0")
    run("ip", "route", "add", UDP_DESTINATION + "/32", "dev", "hevtun0")

    engine = os.path.normpath(ENGINE)
    # The stock binary passes -1 as the tun fd on purpose: on Linux hev opens
    # the device named by tunnel.name itself. Android hands in the fd the
    # VpnService already owns, which the command binary cannot do, so a small
    # harness links the same library and forwards the fd.
    if engine.endswith("hev-socks5-tunnel") and os.environ.get("HEV_FD_MODE") == "1":
        engine = os.path.join(os.path.dirname(engine), "hev-socks5-tunnel-fd")
    scenarios = []
    for label, break_mode in (("owner-readable (fixed)", False), ("owner-unreadable (1.17.x)", True)):
        config = "/tmp/hev-%s.yaml" % ("broken" if break_mode else "fixed")
        write_config(config, 1500, socks.port, "dGVzdA==")
        mode = revoke_owner_read(config) if break_mode else oct(os.stat(config).st_mode & 0o777)
        reachable = bool(int(mode, 8) & 0o400)
        proc = subprocess.Popen(
            [engine, config, str(tun_fd)],
            pass_fds=(tun_fd,),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        time.sleep(1.2)
        relayed = False
        detail = ""
        udp_ok = False
        udp_detail = ""
        if proc.poll() is None:
            socks.target = None
            socks.payloads = []
            client = socket.socket()
            client.settimeout(4)
            try:
                client.connect((DESTINATION, DEST_PORT))
                client.sendall(b"gozar-probe")
                echoed = client.recv(64)
                relayed = echoed == b"gozar-probe" and socks.target == (DESTINATION, DEST_PORT)
                detail = "socks target=%s echo=%r" % (socks.target, echoed)
            except OSError as error:
                detail = "tun connect failed: %s" % error
            finally:
                client.close()
            socks.udp_payloads = []
            datagram = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            datagram.settimeout(4)
            try:
                datagram.sendto(b"gozar-udp-probe", (UDP_DESTINATION, UDP_DEST_PORT))
                echoed, _ = datagram.recvfrom(2048)
                udp_ok = echoed == b"gozar-udp-probe" and socks.udp_payloads == [b"gozar-udp-probe"]
                udp_detail = "udp echo=%r relayed=%r" % (echoed, socks.udp_payloads)
            except OSError as error:
                udp_detail = "tun udp failed: %s" % error
            finally:
                datagram.close()
        else:
            detail = "engine exited with %d before any packet was read" % proc.returncode
        mutex = ""
        proc.terminate()
        try:
            mutex = proc.communicate(timeout=3)[0].decode(errors="replace").strip()
        except subprocess.TimeoutExpired:
            proc.kill()
        run("ip", "route", "flush", "dev", "hevtun0", check=False)
        scenarios.append((label, mode, reachable, relayed, detail, mutex, udp_ok, udp_detail))

    socks.stop()
    os.close(tun_fd)

    print("engine: %s" % os.path.normpath(ENGINE))
    failures = 0
    for label, mode, reachable, relayed, detail, output, udp_ok, udp_detail in scenarios:
        print("\n== %s" % label)
        print("   config mode %s, owner read bit: %s" % (mode, reachable))
        print("   traffic relayed through SOCKS: %s" % relayed)
        print("   %s" % detail)
        print("   UDP relayed through SOCKS ASSOCIATE: %s" % udp_ok)
        print("   %s" % udp_detail)
        if output:
            print("   engine said: %s" % output.replace("\n", " | "))
        expected = not label.startswith("owner-unreadable")
        if reachable != expected:
            failures += 1
            print("   MISMATCH: owner read bit should be %s" % expected)
        if relayed != expected:
            failures += 1
            print("   MISMATCH: expected tcp relayed=%s" % expected)
        if udp_ok != expected:
            failures += 1
            print("   MISMATCH: expected udp relayed=%s" % expected)
    print("\n%s" % ("PASS" if failures == 0 else "FAIL (%d)" % failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
