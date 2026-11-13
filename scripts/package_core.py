import io
import os
import pathlib
import sys
import tarfile
import zipfile

binary, destination, platform, arch = sys.argv[1:5]
root = pathlib.Path(__file__).resolve().parent.parent
binary = pathlib.Path(binary)
destination = pathlib.Path(destination)
name = 'gozar-core.exe' if platform == 'windows' else 'gozar-core'
notice = f'''Gozar desktop core — {platform} {arch}

This archive contains the native Go tunnel core, not the graphical Gozar application.
It is intended for integration with Gozar's JSON-lines --rpc interface and requires
an account/session and appropriate operating-system privileges for TUN operation.
The core does not enable a system proxy by default. Please read the accompanying
licenses and the project source before integrating or redistributing it.

HTTP/3 is opportunistic and depends on the selected proxy endpoint and network.
The Firefox proxy capabilities and destination protocols can be more limited.
'''.encode()
files = [(binary, name)]
for license_name in ('LICENSE', 'NOTICE', 'WINTUN-LICENSE.txt', 'QUIC-GO-LICENSE.txt', 'QPACK-LICENSE.txt', 'GO-DEPS-LICENSES.txt'):
    files.append((root / license_name, license_name))
destination.parent.mkdir(parents=True, exist_ok=True)
if destination.name.endswith('.zip'):
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for source, target in files:
            archive.write(source, target)
        archive.writestr('CORE-README.txt', notice)
elif destination.name.endswith('.tar.xz'):
    with tarfile.open(destination, 'w:xz', preset=9) as archive:
        for source, target in files:
            archive.add(source, arcname=target)
        info = tarfile.TarInfo('CORE-README.txt')
        info.size = len(notice)
        info.mode = 0o644
        archive.addfile(info, io.BytesIO(notice))
else:
    raise SystemExit('Unsupported archive format')
if os.path.getsize(destination) >= 17_000_000:
    raise SystemExit('Core archive exceeds the 17 MB download limit')
