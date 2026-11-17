import hashlib
import io
import json
import os
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import time


VERSION = sys.argv[1]
OUTPUT = pathlib.Path(sys.argv[2])
ROOT = pathlib.Path(__file__).resolve().parent.parent

ARCHES = {
    'amd64': 'freebsd:14:x86:64',
    'arm64': 'freebsd:14:aarch64:64',
}

README = '''Gozar on FreeBSD
================

This package installs the headless Go core and an rc.d script. It does not
contain the Flutter desktop application.

Sign in once:

  sudo env XDG_CONFIG_HOME=/var/db/gozar gozar-core --email name@example.com --password 'the password'

Enable and start the service:

  sysrc gozar_enable=YES
  service gozar start

The service keeps the tunnel up and restores the routes and DNS servers it
changed when it stops. The tunnel needs /dev/tun, which the rc script loads
through kldload if_tun.
'''


def build(binary: pathlib.Path, arch: str, output: pathlib.Path) -> None:
    files = {
        '/usr/local/bin/gozar-core': (binary, 0o755),
        '/usr/local/etc/rc.d/gozar': (ROOT / 'packaging/freebsd/gozar.rc', 0o755),
        '/usr/local/share/gozar/README': (None, 0o644),
        '/usr/local/share/licenses/gozar/LICENSE': (ROOT / 'LICENSE', 0o644),
        '/usr/local/share/licenses/gozar/NOTICE': (ROOT / 'NOTICE', 0o644),
        '/usr/local/share/licenses/gozar/QUIC-GO-LICENSE.txt': (ROOT / 'QUIC-GO-LICENSE.txt', 0o644),
        '/usr/local/share/licenses/gozar/QPACK-LICENSE.txt': (ROOT / 'QPACK-LICENSE.txt', 0o644),
        '/usr/local/share/licenses/gozar/GO-DEPS-LICENSES.txt': (ROOT / 'GO-DEPS-LICENSES.txt', 0o644),
    }

    entries = {}
    flatsize = 0
    for target, (source, mode) in files.items():
        if source is None:
            payload = README.encode()
        else:
            payload = source.read_bytes()
        entries[target] = (payload, mode, hashlib.sha256(payload).hexdigest())
        flatsize += len(payload)

    manifest = {
        'name': 'gozar',
        'version': VERSION,
        'origin': 'net/gozar',
        'comment': 'Firefox Private Network client',
        'arch': ARCHES[arch],
        'www': 'https://github.com/bardia049b-stack/Gozar',
        'maintainer': 'noreply@github.com',
        'prefix': '/usr/local',
        'licenselogic': 'single',
        'licenses': ['AGPL-3.0-or-later'],
        'flatsize': flatsize,
        'desc': (
            'Gozar builds a tunnel to the Firefox Private Network proxy and keeps'
            ' the system behind it. This package carries the headless Go core and'
            ' an rc.d script; the graphical application is a separate desktop build.'
        ),
        'categories': ['net'],
        'files': {path: data[2] for path, data in entries.items()},
        'dirs': [
            '/usr/local/bin',
            '/usr/local/etc/rc.d',
            '/usr/local/share/gozar',
            '/usr/local/share/licenses/gozar',
        ],
    }

    compact = dict(manifest)
    for key in ('files', 'dirs', 'scripts', 'users', 'groups'):
        compact.pop(key, None)

    def manifest_bytes(document):
        return json.dumps(document, indent=2, sort_keys=True).encode() + b'\n'

    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, 'w:xz', preset=9) as archive:
        for name, payload in (
            ('+COMPACT_MANIFEST', manifest_bytes(compact)),
            ('+MANIFEST', manifest_bytes(manifest)),
        ):
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            info.mode = 0o644
            info.mtime = int(time.time())
            info.uid = 0
            info.gid = 0
            info.uname = 'root'
            info.gname = 'wheel'
            archive.addfile(info, io.BytesIO(payload))
        for target, (payload, mode, _) in entries.items():
            info = tarfile.TarInfo(target.lstrip('/'))
            info.size = len(payload)
            info.mode = mode
            info.mtime = int(time.time())
            info.uid = 0
            info.gid = 0
            info.uname = 'root'
            info.gname = 'wheel'
            archive.addfile(info, io.BytesIO(payload))


def main() -> None:
    names = {'amd64': 'Gozar-FreeBSD-amd64.pkg', 'arm64': 'Gozar-FreeBSD-arm64.pkg'}
    with tempfile.TemporaryDirectory() as work:
        for arch in ('amd64', 'arm64'):
            binary = pathlib.Path(work) / f'gozar-core-{arch}'
            env = dict(os.environ)
            env.update({
                'GOOS': 'freebsd',
                'GOARCH': arch,
                'CGO_ENABLED': '0',
                'GOFLAGS': '-mod=mod',
            })
            local_go = pathlib.Path.home() / '.cache/tools/go/bin'
            if (local_go / 'go').exists():
                env['PATH'] = str(local_go) + ':' + env.get('PATH', '/usr/local/bin:/usr/bin:/bin')
            subprocess.run(
                [
                    'go', 'build', '-trimpath', '-buildvcs=false', '-ldflags=-s -w',
                    '-o', str(binary), './cmd/gozar',
                ],
                cwd=ROOT / 'go-desktop',
                check=True,
                env=env,
            )
            build(binary, arch, OUTPUT / names[arch])
            print(OUTPUT / names[arch])


if __name__ == '__main__':
    main()
