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
#include <QPainter>

#include <QDesktopServices>
#include <QApplication>
#include <QInputDialog>
#include <QDialog>
#include <QLabel>
#include <QScrollArea>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QTimer>
#include <QMessageBox>
#include <QPushButton>
#include <QSignalBlocker>
#include <QUrl>
#include <QtMath>
#include <utility>

namespace {
QString local(const char *en, const char *fa) {
    return LanguageController::instance().getLanguage() == QLatin1String("fa") ? QString::fromUtf8(fa) : QString::fromUtf8(en);
}
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
    email_ = new LinkItem(account_, LinkItem::TEXT_ONLY);
    switch_ = new LinkItem(account_, LinkItem::SUBPAGE_LINK);
    logout_ = new LinkItem(account_, LinkItem::BLANK_LINK);
    usage_ = new LinkItem(account_, LinkItem::BLANK_LINK);
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
    if (!readOnly_) new OriginalModeBinding<ComboBoxItem>(mode, binder_);
    else mode->setClickable(false);
    addToggle(connection_, "kill_switch", "Kill switch (next tunnel)", "قطع اضطراری (تونل بعدی)",
              [this](bool enabled) { binder_->setKillSwitch(enabled); }, true);
    addToggle(connection_, "warp_udp", "WARP UDP (experimental)", "WARP UDP (آزمایشی)", [this](bool enabled) {
        if (enabled) {
            QMessageBox dialog(QMessageBox::Question, local("WARP UDP", "WARP UDP"),
                local("Enable experimental WARP UDP for system tunnels? The change takes effect on reconnect. Confirm only if you accept Cloudflare's terms.",
                      "WARP UDP آزمایشی برای تونل سیستم فعال شود؟ تغییر در اتصال بعدی اعمال می‌شود. فقط در صورت پذیرش شرایط کلادفلر تأیید کنید."),
                QMessageBox::Yes | QMessageBox::Cancel, this);
            auto *terms = dialog.addButton(local("Cloudflare terms", "شرایط کلادفلر"), QMessageBox::HelpRole);
            connect(terms, &QPushButton::clicked, &dialog, [] {
                QDesktopServices::openUrl(QUrl(QStringLiteral("https://www.cloudflare.com/application/terms/")));
            });
            dialog.setDefaultButton(QMessageBox::Cancel);
            if (dialog.exec() != QMessageBox::Yes || dialog.clickedButton() == terms) return;
        }
        binder_->setWarpUdp(enabled, enabled);
    }, true);
    auto *chain = new LinkItem(connection_, LinkItem::SUBPAGE_LINK);
    connection_->addItem(chain);
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

