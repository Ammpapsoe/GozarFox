#include "homebinder.h"
#include "connectwindow/background.h"
#include "connectwindow/connectbutton.h"

HomeBinder::HomeBinder(CoreBridge *core, ConnectWindow::Background *background,
                       ConnectWindow::ConnectButton *button, QObject *parent)
    : QObject(parent), core_(core), background_(background), button_(button) {
    // The full upstream ConnectWindowItem owns its widgets privately. A
    // headless binder can drive that window through its public API instead.
    Q_ASSERT(core);
    connect(core, &CoreBridge::ready, this, [this] {
        stopAcknowledged_ = false;
        refresh();
    });
    connect(core, &CoreBridge::shutdownFinished, this, [this](bool acknowledged, const QString &) {
        stopAcknowledged_ = acknowledged;
    });
    connect(core, &CoreBridge::reply, this, &HomeBinder::handleReply);
    connect(core, &CoreBridge::failed, this, &HomeBinder::handleFailure);
    connect(core, &CoreBridge::transportError, this, [this](const QString &) {
        // Invalidate immediately; a fault may be emitted before the process
        // actually exits or its pending requests are failed.
        stateKnown_ = false;
        lastState_ = {};
        emit serversInvalidated();
        setEnabled();
    });
    connect(core, &CoreBridge::stopped, this, &HomeBinder::coreStopped);
    connect(this, &HomeBinder::serversInvalidated, this, [this] { ++catalogueRevision_; });
    if (button) connect(button, &ConnectWindow::ConnectButton::clicked, this, &HomeBinder::toggleConnection);
    refreshTimer_.setInterval(2000);
    connect(&refreshTimer_, &QTimer::timeout, this, &HomeBinder::refresh);
    refreshTimer_.start();
    setEnabled();
    if (core->running()) refresh();
}

bool HomeBinder::isAvailable() const {
    return core_ && core_->running() && stateKnown_ && signedIn_ && mutatingId_ < 0 && connectId_ < 0 && !connecting_;
}

bool HomeBinder::canCancelConnect() const {
    return core_ && core_->running() && stateKnown_ && signedIn_ && mutatingId_ < 0 && (connectId_ >= 0 || connecting_);
}

bool HomeBinder::canTogglePower() const { return isAvailable() || canCancelConnect(); }

bool HomeBinder::canChangeMode() const {
    return isAvailable() && !live_;
}

QJsonObject HomeBinder::currentState() const {
    return stateKnown_ ? lastState_ : QJsonObject();
}

void HomeBinder::setEnabled() {
    const bool available = isAvailable();
    if (button_) button_->setClickable(canTogglePower());
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
    if (!core_ || !core_->running() || stateId_ >= 0 || mutatingId_ >= 0 || connectId_ >= 0) return;
    stateId_ = core_->request(QStringLiteral("state"));
    if (stateId_ < 0) {
        stateKnown_ = false;
        lastState_ = {};
        emit serversInvalidated();
        setEnabled();
        emit error(QStringLiteral("core state unavailable"));
    }
}

void HomeBinder::loadServers() {
    if (!core_ || !core_->running() || !stateKnown_ || !signedIn_ || serversId_ >= 0) return;
    // Never leave an old account's or previous fetch's destinations clickable
    // while a fresh snapshot is pending or if the request subsequently fails.
    emit serversInvalidated();
    requestedCatalogueRevision_ = catalogueRevision_;
    serversEmail_ = lastState_.value(QStringLiteral("email")).toString();
    serversId_ = core_->request(QStringLiteral("servers"));
    if (serversId_ < 0) emit error(QStringLiteral("cannot load servers"));
}

