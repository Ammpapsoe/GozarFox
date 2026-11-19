#pragma once

#include "homebinder.h"
#include "connectwindow/connectwindowitem.h"
#include "locationswindow/locationswindow.h"

// Connect original Windscribe search and keyboard events to the original
// locations widget. The application still owns expansion/collapse animation.
// Custom configs, static IPs and favorites are not connected to Gozar; reject
// those tabs instead of presenting empty or simulated functionality.
inline void bindOriginalLocationControls(ConnectWindow::ConnectWindowItem *home,
                                         LocationsWindow *locations, HomeBinder *binder) {
    Q_ASSERT(home && locations && binder);
    if (home->thread() != locations->thread()) {
        emit binder->error(QStringLiteral("location controls must share the GUI thread"));
        return;
    }
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::searchFilterChanged,
                     locations, &LocationsWindow::onSearchFilterChanged);
    // The original signal carries a stack-owned QKeyEvent*. Widgets live in the
    // same GUI thread, so enforce synchronous delivery and never queue it.
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationsKeyPressed,
                     locations, &LocationsWindow::onLocationsKeyPressed, Qt::DirectConnection);
    const QPointer<HomeBinder> safeBinder(binder);
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationTabClicked,
                     locations, [locations, safeBinder](LOCATION_TAB tab) {
        if (tab == LOCATION_TAB_ALL_LOCATIONS || tab == LOCATION_TAB_SEARCH_LOCATIONS) {
            locations->setTab(tab);
        } else if (safeBinder) {
            emit safeBinder->error(QStringLiteral("location tab is not supported by Gozar yet"));
        }
    });
}
