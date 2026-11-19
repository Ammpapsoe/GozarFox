#include "corebridge.h"

#include <QJsonDocument>
#include <QJsonParseError>
#include <QTimer>
#include <limits>

namespace { constexpr qsizetype kMaxLine = 4 * 1024 * 1024; }

CoreBridge::CoreBridge(QObject *parent) : QObject(parent) {
    // Never merge stderr into protocol output, and never log request bodies:
    // sign-in messages can contain credentials.
    process_.setProcessChannelMode(QProcess::SeparateChannels);
    connect(&process_, &QProcess::started, this, &CoreBridge::ready);
    connect(&process_, &QProcess::readyReadStandardOutput, this, &CoreBridge::readOutput);
    connect(&process_, &QProcess::readyReadStandardError, this, [this] { process_.readAllStandardError(); });
    connect(&process_, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this, &CoreBridge::onFinished);
    connect(&process_, &QProcess::errorOccurred, this, &CoreBridge::onError);
}

bool CoreBridge::start(const QString &corePath) {
    if (process_.state() != QProcess::NotRunning || corePath.isEmpty()) return false;
    buffer_.clear();
    stopping_ = false;
    protocolFault_ = false;
    process_.start(corePath, {QStringLiteral("--rpc")});
    return true;
}

bool CoreBridge::running() const { return process_.state() == QProcess::Running && !stopping_; }

qint64 CoreBridge::request(const QString &method, const QJsonObject &params) {
    if (!running() || method.isEmpty() || nextId_ == std::numeric_limits<qint64>::max()) return -1;
    const qint64 id = ++nextId_;
    QJsonObject envelope{{QStringLiteral("id"), id}, {QStringLiteral("method"), method}};
    if (!params.isEmpty()) envelope.insert(QStringLiteral("params"), params);
    const QByteArray encoded = QJsonDocument(envelope).toJson(QJsonDocument::Compact) + '\n';
    if (encoded.size() > kMaxLine) return -1;
    pending_.insert(id);
    if (process_.write(encoded) != encoded.size()) {
        pending_.remove(id);
        return -1;
    }
    return id;
}

void CoreBridge::readOutput() {
    if (protocolFault_) {
        process_.readAllStandardOutput();
        return;
    }
    buffer_.append(process_.readAllStandardOutput());
    for (;;) {
        const qsizetype end = buffer_.indexOf('\n');
        if (end < 0) break;
        if (end > kMaxLine) {
            protocolFailure(QStringLiteral("core reply too large"));
            return;
        }
        const QByteArray line = buffer_.left(end);
        buffer_.remove(0, end + 1);
        QJsonParseError error;
        const QJsonDocument doc = QJsonDocument::fromJson(line, &error);
        if (error.error != QJsonParseError::NoError || !doc.isObject()) {
            protocolFailure(QStringLiteral("invalid core reply"));
            return;
        }
        const QJsonObject response = doc.object();
        const QJsonValue value = response.value(QStringLiteral("id"));
        if (!value.isDouble()) {
            protocolFailure(QStringLiteral("core reply has no request id"));
            return;
        }
        const qint64 id = value.toInteger(-1);
        if (!pending_.remove(id)) continue; // late, duplicate, or unsolicited reply
        emit reply(id, response);
    }
    if (buffer_.size() > kMaxLine)
        protocolFailure(QStringLiteral("core reply too large"));
}

void CoreBridge::protocolFailure(const QString &message) {
    if (protocolFault_) return;
    protocolFault_ = true;
    buffer_.clear();
    emit transportError(message);
    // A malformed response must not force-kill a core that may own routes or
    // proxy settings. Ask it to shut down and close stdin to allow cleanup.
    shutdown();
}

void CoreBridge::shutdown() {
    if (!running()) return;
    const qint64 id = request(QStringLiteral("quit"));
    if (protocolFault_ && id >= 0) pending_.remove(id);
    // If writing quit failed, EOF still lets the core's normal RPC loop exit.
    stopping_ = true;
    process_.closeWriteChannel();
    if (id < 0) emit transportError(QStringLiteral("could not send core shutdown request"));
    // Teardown can involve platform routes and system proxy restoration.
    // Do not kill the core on a timer while it may still be cleaning up.
    QTimer::singleShot(45000, this, [this] {
        if (stopping_ && process_.state() != QProcess::NotRunning)
            emit transportError(QStringLiteral("core shutdown is taking too long"));
    });
}

void CoreBridge::failPending(const QString &message) {
    const auto ids = pending_;
    pending_.clear();
    for (const auto id : ids) emit failed(id, message);
}

void CoreBridge::onFinished(int exitCode, QProcess::ExitStatus status) {
    Q_UNUSED(exitCode);
    Q_UNUSED(status);
    readOutput();
    failPending(QStringLiteral("core process exited"));
    stopping_ = false;
    emit stopped();
}

void CoreBridge::onError(QProcess::ProcessError error) {
    if (error == QProcess::FailedToStart) {
        failPending(QStringLiteral("core failed to start"));
        emit transportError(QStringLiteral("core failed to start"));
        emit stopped();
    } else if (error == QProcess::WriteError || error == QProcess::ReadError) {
        if (!stopping_) {
            protocolFault_ = true;
            stopping_ = true;
            emit transportError(QStringLiteral("core communication failed"));
            process_.closeWriteChannel();
        }
    }
}