qint64 HomeBinder::sendMutation(const QString &method, const QJsonObject &params) {
    if (!core_ || !core_->running() || !stateKnown_ || !signedIn_) {
        emit error(tr("The core is unavailable or its state is unknown."));
        return -1;
    }
    if (connecting_ || mutatingId_ >= 0 || connectId_ >= 0) {
        emit error(tr("Wait until the current operation finishes."));
        return -1;
    }
    mutatingId_ = core_->request(method, params);
    if (mutatingId_ < 0) emit error(QStringLiteral("cannot send core request"));
    setEnabled();
    return mutatingId_;
}

void HomeBinder::toggleConnection() {
    if (!core_ || !core_->running() || !stateKnown_ || !signedIn_) {
        emit error(tr("The core is unavailable or its state is unknown."));
        return;
    }
    if (!canTogglePower()) return;
    const bool disconnect = live_ || canCancelConnect();
    const auto id = core_->request(disconnect ? QStringLiteral("disconnect") : QStringLiteral("connect"));
    if (id < 0) { emit error(QStringLiteral("cannot send connection request")); return; }
    if (disconnect) disconnectId_ = mutatingId_ = id;
    else connectId_ = id;
    setVisualState(disconnect ? CONNECT_STATE_DISCONNECTING : CONNECT_STATE_CONNECTING);
    setEnabled();
}

void HomeBinder::selectServer(const QString &host, const QString &countryCode) {
    if (live_) {
        emit error(tr("Disconnect before changing the server."));
        return;
    }
    sendMutation(QStringLiteral("server"), {{QStringLiteral("host"), host}, {QStringLiteral("code"), countryCode}});
}

void HomeBinder::switchAccount(const QString &id) {
    if (!isAvailable() || id.isEmpty()) return;
    for (const auto &value : lastState_.value(QStringLiteral("accounts")).toArray()) {
        const auto account = value.toObject();
        if (account.value(QStringLiteral("id")).toString() == id && account.value(QStringLiteral("active")).isBool() &&
            !account.value(QStringLiteral("active")).toBool()) {
            switchAccountId_ = sendMutation(QStringLiteral("switch_account"), {{QStringLiteral("id"), id}});
            if (switchAccountId_ >= 0) pendingAccountId_ = id;
            return;
        }
    }
}

bool HomeBinder::requestUpdate(const QString &artifact) {
    if (!core_ || !core_->running() || updateId_ >= 0 || cancelUpdateId_ >= 0) return false;
    updateCancelRequested_ = updateReplyReceived_ = false;
    updateResult_ = {};
    updateError_.clear();
    cancelError_.clear();
    updateId_ = artifact.isEmpty()
        ? core_->request(QStringLiteral("check_update"))
        : core_->request(QStringLiteral("download_update"), {{QStringLiteral("id"), artifact}});
    return updateId_ >= 0;
}

bool HomeBinder::cancelUpdate() {
    if (!core_ || updateId_ < 0) return false;
    if (updateCancelRequested_) return true;
    cancelUpdateId_ = core_->request(QStringLiteral("cancel_update"));
    updateCancelRequested_ = cancelUpdateId_ >= 0;
    return updateCancelRequested_;
}

bool HomeBinder::handleUpdateReply(qint64 id, const QJsonObject &response, const QString &failure) {
    if (id != updateId_ && id != cancelUpdateId_) return false;
    const auto err = response.value(QStringLiteral("error"));
    const QString message = !failure.isEmpty() ? failure : err.isUndefined() || err.isString()
        ? err.toString() : QStringLiteral("invalid update reply");
    if (id == cancelUpdateId_) {
        cancelUpdateId_ = -1;
        cancelError_ = message;
    } else {
        updateId_ = -1;
        updateReplyReceived_ = true;
        updateError_ = message;
        updateResult_ = response.value(QStringLiteral("reply")).toObject();
    }
    // Do not unlock until both responses arrive; on the stdio transport the
    // asynchronous operation can finish either before or after cancel's ACK.
    if (updateReplyReceived_ && cancelUpdateId_ < 0) {
        updateReplyReceived_ = false;
        if (updateCancelRequested_ && (!cancelError_.isEmpty() || updateError_ != QLatin1String("update operation cancelled")))
            emit updateFinished({}, QStringLiteral("cancel unconfirmed"));
        else emit updateFinished(updateResult_, updateError_);
    }
    return true; // Update-only replies never become VPN state or clear the server model.
}

