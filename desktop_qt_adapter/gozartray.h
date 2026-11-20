#pragma once

#include <QMenu>
#include <QSystemTrayIcon>

class HomeBinder;
class LocationCatalog;
namespace gui_locations { class LocationsModelManager; }

// Hosts Windscribe's native locations submenu with the same model and binder
// as the window. The VPN/account backend never belongs to the tray.
class GozarTray : public QObject {
    Q_OBJECT
public:
    GozarTray(HomeBinder *binder, LocationCatalog *catalog,
              gui_locations::LocationsModelManager *manager);
    void attachIfAvailable();
    bool isVisible() const { return icon_.isVisible() && QSystemTrayIcon::isSystemTrayAvailable(); }
    QMenu *menu() { return &menu_; }
    void rebuild();
signals:
    void showRequested();
    void preferencesRequested();
    void quitRequested();
private:
    HomeBinder *binder_;
    LocationCatalog *catalog_;
    gui_locations::LocationsModelManager *manager_;
    QMenu menu_;
    QSystemTrayIcon icon_;
};
