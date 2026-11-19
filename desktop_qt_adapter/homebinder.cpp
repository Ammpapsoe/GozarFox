#include "homebinder.h"
#include "connectwindow/background.h"
#include "connectwindow/connectbutton.h"

HomeBinder::HomeBinder(CoreBridge *core, ConnectWindow::Background *background,
                       ConnectWindow::ConnectButton *button, QObject *parent)
    : QObject(parent), core_(core), background_(background), button_(button) {
    Q_ASSERT(core && background && button);
    connect(core, &CoreBridge::ready, this, &HomeBinder::refresh);
    connect(core, &CoreBridge::reply, this, &HomeBinder::handleReply);
    connect(core, &CoreBridge::failed, this, &HomeBinder::handleFailure);
    connect(core, &CoreBridge::stopped, this, &HomeBinder::coreStopped);
    connect(button, &ConnectWindow::ConnectButton::clicked, this, &HomeBinder::toggleConnection);
    refreshTimer_.setInterval(2000);
    connect(&refreshTimer_, &QTimer::timeout, this, &HomeBinder::refresh);
    refreshTimer_.start();
    setEnabled();
    if (core->running()) refresh();
}

void HomeBinder::setEnabled() {
    if (button_) button_->setClickable(core_ && core_->running() && signedIn_ && mutatingId_ < 0 && !connecting_);
}

void HomeBinder::setVisualState(CONNECT_STATE next) {
    if (visual_ == next) return;
    if (background_) background_->onConnectStateChanged(next, visual_);
    if (button_) button_->onConnectStateChanged(next, visual_);
    visual_ = next;
}

void HomeBinder::refresh() {
    // The Go RPC server processes ordinary calls serially. Avoid piling up
    // periodic requests while a connection or another state call is pending.
    if (!core_ || !core_->running() || stateId_ >= 0 || mutatingId_ >= 0) return;
    stateId_ = core_->request(QStringLiteral("state"));
    if (stateId_ < 0) emit error(QStringLiteral("core state unavailable"));
}

void HomeBinder::loadServers() {
    if (!core_ || !core_->running() || !signedIn_ || serversId_ >= 0) return;
    serversId_ = core_->request(QStringLiteral("servers"));
    if (serversId_ < 0) emit error(QStringLiteral("cannot load servers"));
}

void HomeBinder::sendMutation(const QString &method, const QJsonObject &params) {
    if (!core_ || !core_->running() || !signedIn_ || connecting_ || mutatingId_ >= 0) return;
    mutatingId_ = core_->request(method, params);
    if (mutatingId_ < 0) emit error(QStringLiteral("cannot send core request"));
    setEnabled();
}

void HomeBinder::toggleConnection() {
    if (!core_ || !core_->running() || !signedIn_ || mutatingId_ >= 0 || connecting_) return;
    const bool disconnect = live_;
    const auto id = core_->request(disconnect ? QStringLiteral("disconnect") : QStringLiteral("connect"));
    if (id < 0) { emit error(QStringLiteral("cannot send connection request")); return; }
    mutatingId_ = id;
    setVisualState(disconnect ? CONNECT_STATE_DISCONNECTING : CONNECT_STATE_CONNECTING);
    setEnabled();
}

void HomeBinder::selectServer(const QString &host, const QString &countryCode) {
    if (live_) return;
    sendMutation(QStringLiteral("server"), {{QStringLiteral("host"), host}, {QStringLiteral("code"), countryCode}});
}

void HomeBinder::setMode(const QString &mode) {
    if (live_ || (mode != QLatin1String("tun") && mode != QLatin1String("proxy-only") && mode != QLatin1String("system-proxy"))) return;
    sendMutation(QStringLiteral("mode"), {{QStringLiteral("mode"), mode}});
}

void HomeBinder::setKillSwitch(bool enabled) {
    sendMutation(QStringLiteral("kill_switch"), {{QStringLiteral("enabled"), enabled}});
}

void HomeBinder::applyState(const QJsonObject &state) {
    // Missing/incomplete data must not manufacture a signed-in or live state.
    signedIn_ = state.value(QStringLiteral("signed_in")).toBool(false);
    live_ = state.value(QStringLiteral("live")).toBool(false);
    connecting_ = state.value(QStringLiteral("connecting")).toBool(false);
    const auto server = state.value(QStringLiteral("server")).toObject();
    const QString code = server.value(QStringLiteral("code")).toString().toLower();
    if (background_ && country_ != code) {
        country_ = code;
        background_->onLocationSelected(country_);
    }
    // An older state request may complete while a mutation is in flight;
    // retain the pending animation until that request's own response arrives.
    if (mutatingId_ < 0)
        setVisualState(connecting_ ? CONNECT_STATE_CONNECTING : live_ ? CONNECT_STATE_CONNECTED : CONNECT_STATE_DISCONNECTED);
    setEnabled();
    emit stateChanged(state);
}

void HomeBinder::handleReply(qint64 id, const QJsonObject &reply) {
    if (mutatingId_ == id) mutatingId_ = -1;
    if (stateId_ == id) stateId_ = -1;
    const bool serversReply = serversId_ == id;
    if (serversReply) serversId_ = -1;
    const auto state = reply.value(QStringLiteral("state"));
    if (state.isObject()) applyState(state.toObject());
    else setEnabled();
    const QString message = reply.value(QStringLiteral("error")).toString();
    if (!message.isEmpty()) emit error(message);
    else if (serversReply) {
        const auto servers = reply.value(QStringLiteral("reply"));
        if (servers.isArray()) emit serversReady(servers.toArray());
        else emit error(QStringLiteral("invalid server list"));
    }
}

void HomeBinder::handleFailure(qint64 id, const QString &message) {
    if (id == mutatingId_) mutatingId_ = -1;
    if (id == stateId_) stateId_ = -1;
    if (id == serversId_) serversId_ = -1;
    setEnabled();
    emit error(message);
}

void HomeBinder::coreStopped() {
    signedIn_ = false;
    connecting_ = false;
    mutatingId_ = stateId_ = serversId_ = -1;
    setEnabled();
    // Never claim the tunnel disconnected solely because its controller died.
    emit error(QStringLiteral("core process stopped; connection state unknown"));
}
