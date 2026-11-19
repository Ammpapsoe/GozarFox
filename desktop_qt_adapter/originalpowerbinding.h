#pragma once

#include "homebinder.h"
#include "connectwindow/connectwindowitem.h"
#include "connectwindow/connectbutton.h"

// Experimental adapter for the original, privately owned power button. This
// changes no upstream graphics and never enables the other controls, whose
// Windscribe semantics may not exist in Gozar. The owner must install it after
// any call to ConnectWindowItem::setClickable(), which affects every control.
inline bool bindOriginalPowerControl(ConnectWindow::ConnectWindowItem *window, HomeBinder *binder) {
    Q_ASSERT(window && binder);
    ConnectWindow::ConnectButton *power = nullptr;
    for (auto *item : window->childItems()) {
        if (auto *candidate = dynamic_cast<ConnectWindow::ConnectButton *>(item)) {
            candidate->setClickable(false);
            if (power) {
                power->setClickable(false);
                emit binder->error(QStringLiteral("ambiguous original power control"));
                return false;
            }
            power = candidate;
        }
    }
    if (!power) {
        emit binder->error(QStringLiteral("original power control not found"));
        return false;
    }
    if (power->property("gozarPowerBound").toBool()) {
        emit binder->error(QStringLiteral("original power control already bound"));
        return false;
    }
    power->setProperty("gozarPowerBound", true);
    QObject::connect(binder, &HomeBinder::availabilityChanged, power,
                     [power](bool available) { power->setClickable(available); });
    QObject::connect(binder, &QObject::destroyed, power, [power] {
        power->setClickable(false);
        power->setProperty("gozarPowerBound", false);
    });
    power->setClickable(binder->isAvailable());
    return true;
}
