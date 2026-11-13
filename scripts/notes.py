import base64
import os
from pathlib import Path
import sys
from urllib.parse import urlencode

SECURITY = r"""## Gozar 1.10.4 — انتخاب خودکار دقیق‌تر، مصرف بیکاری کمتر و Kill Switch واقعی

این نسخه روی همان منطق تونل نسخهٔ ۱.۱۰.۳ ساخته شده است: رمزنگاری، ترنسپورت MASQUE، ورود حساب و ساختار پشته تغییر نکرده‌اند. تغییرات به‌صورت افزایشی اضافه شده‌اند و تست‌های قبلی CI را سبز نگه می‌دارند.

### ۱) انتخاب خودکار سرور با Jitter و Packet Loss
اندروید و دسکتاپ حالا هر سرور را سه بار TCP probe می‌کنند و میانگین تأخیر، جیتر (میانگین اختلاف تلاش‌های متوالی) و درصد ازدست‌رفتن تلاش‌ها را می‌سنجند. امتیاز مسیر `avg + 2*jitter + 10*loss` است؛ مسیرهای fail در انتها می‌روند. نتیجه‌ها حدود ۵ دقیقه TTL دارند تا اتصال‌های پشت‌سرهم ترافیک probe اضافی نسازند. توجه: «packet loss» اینجا نرخ شکست تلاش اتصال TCP است، نه اندازه‌گیری ICMP/UDP packet loss واقعی.

### ۲) مصرف بیکاری کمتر
در اندروید renewal فقط وقتی down/up تغییر کرده باشد یا ترافیک تازه‌ای دیده شده باشد درخواست می‌فرستد؛ اگر توکن نزدیک انقضا باشد، اولین dial بعدی به‌صورت lazy آن را تازه می‌کند. `detectExit` فقط یک بار پس از اتصال اجرا می‌شود. در دسکتاپ نیز renewal در بیکاری skip می‌شود و `currentPass` فقط هنگام نیاز تازه‌سازی می‌کند. تست soak پنج‌دقیقه‌ای در CI بررسی می‌کند اتصال idle هیچ probe/upstream اضافی نمی‌سازد.

### ۳) MTU پویا و Window Scaling
`FoxVpnService` مقدار MTU را بر اساس شبکه می‌دهد (Wi-Fi ۱۴۰۰، Cellular ۱۲۸۰) و `FoxStack` MSS را از همان MTU می‌سازد. SYN-ACK حالا گزینهٔ Window Scale با shift=7 می‌فرستد و پنجرهٔ مؤثر با shift محاسبه می‌شود؛ MSS اعلامی همتا نیز در قطعه‌بندی رعایت می‌شود.

### ۴) Kill Switch واقعی در دسکتاپ
لینوکس از nftables (`inet gozar`)، مک از pf anchor و ویندوز از Windows Firewall با PowerShell استفاده می‌کند. در connect، پس از ساخت اینترفیس، خروجی پیش‌فرض بسته می‌شود و فقط اینترفیس تونل، IPهای پروکسی، DHCP/LAN و در حالت split مسیرهای ایرانی مجاز می‌مانند؛ در teardown قوانین پاک و سیاست قبلی بازگردانده می‌شود. اگر فعال‌سازی kill switch شکست بخورد، اتصال بالا نمی‌آید. اندروید همچنان Always-on/lockdown را اجبار می‌کند و در lockdown مسیر مستقیم را می‌بندد.

### ۵) Split-Tunneling هوشمند
دامنه‌های `.ir` و سرویس‌های ایرانی غیر `.ir` (مثل aparat.com، digikala.com، varzesh3.com، بانک‌ها و اپراتورها) همیشه مستقیم می‌روند؛ دامنه‌های خارجی مثل google.com و youtube.com همیشه از تونل. دسکتاپ از `internal/geo` با لیست دامنه + GeoIP ایران و یادگیری IP→domain از پاسخ‌های DNS استفاده می‌کند و برای مقصد مستقیم host-route می‌سازد. اندروید از `FoxGeo`/`FoxRoute` با همان لیست استفاده می‌کند. لیست `packaging/ir-domains.txt` به‌صورت دوره‌ای از مخزن به‌روزرسانی می‌شود و لیست نامعتبر جایگزین لیست سالم نمی‌شود.

### ۶) خطا و دیباگ دقیق‌تر
هر سرور fail شده در connect با دلیل log می‌شود و پیام خطای کاربر شامل نام/دلیل سرور است. `soTimeout=0` در مسیرهای تونل به timeout مشخص ۱۵۰۰۰ میلی‌ثانیه تبدیل شد تا اتصال مرده برای همیشه باز نماند.

### اعتبارسنجی
تست‌های جدید: انتخاب خودکار بهترین مسیر، failover، idle renewal، soak پنج‌دقیقه‌ای، MTU پویا، Window Scaling، split tunneling برای aparat/digikala/google/youtube و GeoIP. `./gradlew :core:test :app:testDebugUnitTest` و `go test ./...` در CI اجرا می‌شوند و job جدید `proof` تست‌های split/idle/soak را جداگانه اجرا می‌کند.
"""


