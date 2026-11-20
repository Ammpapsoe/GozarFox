// SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
#pragma once

#include "homebinder.h"
#include "connectwindow/connectwindowitem.h"
#include "locationswindow/locationswindow.h"

// Connect original Windscribe search and keyboard events to the original
// locations widget. The application still owns expansion/collapse animation.
// The modified upstream menu must reject unsupported tabs before changing
// visual or persisted state; filtering the outgoing signal alone is too late.
inline bool bindOriginalLocationControls(ConnectWindow::ConnectWindowItem *home,
                                         LocationsWindow *locations, HomeBinder *binder, bool favourites = false) {
    Q_ASSERT(home && locations && binder);
    if (home->thread() != locations->thread() || home->thread() != binder->thread()) {
        emit binder->error(QStringLiteral("location controls must share the GUI thread"));
        return false;
    }
    ConnectWindow::LocationsMenu *menu = nullptr;
    for (auto *item : home->childItems()) {
        auto *button = dynamic_cast<ConnectWindow::LocationsButton *>(item);
        if (!button) continue;
        for (auto *child : button->childItems()) {
            auto *candidate = dynamic_cast<ConnectWindow::LocationsMenu *>(child);
            if (!candidate) continue;
            if (menu) {
                home->setClickable(false);
                emit binder->error(QStringLiteral("ambiguous original locations menu"));
                return false;
            }
            menu = candidate;
        }
    }
    if (!menu || menu->property("gozarLocationsBound").toBool()) {
        home->setClickable(false);
        emit binder->error(QStringLiteral("original locations menu missing or already bound"));
        return false;
    }
    menu->setProperty("gozarLocationsBound", true);
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::searchFilterChanged,
                     locations, &LocationsWindow::onSearchFilterChanged);
    // The original signal carries a stack-owned QKeyEvent*. Widgets live in the
    // same GUI thread, so enforce synchronous delivery and never queue it.
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationsKeyPressed,
                     locations, &LocationsWindow::onLocationsKeyPressed, Qt::DirectConnection);
    const QPointer<HomeBinder> safeBinder(binder);
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationTabClicked,
                     locations, [locations, safeBinder, favourites](LOCATION_TAB tab) {
        if (tab == LOCATION_TAB_ALL_LOCATIONS || tab == LOCATION_TAB_SEARCH_LOCATIONS ||
            (favourites && tab == LOCATION_TAB_FAVORITE_LOCATIONS)) {
            locations->setTab(tab);
        } else if (safeBinder) {
            emit safeBinder->error(QStringLiteral("location tab is not supported by Gozar yet"));
        }
    });
    menu->restrictToGozarLocations(favourites);
    return true;
}
