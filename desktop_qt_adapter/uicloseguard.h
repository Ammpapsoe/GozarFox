#pragma once

#include <QObject>
#include <QPointer>

class CoreBridge;
class QWidget;

// Experimental GUI-thread close coordinator. A normal close is delayed until
// the child reports clean shutdown and exits; no timer kills the process or
// silently treats uncertain cleanup as successful. The application must keep
// the event loop alive and display errors. This does not validate OS cleanup.
class UiCloseGuard final : public QObject {
    Q_OBJECT
public:
    UiCloseGuard(QWidget *window, CoreBridge *core);

    // Only after independent, explicit recovery/verification by the operator.
    // Never call this automatically on a timeout or a failed core reply.
    void allowCloseAfterExplicitRecovery();

signals:
    void shutdownStarted();
    void shutdownUnconfirmed(const QString &reason);

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    QPointer<QWidget> window_;
    QPointer<CoreBridge> core_;
    bool closing_ = false;
    bool allowed_ = false;
};