def badge(label, value, colour, logo, filename, prefix=None):
    query = urlencode({'logo': logo, 'logoColor': 'white'})
    path = f'{label}-{value}-{colour}'.replace(' ', '%20')
    target = (base if prefix is None else prefix) + filename
    shown = value.replace('__', '_')
    return f'[![{label} {shown}](https://img.shields.io/badge/{path}?{query})]({target})'


def stack(*items):
    return '<br>'.join(items)


version = sys.argv[1]
repo = sys.argv[2] if len(sys.argv) > 2 else 'bardia049b-stack/Gozar'
android = sys.argv[3] if len(sys.argv) > 3 else version
tag = sys.argv[4] if len(sys.argv) > 4 else f'v{version}'
base = f'https://github.com/{repo}/releases/download/{tag}/'
windows_svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><path fill="white" d="M0 0h7v7H0zm9 0h7v7H9zM0 9h7v7H0zm9 0h7v7H9z"/></svg>'
windows_logo = 'data:image/svg+xml;base64,' + base64.b64encode(windows_svg.encode()).decode()

apk = f'Gozar_{android}_'
rows = [
    ('**Android**', stack(
        badge('APK', 'ARM64', '2da44e', 'android', apk + 'arm64-v8a.apk'),
        badge('APK', 'ARMv7', '2da44e', 'android', apk + 'armeabi-v7a.apk'),
        badge('APK', 'x86__64', '2da44e', 'android', apk + 'x86_64.apk'))),
    ('**Windows**', stack(
        badge('Installer', 'EXE__x64', '0550ae', windows_logo, 'Gozar-Windows-x64-Setup.exe'),
        badge('Portable', 'ZIP__x64', '0550ae', windows_logo, 'Gozar-Windows-x64.zip'))),
    ('**Linux**', stack(
        badge('Package', 'DEB__amd64', 'e36209', 'linux', 'Gozar-Linux-amd64.deb'),
        badge('Portable', 'TAR.XZ__amd64', 'e36209', 'linux', 'Gozar-Linux-amd64.tar.xz'))),
    ('**macOS**', stack(
        badge('TAR.XZ', 'Apple__Silicon', '24292f', 'apple', 'Gozar-macOS-ARM.tar.xz'),
        badge('TAR.XZ', 'Intel', '24292f', 'apple', 'Gozar-macOS-Intel.tar.xz'))),
    ('**Flatpak**', stack(
        badge('Flatpak', 'x86__64', '4a90d9', 'flatpak', f'Gozar-{version}-x86_64.flatpak'))),
    ('**Snap**', stack(
        badge('Snap', 'amd64', '82bea0', 'snapcraft', f'Gozar_{version}_amd64.snap'))),
    ('**AppImage**', stack(
        badge('AppImage', 'x86__64', '8e44ad', 'appimage', f'Gozar-{version}-x86_64.AppImage'))),
    ('**Fedora**', stack(
        badge('RPM', 'x86__64', '51a2da', 'fedora', 'Gozar-Fedora-x86_64.rpm'))),
    ('**RHEL**', stack(
        badge('RPM', 'x86__64', 'ee0000', 'redhat', 'Gozar-RHEL-x86_64.rpm'))),
    ('**Arch Linux**', stack(
        badge('Pkg', 'x86__64', '1793d1', 'archlinux', f'Gozar-{version}-x86_64.pkg.tar.zst'))),
    ('**AUR**', stack(
        badge('AUR', 'PKGBUILD', '2D5A8C', 'archlinux', '',
              prefix=f'https://github.com/{repo}/tree/main/packaging/aur'))),
    ('**OpenWrt**', stack(
        badge('IPK', 'mips', '00a3e0', 'openwrt', 'Gozar-OpenWrt-mips_24kc.ipk'),
        badge('IPK', 'mipsel', '00a3e0', 'openwrt', 'Gozar-OpenWrt-mipsel_24kc.ipk'),
        badge('IPK', 'armv7', '00a3e0', 'openwrt', 'Gozar-OpenWrt-armv7.ipk'),
        badge('IPK', 'aarch64', '00a3e0', 'openwrt', 'Gozar-OpenWrt-aarch64.ipk'),
        badge('LuCI', 'all', '0074a6', 'openwrt', 'LuCI-Gozar-all.ipk'))),
    ('**Raspberry Pi**', stack(
        badge('Raspberry Pi', '64__ARM64', 'c51a4a', 'raspberrypi', 'Gozar-RaspberryPi-arm64.tar.xz'),
        badge('Raspberry Pi', '32__ARMv7', 'c51a4a', 'raspberrypi', 'Gozar-RaspberryPi-armv7.tar.xz'))),
    ('**FreeBSD**', stack(
        badge('PKG', 'amd64', 'ab2b28', 'freebsd', 'Gozar-FreeBSD-amd64.pkg'),
        badge('PKG', 'arm64', 'ab2b28', 'freebsd', 'Gozar-FreeBSD-arm64.pkg'))),
    ('**Separate cores**', stack(
        badge('Core', 'Windows__x64', '4b4b4b', windows_logo, 'Gozar-Core-Windows-x64.zip'),
        badge('Core', 'Windows__ARM64', '4b4b4b', windows_logo, 'Gozar-Core-Windows-arm64.zip'),
        badge('Core', 'Linux__amd64', '4b4b4b', 'linux', 'Gozar-Core-Linux-amd64.tar.xz'),
        badge('Core', 'Linux__ARM64', '4b4b4b', 'linux', 'Gozar-Core-Linux-arm64.tar.xz'),
        badge('Core', 'macOS__Intel', '4b4b4b', 'apple', 'Gozar-Core-macOS-Intel.tar.xz'),
        badge('Core', 'macOS__ARM64', '4b4b4b', 'apple', 'Gozar-Core-macOS-ARM.tar.xz'))),
]

release_version = tag.removeprefix('v').removesuffix('-desktop')
notes_file = Path(__file__).resolve().parent.parent / 'docs' / 'release-notes' / f'{release_version}.md'
if notes_file.is_file():
    notes = notes_file.read_text(encoding='utf-8')
    if notes.startswith('<!-- DRAFT -->') and os.environ.get('GOZAR_ALLOW_DRAFT_NOTES') != '1':
        raise SystemExit(f'refusing to publish draft release notes: {notes_file.name}')
elif release_version == '1.10.4':
    notes = SECURITY
else:
    raise SystemExit(f'missing release notes for {release_version}')
print(notes)
print('| System | Download |')
print('|:--:|:--|')
for name, downloads in rows:
    print(f'| {name} | {downloads} |')
