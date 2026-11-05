import base64
import sys
from urllib.parse import urlencode

SECURITY = r"""## Gozar 1.10.3 — نسخهٔ سخت‌سازی امنیتی

این نسخه حاصل یک بازبینی امنیتی کامل روی کلاینت است: هر مورد در محیط آزمایشی بازتولید شد، رفع شد و برای آن تست خودکار نوشته شد. هیچ‌چیز در روش برقراری تونل تغییر نکرده است؛ رمزنگاری، ترنسپورت MASQUE، جریان ورود به حساب و انتخاب سرور دست‌نخورده‌اند، بنابراین نسخهٔ نصب‌شدهٔ فعلی بعد از آپدیت دقیقاً مثل قبل کار می‌کند.

### ۱) نشت IPv6 — افشای هویت واقعی کاربر
**مشکل:** در اندروید فقط مسیر `0.0.0.0/0` به اینترفیس VPN داده می‌شد و در دسکتاپ فقط `0.0.0.0/1` و `128.0.0.0/1`. روی هر شبکه‌ای که IPv6 بدهد (اپراتورهای موبایل، اکثر ISPهای خانگی و همهٔ مودم‌های جدید) سوکت‌های IPv6 کلاً از تونل بیرون می‌زدند و آدرس واقعی دستگاه روی اینترنت دیده می‌شد؛ یک کوئری AAAA کافی بود.
**رفع:** اینترفیس اندروید حالا آدرس `fd00:111:0:2::2/128` و مسیر `::/0` را در اختیار می‌گیرد؛ ویندوز، لینوکس و مک مسیرهای `::/1` و `8000::/1` را به اینترفیس مجازی اضافه می‌کنند. چون پشتهٔ فضای کاربر فقط IPv4 را پردازش می‌کند، بستهٔ IPv6 الان وارد تونل می‌شود و همان‌جا دور ریخته می‌شود، نه اینکه از لینک فیزیکی فرار کند. رزولور هم مثل قبل به کوئری AAAA پاسخ خالی می‌دهد تا برنامه‌ها بلافاصله روی IPv4 بیفتند. این مسیرها «بهترین تلاش» هستند: اگر کرنلی اضافه‌کردن‌شان را رد کند، تونل باز هم بالا می‌آید و فقط همان خانواده مسدود نمی‌شود.

### ۲) ذخیرهٔ توکن‌ها به‌صورت متن خام
**مشکل (اندروید):** اکسس‌توکن، رفرش‌توکن و رمز تونل در `SharedPreferences` ساده ذخیره می‌شدند؛ هر بکاپ، هر باگ‌گیری یا هر ابزار روت می‌توانست آن‌ها را بردارد و حساب موزیلا را تصاحب کند.
**مشکل (دسکتاپ):** همان اطلاعات به‌صورت JSON خوانا در `config.json` بود.
**رفع (اندروید):** همهٔ رازها با AES-256-GCM و کلیدی که از Android Keystore خارج نمی‌شود مهر و موم می‌شوند (نام مستعار `gozar.session.v1`، IV تصادفی برای هر مقدار، AAD وابسته به پکیج). اگر دستگاهی Keystore قابل‌استفاده نداشته باشد، به کلید مخصوص همان نصب در `noBackupFilesDir` برمی‌گردد که هرگز داخل بکاپ نمی‌رود. نشست‌های قدیمی متن‌خام در اولین نوشتن به‌صورت خودکار مهاجرت می‌کنند، پس کسی با این آپدیت بیرون نمی‌افتد. مقدار دست‌کاری‌شده «بسته» شکست می‌خورد و به نشست خالی تبدیل می‌شود، نه به دادهٔ نامعتبر.
**رفع (دسکتاپ):** در ویندوز از DPAPI (محدود به همان کاربر و همان ماشین، با یک بلاب آنتروپی مخصوص Gozar) و در لینوکس و مک از AES-256-GCM با کلید `0600` مخصوص نصب استفاده می‌شود. `config.json` دیگر حتی یک توکن خوانا ندارد و فایل‌های قدیمی در اولین ذخیره مهاجرت می‌کنند.

### ۳) پورت‌های لوکال بدون احراز هویت
**مشکل:** پورت‌های ثابت `10810` (SOCKS5)، `10811` (HTTP) و `10812` (DNS) مشتری بدون رمز قبول می‌کردند. هر اپ نصب‌شده روی گوشی می‌توانست از تونل استفاده کند، سهمیهٔ حساب را بسوزاند، split-tunneling را دور بزند یا از IP خروجی کاربر عبور کند.
**رفع:** سه شنونده حالا روی پورت‌های موقت و تازه در هر نشست بالا می‌آیند و ثابت‌های قدیمی حذف شدند؛ چیزی نمی‌تواند آن‌ها را حدس بزند یا هاردکد کند. شنوندهٔ SOCKS5 احراز هویت RFC 1929 را با یک اعتبار ۱۴۴ بیتی مخصوص هر نشست و مقایسهٔ زمان‌ثابت اجباری می‌کند و به‌جای `0x00` مقدار `0x02` اعلام می‌کند. تعداد اتصال هر شنونده به ۶۴ محدود شد تا یک پروسهٔ محلی نتواند حافظه را تمام کند، همتاهای غیرلوپ‌بک قبل از هر پردازشی رد می‌شوند و خط درخواست HTTP هم به‌صورت دفاعی پارس می‌شود.

### ۴) نبود Kill Switch واقعی در دسکتاپ
کلاینت دسکتاپ همچنان قوانین WFP یا pf یا nftables نصب نمی‌کند؛ انتشار دستکاری فایروال بدون تست روی هر سه سیستم یعنی ریسک اینکه کاربر کلاً اینترنتش را از دست بدهد. آنچه تغییر کرد شفافیت و دامنهٔ آسیب است: IPv6 حالا مسدود می‌شود نه نشت، کلاینت وضعیت واقعی تونل را گزارش می‌کند و به‌صورت خاموش به مسیر مستقیم برنمی‌گردد، و در اندروید تشخیص Always-on/lockdown همچنان اجباری است (به‌محض دیدن lockdown، مسیر مستقیم غیرفعال می‌شود). Kill switch واقعی در سطح سیستم‌عامل به یک helper امضاشده نیاز دارد و آگاهانه در این نسخه جعل نشده است.

### ۵) DLL Hijacking در ویندوز
**مشکل:** فایل `wintun.dll` کنار فایل اجرایی یا در `%LOCALAPPDATA%/Gozar` نوشته می‌شد و با `SetDllDirectory` سراسری لود می‌شد — نمونهٔ کلاسیک ربایش ترتیب جست‌وجوی DLL.
**رفع:** DLL قبل از استفاده با SHA-256 باینری امبدشده تطبیق داده می‌شود، با نام وابسته به محتوا (`wintun-<hash>.dll`) داخل پوشهٔ `0700` نصب می‌شود، با فایل موقت و rename اتمیک نوشته می‌شود و بعد از نوشتن دوباره هش آن چک می‌شود، و در نهایت با `LoadLibraryEx(..., LOAD_LIBRARY_SEARCH_SYSTEM32 | LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR)` لود می‌شود. سپس مسیر جست‌وجوی سراسری به `SYSTEM32` محدود می‌شود تا DLL مخربی که هرجای پروفایل کاربر افتاده باشد برنده نشود.

### ۶) به‌روزرسانی بدون اعتبارسنجی
بررسی آپدیت حالا مالک مخزن را پین می‌کند، فقط `https://github.com/bardia049b-stack/{Gozar,Gozar1}/releases/...` را می‌پذیرد، وجود یوزرفو و پورت غیرپیش‌فرض و هر هاست دیگر را رد می‌کند و تگ را قبل از نمایش با الگوی سخت‌گیرانهٔ عددی اعتبارسنجی می‌کند. فقط صفحهٔ ریلیز در مرورگر خود کاربر باز می‌شود، هرگز یک URL دلبخواه از پاسخ API.

### ۷) تک‌نقطه‌ای بودن رزولور
پروفایل `AUTO` حالا بین سه رزولور مستقل DoH (موزیلا، گوگل، کواد۹) می‌چرخد، با همان اعتبارسنجی سخت‌گیرانهٔ پاسخ، بازبرچسب‌زنی شناسهٔ تراکنش و محافظت از کش منفی. از دست رفتن یک سرویس‌دهنده دیگر کل ترافیک نام دامنه‌ها را متوقف نمی‌کند.

### ۸) سخت‌سازی پشتهٔ بسته
پشتهٔ TCP فضای کاربر دیگر بی‌مرز رشد نمی‌کند: یک سطل توکن نرخ جریان جدید را روی ۲۵۶ در ثانیه نگه می‌دارد، جدول جریان روی ۵۱۲ ورودی با حذف قدیمی‌ترین‌ها سقف دارد، جدول نام‌ها روی ۴۰۹۶ ورودی محدود است و سیل SYN یا ACK جعلی با RST پاسخ می‌گیرد و حالتی تخصیص نمی‌دهد. سطح حملهٔ پارس بسته هم با تست‌هایی پوشش داده شد که بستهٔ خراب و فریم IPv6 را از پمپ عبور می‌دهند.

### اعتبارسنجی
`./gradlew :core:test :app:testDebugUnitTest` و `go test ./...` روی هر سه سیستم‌عامل در CI اجرا می‌شوند. تست‌های جدید پوشش می‌دهند: رفت‌وبرگشت مقدار مهروموم و رد دست‌کاری، مهاجرت از متن خام به مهروموم، رد شدن SOCKS بدون رمز، تخصیص پورت موقت، سقف اتصال، دور ریختن فریم IPv6، مهار سیل SYN، جانشینی رزولور و لیست سفید URL. بیلدهای اندروید، ویندوز، لینوکس و مک از همین کامیت ساخته می‌شوند.
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

print(SECURITY)
print('| System | Download |')
print('|:--:|:--|')
for name, downloads in rows:
    print(f'| {name} | {downloads} |')
