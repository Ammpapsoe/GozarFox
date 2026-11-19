#include "corebridge.h"
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QFile>
#include <QtTest>

class CoreBridgeTest : public QObject {
    Q_OBJECT
private slots:
    void multipleRequests();
    void crashFailsPending();
    void malformedReplyFailsClosed();
    void missingExecutable();
};

static QString fakeCore() { return qEnvironmentVariable("GOZAR_FAKE_CORE"); }

void CoreBridgeTest::multipleRequests() {
    CoreBridge core;
    QSignalSpy ready(&core, &CoreBridge::ready), replies(&core, &CoreBridge::reply), stopped(&core, &CoreBridge::stopped);
    QVERIFY(core.start(fakeCore()));
    QTRY_COMPARE_WITH_TIMEOUT(ready.size(), 1, 5000);
    const auto first = core.request("state");
    const auto second = core.request("server", QJsonObject{{"host", "test.example"}});
    QVERIFY(first > 0 && second > first);
    QTRY_COMPARE_WITH_TIMEOUT(replies.size(), 2, 5000);
    QCOMPARE(replies[0][0].toLongLong(), first);
    QCOMPARE(replies[1][0].toLongLong(), second);
    QCOMPARE(replies[1][1].toJsonObject().value("reply").toObject().value("host").toString(), QStringLiteral("test.example"));
    core.shutdown();
    QCOMPARE(core.request("connect"), qint64(-1));
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
}

void CoreBridgeTest::crashFailsPending() {
    CoreBridge core;
    QSignalSpy ready(&core, &CoreBridge::ready), failed(&core, &CoreBridge::failed), stopped(&core, &CoreBridge::stopped);
    QVERIFY(core.start(fakeCore())); QTRY_COMPARE_WITH_TIMEOUT(ready.size(), 1, 5000);
    const auto id = core.request("crash"); QVERIFY(id > 0);
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
    QCOMPARE(failed.size(), 1); QCOMPARE(failed[0][0].toLongLong(), id);
}

void CoreBridgeTest::malformedReplyFailsClosed() {
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString marker = dir.filePath(QStringLiteral("quit-marker"));
    qputenv("FAKE_CORE_QUIT_MARKER", marker.toUtf8());
    CoreBridge core;
    QSignalSpy ready(&core, &CoreBridge::ready), errors(&core, &CoreBridge::transportError), failed(&core, &CoreBridge::failed), stopped(&core, &CoreBridge::stopped);
    QVERIFY(core.start(fakeCore())); QTRY_COMPARE_WITH_TIMEOUT(ready.size(), 1, 5000);
    QVERIFY(core.request("invalid") > 0);
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
    QCOMPARE(errors.size(), 1); QCOMPARE(failed.size(), 1);
    QVERIFY2(QFile::exists(marker), "core must receive quit instead of being killed");
    qunsetenv("FAKE_CORE_QUIT_MARKER");
}

void CoreBridgeTest::missingExecutable() {
    CoreBridge core; QSignalSpy errors(&core, &CoreBridge::transportError);
    QVERIFY(core.start(QStringLiteral("/definitely/not/a/gozar/core")));
    QTRY_COMPARE_WITH_TIMEOUT(errors.size(), 1, 5000);
    QVERIFY(!core.running());
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    qputenv("GOZAR_FAKE_CORE", argv[1]);
    int testArgc = 1;
    QCoreApplication app(testArgc, argv);
    CoreBridgeTest test;
    return QTest::qExec(&test, testArgc, argv);
}
#include "corebridge_test.moc"