void HomeBinder::refreshUsage() {
    if (isAvailable()) sendMutation(QStringLiteral("usage"), {});
}

void HomeBinder::signOut() {
    if (isAvailable()) signOutId_ = sendMutation(QStringLiteral("logout"), {});
}

void HomeBinder::setMode(const QString &mode) {
    if (live_) {
        emit error(tr("Disconnect before changing the connection mode."));
        return;
    }
    if (mode != QLatin1String("tun") && mode != QLatin1String("proxy-only") && mode != QLatin1String("system-proxy")) {
        emit error(tr("Unsupported connection mode."));
        return;
    }
#ifndef Q_OS_WIN
    // The unchanged core does not implement system-proxy ownership on Linux
    // or macOS. Match the existing Flutter UI rather than letting users save
    // a mode that cannot connect on their platform.
    if (mode == QLatin1String("system-proxy")) {
        emit error(tr("System proxy mode is available on Windows only."));
        return;
    }
#endif
    sendMutation(QStringLiteral("mode"), {{QStringLiteral("mode"), mode}});
}

void HomeBinder::setProxyPort(int port) {
    if (!canChangeMode() || port < 1 || port > 65535) return;
    sendMutation(QStringLiteral("proxy_port"), {{QStringLiteral("port"), port}});
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
    if (live_) {
        emit error(tr("Disconnect before changing the proxy chain."));
        return;
    }
    sendMutation(QStringLiteral("chain"), {{QStringLiteral("hops"), hops}});
}

void HomeBinder::applyState(const QJsonObject &state) {
    // Unknown connection state must never be interpreted as disconnected.
    if (!state.value(QStringLiteral("signed_in")).isBool() ||
        !state.value(QStringLiteral("live")).isBool() ||
        !state.value(QStringLiteral("connecting")).isBool()) {
        stateKnown_ = false;
        lastState_ = {};
        emit serversInvalidated();
        setEnabled();
        emit error(QStringLiteral("invalid core state; connection state unknown"));
        return;
    }
    const bool previousKnown = stateKnown_;
    const bool previousSignedIn = signedIn_;
    const QString previousEmail = lastState_.value(QStringLiteral("email")).toString();
    stateKnown_ = true;
    lastState_ = state;
    signedIn_ = state.value(QStringLiteral("signed_in")).toBool(false);
    const QString email = state.value(QStringLiteral("email")).toString();
    if (!signedIn_ || (previousKnown && previousSignedIn && previousEmail != email))
        emit serversInvalidated();
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
    if (mutatingId_ < 0 && connectId_ < 0)
        setVisualState(connecting_ ? CONNECT_STATE_CONNECTING : live_ ? CONNECT_STATE_CONNECTED : CONNECT_STATE_DISCONNECTED);
    setEnabled();
    emit stateChanged(state);
}

