// SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
#include "uireadiness.h"
#include "homebinder.h"

#include <QEvent>
#include <QThread>
#include <QTimer>
#include <QWidget>

UiReadiness::UiReadiness(QWidget *window, HomeBinder *binder)
    : QObject(window), window_(window), binder_(binder) {
    Q_ASSERT(window && binder);
    window->installEventFilter(this);
    connect(binder, &HomeBinder::stateChanged, this, [this](const QJsonObject &) { check(); });
    connect(binder, &HomeBinder::availabilityChanged, this, [this](bool) { check(); });
}

void UiReadiness::markUiInitialized() {
    initialized_ = true;
    check();
}

bool UiReadiness::eventFilter(QObject *watched, QEvent *event) {
    if (watched == window_ && event->type() == QEvent::Show)
        QTimer::singleShot(0, this, [this] { check(); });
    return QObject::eventFilter(watched, event);
}

void UiReadiness::check() {
    if (notified_ || !initialized_ || !window_ || !binder_) return;
    if (window_->thread() != QThread::currentThread() || binder_->thread() != QThread::currentThread()) return;
    // canChangeMode requires a running core, validated signed-in state, no
    // pending mutation, and neither a live nor connecting session.
    if (!window_->isVisible() || !binder_->canChangeMode()) return;
    const QJsonObject state = binder_->currentState();
    const QJsonValue modeValue = state.value(QStringLiteral("mode"));
    const QString mode = modeValue.toString();
    if (!modeValue.isString() ||
        (mode != QLatin1String("tun") && mode != QLatin1String("proxy-only") &&
         mode != QLatin1String("system-proxy"))) return;
#ifndef Q_OS_WIN
    // The unchanged core cannot establish system-proxy mode on these hosts.
    if (mode == QLatin1String("system-proxy")) return;
#endif
    notified_ = true;
    emit readyForHandoff();
}
