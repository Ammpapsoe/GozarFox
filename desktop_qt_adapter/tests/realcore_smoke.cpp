#include "corebridge.h"
#include <QCoreApplication>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QtTest>

// Local read-only protocol check. No login, server choice, proxy or TUN action.
class RealCoreSmoke : public QObject {
    Q_OBJECT
private slots:
    void stateAndQuit();
};
void RealCoreSmoke::stateAndQuit() {
    QTemporaryDir isolated;
    QVERIFY(isolated.isValid());
    qputenv("HOME", isolated.path().toUtf8());
    qputenv("XDG_CONFIG_HOME", isolated.path().toUtf8());
    // Windows Go uses AppData for UserConfigDir; isolate it as well.
    qputenv("APPDATA", isolated.path().toUtf8());
    qputenv("LOCALAPPDATA", isolated.path().toUtf8());
    qputenv("USERPROFILE", isolated.path().toUtf8());
    CoreBridge bridge;
    QSignalSpy ready(&bridge, &CoreBridge::ready), replies(&bridge, &CoreBridge::reply), stopped(&bridge, &CoreBridge::stopped);
    QSignalSpy shutdown(&bridge, &CoreBridge::shutdownFinished);
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_TEST_REAL_CORE")));
    QTRY_COMPARE_WITH_TIMEOUT(ready.size(), 1, 10000);
    const qint64 id = bridge.request("state");
    QVERIFY(id > 0);
    QTRY_VERIFY_WITH_TIMEOUT(!replies.isEmpty(), 10000);
    QCOMPARE(replies.first().at(0).toLongLong(), id);
    const QJsonObject response = replies.first().at(1).toJsonObject();
    QVERIFY(response.value("error").toString().isEmpty());
    const auto state = response.value("state").toObject();
    const QString expectedVersion = qEnvironmentVariable("GOZAR_CI_EXPECTED_CORE_VERSION");
    QVERIFY2(!expectedVersion.isEmpty(), "CTest must supply the expected real-core RPC version");
    QCOMPARE(state.value("version").toString(), expectedVersion);
    QCOMPARE(state.value("signed_in").toBool(), false);
    QCOMPARE(state.value("live").toBool(), false);
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 10000);
    QCOMPARE(shutdown.size(), 1);
    QVERIFY(shutdown.first().at(0).toBool());
}
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    qputenv("GOZAR_TEST_REAL_CORE", argv[1]);
    int n = 1;
    QCoreApplication app(n, argv);
    RealCoreSmoke test;
    return QTest::qExec(&test, n, argv);
}
#include "realcore_smoke.moc"
