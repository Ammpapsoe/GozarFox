#include "gozarpreferences.h"
#include "homebinder.h"
#include "originallanguagebinding.h"
#include "originalmodebinding.h"
#include "preferenceswindow/comboboxitem.h"
#include "preferenceswindow/linkitem.h"
#include "preferenceswindow/preferencegroup.h"
#include "preferenceswindow/preferencestab/preferencestabcontrolitem.h"
#include "preferenceswindow/titleitem.h"
#include "preferenceswindow/toggleitem.h"
#include "dpiscalemanager.h"
#include "graphicresources/fontmanager.h"
#include "graphicresources/imageresourcessvg.h"
#include "graphicresources/independentpixmap.h"
#include <QLocale>
#include <QFontDatabase>
#include <QPainter>

#include <QDesktopServices>
#include <QApplication>
#include <QInputDialog>
#include <QDialog>
#include <QLabel>
#include <QScrollArea>
#include <QScreen>
#include <QScrollBar>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QTimer>
#include <QMessageBox>
#include <QPushButton>
#include <QSignalBlocker>
#include <QUrl>
#include <QClipboard>
#include <QDir>
#include <QFileInfo>
#include <QListWidget>
#include <QProgressBar>
#include <QtMath>
#include <utility>
#include <limits>

namespace {
QString local(const char *en, const char *fa) {
    return LanguageController::instance().getLanguage() == QLatin1String("fa") ? QString::fromUtf8(fa) : QString::fromUtf8(en);
}
QSharedPointer<IndependentPixmap> art(const char *id) {
    return ImageResourcesSvg::instance().getIndependentPixmap(QString::fromLatin1(id));
}
QIcon glyph(const char *id, const QColor &color) {
    auto asset = art(id);
    if (!asset) return {};
    QPixmap shape = asset->getIcon().pixmap(QSize(48, 48));
    QPainter painter(&shape);
    painter.setCompositionMode(QPainter::CompositionMode_SourceIn);
    painter.fillRect(QRectF(QPointF(), shape.deviceIndependentSize()), color);
    painter.end();
    QIcon icon;
    for (auto mode : {QIcon::Normal, QIcon::Disabled, QIcon::Active, QIcon::Selected}) icon.addPixmap(shape, mode);
    return icon;
}
QVBoxLayout *sheet(QDialog &dialog, const QString &heading, const char *asset) {
    auto &fonts = FontManager::instance();
    dialog.setWindowTitle(heading);
    dialog.setLayoutDirection(LanguageController::instance().getLanguage() == QLatin1String("fa") ? Qt::RightToLeft : Qt::LeftToRight);
    dialog.setWindowFlags(Qt::Dialog | Qt::FramelessWindowHint);
    dialog.setFont(fonts.getFont(14, QFont::Normal));
    dialog.setMinimumWidth(qCeil(326 * G_SCALE));
    if (auto *screen = dialog.parentWidget()->screen())
        dialog.setMaximumWidth(qMax(dialog.minimumWidth(), qMin(qCeil(385 * G_SCALE), screen->availableGeometry().width() - 20)));
    dialog.setStyleSheet(QStringLiteral(
        "QDialog {background:%1;border:1px solid %2;} QLabel {color:#e4e9f0;}"
        "QPushButton {background:%3;color:#e4e9f0;border:1px solid transparent;border-radius:4px;padding:6px 10px;}"
        "QPushButton:hover {background:%2;} QPushButton:focus {border-color:#8491a0;}"
        "QPushButton:disabled {color:#8491a0;} QPushButton[plain=\"true\"] {background:transparent;}"
        "QPushButton[plain=\"true\"]:hover {background:%2;}"
        "QPushButton[accountRow=\"true\"] {border-radius:0;text-align:left;border-bottom:1px solid %2;}"
        "QPushButton#activeAccount {color:#e4e9f0;} QScrollArea {border:0;background:%1;}"
        "QListWidget {background:%3;color:#e4e9f0;border:0;outline:0;} QListWidget::item {padding:7px;}"
        "QListWidget::item:selected {background:%2;color:white;} QProgressBar {background:%3;border:0;height:2px;}"
        "QProgressBar::chunk {background:#17e9ad;}")
        .arg(fonts.getMidnightColor().name(), fonts.getCharcoalColor().name(), fonts.getDarkBlueColor().name()));
    auto *body = new QVBoxLayout(&dialog);
    body->setContentsMargins(14, 14, 14, 14);
    body->setSpacing(12);
    auto *header = new QHBoxLayout;
    header->setSpacing(8);
    auto *symbol = new QLabel(&dialog);
    const auto size = qCeil(16 * G_SCALE);
    symbol->setPixmap(glyph(asset, QColor(0xb9, 0xc4, 0xcf)).pixmap(QSize(size, size), dialog.devicePixelRatioF()));
    auto *title = new QLabel(heading, &dialog);
    title->setTextFormat(Qt::PlainText);
    title->setFont(fonts.getFont(16, QFont::DemiBold));
    header->addWidget(symbol);
    header->addWidget(title, 1);
    body->addLayout(header);
    return body;
}
QLabel *plainLabel(QDialog &dialog, QVBoxLayout *body, const QString &text, bool selectable = false) {
    auto *item = new QLabel(text, &dialog);
    item->setTextFormat(Qt::PlainText);
    item->setWordWrap(true);
    if (selectable) item->setTextInteractionFlags(Qt::TextSelectableByMouse | Qt::TextSelectableByKeyboard);
    body->addWidget(item);
    return item;
}
QPushButton *action(QDialog &dialog, QHBoxLayout *actions, const QString &text, const char *icon = nullptr, bool plain = false) {
    auto *button = new QPushButton(text, &dialog);
    button->setProperty("plain", plain);
    if (icon) button->setIcon(glyph(icon, QColor(0xb9, 0xc4, 0xcf)));
    actions->addWidget(button);
    return button;
}
class UpdateDialog final : public QDialog {
public:
    using QDialog::QDialog;
    std::function<void()> cancel;
    void reject() override { if (cancel) cancel(); }
};

}

