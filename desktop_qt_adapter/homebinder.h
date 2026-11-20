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
    void switchAccount(const QString &id);
    void signOut();
    void refreshUsage();
    bool requestUpdate(const QString &artifact = {});
    bool cancelUpdate();
    void setMode(const QString &mode);
    void setProxyPort(int port);
    void setKillSwitch(bool enabled);
    void setLanguage(const QString &language);
    void setTheme(const QString &theme);
    void setRecents(bool enabled);
    void setWarpUdp(bool enabled, bool consent);
    void setChain(const QJsonArray &hops);
    void toggleConnection();
    bool isAvailable() const;
    bool canTogglePower() const;
    bool canCancelConnect() const;
    bool canChangeMode() const;
    QJsonObject currentState() const;

signals:
    void updateFinished(const QJsonObject &result, const QString &error);
    void signedOutConfirmed();
    void accountSwitchConfirmed();
    void error(const QString &message);
    void stateChanged(const QJsonObject &state);
    void visualStateChanged(CONNECT_STATE state);
    void availabilityChanged(bool available);
    void serversReady(const QJsonArray &servers);
    // Invalidates the old catalogue before refresh or when access is lost.
    void serversInvalidated();

private slots:
    void handleReply(qint64 id, const QJsonObject &reply);
    void handleFailure(qint64 id, const QString &message);
    void coreStopped();

private:
    void setVisualState(CONNECT_STATE state);
    void applyState(const QJsonObject &state);
    void setEnabled();
    qint64 sendMutation(const QString &method, const QJsonObject &params);
    QPointer<CoreBridge> core_;
    QPointer<ConnectWindow::Background> background_;
    QPointer<ConnectWindow::ConnectButton> button_;
    CONNECT_STATE visual_ = CONNECT_STATE_DISCONNECTED;
    qint64 updateId_ = -1;
    qint64 cancelUpdateId_ = -1;
    bool updateCancelRequested_ = false;
    bool updateReplyReceived_ = false;
    QJsonObject updateResult_;
    QString updateError_;
    QString cancelError_;
    bool handleUpdateReply(qint64 id, const QJsonObject &response, const QString &error);
    qint64 signOutId_ = -1;
    qint64 switchAccountId_ = -1;
    QString pendingAccountId_;
    qint64 mutatingId_ = -1;
    qint64 connectId_ = -1;
    qint64 disconnectId_ = -1;
    qint64 stateId_ = -1;
    qint64 serversId_ = -1;
    QString serversEmail_;
    quint64 catalogueRevision_ = 0;
    quint64 requestedCatalogueRevision_ = 0;
    QTimer refreshTimer_;
    bool stateKnown_ = false;
    bool stopAcknowledged_ = false;
    bool signedIn_ = false;
    bool live_ = false;
    bool connecting_ = false;
    QJsonObject lastState_;
    QString country_;
};
