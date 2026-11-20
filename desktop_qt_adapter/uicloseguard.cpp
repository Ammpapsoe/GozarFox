#include "uicloseguard.h"
#include "corebridge.h"

#include <QCloseEvent>
#include <QTimer>
#include <QWidget>

UiCloseGuard::UiCloseGuard(QWidget *window, CoreBridge *core)
    : QObject(window), window_(window), core_(core) {
    Q_ASSERT(window && core && window->thread() == core->thread());
    window->installEventFilter(this);
    connect(core, &CoreBridge::shutdownFinished, this, [this](bool confirmed, const QString &reason) {
        if (!closing_) return;
        if (!confirmed) {
            emit shutdownUnconfirmed(reason);
            return;
        }
        allowed_ = true;
        QTimer::singleShot(0, this, [this] { if (window_) window_->close(); });
    });
    connect(core, &QObject::destroyed, this, [this] {
        if (closing_ && !allowed_) emit shutdownUnconfirmed(QStringLiteral("core bridge was destroyed"));
    });
}

bool UiCloseGuard::eventFilter(QObject *watched, QEvent *event) {
    if (watched != window_ || event->type() != QEvent::Close || allowed_)
        return QObject::eventFilter(watched, event);
    static_cast<QCloseEvent *>(event)->ignore();
    if (closing_) return true;
    closing_ = true;
    QPointer<UiCloseGuard> alive(this);
    emit shutdownStarted();
    if (!alive) return true;
    if (core_ && core_->running()) {
        core_->shutdown();
    } else {
        // Starting, already stopped or transport failure: none proves cleanup.
        emit shutdownUnconfirmed(QStringLiteral("core shutdown could not be confirmed"));
    }
    return true;
}

void UiCloseGuard::allowCloseAfterExplicitRecovery() {
    if (!closing_ || !window_) return;
    allowed_ = true;
    QTimer::singleShot(0, this, [this] { if (window_) window_->close(); });
}