GozarPreferences::GozarPreferences(HomeBinder *binder, UiLanguage *language, PreferencesHelper *helper, bool readOnly)
    : binder_(binder), readOnly_(readOnly) {
    using namespace PreferencesWindow;
    setScene(&scene_);
    setObjectName(QStringLiteral("gozarPreferences"));
    setFrameShape(QFrame::NoFrame);
    setAlignment(Qt::AlignTop | Qt::AlignLeft);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    setBackgroundBrush(QColor(9, 14, 25));
    setStyleSheet(QStringLiteral("QScrollBar:vertical {width: 6px; background: #090e19;} QScrollBar::handle:vertical {background: #52627a; min-height: 25px; border-radius: 3px;} QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {height: 0;}"));
    title_ = new TitleItem(nullptr);
    scene_.addItem(title_);
    connection_ = new PreferenceGroup(nullptr);
    general_ = new PreferenceGroup(nullptr);
    scene_.addItem(connection_);
    scene_.addItem(general_);
    appearance_ = new PreferenceGroup(nullptr);
    scene_.addItem(appearance_);
    account_ = new PreferenceGroup(nullptr);
    scene_.addItem(account_);
    about_ = new PreferenceGroup(nullptr);
    scene_.addItem(about_);
    version_ = new LinkItem(about_, LinkItem::TEXT_ONLY);
    update_ = new LinkItem(about_, LinkItem::SUBPAGE_LINK);
    project_ = new LinkItem(about_, LinkItem::EXTERNAL_LINK, {}, QStringLiteral("https://github.com/Ammpapsoe/GozarFox"));
    support_ = new LinkItem(about_, LinkItem::SUBPAGE_LINK);
    update_->setIcon(art("preferences/UPDATE_CHANNEL"));
    project_->setIcon(art("update/INFO_ICON"));
    support_->setIcon(art("locations/FAV_ICON_DESELECTED"));
    about_->addItem(version_);
    about_->addItem(update_);
    about_->addItem(project_);
    about_->addItem(support_);
    update_->setEnabled(!readOnly_);
    update_->setObjectName(QStringLiteral("updatesRow"));
    support_->setObjectName(QStringLiteral("supportRow"));
    connect(update_, &LinkItem::clicked, this, &GozarPreferences::checkUpdates);
    connect(support_, &LinkItem::clicked, this, &GozarPreferences::showDonation);
    email_ = new LinkItem(account_, LinkItem::TEXT_ONLY);
    switch_ = new LinkItem(account_, LinkItem::SUBPAGE_LINK);
    logout_ = new LinkItem(account_, LinkItem::BLANK_LINK);
    usage_ = new LinkItem(account_, LinkItem::BLANK_LINK);
    email_->setIcon(art("preferences/ACCOUNT_ICON"));
    logout_->setIcon(art("preferences/LOGOUT_ICON"));
    account_->addItem(email_);
    account_->addItem(switch_);
    account_->addItem(usage_);
    account_->addItem(logout_);
    connect(usage_, &LinkItem::clicked, this, [this] { if (!readOnly_) binder_->refreshUsage(); });
    connect(switch_, &LinkItem::clicked, this, &GozarPreferences::showAccounts);
    connect(logout_, &LinkItem::clicked, this, [this] {
        if (readOnly_ || !binder_->isAvailable()) return;
        if (QMessageBox::question(this, local("Sign out", "خروج از حساب"),
            local("Sign out of the active account? The current tunnel will close; other saved accounts will remain.",
                  "از حساب فعال خارج می‌شوید؟ تونل فعلی بسته می‌شود و حساب‌های ذخیره‌شدهٔ دیگر باقی می‌مانند."),
            QMessageBox::Yes | QMessageBox::Cancel, QMessageBox::Cancel) == QMessageBox::Yes) binder_->signOut();
    });
    connect(binder_, &HomeBinder::availabilityChanged, this, &GozarPreferences::syncAccount);
    connect(binder_, &HomeBinder::stateChanged, this, &GozarPreferences::syncAccount);
    tabs_ = new PreferencesTabControlItem(nullptr, helper);
    scene_.addItem(tabs_);
    tabs_->restrictToGozar();
    tabs_->setGozarQuitVisible(!readOnly_);
    connect(tabs_, &PreferencesTabControlItem::currentTabChanged, this, [this](PREFERENCES_TAB_TYPE page) { showPage(page); });
    connect(tabs_, &PreferencesTabControlItem::quitClick, this, &GozarPreferences::quitRequested);
    auto *mode = new ComboBoxItem(connection_);
    connection_->addItem(mode);
    mode->setIcon(art("preferences/CONNECTION_MODE"));
    if (!readOnly_) new OriginalModeBinding<ComboBoxItem>(mode, binder_);
    else mode->setClickable(false);
    addToggle(connection_, "kill_switch", "Kill switch (next tunnel)", "قطع اضطراری (تونل بعدی)",
              [this](bool enabled) { binder_->setKillSwitch(enabled); }, true);
    addToggle(connection_, "warp_udp", "WARP UDP (experimental)", "WARP UDP (آزمایشی)", [this](bool enabled) {
        const auto state = binder_->currentState();
        const bool live = state.value(QStringLiteral("live")).toBool();
        const auto reconnect = local("An active tunnel disconnects and reconnects; protection can be interrupted briefly.",
            "تونل فعال قطع و دوباره برقرار می‌شود؛ ممکن است محافظت برای مدت کوتاهی قطع شود.");
        if (enabled) {
            const auto consent = local(
                "A free WARP identity is registered and kept encrypted for reuse; turning this off does not unregister it. Cloudflare processes IPv4 UDP sent through WARP inside the Mozilla tunnel. Selective direct routing is paused while WARP is on. LAN, relay and IPv6 traffic may bypass the tunnel.",
                "هویت رایگان WARP ثبت و برای استفادهٔ دوباره رمزگذاری و ذخیره می‌شود؛ خاموش‌کردن گزینه آن را حذف نمی‌کند. کلادفلر UDP نسخهٔ IPv4 را که از WARP در تونل موزیلا عبور می‌کند پردازش می‌کند. مسیریابی مستقیم انتخابی هنگام فعال بودن WARP متوقف می‌شود. ترافیک شبکهٔ محلی، رله و IPv6 ممکن است خارج از تونل باشد.");
            QMessageBox dialog(QMessageBox::Question, local("Enable WARP UDP?", "WARP UDP فعال شود؟"),
                consent + QStringLiteral("\n\n") + reconnect, QMessageBox::Cancel, this);
            auto *accept = dialog.addButton(local("Accept Cloudflare’s terms", "پذیرش شرایط کلادفلر"), QMessageBox::AcceptRole);
            auto *terms = dialog.addButton(local("Read terms", "خواندن شرایط"), QMessageBox::HelpRole);
            connect(terms, &QPushButton::clicked, &dialog, [] {
                QDesktopServices::openUrl(QUrl(QStringLiteral("https://www.cloudflare.com/application/terms/")));
            });
            dialog.setDefaultButton(QMessageBox::Cancel);
            dialog.exec();
            if (dialog.clickedButton() != accept) return;
        } else if (live && QMessageBox::question(this, local("Disable WARP UDP?", "WARP UDP غیرفعال شود؟"),
                    reconnect, QMessageBox::Yes | QMessageBox::Cancel, QMessageBox::Cancel) != QMessageBox::Yes) {
            return;
        }
        binder_->setWarpUdp(enabled, enabled);
    }, true);
    auto *chain = new LinkItem(connection_, LinkItem::SUBPAGE_LINK);
    connection_->addItem(chain);
    chain->setIcon(art("preferences/PROXY"));
    const auto syncChain = [this, chain] { chain->setEnabled(!readOnly_ && binder_->canChangeMode()); };
    connect(chain, &LinkItem::clicked, this, [this] { editProxyChain(); });
    connect(binder_, &HomeBinder::availabilityChanged, this, syncChain);
    const auto translateChain = [chain] {
        chain->setTitle(local("Proxy chain", "زنجیرهٔ پراکسی"));
        chain->setLinkText(local("Edit", "ویرایش"));
    };
    connect(&LanguageController::instance(), &LanguageController::languageChanged, this, translateChain);
    translateChain();
    syncChain();
    session_ = new LinkItem(connection_, LinkItem::SUBPAGE_LINK);
    session_->setObjectName(QStringLiteral("sessionRow"));
    session_->setIcon(art("pingbar/ON_FULL"));
    connection_->addItem(session_);
    connect(session_, &LinkItem::clicked, this, &GozarPreferences::showSession);
    routing_ = new LinkItem(connection_, LinkItem::TEXT_ONLY);
    routing_->setIcon(art("preferences/SPLIT_TUNNELING"));
    connection_->addItem(routing_);

    auto *languageItem = new ComboBoxItem(general_);
    general_->addItem(languageItem);
    languageItem->setIcon(art("preferences/LANGUAGE"));
    if (!readOnly_) new OriginalLanguageBinding<ComboBoxItem>(languageItem, language, binder_);
    else languageItem->setClickable(false);
    theme_ = new ComboBoxItem(appearance_);
    appearance_->addItem(theme_);
    theme_->setIcon(art("preferences/APP_SKIN"));
    connect(theme_, &ComboBoxItem::currentItemChanged, this, [this](const QVariant &value) {
        const auto old = binder_->currentState().value(QStringLiteral("theme")).toString();
        theme_->setCurrentItem(old.isEmpty() ? QStringLiteral("system") : old);
        if (!readOnly_ && binder_->isAvailable() && old != value.toString()) binder_->setTheme(value.toString());
    });
    connect(binder_, &HomeBinder::stateChanged, this, [this] { syncTheme(); });
    connect(binder_, &HomeBinder::availabilityChanged, this, [this] { theme_->setClickable(!readOnly_ && binder_->isAvailable()); });
    addToggle(general_, "recents", "Remember last server", "به‌خاطر سپردن سرور قبلی", [this](bool enabled) { binder_->setRecents(enabled); });
    connect(&LanguageController::instance(), &LanguageController::languageChanged, this, [this] { retranslate(); });
    showPage(TAB_GENERAL);
}

