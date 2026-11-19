#include "homebinder.h"
#include "connectwindow/background.h"
#include "connectwindow/connectbutton.h"

HomeBinder::HomeBinder(CoreBridge *core, ConnectWindow::Background *background,
                       ConnectWindow::ConnectButton *button, QObject *parent)
    : QObject(parent), core_(core), background_(background), button_(button) {
    // The full upstream ConnectWindowItem owns its widgets privately. A
    // headless binder can drive that window through its public API instead.
    Q_ASSERT(core);
    connect(core, &CoreBridge::ready, this, &HomeBinder::refresh);
    connect(core, &CoreBridge::reply, this, &HomeBinder::handleReply);
    connect(core, &CoreBridge::failed, this, &HomeBinder::handleFailure);
    connect(core, &CoreBridge::stopped, this, &HomeBinder::coreStopped);
    if (button) connect(button, &ConnectWindow::ConnectButton::clicked, this, &HomeBinder::toggleConnection);
    refreshTimer_.setInterval(2000);
    connect(&refreshTimer_, &QTimer::timeout, this, &HomeBinder::refresh);
    refreshTimer_.start();
    setEnabled();
    if (core->running()) refresh();
}

void HomeBinder::setEnabled() {
    const bool available = core_ && core_->running() && stateKnown_ && signedIn_ && mutatingId_ < 0 && !connecting_;
    if (button_) button_->setClickable(available);
    emit availabilityChanged(available);
}

void HomeBinder::setVisualState(CONNECT_STATE next) {
    if (visual_ == next) return;
    if (background_) background_->onConnectStateChanged(next, visual_);
    if (button_) button_->onConnectStateChanged(next, visual_);
    visual_ = next;
    emit visualStateChanged(next);
}

void HomeBinder::refresh() {
    // The Go RPC server processes ordinary calls serially. Avoid piling up
    // periodic requests while a connection or another state call is pending.
    if (!core_ || !core_->running() || stateId_ >= 0 || mutatingId_ >= 0) return;
    stateId_ = core_->request(QStringLiteral("state"));
    if (stateId_ < 0) {
        stateKnown_ = false;
        setEnabled();
        emit error(QStringLiteral("core state unavailable"));
    }
}

void HomeBinder::loadServers() {
    if (!core_ || !core_->running() || !signedIn_ || serversId_ >= 0) return;
    serversId_ = core_->request(QStringLiteral("servers"));
    if (serversId_ < 0) emit error(QStringLiteral("cannot load servers"));
}

void HomeBinder::sendMutation(const QString &method, const QJsonObject &params) {
    if (!core_ || !core_->running() || !stateKnown_ || !signedIn_ || connecting_ || mutatingId_ >= 0) return;
    mutatingId_ = core_->request(method, params);
    if (mutatingId_ < 0) emit error(QStringLiteral("cannot send core request"));
    setEnabled();
}

void HomeBinder::toggleConnection() {
    if (!core_ || !core_->running() || !stateKnown_ || !signedIn_ || mutatingId_ >= 0 || connecting_) return;
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

void HomeBinder::setLanguage(const QString &language) {
    sendMutation(QStringLiteral("language"), {{QStringLiteral("language"), language}});
}

void HomeBinder::setTheme(const QString &theme) {
    sendMutation(QStringLiteral("theme"), {{QStringLiteral("theme"), theme}});
}

void HomeBinder::setRecents(bool enabled) {
    sendMutation(QStringLiteral("recents"), {{QStringLiteral("enabled"), enabled}});
}

void HomeBinder::setWarpUdp(bool enabled, bool consent) {
    // Never manufacture consent: it must come from the UI's explicit prompt.
    sendMutation(QStringLiteral("warp_udp"), {{QStringLiteral("enabled"), enabled}, {QStringLiteral("consent"), consent}});
}

void HomeBinder::setChain(const QJsonArray &hops) {
    if (live_) return;
    sendMutation(QStringLiteral("chain"), {{QStringLiteral("hops"), hops}});
}

void HomeBinder::applyState(const QJsonObject &state) {
    // Unknown connection state must never be interpreted as disconnected.
    if (!state.value(QStringLiteral("signed_in")).isBool() ||
        !state.value(QStringLiteral("live")).isBool() ||
        !state.value(QStringLiteral("connecting")).isBool()) {
        stateKnown_ = false;
        setEnabled();
        emit error(QStringLiteral("invalid core state; connection state unknown"));
        return;
    }
    stateKnown_ = true;
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
    const bool needsState = mutatingId_ == id || stateId_ == id;
    if (mutatingId_ == id) mutatingId_ = -1;
    if (stateId_ == id) stateId_ = -1;
    const bool serversReply = serversId_ == id;
    if (serversReply) serversId_ = -1;
    const auto state = reply.value(QStringLiteral("state"));
    if (state.isObject()) applyState(state.toObject());
    else {
        if (needsState) {
            stateKnown_ = false;
            emit error(QStringLiteral("core reply has no state; connection state unknown"));
        }
        setEnabled();
    }
    const QString message = reply.value(QStringLiteral("error")).toString();
    if (!message.isEmpty()) emit error(message);
    else if (serversReply) {
        const auto servers = reply.value(QStringLiteral("reply"));
        if (servers.isArray()) emit serversReady(servers.toArray());
        else emit error(QStringLiteral("invalid server list"));
    }
}

void HomeBinder::handleFailure(qint64 id, const QString &message) {
    if (id == mutatingId_ || id == stateId_) stateKnown_ = false;
    if (id == mutatingId_) mutatingId_ = -1;
    if (id == stateId_) stateId_ = -1;
    if (id == serversId_) serversId_ = -1;
    setEnabled();
    emit error(message);
}

void HomeBinder::coreStopped() {
    stateKnown_ = false;
    signedIn_ = false;
    connecting_ = false;
    mutatingId_ = stateId_ = serversId_ = -1;
    setEnabled();
    // Never claim the tunnel disconnected solely because its controller died.
    emit error(QStringLiteral("core process stopped; connection state unknown"));
}
