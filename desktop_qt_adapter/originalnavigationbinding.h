#pragma once

#include "homebinder.h"
#include "connectwindow/connectwindowitem.h"
#include <QPointer>

// Connect the original home button to MainWindowController's own expanding
// and collapsing animations. Do not also keep Windscribe MainWindow's original
// backend-bound locationsClick handler connected, or clicks will run twice.
// The caller owns the controller and is responsible for all unsupported pages.
template<class Controller>
void bindOriginalLocationsNavigation(Controller *controller, HomeBinder *binder) {
    Q_ASSERT(controller && binder && controller->getConnectWindow());
    auto *home = controller->getConnectWindow();
    const QPointer<HomeBinder> safeBinder(binder);
    const QPointer<Controller> safeController(controller);
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationsClick,
                     controller, [safeController, safeBinder] {
        if (!safeController || !safeBinder || safeController->currentWindow() != Controller::WINDOW_ID_CONNECT) return;
        if (safeController->isLocationsExpanded()) {
            safeController->collapseLocations();
            return;
        }
        if (!safeBinder->isAvailable()) {
            emit safeBinder->error(QObject::tr("The core is unavailable or its state is unknown."));
            return;
        }
        safeBinder->loadServers();
        if (safeController) safeController->expandLocations();
    });
}