void GozarPreferences::addToggle(PreferencesWindow::PreferenceGroup *group, const char *key,
                                const char *en, const char *fa, std::function<void(bool)> change, bool tunnelOnly) {
    using namespace PreferencesWindow;
    auto *item = new ToggleItem(group, local(en, fa));
    const QByteArray type(key);
    item->setIcon(art(type == "kill_switch" ? "preferences/FIREWALL_MODE" :
                      type == "warp_udp" ? "preferences/PREFERRED_PROTOCOL" : "preferences/LOCATION_ORDER"));
    group->addItem(item);
    const QString name = QString::fromLatin1(key);
    item->setObjectName(name);
    const auto sync = [this, item, name, tunnelOnly] {
        const auto state = binder_->currentState();
        const auto saved = state.value(name);
        item->setEnabled(!readOnly_ && saved.isBool() && binder_->isAvailable() &&
                         // In proxy mode an enabled setting blocks Connect. It
                         // must remain possible to clear it without switching modes.
                         (!tunnelOnly || saved.toBool() || state.value(QStringLiteral("mode")).toString() == QLatin1String("tun")));
        const QSignalBlocker blocker(item);
        item->setState(saved.toBool());
    };
    connect(binder_, &HomeBinder::availabilityChanged, item, sync);
    connect(binder_, &HomeBinder::stateChanged, item, sync);
    connect(&LanguageController::instance(), &LanguageController::languageChanged, item,
            [item, en, fa] { item->setCaption(local(en, fa)); });
    connect(item, &ToggleItem::stateChanged, item, [item, sync, change](bool enabled) {
        const bool allowed = item->isEnabled();
        sync();
        if (allowed) change(enabled);
    });
    sync();
}

