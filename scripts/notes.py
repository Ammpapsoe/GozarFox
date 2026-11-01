import base64
import sys
from urllib.parse import urlencode

version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else 'bardia049b-stack/Gozar1'
android = sys.argv[3] if len(sys.argv) > 3 else version
tag = sys.argv[4] if len(sys.argv) > 4 else f'v{version}'
base = f'https://github.com/{repo}/releases/download/{tag}/'
windows_svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><path fill="white" d="M0 0h7v7H0zm9 0h7v7H9zM0 9h7v7H0zm9 0h7v7H9z"/></svg>'
windows_logo = 'data:image/svg+xml;base64,' + base64.b64encode(windows_svg.encode()).decode()


def badge(label, value, colour, logo, filename):
    query = urlencode({'logo': logo, 'logoColor': 'white'})
    return f'[![{label} {value}](https://img.shields.io/badge/{label}-{value}-{colour}?{query})]({base}{filename})'


def stack(*items):
    return '<br>'.join(items)


apk = f'Gozar_{android}_'
rows = [
    ('**Android**', stack(
        badge('APK', 'Universal', '1a7f37', 'android', apk + 'universal.apk'),
        badge('APK', 'ARM64', '2da44e', 'android', apk + 'arm64-v8a.apk'),
        badge('APK', 'ARMv7', '2da44e', 'android', apk + 'armeabi-v7a.apk'),
        badge('APK', 'x86__64', '2da44e', 'android', apk + 'x86_64.apk'),
        badge('APK', 'x86', '2da44e', 'android', apk + 'x86.apk'))),
    ('**Windows**', stack(
        badge('Installer', 'EXE__x64', '0550ae', windows_logo, 'Gozar-Windows-x64-Setup.exe'),
        badge('Portable', 'ZIP__x64', '0550ae', windows_logo, 'Gozar-Windows-x64.zip'))),
    ('**Linux**', stack(
        badge('Package', 'DEB__amd64', 'e36209', 'linux', 'Gozar-Linux-amd64.deb'),
        badge('Portable', 'TAR.XZ__amd64', 'e36209', 'linux', 'Gozar-Linux-amd64.tar.xz'))),
    ('**macOS**', stack(
        badge('TAR.XZ', 'Apple__Silicon', '24292f', 'apple', 'Gozar-macOS-ARM.tar.xz'),
        badge('TAR.XZ', 'Intel', '24292f', 'apple', 'Gozar-macOS-Intel.tar.xz'))),
    ('**Separate cores**', stack(
        badge('Core', 'Windows__x64', '4b4b4b', windows_logo, 'Gozar-Core-Windows-x64.zip'),
        badge('Core', 'Windows__ARM64', '4b4b4b', windows_logo, 'Gozar-Core-Windows-arm64.zip'),
        badge('Core', 'Linux__amd64', '4b4b4b', 'linux', 'Gozar-Core-Linux-amd64.tar.xz'),
        badge('Core', 'Linux__ARM64', '4b4b4b', 'linux', 'Gozar-Core-Linux-arm64.tar.xz'),
        badge('Core', 'macOS__Intel', '4b4b4b', 'apple', 'Gozar-Core-macOS-Intel.tar.xz'),
        badge('Core', 'macOS__ARM64', '4b4b4b', 'apple', 'Gozar-Core-macOS-ARM.tar.xz'))),
]

print(f'# Gozar Android {android} / Desktop {version}')
print()
print('Firefox-only Gozar for Android, Windows, macOS and Linux. Earlier releases remain available.')
print()
print('| System | Download |')
print('|:--:|:--|')
for name, downloads in rows:
    print(f'| {name} | {downloads} |')
print()
print('The Windows EXE and Linux DEB include the app, native core, libraries and licenses. '
      'Portable archives are also available. Separate core downloads are native developer components, not graphical apps. '
      'Desktop downloads are individually limited to under 17 MB and do not include a JVM.')
print()
print('## Added')
print()
print('- **Android protection:** system Always-on VPN and Block connections without VPN settings, '
      'with available system status and automatic retry. Direct routes are disabled under lockdown and on Android 9 or earlier, '
      'where lockdown status is not available; LAN bypass covers TCP only.')
print('- **Android DNS:** choose an encrypted DNS provider or a custom HTTPS endpoint. '
      'Optional AdGuard DNS filtering blocks many advertising, tracking and malicious domains; '
      'it cannot block every ad or traffic using its own DNS or bypassing the VPN.')
print('- **Android connection information:** an ongoing notification, live upload/download rates, '
      'session totals and a traffic chart. Supported Android versions may promote the notification; '
      'Xiaomi Focus/Super Island requires a compatible device, Xiaomi authorization and an app ID; '
      'an app ID is not configured for this release.')
print('- **Desktop:** a compact Windows window with custom controls, server flags and traffic rates; '
      'Linux and macOS retain their native window frames. Settings are organized into sections, '
      'with a brief visual refresh across the apps.')
print('- Improved relay startup verification and proxy connection cleanup.')
print()
print('## Platform notes')
print()
print('Android lockdown is controlled by Android, not an in-app switch, and system or per-app exceptions can apply. '
      'Root routing remains experimental. The new Android features and Xiaomi presentation have not been verified on a physical device.')
print()
print('Linux and macOS still require suitable privileges for the desktop TUN; abrupt termination can leave routes or DNS changed. '
      'Windows is not Authenticode-signed and macOS is not notarized. '
      'Local mock-relay tests do not establish interoperability with a real Firefox server.')
print()
print('Android updates must use the existing release signing key. Downloads are accompanied by detached OpenPGP signatures '
      'and the verification key.')
