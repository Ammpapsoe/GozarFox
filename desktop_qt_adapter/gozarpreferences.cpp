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

#include <QDesktopServices>
#include <QInputDialog>
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
        page_ == TAB_LOOK_AND_FEEL ? local("Appearance", "ظاهر") : local("General", "عمومی");
    title_->setTitle(page + QStringLiteral("  ·  ") + local("Experimental", "آزمایشی"));
    title_->update();
    theme_->setLabelCaption(local("Flutter sign-in theme", "پوستهٔ ورود Flutter"));
    syncTheme();
}

void GozarPreferences::showPage(int page) {
    page_ = page;
    setProperty("gozarPage", page == TAB_CONNECTION ? QStringLiteral("connection") :
        page == TAB_LOOK_AND_FEEL ? QStringLiteral("appearance") : QStringLiteral("general"));
    general_->setVisible(page == TAB_GENERAL);
    connection_->setVisible(page == TAB_CONNECTION);
    appearance_->setVisible(page == TAB_LOOK_AND_FEEL);
    retranslate();
    viewport()->update();
}

void GozarPreferences::fitPanel(int w, int maxHeight) {
    title_->updateScaling();
    title_->setPos(60 * G_SCALE, 14 * G_SCALE);
    for (auto *group : {connection_, general_, appearance_}) {
        group->updateScaling();
        group->setPos(60 * G_SCALE, 39 * G_SCALE);
    }
    const int contentHeight = qMax(qCeil(224 * G_SCALE), qCeil(connection_->sceneBoundingRect().bottom() + 14 * G_SCALE));
    tabs_->setHeight(contentHeight);
    tabs_->updateScaling();
    setSceneRect(0, 0, w - 6, contentHeight);
    setFixedSize(w, qBound(1, contentHeight, qMax(1, maxHeight)));
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