void GozarPreferences::syncTheme() {
    const QString value = binder_->currentState().value(QStringLiteral("theme")).toString();
    const QSignalBlocker blocker(theme_);
    const auto current = value.isEmpty() ? QStringLiteral("system") : value;
    const auto language = LanguageController::instance().getLanguage();
    if (!themePopulated_ || language != themeLanguage_) {
        themePopulated_ = true;
        themeLanguage_ = language;
        theme_->setItems({{local("System default", "پیش‌فرض سیستم"), QStringLiteral("system")},
                     {local("Light", "روشن"), QStringLiteral("light")},
                     {local("Dark", "تیره"), QStringLiteral("dark")}}, current);
    } else theme_->setCurrentItem(current);
    theme_->setClickable(!readOnly_ && binder_->isAvailable());
}

void GozarPreferences::retranslate() {
    const auto page = page_ == TAB_CONNECTION ? local("Connection", "اتصال") :
        page_ == TAB_LOOK_AND_FEEL ? local("Appearance", "ظاهر") :
        page_ == TAB_ACCOUNT ? local("Account", "حساب کاربری") :
        page_ == TAB_ABOUT ? local("About", "درباره") : local("General", "عمومی");
    title_->setTitle(page);
    update_->setTitle(local("Check for updates", "بررسی به‌روزرسانی"));
    support_->setTitle(local("Support Gozar", "حمایت از گذر"));
    project_->setTitle(local("Source & license", "کد منبع و مجوز"));
    project_->setLinkText(QStringLiteral("AGPL-3.0"));
    routing_->setTitle(local("Routing", "مسیریابی"));
    routing_->setDescription(local("Per-app and per-domain routing are not available on desktop.",
                                  "مسیریابی بر اساس برنامه و دامنه در نسخهٔ دسکتاپ پشتیبانی نمی‌شود."));
    session_->setTitle(local("Session details", "جزئیات نشست"));
    title_->update();
    theme_->setLabelCaption(local("Flutter sign-in theme", "پوستهٔ ورود Flutter"));
    syncTheme();
    syncAccount();
}