void HomeBinder::handleReply(qint64 id, const QJsonObject &reply) {
    if (handleUpdateReply(id, reply, {})) return;
    const bool connectReply = connectId_ == id;
    if (connectReply) connectId_ = -1;
    // Disconnect's response—not a racing Connect error—is authoritative.
    if (connectReply && disconnectId_ >= 0) return;
    if (disconnectId_ == id) disconnectId_ = -1;
    const bool switchReply = switchAccountId_ == id;
    const QString targetAccount = switchReply ? pendingAccountId_ : QString();
    if (switchReply) { switchAccountId_ = -1; pendingAccountId_.clear(); }
    const bool signOutReply = signOutId_ == id;
    if (signOutReply) signOutId_ = -1;
    const bool needsState = connectReply || mutatingId_ == id || stateId_ == id;
    if (mutatingId_ == id) mutatingId_ = -1;
    if (stateId_ == id) stateId_ = -1;
    const bool serversReply = serversId_ == id;
    if (serversReply) serversId_ = -1;
    // Validate the error before publishing state or enabling controls. An
    // error-bearing response is not evidence that the handoff target is ready,
    // even if it also contains a superficially valid signed-in state.
    const QJsonValue errorValue = reply.value(QStringLiteral("error"));
    const bool malformedError = !errorValue.isUndefined() && !errorValue.isNull() && !errorValue.isString();
    const QString message = errorValue.toString();
    if (malformedError || !message.isEmpty()) {
        stateKnown_ = false;
        lastState_ = {};
        emit serversInvalidated();
        setEnabled();
        emit error(malformedError ? QStringLiteral("invalid core error field") : message);
        return;
    }
    const auto state = reply.value(QStringLiteral("state"));
    if (state.isObject()) applyState(state.toObject());
    else {
        if (needsState || serversReply) {
            stateKnown_ = false;
            lastState_ = {};
            emit serversInvalidated();
            emit error(QStringLiteral("core reply has no state; connection state unknown"));
        }
        setEnabled();
    }
    if (switchReply) {
        bool active = false;
        for (const auto &value : lastState_.value(QStringLiteral("accounts")).toArray()) {
            const auto account = value.toObject();
            if (account.value(QStringLiteral("id")).toString() == targetAccount &&
                account.value(QStringLiteral("active")).toBool()) active = true;
        }
        if (stateKnown_ && signedIn_ && !live_ && !connecting_ && active && !targetAccount.isEmpty())
            emit accountSwitchConfirmed();
        else emit error(tr("The core did not confirm the selected account."));
    }
    if (signOutReply) {
        if (stateKnown_ && !signedIn_ && !live_ && !connecting_) emit signedOutConfirmed();
        else emit error(tr("The core did not confirm sign-out."));
    }
    if (serversReply) {
        const auto servers = reply.value(QStringLiteral("reply"));
        if (stateKnown_ && signedIn_ && catalogueRevision_ == requestedCatalogueRevision_ &&
            lastState_.value(QStringLiteral("email")).toString() == serversEmail_ && servers.isArray()) {
            emit serversReady(servers.toArray());
        } else {
            emit serversInvalidated();
            emit error(QStringLiteral("server list unavailable or account changed"));
        }
    }
}

void HomeBinder::handleFailure(qint64 id, const QString &message) {
    if (handleUpdateReply(id, {}, message)) return;
    if (signOutId_ == id) signOutId_ = -1;
    if (switchAccountId_ == id) { switchAccountId_ = -1; pendingAccountId_.clear(); }
    const bool connectFailure = connectId_ == id;
    if (connectFailure) connectId_ = -1;
    if (connectFailure && disconnectId_ >= 0) return;
    if (disconnectId_ == id) disconnectId_ = -1;
    const bool losesState = connectFailure || id == mutatingId_ || id == stateId_;
    if (losesState) { stateKnown_ = false; lastState_ = {}; }
    if (id == mutatingId_) mutatingId_ = -1;
    if (id == stateId_) stateId_ = -1;
    const bool losesServers = id == serversId_;
    if (losesServers) serversId_ = -1;
    if (losesState || losesServers) emit serversInvalidated();
    setEnabled();
    emit error(message);
}

void HomeBinder::coreStopped() {
    signOutId_ = switchAccountId_ = -1;
    pendingAccountId_.clear();
    stateKnown_ = false;
    lastState_ = {};
    signedIn_ = false;
    connecting_ = false;
    mutatingId_ = stateId_ = serversId_ = connectId_ = disconnectId_ = -1;
    serversEmail_.clear();
    emit serversInvalidated();
    setEnabled();
    // Never claim the tunnel disconnected solely because its controller died.
    if (!stopAcknowledged_)
        emit error(QStringLiteral("core process stopped; connection state unknown"));
    stopAcknowledged_ = false;
}
