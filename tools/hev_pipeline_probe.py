#!/usr/bin/env python3
"""Local Linux TUN/native-hev diagnostic; NOT an Android-device or WAN benchmark.

Runs only in an isolated network namespace. Compares sequential and pipelined
SOCKS handshakes with a strict authenticated loopback peer, exercises TCP and
UDP, and checks that bad credentials never reach a CONNECT handler. The mock
peer is not Gozar's JVM proxy; its parser is covered separately by unit tests.
"""
import fcntl
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

TUNSETIFF = 0x400454CA
IFF_TUN_NO_PI = 0x0001 | 0x1000
TCP_HOST = '198.18.0.80'
UDP_HOST = '198.18.0.81'
SECRET = b'local-test-secret'


def receive(conn, count):
    result = bytearray()
    while len(result) < count:
        part = conn.recv(count - len(result))
        if not part:
            raise EOFError('unexpected EOF')
        result.extend(part)
    return bytes(result)


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


class Peer:
    def __init__(self):
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(32)
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]
        self.stopped = threading.Event()
        self.lock = threading.Lock()
        self.connects = 0
        self.rejections = 0
        self.errors = []
        self.workers = []
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        while not self.stopped.is_set():
            try:
                conn, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            worker = threading.Thread(target=self.serve, args=(conn,), daemon=True)
            self.workers.append(worker)
            worker.start()

    def serve(self, conn):
        try:
            conn.settimeout(5)
            version, methods = receive(conn, 2)
            assert version == 5 and b'\x02' in receive(conn, methods), 'auth method'
            conn.sendall(b'\x05\x02')
            version, userlen = receive(conn, 2)
            user = receive(conn, userlen)
            passlen = receive(conn, 1)[0]
            password = receive(conn, passlen)
            if version != 1 or user != SECRET or password != SECRET:
                with self.lock:
                    self.rejections += 1
                conn.sendall(b'\x01\x01')
                return
            conn.sendall(b'\x01\x00')
            version, command, reserved, address_type = receive(conn, 4)
            assert version == 5 and reserved == 0 and address_type == 1, 'request'
            host = socket.inet_ntoa(receive(conn, 4))
            port = struct.unpack('!H', receive(conn, 2))[0]
            if command == 1:
                assert (host, port) == (TCP_HOST, 443), (host, port)
                with self.lock:
                    self.connects += 1
                conn.sendall(b'\x05\x00\x00\x01' + b'\x00' * 6)
                while True:
                    data = conn.recv(65536)
                    if not data:
                        return
                    conn.sendall(data)
            elif command == 3:
                self.serve_udp(conn)
            else:
                raise AssertionError('unexpected command %d' % command)
        except (EOFError, ConnectionResetError, BrokenPipeError, socket.timeout):
            pass
        except Exception as error:
            with self.lock:
                self.errors.append(repr(error))
        finally:
            conn.close()

    def serve_udp(self, conn):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.bind(('127.0.0.1', 0))
            udp.settimeout(0.2)
            port = udp.getsockname()[1]
            conn.sendall(b'\x05\x00\x00\x01' + socket.inet_aton('127.0.0.1') + struct.pack('!H', port))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and not self.stopped.is_set():
                try:
                    frame, source = udp.recvfrom(65535)
                except socket.timeout:
                    continue
                assert len(frame) >= 10 and frame[:4] == b'\x00\x00\x00\x01', 'UDP SOCKS frame'
                assert socket.inet_ntoa(frame[4:8]) == UDP_HOST, 'UDP destination'
                assert struct.unpack('!H', frame[8:10])[0] == 443, 'UDP port'
                udp.sendto(frame, source)
                return

    def stop(self):
        self.stopped.set()
        self.listener.close()
        self.thread.join(2)
        for worker in self.workers:
            worker.join(6)
        if self.errors:
            raise AssertionError('peer errors: %r' % self.errors)