void GozarPreferences::showPage(int page) {
    page_ = page;
    setProperty("gozarPage", page == TAB_CONNECTION ? QStringLiteral("connection") :
        page == TAB_LOOK_AND_FEEL ? QStringLiteral("appearance") :
        page == TAB_ACCOUNT ? QStringLiteral("account") :
        page == TAB_ABOUT ? QStringLiteral("about") : QStringLiteral("general"));
    general_->setVisible(page == TAB_GENERAL);
    connection_->setVisible(page == TAB_CONNECTION);
    appearance_->setVisible(page == TAB_LOOK_AND_FEEL);
    account_->setVisible(page == TAB_ACCOUNT);
    about_->setVisible(page == TAB_ABOUT);
    verticalScrollBar()->setValue(0);
    retranslate();
    viewport()->update();
    emit pageChanged();
}

void GozarPreferences::fitPanel(int w, int maxHeight) {
    title_->updateScaling();
    title_->setPos(60 * G_SCALE, 14 * G_SCALE);
    for (auto *group : {connection_, general_, appearance_, account_, about_}) {
        group->updateScaling();
        group->setPos(60 * G_SCALE, 39 * G_SCALE);
    }
    auto *visible = page_ == TAB_CONNECTION ? connection_ : page_ == TAB_ACCOUNT ? account_ :
                    page_ == TAB_LOOK_AND_FEEL ? appearance_ : page_ == TAB_ABOUT ? about_ : general_;
    const int contentHeight = qMax(qCeil(306 * G_SCALE), qCeil(visible->sceneBoundingRect().bottom() + 14 * G_SCALE));
    tabs_->setHeight(contentHeight);
    tabs_->updateScaling();
    setSceneRect(0, 0, w - 6, contentHeight);
    setFixedSize(w, qBound(1, contentHeight, qMax(1, maxHeight)));
}

void GozarPreferences::syncAccount() {
    if (!email_) return;
    const auto state = binder_->currentState();
    email_->setTitle(state.value(QStringLiteral("email")).toString());
    const auto version = state.value(QStringLiteral("version")).toString();
    version_->setTitle(version.isEmpty() ? QStringLiteral("Gozar") : QStringLiteral("Gozar %1").arg(version));
    const auto limit = state.value(QStringLiteral("limit")).toInteger();
    const auto remaining = state.value(QStringLiteral("remaining")).toInteger();
    const auto locale = LanguageController::instance().getLanguage() == QLatin1String("fa")
        ? QLocale(QLocale::Persian) : QLocale(QLocale::English);
    usage_->setTitle(limit > 0 ? local("Data remaining", "حجم باقی‌مانده") : local("Data usage", "مصرف داده"));
    usage_->setLinkText(limit > 0 && remaining >= 0 ? locale.formattedDataSize(remaining, 1) : local("Refresh", "تازه‌سازی"));
    usage_->setToolTip(local("Refresh account usage", "تازه‌سازی مصرف حساب"));
    usage_->setEnabled(!readOnly_ && binder_->isAvailable());
    switch_->setTitle(local("Saved accounts", "حساب‌های ذخیره‌شده"));
    switch_->setLinkText(local("Switch", "تغییر"));
    logout_->setTitle(local("Sign out", "خروج از حساب"));
    switch_->setEnabled(!readOnly_ && binder_->isAvailable());
    logout_->setEnabled(!readOnly_ && binder_->isAvailable());
}

void GozarPreferences::showAccounts() {
    if (readOnly_ || !binder_->isAvailable()) return;
    const auto state = binder_->currentState();
    QDialog dialog(this);
    dialog.setObjectName(QStringLiteral("gozarAccountSwitcher"));
    auto *body = sheet(dialog, local("Switch account", "تغییر حساب"), "preferences/ACCOUNT_ICON");
    auto *scroll = new QScrollArea(&dialog);
    scroll->setWidgetResizable(true);
    auto *rows = new QWidget(scroll);
    rows->setObjectName(QStringLiteral("gozarAccountRows"));
    rows->setStyleSheet(QStringLiteral("#gozarAccountRows {background:#090e19;}"));
    auto *list = new QVBoxLayout(rows);
    list->setContentsMargins(0, 0, 0, 0);
    list->setSpacing(0);
    QString selected;
    const auto accounts = state.value(QStringLiteral("accounts")).toArray();
    for (const auto &value : accounts) {
        const auto account = value.toObject();
        const auto id = account.value(QStringLiteral("id")).toString();
        const auto address = account.value(QStringLiteral("email")).toString();
        if (id.isEmpty() || address.isEmpty()) continue;
        const bool active = account.value(QStringLiteral("active")).toBool();
        auto *row = new QPushButton(address, rows);
        row->setProperty("accountRow", true);
        row->setIcon(glyph(active ? "CHECKMARK" : "preferences/ACCOUNT_ICON",
            active ? FontManager::instance().getSeaGreenColor() : QColor(0x9b, 0xa7, 0xb3)));
        row->setMinimumHeight(qCeil(38 * G_SCALE));
        row->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Fixed);
        row->setLayoutDirection(Qt::LeftToRight);
        row->setEnabled(!active);
        row->setObjectName(active ? QStringLiteral("activeAccount") : QStringLiteral("savedAccount"));
        row->setToolTip(address + QLatin1Char('\n') + (active ? local("Active account", "حساب فعال") : local("Switch to this account", "تغییر به این حساب")));
        list->addWidget(row);
        connect(row, &QPushButton::clicked, &dialog, [&dialog, &selected, id] { selected = id; dialog.accept(); });
    }
    list->addStretch();
    scroll->setWidget(rows);
    scroll->setFixedHeight(qCeil(qBound(40, int(accounts.size()) * 38, 190) * G_SCALE));
    body->addWidget(scroll);
    auto *actions = new QHBoxLayout;
    auto *close = action(dialog, actions, local("Close", "بستن"), nullptr, true);
    actions->addStretch();
    auto *add = action(dialog, actions, local("Add account", "افزودن حساب"));
    body->addLayout(actions);
    close->setFocus();
    connect(close, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(add, &QPushButton::clicked, &dialog, [&dialog] { dialog.done(2); });
    QTimer::singleShot(0, &dialog, [&] { emit accountDialogOpened(&dialog); });
    const auto result = dialog.exec();
    if ((result != QDialog::Accepted && result != 2) || !binder_->isAvailable()) return;
    if (binder_->currentState().value(QStringLiteral("live")).toBool() &&
        QMessageBox::question(this, local("Disconnect first?", "اتصال قطع شود؟"),
            local("Changing accounts closes the current tunnel.", "تغییر حساب، تونل فعلی را می‌بندد."),
            QMessageBox::Yes | QMessageBox::Cancel, QMessageBox::Cancel) != QMessageBox::Yes) return;
    if (result == 2) emit addAccountRequested();
    else binder_->switchAccount(selected);
}

