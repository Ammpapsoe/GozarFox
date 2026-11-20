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
    notified_ = true;
    emit readyForHandoff();
}
