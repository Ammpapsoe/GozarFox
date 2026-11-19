#include "corebridge.h"
#include "homebinder.h"
#include "connectwindow/background.h"
#include "connectwindow/connectbutton.h"
#include "graphicresources/imageresourcessvg.h"
#include <QApplication>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QImage>
#include <QPainter>
#include <QSignalSpy>
#include <QtTest>

class UpstreamBackgroundTest : public QObject {
    Q_OBJECT
private slots:
    void sourceFlagFollowsSimulatedCore();
    void upstreamPowerButtonTriggersSimulatedCore();
    void serverAndSettingsUseCoreResponses();
    void cleanupTestCase();
};

void UpstreamBackgroundTest::sourceFlagFollowsSimulatedCore() {
    Preferences preferences;
    QGraphicsScene scene;
    auto *background = new ConnectWindow::Background(nullptr, &preferences);
    scene.addItem(background);
    scene.setSceneRect(background->boundingRect());
    auto render = [&scene] {
        QImage image(350, 240, QImage::Format_ARGB32_Premultiplied);
        image.fill(Qt::transparent);
        QPainter painter(&image);
        scene.render(&painter);
        return image;
    };

    CoreBridge bridge;
    QSignalSpy ready(&bridge, &CoreBridge::ready), replies(&bridge, &CoreBridge::reply), stopped(&bridge, &CoreBridge::stopped);
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE")));
    QTRY_COMPARE_WITH_TIMEOUT(ready.size(), 1, 5000);
    CONNECT_STATE previous = CONNECT_STATE_DISCONNECTED;
    connect(&bridge, &CoreBridge::reply, this, [background, &previous](qint64, const QJsonObject &response) {
        const QJsonObject state = response.value("state").toObject();
        const QString code = state.value("server").toObject().value("code").toString().toLower();
        background->onLocationSelected(code);
        const CONNECT_STATE next = state.value("live").toBool() ? CONNECT_STATE_CONNECTED : CONNECT_STATE_DISCONNECTED;
        background->onConnectStateChanged(next, previous);
        previous = next;
    });
    QVERIFY(bridge.request("server", {{"code", "DE"}}) > 0);
    QTRY_COMPARE_WITH_TIMEOUT(replies.size(), 1, 5000);
    const QImage germany = render();
    QVERIFY(bridge.request("connect") > 0);
    QTRY_COMPARE_WITH_TIMEOUT(replies.size(), 2, 5000);
    QTest::qWait(700); // upstream's 600 ms state animation
    const QImage connected = render();
    QVERIFY(germany != connected);
    QVERIFY(bridge.request("server", {{"code", "CA"}}) > 0);
    QTRY_COMPARE_WITH_TIMEOUT(replies.size(), 3, 5000);
    QTest::qWait(650); // upstream's 500 ms flag transition
    const QImage canada = render();
    QVERIFY(connected != canada);
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
    scene.clear();
}

void UpstreamBackgroundTest::upstreamPowerButtonTriggersSimulatedCore() {
    QGraphicsScene scene;
    auto *button = new ConnectWindow::ConnectButton();
    scene.addItem(button);
    button->setInternetConnectivity(true);
    QGraphicsView view(&scene);
    view.setSceneRect(0, 0, 82, 82);
    view.resize(120, 120);
    view.show();
    QTest::qWait(50);

    CoreBridge bridge;
    Preferences preferences;
    ConnectWindow::Background background(nullptr, &preferences);
    HomeBinder binder(&bridge, &background, button);
    QSignalSpy ready(&bridge, &CoreBridge::ready), replies(&bridge, &CoreBridge::reply), stopped(&bridge, &CoreBridge::stopped);
    QSignalSpy clicked(button, &ConnectWindow::ConnectButton::clicked), states(&binder, &HomeBinder::stateChanged);
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE")));
    QTRY_COMPARE_WITH_TIMEOUT(ready.size(), 1, 5000);
    QTRY_COMPARE_WITH_TIMEOUT(states.size(), 1, 5000);
    QTest::mouseClick(view.viewport(), Qt::LeftButton, Qt::NoModifier, view.mapFromScene(QPointF(41, 41)));
    QTRY_COMPARE_WITH_TIMEOUT(clicked.size(), 1, 5000);
    QTRY_COMPARE_WITH_TIMEOUT(replies.size(), 2, 5000);
    QVERIFY(replies.at(1).at(1).toJsonObject().value("state").toObject().value("live").toBool());
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
}

