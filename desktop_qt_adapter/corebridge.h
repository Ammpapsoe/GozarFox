#pragma once

#include <QObject>
#include <QJsonObject>
#include <QProcess>
#include <QSet>

// Experimental Qt-to-Gozar JSON-lines transport. Not yet connected to the UI.
// The core remains a separate process and retains ownership of network state.
class CoreBridge final : public QObject {
    Q_OBJECT
public:
    explicit CoreBridge(QObject *parent = nullptr);
    ~CoreBridge() override;
    bool start(const QString &corePath);
    qint64 request(const QString &method, const QJsonObject &params = {});
    bool running() const;
    void shutdown();

signals:
    void ready();
    void reply(qint64 id, const QJsonObject &response);
    void failed(qint64 id, const QString &message);
    void transportError(const QString &message);
    void stopped();
    // True only when quit was acknowledged without an error, the core
    // reported neither live nor connecting, and the process exited normally.
    // This is the core's report, not independent proof of OS cleanup.
    void shutdownFinished(bool acknowledged, const QString &reason);

private slots:
    void readOutput();
    void onFinished(int exitCode, QProcess::ExitStatus status);
    void onError(QProcess::ProcessError error);

private:
    void failPending(const QString &message);
    void protocolFailure(const QString &message);
    QProcess *process_;
    QByteArray buffer_;
    QSet<qint64> pending_;
    qint64 nextId_ = 0;
    qint64 quitId_ = -1;
    bool quitRequested_ = false;
    bool quitAcknowledged_ = false;
    bool stopping_ = false;
    bool protocolFault_ = false;
};
