#pragma once

#include "corebridge.h"
#include "types/enums.h"
#include <QPointer>
#include <QObject>

namespace ConnectWindow { class Background; class ConnectButton; }

// Experimental, isolated integration of two actual upstream widgets with the
// existing Gozar RPC. Not yet a complete application or a replacement login.
class HomeBinder final : public QObject {
    Q_OBJECT
public:
    HomeBinder(CoreBridge *core, ConnectWindow::Background *background,
               ConnectWindow::ConnectButton *button, QObject *parent = nullptr);
    void refresh();
    void selectServer(const QString &host, const QString &countryCode);

signals:
    void error(const QString &message);
    void stateChanged(const QJsonObject &state);

private slots:
    void toggleConnection();
    void handleReply(qint64 id, const QJsonObject &reply);
    void handleFailure(qint64 id, const QString &message);
    void coreStopped();

private:
    void setVisualState(CONNECT_STATE state);
    void applyState(const QJsonObject &state);
    void setEnabled();
    QPointer<CoreBridge> core_;
    QPointer<ConnectWindow::Background> background_;
    QPointer<ConnectWindow::ConnectButton> button_;
    CONNECT_STATE visual_ = CONNECT_STATE_DISCONNECTED;
    qint64 mutatingId_ = -1;
    bool signedIn_ = false;
    bool live_ = false;
    bool connecting_ = false;
    QString country_;
};
