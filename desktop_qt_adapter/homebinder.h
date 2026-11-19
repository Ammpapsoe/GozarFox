#pragma once

#include "corebridge.h"
#include "types/enums.h"
#include <QPointer>
#include <QObject>
#include <QJsonArray>
#include <QTimer>

namespace ConnectWindow { class Background; class ConnectButton; }

// Experimental, isolated integration of two actual upstream widgets with the
// existing Gozar RPC. Not yet a complete application or a replacement login.
class HomeBinder final : public QObject {
    Q_OBJECT
public:
    HomeBinder(CoreBridge *core, ConnectWindow::Background *background,
               ConnectWindow::ConnectButton *button, QObject *parent = nullptr);
    void refresh();
    void loadServers();
    void selectServer(const QString &host, const QString &countryCode);
    void setMode(const QString &mode);
    void setKillSwitch(bool enabled);
    void setLanguage(const QString &language);
    void setTheme(const QString &theme);
    void setRecents(bool enabled);
    void setWarpUdp(bool enabled, bool consent);
    void setChain(const QJsonArray &hops);
    void toggleConnection();
    bool isAvailable() const;

signals:
    void error(const QString &message);
    void stateChanged(const QJsonObject &state);
    void visualStateChanged(CONNECT_STATE state);
    void availabilityChanged(bool available);
    void serversReady(const QJsonArray &servers);

private slots:
    void handleReply(qint64 id, const QJsonObject &reply);
    void handleFailure(qint64 id, const QString &message);
    void coreStopped();

private:
    void setVisualState(CONNECT_STATE state);
    void applyState(const QJsonObject &state);
    void setEnabled();
    void sendMutation(const QString &method, const QJsonObject &params);
    QPointer<CoreBridge> core_;
    QPointer<ConnectWindow::Background> background_;
    QPointer<ConnectWindow::ConnectButton> button_;
    CONNECT_STATE visual_ = CONNECT_STATE_DISCONNECTED;
    qint64 mutatingId_ = -1;
    qint64 stateId_ = -1;
    qint64 serversId_ = -1;
    QTimer refreshTimer_;
    bool stateKnown_ = false;
    bool stopAcknowledged_ = false;
    bool signedIn_ = false;
    bool live_ = false;
    bool connecting_ = false;
    QString country_;
};
