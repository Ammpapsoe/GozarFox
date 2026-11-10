class Words {
  const Words(this.fa);

  final bool fa;

  String pick(String persian, String english) => fa ? persian : english;

  String get signIn => pick('ورود', 'Sign in');
  String get signInDescription => pick(
        'برای استفاده از Gozar، وارد حساب Firefox خود شوید.',
        'Use your Firefox account to continue with Gozar.',
      );
  String get createTitle => pick('ساخت حساب', 'Create account');
  String get createDescription => pick(
        'برای ساخت حساب Firefox اطلاعات زیر را وارد کنید.',
        'Enter your details to create a Firefox account.',
      );
  String get createAction => pick('ساخت حساب', 'Create account');
  String get createOnMozilla => pick(
      'ساخت حساب در وب‌سایت موزیلا', 'Create account on Mozilla’s website');
  String get haveAccount =>
      pick('حساب دارید؟ وارد شوید', 'Already have an account? Sign in');
  String get repeatPassword => pick('تکرار گذرواژه', 'Repeat password');
  String get passwordMismatch =>
      pick('دو گذرواژه یکسان نیستند.', 'The passwords do not match.');
  String get checkEmail => pick(
        'کد تأیید ارسال‌شده به ایمیل را وارد کنید.',
        'Enter the verification code sent to your email.',
      );
  String get verifyAndCreate =>
      pick('تأیید و ساخت حساب', 'Verify and create account');
  String get securityCheck =>
      pick('بررسی امنیتی Mozilla', 'Mozilla security check');
  String get securityInstruction => pick(
        'متن تصویر را وارد کنید.',
        'Enter the text shown in the image.',
      );
  String get securityAnswer => pick('متن تصویر', 'Image text');
  String get securityRequired =>
      pick('متن تصویر امنیتی را وارد کنید.', 'Enter the security image text.');
  String get securityFailed => pick(
        'بررسی امنیتی Mozilla کامل نشد؛ دوباره تلاش کنید.',
        'Mozilla’s security check could not be completed. Please try again.',
      );
  String get email => pick('ایمیل', 'Email');
  String get password => pick('گذرواژه', 'Password');
  String get verificationCode => pick('کد تأیید', 'Verification code');
  String get verifyAndSignIn => pick('تأیید و ورود', 'Verify and sign in');
  String get forgotPassword =>
      pick('گذرواژه را فراموش کرده‌اید؟', 'Forgot password?');
  String get createAccount => pick(
      'حساب ندارید؟ ساخت حساب Firefox', 'New here? Create a Firefox account');
  String get passwordNote => pick(
        'گذرواژه ذخیره نمی‌شود؛ ورود از طریق Mozilla انجام می‌شود.',
        'Your password is not stored. Sign-in takes place through Mozilla.',
      );
  String get settings => pick('تنظیمات', 'Settings');
  String get settingsSections => pick('بخش‌های تنظیمات', 'Settings sections');
  String get connectionSettings => pick('اتصال', 'Connection');
  String get displaySettings => pick('شخصی‌سازی', 'Personalization');
  String get generalSettings => pick('عمومی', 'General');
  String get accountSectionDetail =>
      pick('حساب‌ها و ورود', 'Accounts and sign-in');
  String get connectionSectionDetail =>
      pick('سرورها، پراکسی و مسیرها', 'Servers, proxy and routing');
  String get displaySectionDetail =>
      pick('زبان و ظاهر', 'Language and appearance');
  String get generalSectionDetail =>
      pick('به‌روزرسانی و پشتیبانی', 'Updates and support');
  String get back => pick('بازگشت', 'Back');
  String get minimize => pick('کوچک‌کردن', 'Minimize');
  String get maximize => pick('بزرگ‌کردن', 'Maximize');
  String get totalUsage => pick('مصرف این اتصال', 'Session traffic');
  String get pending => pick('در انتظار', 'Pending');
  String get account => pick('حساب Firefox', 'Firefox account');
  String get language => pick('زبان', 'Language');
  String get system => pick('سیستم', 'System');
  String get signOut => pick('خروج از حساب', 'Sign out');
  String get close => pick('بستن', 'Close');
  String get connected => pick('متصل است', 'Connected');
  String get disconnected => pick('قطع است', 'Disconnected');
  String get connecting => pick('در حال اتصال…', 'Connecting…');
  String get disconnecting => pick('در حال قطع…', 'Disconnecting…');
  String get tapConnect => pick('برای اتصال بزنید', 'Tap to connect');
  String get tapDisconnect => pick('برای قطع اتصال بزنید', 'Tap to disconnect');
  String get connect => pick('اتصال', 'Connect');
  String get disconnect => pick('قطع اتصال', 'Disconnect');
  String get server => pick('سرور', 'Server');
  String get bestServer => pick('خودکار — بهترین مسیر', 'Auto — best path');
  String get autoServer => pick('خودکار (بهترین مسیر)', 'Auto (best path)');
  String get chooseServer => pick('انتخاب سرور', 'Select server');
  String get loadingServers =>
      pick('در حال دریافت سرورها…', 'Loading servers…');
  String get searchServers =>
      pick('جست‌وجوی کشور یا شهر', 'Search countries or cities');
  String get noServers => pick('سروری پیدا نشد.', 'No servers found.');
  String get download => pick('دریافت', 'Download');
  String get upload => pick('ارسال', 'Upload');
  String get remaining => pick('حجم باقی‌مانده', 'Remaining');
  String get locations => pick('مکان‌ها', 'Locations');
  String get firewall => pick('دیوار آتش', 'Firewall');
  String get unavailable => pick('در دسترس نیست', 'Unavailable');
  String get exitIp => pick('آی‌پی خروجی', 'Exit IP');
  String get tunnelDevice => pick('رابط تونل', 'Tunnel device');
  String get usageUnavailable => pick('مصرف نامشخص', 'Usage unavailable');
  String get closeFailed => pick(
      'قطع امن تونل پیش از بستن برنامه انجام نشد؛ دوباره تلاش کنید.',
      'The tunnel could not be safely disconnected. Please try closing again.');
  String get tunnelNotice => pick(
        'نسخهٔ آزمایشی: IPv6 از تونل عبور نمی‌کند. UDP نسخهٔ IPv4 فقط با روشن‌کردن WARP پشتیبانی می‌شود.',
        'Experimental: IPv6 bypasses the tunnel. IPv4 UDP requires the WARP setting.',
      );
  String get warpUdp => pick('UDP با Cloudflare WARP', 'UDP via Cloudflare WARP');
  String get warpConsent => pick(
        'با پذیرش شرایط Cloudflare یک هویت رایگان WARP ثبت می‌شود. UDP نسخهٔ IPv4 از WARP در تونل موزیلا عبور می‌کند و Cloudflare آن را پردازش می‌کند. هویت رمزگذاری‌شده برای استفادهٔ بعدی نگه داشته می‌شود؛ خاموش‌کردن گزینه ثبت آن را حذف نمی‌کند. مسیریابی مستقیم انتخابی تا زمان فعال‌بودن WARP متوقف می‌شود. مسیرهای محلی، مسیر رله و ترافیک IPv6، از جمله UDP، ممکن است خارج از تونل باشند. VPN فعال دوباره متصل می‌شود و محافظت ممکن است موقتاً قطع شود.',
        'Accepting Cloudflare’s terms registers a free WARP identity. IPv4 UDP uses WARP inside the Mozilla tunnel; Cloudflare processes it. The encrypted identity is kept for reuse; turning this off does not unregister it. Selective direct routing is paused while WARP is enabled. LAN, relay routes and IPv6 traffic, including UDP, can be outside the tunnel. An active VPN reconnects and protection may briefly be interrupted.',
      );
  String get cloudflareTerms => pick('شرایط Cloudflare', 'Cloudflare terms');
  String get warpReconnect => pick('اتصال VPN برای اعمال این تغییر قطع و دوباره برقرار می‌شود.', 'The VPN will disconnect and reconnect to apply this change.');
  String get accept => pick('می‌پذیرم', 'Accept');
  String get credentialsRequired =>
      pick('ایمیل و گذرواژه را وارد کنید.', 'Enter your email and password.');
  String get codeRequired =>
      pick('کد تأیید را وارد کنید.', 'Enter your verification code.');
  String get verifyLink => pick(
        'پیوند تأیید Mozilla را در ایمیل باز کنید، سپس دوباره ورود را بزنید.',
        'Open the Mozilla verification link in your email, then select Sign in again.',
      );
  String get serverError => pick(
      'دریافت فهرست سرورها ناموفق بود.', 'Could not load the server list.');
  String get browserError =>
      pick('باز کردن صفحه ناموفق بود.', 'Could not open the page.');
  String get coreMissing => pick('هستهٔ Gozar در کنار برنامه پیدا نشد.',
      'The Gozar core was not found next to the app.');
  String get coreStopped => pick(
      'ارتباط با هستهٔ برنامه قطع شد.', 'The connection to the core was lost.');
  String get timeout => pick('پاسخی دریافت نشد؛ اتصال اینترنت را بررسی کنید.',
      'No response. Check your internet connection.');
  String get signInRequired => pick('ابتدا وارد حساب Firefox شوید.',
      'Sign in to your Firefox account first.');
  String get connectionError => pick(
      'اتصال به سرور برقرار نشد. اینترنت را بررسی کنید.',
      'Could not reach the server. Check your internet connection.');
  String get adminRequired => pick(
      'برای اتصال، برنامه به دسترسی مدیر سیستم نیاز دارد.',
      'The tunnel needs administrator privileges.');
  String get windowsAdminRequired => pick(
        'برای اتصال، برنامه را با دسترسی Administrator اجرا کنید.',
        'Run the app as Administrator to connect.',
      );
  String get appearance => pick('ظاهر', 'Appearance');
  String get lightTheme => pick('روشن', 'Light');
  String get darkTheme => pick('تیره', 'Dark');
  String get rememberServer =>
      pick('به‌خاطر سپردن سرور آخر', 'Remember last server');
  String get proxyChain => pick('زنجیرهٔ پراکسی', 'Proxy chain');
  String get proxyChainHint => pick(
      'هر خط یک پراکسی؛ برای نمونه socks5://127.0.0.1:1080',
      'One proxy per line, for example socks5://127.0.0.1:1080');
  String get proxyChainDetail => pick(
      'پراکسی‌های SOCKS5 یا HTTP به ترتیب استفاده می‌شوند.',
      'SOCKS5 or HTTP proxies are used in order.');
  String get noProxyChain => pick('بدون پراکسی میانی', 'No upstream proxies');
  String get save => pick('ذخیره', 'Save');
  String get cancel => pick('انصراف', 'Cancel');
  String get manageAccounts => pick('مدیریت حساب‌ها', 'Manage accounts');
  String get savedAccounts =>
      pick('حساب‌های ذخیره‌شده در گذر', 'Saved Gozar accounts');
  String get addAccount => pick('افزودن حساب', 'Add account');
  String get activeAccount => pick('فعال', 'Active');
  String get switchDisconnect => pick(
      'برای تغییر یا افزودن حساب، اتصال قطع می‌شود.',
      'The VPN will disconnect before you switch or add an account.');
  String get systemAccounts => pick(
      'انتخاب ایمیل از حساب‌های سیستم', 'Choose an email from system accounts');
  String get noSystemAccounts => pick(
      'حساب ایمیلی در حساب‌های سیستم در دسترس نیست؛ می‌توانید ایمیل را دستی وارد کنید.',
      'No system email accounts are available. You can enter your email manually.');
  String get systemPickerUnavailable => pick(
      'انتخاب حساب سیستم روی این دستگاه در دسترس نیست؛ ایمیل را دستی وارد کنید.',
      'The system account picker is unavailable on this device. Enter your email manually.');
  String get desktopRoutingLimits => pick(
      'مسیریابی بر اساس برنامه و دامنه در نسخهٔ دسکتاپ هنوز پشتیبانی نمی‌شود.',
      'Per-app and per-domain routing are not yet supported on desktop.');
  String get routing => pick('مسیریابی', 'Routing');
  String get updates => pick('بررسی به‌روزرسانی', 'Check for updates');
  String get about => pick('درباره و مجوز', 'About and license');
  String get updateAvailable =>
      pick('نسخهٔ تازه در دسترس است:', 'A new release is available:');
  String get updateCurrent =>
      pick('نسخهٔ شما به‌روز است.', 'You are up to date.');
  String get updateFailed => pick('بررسی یا تأیید به‌روزرسانی انجام نشد.',
      'Could not check or verify the update.');
  String get updateChecking =>
      pick('در حال بررسی امضای نسخه…', 'Checking the signed release…');
  String get updateDownloading => pick(
      'در حال دریافت و بررسی فایل…', 'Downloading and verifying the package…');
  String get updateChoosePackage => pick('بستهٔ مناسب نصب خود را انتخاب کنید:',
      'Choose the package for your installation:');
  String get updateNoPackage => pick(
      'بستهٔ امضاشده‌ای برای این دستگاه موجود نیست.',
      'No signed package is available for this device.');
  String get updateDownloaded => pick(
      'فایل با امضای نسخه بررسی شد، اما نصب نشده است. برای نصب، پوشه را باز کنید و مراحل سیستم‌عامل را انجام دهید. بازیابی خودکار هنوز پشتیبانی نمی‌شود.',
      'The file was verified against the signed release, but is not installed. Open the folder and follow your system’s installation steps. Automatic rollback is not yet supported.');
  String get updateOpenFolder => pick('باز کردن پوشه', 'Open folder');
  String get updateCopyPath => pick('کپی مسیر', 'Copy path');
  String get updateDownloadFailed => pick('دریافت یا بررسی فایل ناموفق بود.',
      'The package could not be downloaded or verified.');
  String get openRelease => pick('دیدن نسخه', 'View release');
  String get donate => pick('حمایت از گذر', 'Support Gozar');
  String get donateMessage => pick(
      'اگر گذر برایتان مفید بوده، می‌توانید با رمزارز TON از توسعهٔ آن حمایت کنید.',
      'If Gozar is useful to you, you can support its development with TON.');
  String get copyAddress => pick('کپی نشانی', 'Copy address');
  String get addressCopied => pick('نشانی کپی شد.', 'Address copied.');
  String get sourceCode => pick('کد منبع', 'Source code');
}