    auto *languageItem = new ComboBoxItem(general_);
    general_->addItem(languageItem);
    if (!readOnly_) new OriginalLanguageBinding<ComboBoxItem>(languageItem, language, binder_);
    else languageItem->setClickable(false);
    theme_ = new ComboBoxItem(appearance_);
    appearance_->addItem(theme_);
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
    group->addItem(item);
    const QString name = QString::fromLatin1(key);
    const auto sync = [this, item, name, tunnelOnly] {
        const auto state = binder_->currentState();
        const auto saved = state.value(name);
        item->setEnabled(!readOnly_ && saved.isBool() && binder_->isAvailable() &&
                         (!tunnelOnly || state.value(QStringLiteral("mode")).toString() == QLatin1String("tun")));
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
        page_ == TAB_ACCOUNT ? local("Account", "حساب کاربری") : local("General", "عمومی");
    title_->setTitle(page + QStringLiteral("  ·  ") + local("Experimental", "آزمایشی"));
    title_->update();
    theme_->setLabelCaption(local("Flutter sign-in theme", "پوستهٔ ورود Flutter"));
    syncTheme();
    syncAccount();
}

void GozarPreferences::showPage(int page) {
    page_ = page;
    setProperty("gozarPage", page == TAB_CONNECTION ? QStringLiteral("connection") :
        page == TAB_LOOK_AND_FEEL ? QStringLiteral("appearance") :
        page == TAB_ACCOUNT ? QStringLiteral("account") : QStringLiteral("general"));
    general_->setVisible(page == TAB_GENERAL);
    connection_->setVisible(page == TAB_CONNECTION);
    appearance_->setVisible(page == TAB_LOOK_AND_FEEL);
    account_->setVisible(page == TAB_ACCOUNT);
    retranslate();
    viewport()->update();
}

void GozarPreferences::fitPanel(int w, int maxHeight) {
    title_->updateScaling();
    title_->setPos(60 * G_SCALE, 14 * G_SCALE);
    for (auto *group : {connection_, general_, appearance_, account_}) {
        group->updateScaling();
        group->setPos(60 * G_SCALE, 39 * G_SCALE);
    }
    const int contentHeight = qMax(qCeil(264 * G_SCALE), qCeil(connection_->sceneBoundingRect().bottom() + 14 * G_SCALE));
    tabs_->setHeight(contentHeight);
    tabs_->updateScaling();
    setSceneRect(0, 0, w - 6, contentHeight);
    setFixedSize(w, qBound(1, contentHeight, qMax(1, maxHeight)));
}

void GozarPreferences::syncAccount() {
    if (!email_) return;
    const auto state = binder_->currentState();
    email_->setTitle(state.value(QStringLiteral("email")).toString());
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
    dialog.setWindowTitle(local("Saved accounts", "حساب‌های ذخیره‌شده"));
    dialog.setWindowFlags(Qt::Dialog | Qt::FramelessWindowHint);
    dialog.setLayoutDirection(LanguageController::instance().getLanguage() == QLatin1String("fa") ? Qt::RightToLeft : Qt::LeftToRight);
    dialog.setFont(FontManager::instance().getFont(14, QFont::Normal));
    dialog.setStyleSheet(QStringLiteral("QDialog {background:#090e19;border:1px solid #2a3b54;} QLabel {color:#e8f0fc;} QPushButton {background:#1a273a;color:#e8f0fc;border:1px solid #2a3b54;border-radius:6px;padding:9px;} QPushButton:hover {background:#27374d;border-color:#91b6d7;} QPushButton:disabled {background:#182b31;color:#73edc3;} QPushButton:focus {border-color:#91b6d7;} QScrollArea {border:0;background:#090e19;}"));
    auto *body = new QVBoxLayout(&dialog);
    body->setContentsMargins(14, 14, 14, 14);
    auto *heading = new QHBoxLayout;
    auto *icon = new QLabel(&dialog);
    if (auto asset = ImageResourcesSvg::instance().getIndependentPixmap(QStringLiteral("preferences/ACCOUNT_ICON"))) {
        const auto size = qCeil(16 * G_SCALE);
        auto pixmap = asset->getIcon().pixmap(QSize(size, size), dialog.devicePixelRatioF());
        QPainter tint(&pixmap);
        tint.setCompositionMode(QPainter::CompositionMode_SourceIn);
        tint.fillRect(QRectF(QPointF(), pixmap.deviceIndependentSize()), QColor(0xa9, 0xb7, 0xce));
        tint.end();
        icon->setPixmap(pixmap);
        heading->addWidget(icon);
    } else icon->hide();
    auto *caption = new QLabel(local("Switch account", "تغییر حساب"), &dialog);
    caption->setFont(FontManager::instance().getFont(15, QFont::DemiBold));
    heading->addWidget(caption, 1);
    body->addLayout(heading);
    auto *scroll = new QScrollArea(&dialog);
    scroll->setWidgetResizable(true);
    auto *rows = new QWidget(scroll);
    rows->setObjectName(QStringLiteral("gozarAccountRows"));
    rows->setStyleSheet(QStringLiteral("#gozarAccountRows {background:#090e19;}"));
    auto *list = new QVBoxLayout(rows);
    list->setContentsMargins(0, 0, 0, 0);
    QString selected;
    const auto accounts = state.value(QStringLiteral("accounts")).toArray();
    for (const auto &value : accounts) {
        const auto account = value.toObject();
        const auto id = account.value(QStringLiteral("id")).toString();
        const auto address = account.value(QStringLiteral("email")).toString();
        if (id.isEmpty() || address.isEmpty()) continue;
        const bool active = account.value(QStringLiteral("active")).toBool();
        auto *row = new QPushButton((active ? QStringLiteral("✓  ") : QString()) + address, rows);
        row->setLayoutDirection(Qt::LeftToRight);
        row->setEnabled(!active);
        row->setObjectName(active ? QStringLiteral("activeAccount") : QStringLiteral("savedAccount"));
        row->setToolTip(active ? local("Active account", "حساب فعال") : local("Switch to this account", "تغییر به این حساب"));
        list->addWidget(row);
        connect(row, &QPushButton::clicked, &dialog, [&dialog, &selected, id] { selected = id; dialog.accept(); });
    }
    list->addStretch();
    scroll->setWidget(rows);
    scroll->setFixedHeight(qBound(84, int(accounts.size()) * 44, 220));
    body->addWidget(scroll);
    auto *actions = new QHBoxLayout;
    auto *close = new QPushButton(local("Close", "بستن"), &dialog);
    auto *add = new QPushButton(local("Add account", "افزودن حساب"), &dialog);
    actions->addWidget(close);
    actions->addStretch();
    actions->addWidget(add);
    body->addLayout(actions);
    close->setFocus();
    connect(close, &QPushButton::clicked, &dialog, &QDialog::reject);
    connect(add, &QPushButton::clicked, &dialog, [&dialog] { dialog.done(2); });
    dialog.setMinimumWidth(qCeil(332 * G_SCALE));
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
