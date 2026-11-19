#pragma once

#include "homebinder.h"
#include "locationcatalog.h"

// Experimental integration contract for the original LocationsWindow and
// LocationsModelManager. The owner must keep catalog alive at least as long as
// window, manager and binder. Other location tabs/controls are not integrated.
template<class Window, class Manager>
void bindOriginalLocations(Window *window, Manager *manager, HomeBinder *binder, LocationCatalog *catalog) {
    Q_ASSERT(window && manager && binder && catalog);
    QObject::connect(binder, &HomeBinder::serversReady, manager,
                     [manager, binder, catalog](const QJsonArray &servers) {
        QString error;
        if (!catalog->replace(servers, &error)) {
            manager->updateLocations(LocationID(), {});
            emit binder->error(error);
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
