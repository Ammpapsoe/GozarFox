#pragma once

#include "homebinder.h"
#include "types/connectstate.h"
#include "types/pingtime.h"

// Experimental binding for the actual upstream ConnectWindowItem's public API.
// The application must keep unsupported controls disabled and must not ship
// this as a complete home screen. In particular, upstream's protocol and
// firewall displays do not correspond to Gozar's mode/kill-switch fields.
// The binder must be constructed without the standalone background/button:
// the original window itself owns and updates those children.
template<class Window>
void bindOriginalHomeWindow(Window *window, HomeBinder *binder) {
    Q_ASSERT(window && binder);
    QObject::connect(window, &Window::connectClick, binder, &HomeBinder::toggleConnection);
    QObject::connect(binder, &HomeBinder::visualStateChanged, window,
                     [window](CONNECT_STATE state) {
        types::ConnectState converted;
        converted.connectState = state;
        window->updateConnectState(converted);
    });
    QObject::connect(binder, &HomeBinder::stateChanged, window,
                     [window](const QJsonObject &state) {
        const auto server = state.value(QStringLiteral("server")).toObject();
        const QString country = server.value(QStringLiteral("country")).toString();
        const QString city = server.value(QStringLiteral("city")).toString();
        const QString code = server.value(QStringLiteral("code")).toString().toLower();
        // No ping measurement exists in the Gozar state; do not invent one.
        window->updateLocationInfo(country, city, code, PingTime(), false);
    });
}