void GozarPreferences::editProxyChain() {
    if (readOnly_ || !binder_->canChangeMode()) return;
    QStringList values;
    for (const auto &value : binder_->currentState().value(QStringLiteral("chain")).toArray())
        if (value.isString()) values.append(value.toString());
    bool ok = false;
    const auto text = QInputDialog::getMultiLineText(this, local("Proxy chain", "زنجیرهٔ پراکسی"),
        local("One SOCKS5 or HTTP proxy URL per line, in order. Leave blank to clear.",
              "در هر خط نشانی یک پراکسی SOCKS5 یا HTTP را به ترتیب وارد کنید. برای حذف خالی بگذارید."),
        values.join(QLatin1Char('\n')), &ok);
    if (!ok || !binder_->canChangeMode()) return;
    QJsonArray hops;
    for (const auto &line : text.split(QLatin1Char('\n'))) if (!line.trimmed().isEmpty()) hops.append(line.trimmed());
    binder_->setChain(hops);
}

void GozarPreferences::showDonation() {
    const auto address = QStringLiteral("UQDxiU-jPFK_TgB0wOzZndhf3IKJLTLm8Vg_Uaif7_C2eCL3"); // same as desktop 1.19.0
    QDialog dialog(this);
    dialog.setObjectName(QStringLiteral("supportGozar"));
    auto *body = sheet(dialog, local("Support Gozar", "حمایت از گذر"), "locations/FAV_ICON_DESELECTED");
    plainLabel(dialog, body, local("Support development with TON.", "با رمزارز TON از توسعهٔ گذر حمایت کنید."));
    auto *value = plainLabel(dialog, body, address, true);
    auto fixed = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    fixed.setPixelSize(qCeil(10 * G_SCALE));
    value->setFont(fixed);
    value->setWordWrap(false);
    value->setLayoutDirection(Qt::LeftToRight);
    value->setAlignment(Qt::AlignLeft | Qt::AlignAbsolute);
    auto *actions = new QHBoxLayout;
    body->addLayout(actions);
    auto *close = action(dialog, actions, local("Close", "بستن"), nullptr, true);
    actions->addStretch();
    auto *copy = action(dialog, actions, local("Copy address", "کپی نشانی"), "preferences/COPY_ICON");
    copy->setObjectName(QStringLiteral("copyDonation"));
    connect(close, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(copy, &QPushButton::clicked, &dialog, [copy, address] {
        QApplication::clipboard()->setText(address);
        copy->setText(local("Copied", "کپی شد"));
    });
    QTimer::singleShot(0, &dialog, [&] { emit detailDialogOpened(&dialog); });
    dialog.exec();
}

void GozarPreferences::showSession() {
    QDialog dialog(this);
    dialog.setObjectName(QStringLiteral("sessionDetails"));
    auto *body = sheet(dialog, local("Session details", "جزئیات نشست"), "pingbar/ON_FULL");
    const auto state = binder_->currentState();
    const bool live = state.value(QStringLiteral("live")).toBool();
    const auto mode = state.value(QStringLiteral("mode")).toString();
    const auto modeLabel = mode == QLatin1String("tun") ? local("System tunnel", "تونل سیستم") :
        mode == QLatin1String("proxy-only") ? local("Local proxy", "پراکسی محلی") :
        mode == QLatin1String("system-proxy") ? local("System proxy", "پراکسی سیستم") : QStringLiteral("—");
    const auto add = [&](const QString &title, const QString &value) {
        plainLabel(dialog, body, title + QStringLiteral(":  ") + (value.isEmpty() ? QStringLiteral("—") : value), true);
    };
    add(local("Connection mode", "حالت اتصال"), modeLabel);
    const auto relay = state.value(QStringLiteral("server")).toObject().value(QStringLiteral("port")).toInt();
    if (relay > 0 && relay <= 65535) add(local("Relay port", "درگاه رله"), QString::number(relay));
    if (!live) plainLabel(dialog, body, local("No active session.", "نشست فعالی وجود ندارد."));
    else if (mode == QLatin1String("tun")) {
        add(local("Tunnel device", "رابط تونل"), state.value(QStringLiteral("device")).toString());
        add(local("Exit IP", "آی‌پی خروجی"), state.value(QStringLiteral("exit")).toString());
        const auto down = state.value(QStringLiteral("down")).toInteger(-1);
        const auto up = state.value(QStringLiteral("up")).toInteger(-1);
        add(local("Session traffic", "ترافیک نشست"), down >= 0 && up >= 0 && down <= std::numeric_limits<qint64>::max() - up
            ? QLocale().formattedDataSize(down + up, 1) : QString());
    } else {
        const int port = state.value(QStringLiteral("port")).toInt();
        if (port > 0 && port <= 65535) add(local("Local address", "نشانی محلی"), QStringLiteral("127.0.0.1:%1").arg(port));
        plainLabel(dialog, body, local("The core does not report traffic counters in this mode.", "هسته در این حالت شمارندهٔ ترافیک گزارش نمی‌کند."));
    }
    auto *actions = new QHBoxLayout;
    body->addLayout(actions);
    actions->addStretch();
    connect(action(dialog, actions, local("Close", "بستن")), &QPushButton::clicked, &dialog, &QDialog::reject);
    QTimer::singleShot(0, &dialog, [&] { emit detailDialogOpened(&dialog); });
    dialog.exec();
}

QJsonObject GozarPreferences::runUpdate(const QString &artifact, bool &ok) {
    ok = false;
    const bool downloading = !artifact.isEmpty();
    UpdateDialog dialog(this);
    dialog.setObjectName(QStringLiteral("updateProgress"));
    auto *body = sheet(dialog, local("Updates", "به‌روزرسانی"), "preferences/UPDATE_CHANNEL");
    auto *label = plainLabel(dialog, body, downloading ? local("Downloading and verifying the package…", "در حال دریافت و بررسی بسته…") :
        local("Checking the signed release…", "در حال بررسی امضای نسخه…"));
    auto *busy = new QProgressBar(&dialog);
    busy->setRange(0, 0);
    busy->setTextVisible(false);
    busy->setFixedHeight(qCeil(3 * G_SCALE));
    body->addWidget(busy);
    auto *actions = new QHBoxLayout;
    body->addLayout(actions);
    actions->addStretch();
    auto *cancel = action(dialog, actions, local("Cancel", "لغو"));
    cancel->setObjectName(QStringLiteral("cancelUpdate"));
    dialog.cancel = [=, this] {
        if (!cancel->isEnabled()) return;
        if (binder_->cancelUpdate()) {
            label->setText(local("Cancelling…", "در حال لغو…"));
            cancel->setEnabled(false);
        } else label->setText(local("Could not send cancellation. Waiting for the core.", "درخواست لغو ارسال نشد. منتظر پاسخ هسته بمانید."));
    };
    connect(cancel, &QPushButton::clicked, &dialog, [&] { dialog.reject(); });
    QJsonObject result;
    auto error = QStringLiteral("update unavailable");
    connect(binder_, &HomeBinder::updateFinished, &dialog, [&](const QJsonObject &reply, const QString &message) {
        result = reply;
        error = message;
        dialog.accept();
    });
    if (binder_->requestUpdate(artifact)) {
        QTimer::singleShot(0, &dialog, [&] { emit detailDialogOpened(&dialog); });
        dialog.exec();
    }
    if (error == QLatin1String("update operation cancelled")) return {};
    if (!error.isEmpty()) {
        QMessageBox::warning(this, local("Updates", "به‌روزرسانی"), error == QLatin1String("cancel unconfirmed")
            ? local("The core did not confirm cancellation. No file was opened.", "هسته لغو را تأیید نکرد. هیچ فایلی باز نشد.")
            : downloading ? local("Could not download or verify the package.", "دریافت یا بررسی بسته ناموفق بود.")
                          : local("Could not check or verify the update.", "بررسی یا تأیید به‌روزرسانی ناموفق بود."));
        return {};
    }
    ok = true;
    return result;
}

void GozarPreferences::checkUpdates() {
    if (readOnly_) return;
    bool ok = false;
    const auto update = runUpdate({}, ok);
    if (!ok) return;
    const auto version = update.value(QStringLiteral("version")).toString();
    const auto newer = update.value(QStringLiteral("newer"));
    if (version.isEmpty() || !newer.isBool()) {
        QMessageBox::warning(this, local("Updates", "به‌روزرسانی"), local("Invalid update response.", "پاسخ به‌روزرسانی نامعتبر است."));
        return;
    }
    if (!newer.toBool()) {
        QMessageBox::information(this, local("Updates", "به‌روزرسانی"), local("You are up to date.", "نسخهٔ شما به‌روز است."));
        return;
    }
    QString artifact;
    {
        QDialog dialog(this);
        dialog.setObjectName(QStringLiteral("updatePackages"));
        auto *body = sheet(dialog, local("Updates", "به‌روزرسانی"), "preferences/UPDATE_CHANNEL");
        plainLabel(dialog, body, local("Verified release", "نسخهٔ تأییدشده") + QStringLiteral("  \u2066%1\u2069").arg(version));
        auto *list = new QListWidget(&dialog);
        list->setLayoutDirection(Qt::LeftToRight);
        for (const auto &value : update.value(QStringLiteral("artifacts")).toArray()) {
            const auto item = value.toObject();
            const auto id = item.value(QStringLiteral("id")).toString();
            const auto name = item.value(QStringLiteral("name")).toString();
            const auto size = item.value(QStringLiteral("size")).toInteger(-1);
            if (id.isEmpty() || name.isEmpty() || size < 0) continue;
            auto *row = new QListWidgetItem(name + QStringLiteral("  ·  ") + QLocale().formattedDataSize(size, 1), list);
            row->setData(Qt::UserRole, id);
            row->setToolTip(name);
        }
        if (!list->count()) {
            QMessageBox::information(this, local("Updates", "به‌روزرسانی"), local("No signed package is available for this device.", "بستهٔ امضاشده‌ای برای این دستگاه موجود نیست."));
            return;
        }
        list->setFixedHeight(qCeil(qBound(64, list->count() * 35, 186) * G_SCALE));
        body->addWidget(list);
        auto *actions = new QHBoxLayout;
        body->addLayout(actions);
        auto *close = action(dialog, actions, local("Close", "بستن"), nullptr, true);
        actions->addStretch();
        auto *download = action(dialog, actions, local("Download", "دریافت"));
        download->setObjectName(QStringLiteral("downloadUpdate"));
        download->setEnabled(false);
        connect(list, &QListWidget::currentItemChanged, download, [download](QListWidgetItem *row) { download->setEnabled(row != nullptr); });
        connect(close, &QPushButton::clicked, &dialog, &QDialog::reject);
        connect(download, &QPushButton::clicked, &dialog, [&] {
            if (!list->currentItem()) return;
            artifact = list->currentItem()->data(Qt::UserRole).toString();
            dialog.accept();
        });
        QTimer::singleShot(0, &dialog, [&] { emit detailDialogOpened(&dialog); });
        if (dialog.exec() != QDialog::Accepted || artifact.isEmpty()) return;
    }
    const auto downloaded = runUpdate(artifact, ok);
    if (!ok) return;
    const auto path = downloaded.value(QStringLiteral("downloaded_path")).toString();
    const QFileInfo file(path);
    if (!file.isAbsolute() || !file.isFile()) {
        QMessageBox::warning(this, local("Updates", "به‌روزرسانی"), local("Verified download path is unavailable.", "مسیر فایل تأییدشده در دسترس نیست."));
        return;
    }
    QDialog dialog(this);
    dialog.setObjectName(QStringLiteral("updateDownloaded"));
    auto *body = sheet(dialog, local("Package verified", "بسته تأیید شد"), "preferences/SUCCESS_ICON");
    plainLabel(dialog, body, local("Downloaded and verified by the core. Not installed. Automatic rollback is not supported.",
                                 "هسته فایل را دریافت و تأیید کرد. بسته نصب نشده و بازگشت خودکار پشتیبانی نمی‌شود."));
    auto *fileLabel = plainLabel(dialog, body, QDir::toNativeSeparators(path), true);
    fileLabel->setLayoutDirection(Qt::LeftToRight);
    auto *actions = new QHBoxLayout;
    body->addLayout(actions);
    auto *close = action(dialog, actions, local("Close", "بستن"), nullptr, true);
    actions->addStretch();
    auto *copy = action(dialog, actions, local("Copy path", "کپی مسیر"), "preferences/COPY_ICON");
    copy->setObjectName(QStringLiteral("copyUpdatePath"));
    auto *open = action(dialog, actions, local("Open folder", "بازکردن پوشه"));
    connect(close, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(copy, &QPushButton::clicked, &dialog, [path] { QApplication::clipboard()->setText(QDir::toNativeSeparators(path)); });
    connect(open, &QPushButton::clicked, &dialog, [&] {
        if (QDesktopServices::openUrl(QUrl::fromLocalFile(file.absolutePath()))) dialog.accept();
        else QMessageBox::warning(&dialog, local("Updates", "به‌روزرسانی"), local("Could not open the folder.", "پوشه باز نشد."));
    });
    QTimer::singleShot(0, &dialog, [&] { emit detailDialogOpened(&dialog); });
    dialog.exec();
}
