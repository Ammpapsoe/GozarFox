#pragma once

#include "homebinder.h"
#include "locationcatalog.h"

// Experimental integration contract for the original LocationsWindow and
// LocationsModelManager. The owner must keep catalog alive at least as long as
// window, manager and binder. Other location tabs/controls are not integrated.
template<class Window, class Manager>
void bindOriginalLocations(Window *window, Manager *manager, HomeBinder *binder, LocationCatalog *catalog) {
    Q_ASSERT(window && manager && binder && catalog);
    const auto invalidate = [manager, catalog] {
        catalog->replace(QJsonArray());
        manager->updateLocations(LocationID(), {});
    };
    invalidate();
    QObject::connect(binder, &HomeBinder::serversInvalidated, manager, invalidate);
    QObject::connect(binder, &QObject::destroyed, manager, invalidate);
    const QPointer<HomeBinder> safeBinder(binder);
    QObject::connect(binder, &HomeBinder::serversReady, manager,
                     [manager, safeBinder, catalog](const QJsonArray &servers) {
        if (!safeBinder) return;
        QString error;
        if (!catalog->replace(servers, &error)) {
            manager->updateLocations(LocationID(), {});
            emit safeBinder->error(error);
            return;
        }
        // Gozar's automatic-server selection is not mapped to Windscribe's
        // best-location identity yet; do not fabricate a preferred server.
        manager->updateLocations(LocationID(), catalog->locations());
    });
    QObject::connect(window, &Window::selected, binder,
                     [binder, catalog](const LocationID &id) {
        LocationCatalog::Selection choice;
        if (!catalog->selection(id, &choice)) {
            emit binder->error(QStringLiteral("selected location is unavailable"));
            return;
        }
        binder->selectServer(choice.host, choice.countryCode);
    });
}
