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
    if (!core_ || !core_->running() || core_->request(QStringLiteral("state")) < 0)
        emit error(QStringLiteral("core state unavailable"));
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
    if (!core_ || !core_->running() || !signedIn_ || live_ || connecting_ || mutatingId_ >= 0) return;
    const auto id = core_->request(QStringLiteral("server"), {{QStringLiteral("host"), host}, {QStringLiteral("code"), countryCode}});
    if (id < 0) { emit error(QStringLiteral("cannot send server selection")); return; }
    mutatingId_ = id;
    setEnabled();
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
    const auto state = reply.value(QStringLiteral("state"));
    if (state.isObject()) applyState(state.toObject());
    else setEnabled();
    const QString message = reply.value(QStringLiteral("error")).toString();
    if (!message.isEmpty()) emit error(message);
}

void HomeBinder::handleFailure(qint64 id, const QString &message) {
    if (id == mutatingId_) mutatingId_ = -1;
    setEnabled();
    emit error(message);
}

void HomeBinder::coreStopped() {
    signedIn_ = false;
    connecting_ = false;
    mutatingId_ = -1;
    setEnabled();
    // Never claim the tunnel disconnected solely because its controller died.
    emit error(QStringLiteral("core process stopped; connection state unknown"));
}