def write_config(path, port, pipeline, credential):
    with open(path, 'w', encoding='ascii') as out:
        out.write('tunnel:\n  mtu: 1280\nsocks5:\n  address: 127.0.0.1\n')
        out.write("  port: %d\n  username: '%s'\n  password: '%s'\n" % (port, credential, credential))
        out.write("  udp: 'udp'\n  pipeline: %s\nmisc:\n  log-level: warn\n" % ('true' if pipeline else 'false'))
    os.chmod(path, 0o600)


def scenario(executable, tun_fd, peer, pipeline, valid):
    with tempfile.TemporaryDirectory(prefix='gozar-hev-probe-') as directory:
        config = os.path.join(directory, 'config.yml')
        write_config(config, peer.port, pipeline, SECRET.decode() if valid else 'wrong-secret')
        proc = subprocess.Popen([executable, config, str(tun_fd)], pass_fds=(tun_fd,), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            time.sleep(0.5)
            if proc.poll() is not None:
                raise AssertionError('native engine exited: %s' % proc.stderr.read().decode(errors='replace'))
            samples = []
            initial = peer.connects
            initial_rejections = peer.rejections
            if valid:
                for _ in range(5):
                    start = time.monotonic()
                    with socket.create_connection((TCP_HOST, 443), timeout=5) as client:
                        client.settimeout(5)
                        client.sendall(b'probe-over-native-hev')
                        assert receive(client, 21) == b'probe-over-native-hev'
                    samples.append((time.monotonic() - start) * 1000)
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                    client.settimeout(5)
                    client.sendto(b'udp-over-native-hev', (UDP_HOST, 443))
                    assert client.recv(512) == b'udp-over-native-hev'
                assert peer.connects - initial == 5, 'TCP requests not observed'
                print('pipeline=%s TCP/UDP PASS; local connect+echo ms=%s' % (pipeline, ','.join('%.2f' % x for x in samples)), flush=True)
            else:
                with socket.create_connection((TCP_HOST, 443), timeout=5) as client:
                    client.settimeout(5)
                    client.sendall(b'must-not-forward')
                    try:
                        answer = client.recv(128)
                    except (socket.timeout, ConnectionResetError):
                        answer = b''
                    assert answer != b'must-not-forward', 'unauthenticated data forwarded'
                assert peer.rejections > initial_rejections and peer.connects == initial, 'authentication failure was not enforced'
                print('pipeline=%s bad credentials rejected before CONNECT' % pipeline, flush=True)
        finally:
            proc.terminate()
            try:
                _, output = proc.communicate(timeout=4)
            except subprocess.TimeoutExpired:
                proc.kill()
                _, output = proc.communicate()
            if output:
                print('native log:', output.decode(errors='replace')[-1500:], flush=True)


def main():
    if len(sys.argv) != 2 or os.geteuid() != 0:
        raise SystemExit('usage: sudo unshare -n python3 hev_pipeline_probe.py ABSOLUTE_HARNESS')
    run('ip', 'link', 'set', 'lo', 'up')
    tun_fd = os.open('/dev/net/tun', os.O_RDWR)
    fcntl.ioctl(tun_fd, TUNSETIFF, struct.pack('16sH', b'hevpipeline0', IFF_TUN_NO_PI))
    run('ip', 'addr', 'add', '10.111.0.2/32', 'dev', 'hevpipeline0')
    run('ip', 'link', 'set', 'hevpipeline0', 'mtu', '1280', 'up')
    for host in (TCP_HOST, UDP_HOST):
        run('ip', 'route', 'add', host + '/32', 'dev', 'hevpipeline0')
    peer = Peer()
    try:
        for pipeline in (False, True):
            scenario(sys.argv[1], tun_fd, peer, pipeline, True)
            scenario(sys.argv[1], tun_fd, peer, pipeline, False)
    finally:
        peer.stop()
        os.close(tun_fd)
    print('PASS: Linux native test only; Android device and WAN remain untested')


if __name__ == '__main__':
    main()
