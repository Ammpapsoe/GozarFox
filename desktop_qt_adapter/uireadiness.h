// SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
#pragma once

#include <QObject>
#include <QPointer>

class QWidget;
class HomeBinder;

// Experimental Qt-side readiness gate for the Flutter process launcher.
// A real application must explicitly mark its complete, usable original UI as
// initialized, then write the protocol marker only on readyForHandoff().
// This gate does not build an application, validate OS cleanup, or attest that
// unsupported controls are safe. Both objects must live on the GUI thread.
class UiReadiness final : public QObject {
    Q_OBJECT
public:
    UiReadiness(QWidget *window, HomeBinder *binder);
    void markUiInitialized();

signals:
    void readyForHandoff();

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void check();
    QPointer<QWidget> window_;
    QPointer<HomeBinder> binder_;
    bool initialized_ = false;
    bool notified_ = false;
};
