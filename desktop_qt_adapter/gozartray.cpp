#include "gozartray.h"
#include "homebinder.h"
#include "locationcatalog.h"
#include "languagecontroller.h"
#include "locations/locationsmodel_manager.h"
#include "systemtray/locationstraymenunative.h"

#include <QIcon>

namespace {
QString label(const char *en, const char *fa) {
    return LanguageController::instance().getLanguage() == QLatin1String("fa") ? QString::fromUtf8(fa) : QString::fromUtf8(en);
}
}

GozarTray::GozarTray(HomeBinder *binder, LocationCatalog *catalog, gui_locations::LocationsModelManager *manager)
    : binder_(binder), catalog_(catalog), manager_(manager) {
    icon_.setIcon(QIcon(QStringLiteral(":/gozar/app.png")));
    icon_.setToolTip(QStringLiteral("Gozar"));
    icon_.setContextMenu(&menu_);
    connect(&menu_, &QMenu::aboutToShow, this, &GozarTray::rebuild);
    connect(&icon_, &QSystemTrayIcon::activated, this, [this](QSystemTrayIcon::ActivationReason reason) {
        if (reason == QSystemTrayIcon::Trigger || reason == QSystemTrayIcon::DoubleClick) emit showRequested();
    });
    rebuild();
}

void GozarTray::attachIfAvailable() {
    if (QSystemTrayIcon::isSystemTrayAvailable()) icon_.show();
}

void GozarTray::rebuild() {
    // Copy only for destruction: QMenu::clear() may leave a submenu object
    // alive, and stale action callbacks must not accumulate across openings.
    for (auto *action : menu_.actions()) if (auto *submenu = action->menu()) delete submenu;
    menu_.clear();
    const auto state = binder_->currentState();
    const bool live = state.value(QStringLiteral("live")).toBool();
    const bool cancel = binder_->canCancelConnect();
    auto *power = menu_.addAction(cancel ? label("Cancel connection", "لغو اتصال") :
        live ? label("Disconnect", "قطع اتصال") : label("Connect", "اتصال"));
    power->setEnabled(binder_->canTogglePower());
    connect(power, &QAction::triggered, this, [this, live, cancel] {
        if (binder_->canTogglePower() && binder_->canCancelConnect() == cancel &&
            binder_->currentState().value(QStringLiteral("live")).toBool() == live)
            binder_->toggleConnection();
    });
    const auto addLocationMenu = [this](const QString &title, QAbstractItemModel *model) {
        auto *submenu = new LocationsTrayMenuNative(&menu_, model, false);
        submenu->setTitle(title);
        submenu->setEnabled(binder_->canChangeMode() && model->rowCount() > 0);
        menu_.addMenu(submenu);
        connect(submenu, &LocationsTrayMenuNative::locationSelected, this, [this](const LocationID &id) {
            LocationCatalog::Selection choice;
            if (binder_->canChangeMode() && catalog_->selection(id, &choice)) binder_->selectServer(choice.host, choice.countryCode);
        });
    };
    addLocationMenu(label("Locations", "مکان‌ها"), manager_->sortedLocationsProxyModel());
    if (manager_->favoriteCitiesProxyModel()->rowCount() > 0)
        addLocationMenu(label("Favourites", "دلخواه‌ها"), manager_->favoriteCitiesProxyModel());
    menu_.addSeparator();
    menu_.addAction(label("Show Gozar", "نمایش گذر"), this, &GozarTray::showRequested);
    menu_.addAction(label("Preferences", "تنظیمات"), this, &GozarTray::preferencesRequested);
    menu_.addSeparator();
    menu_.addAction(label("Quit Gozar", "خروج از گذر"), this, &GozarTray::quitRequested);
}