void UpstreamBackgroundTest::serverAndSettingsUseCoreResponses() {
    Preferences preferences;
    ConnectWindow::Background background(nullptr, &preferences);
    ConnectWindow::ConnectButton button;
    CoreBridge bridge;
    HomeBinder binder(&bridge, &background, &button);
    QSignalSpy states(&binder, &HomeBinder::stateChanged);
    QSignalSpy servers(&binder, &HomeBinder::serversReady);
    QSignalSpy errors(&binder, &HomeBinder::error);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE")));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    binder.loadServers();
    QTRY_COMPARE_WITH_TIMEOUT(servers.size(), 1, 5000);
    QCOMPARE(servers.first().at(0).toJsonArray().first().toObject().value("code").toString(), QStringLiteral("DE"));

    states.clear();
    binder.selectServer(QStringLiteral("test.example"), QStringLiteral("DE"));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("server").toObject().value("code").toString(), QStringLiteral("DE"));
    states.clear();
    binder.setMode(QStringLiteral("system-proxy"));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("mode").toString(), QStringLiteral("system-proxy"));
    states.clear();
    binder.setKillSwitch(true);
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("kill_switch").toBool(), true);
    states.clear();
    binder.setLanguage(QStringLiteral("fa"));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("language").toString(), QStringLiteral("fa"));
    states.clear();
    binder.setTheme(QStringLiteral("dark"));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("theme").toString(), QStringLiteral("dark"));
    states.clear();
    binder.setRecents(true);
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("recents").toBool(), true);
    states.clear();
    const QJsonArray chain{QStringLiteral("socks5://proxy.example:1080")};
    binder.setChain(chain);
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("chain").toArray(), chain);
    states.clear();
    binder.setWarpUdp(true, false);
    QTRY_VERIFY_WITH_TIMEOUT(!errors.isEmpty(), 5000);
    QVERIFY(!states.last().at(0).toJsonObject().value("warp_udp").toBool());
    states.clear();
    binder.setWarpUdp(true, true);
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QVERIFY(states.last().at(0).toJsonObject().value("warp_udp").toBool());

    // A live tunnel locks mode/server changes. No request should be sent.
    QVERIFY(bridge.request(QStringLiteral("connect")) > 0);
    QTRY_VERIFY_WITH_TIMEOUT(states.last().at(0).toJsonObject().value("live").toBool(), 5000);
    states.clear();
    binder.setMode(QStringLiteral("tun"));
    binder.selectServer(QStringLiteral("other.example"), QStringLiteral("CA"));
    QVERIFY(bridge.request(QStringLiteral("state")) > 0);
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    const auto locked = states.last().at(0).toJsonObject();
    QCOMPARE(locked.value("mode").toString(), QStringLiteral("system-proxy"));
    QCOMPARE(locked.value("server").toObject().value("code").toString(), QStringLiteral("DE"));
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
}

void UpstreamBackgroundTest::cleanupTestCase() {
    ImageResourcesSvg::instance().finishGracefully();
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    qputenv("GOZAR_FAKE_CORE", argv[1]);
    int testArgc = 1;
    QApplication app(testArgc, argv);
    Q_INIT_RESOURCE(images);
    UpstreamBackgroundTest test;
    return QTest::qExec(&test, testArgc, argv);
}
#include "upstream_background_test.moc"
