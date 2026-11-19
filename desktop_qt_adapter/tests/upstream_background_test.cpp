#include "corebridge.h"
#include "homebinder.h"
#include "originalhomebinding.h"
#include "originallocationsbinding.h"
#include "connectwindow/background.h"
#include "connectwindow/connectbutton.h"
#include "connectwindow/connectwindowitem.h"
#include "locations/locationsmodel_manager.h"
#include "locationswindow/locationswindow.h"
#include "locations/view/locationsview.h"
#include "commongraphics/commongraphics.h"
#include "dpiscalemanager.h"
#include "graphicresources/imageresourcessvg.h"
#include <QApplication>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QImage>
#include <QPainter>
#include <QSignalSpy>
#include <QSettings>
#include <QTemporaryDir>
#include <QtMath>
#include <QtTest>

// Emulates the public contract of the original window. The actual window
// declaration is checked separately; this is not a full-window runtime test.
class FakeOriginalWindow : public QObject {
    Q_OBJECT
public:
    QString countryCode;
    CONNECT_STATE state = CONNECT_STATE_DISCONNECTED;
    void updateConnectState(const types::ConnectState &value) { state = value.connectState; }
    void updateLocationInfo(const QString &, const QString &, const QString &code, PingTime, bool) { countryCode = code; }
    void click() { emit connectClick(); }
signals:
    void connectClick();
};

class UpstreamBackgroundTest : public QObject {
    Q_OBJECT
private slots:
    void sourceFlagFollowsSimulatedCore();
    void upstreamPowerButtonTriggersSimulatedCore();
    void serverAndSettingsUseCoreResponses();
    void originalWindowPublicApiBindingWithFakeWindow();
    void originalWindowConnectWithFakeCore();
    void locationsMapAndSelection();
    void originalWindowReadsRealCoreState();
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

void UpstreamBackgroundTest::originalWindowPublicApiBindingWithFakeWindow() {
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    FakeOriginalWindow window;
    bindOriginalHomeWindow(&window, &binder);
    QSignalSpy states(&binder, &HomeBinder::stateChanged);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE")));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    binder.selectServer(QStringLiteral("test.example"), QStringLiteral("DE"));
    QTRY_COMPARE_WITH_TIMEOUT(window.countryCode, QStringLiteral("de"), 5000);
    window.click();
    QTRY_COMPARE_WITH_TIMEOUT(window.state, CONNECT_STATE_CONNECTED, 5000);
    window.click();
    QTRY_COMPARE_WITH_TIMEOUT(window.state, CONNECT_STATE_DISCONNECTED, 5000);
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
}

void UpstreamBackgroundTest::originalWindowConnectWithFakeCore() {
    Preferences preferences;
    PreferencesHelper helper;
    QGraphicsScene scene;
    auto *window = new ConnectWindow::ConnectWindowItem(nullptr, &preferences, &helper);
    scene.addItem(window);
    scene.setSceneRect(window->boundingRect());
    QGraphicsView view(&scene);
    view.resize(380, 300);
    view.show();
    window->setInternetConnectivity(true);
    window->setClickable(true); // test fixture only; unsupported controls are not integrated
    ConnectWindow::ConnectButton *power = nullptr;
    for (auto *child : window->childItems()) {
        if (auto *button = dynamic_cast<ConnectWindow::ConnectButton *>(child)) power = button;
    }
    QVERIFY(power);
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    bindOriginalHomeWindow(window, &binder);
    QSignalSpy states(&binder, &HomeBinder::stateChanged);
    QSignalSpy clicks(window, &ConnectWindow::ConnectWindowItem::connectClick);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE")));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    binder.selectServer(QStringLiteral("de.example"), QStringLiteral("DE"));
    QTRY_COMPARE_WITH_TIMEOUT(states.last().at(0).toJsonObject().value("server").toObject().value("code").toString(), QStringLiteral("DE"), 5000);
    const QPoint clickPos = view.mapFromScene(power->mapToScene(power->boundingRect().center()));
    QTest::mouseClick(view.viewport(), Qt::LeftButton, Qt::NoModifier, clickPos);
    QTRY_COMPARE_WITH_TIMEOUT(clicks.size(), 1, 5000);
    QTRY_VERIFY_WITH_TIMEOUT(states.last().at(0).toJsonObject().value("live").toBool(), 5000);
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
}

void UpstreamBackgroundTest::locationsMapAndSelection() {
    LocationCatalog catalog;
    const QJsonObject germany{{"code", "DE"}, {"country", "Germany"}, {"city", "Berlin"}, {"host", "de.example"}};
    const QJsonObject canada{{"code", "CA"}, {"country", "Canada"}, {"city", "Toronto"}, {"host", "ca.example"}};
    QVERIFY(catalog.replace(QJsonArray{germany, canada}));
    QCOMPARE(catalog.locations().size(), 2);
    const auto caId = catalog.locations().at(0).cities.at(0).id;
    LocationCatalog::Selection choice;
    QVERIFY(catalog.selection(caId, &choice));
    QCOMPARE(choice.host, QStringLiteral("ca.example"));
    QVERIFY(catalog.replace(QJsonArray{canada, germany}));
    QVERIFY(catalog.selection(caId, &choice)); // IDs stay stable when input order changes
    QVERIFY(!catalog.replace(QJsonArray{QJsonObject{{"code", "DE"}}}));
    QVERIFY(catalog.locations().isEmpty());
    QVERIFY(!catalog.selection(caId, &choice));

    QTemporaryDir settingsDir;
    QVERIFY(settingsDir.isValid());
    QCoreApplication::setOrganizationName(QStringLiteral("GozarQtTest"));
    QCoreApplication::setApplicationName(QStringLiteral("GozarQtTest"));
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, settingsDir.path());
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    gui_locations::LocationsModelManager manager;
    Preferences preferences;
    LocationsWindow window(nullptr, &preferences, &manager);
    window.resize(350, 430);
    window.show();
    bindOriginalLocations(&window, &manager, &binder, &catalog);
    QSignalSpy states(&binder, &HomeBinder::stateChanged);
    QSignalSpy selections(&window, &LocationsWindow::selected);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE")));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    binder.loadServers();
    QTRY_COMPARE_WITH_TIMEOUT(manager.locationsModel()->rowCount(), 1, 5000);
    const auto selectedId = catalog.locations().at(0).cities.at(0).id;
    QVERIFY(manager.getIndexByLocationId(selectedId).isValid());
    gui_locations::LocationsView *visibleView = nullptr;
    for (auto *view : window.findChildren<gui_locations::LocationsView *>()) {
        if (view->isVisible()) { visibleView = view; break; }
    }
    QVERIFY(visibleView);
    QWidget *items = visibleView->widget();
    QVERIFY(items);
    const int row = qCeil(LOCATION_ITEM_HEIGHT * G_SCALE);
    QTest::mouseClick(items, Qt::LeftButton, Qt::NoModifier, QPoint(120, row / 2));
    QTRY_VERIFY_WITH_TIMEOUT(items->height() >= row * 2, 5000);
    QTest::qWait(300); // allow the original row expansion animation to finish
    states.clear();
    QTest::mouseClick(items, Qt::LeftButton, Qt::NoModifier, QPoint(120, row + row / 2));
    QTRY_COMPARE_WITH_TIMEOUT(selections.size(), 1, 5000);
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("server").toObject().value("code").toString(), QStringLiteral("DE"));
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
}

void UpstreamBackgroundTest::originalWindowReadsRealCoreState() {
    const QString corePath = qEnvironmentVariable("GOZAR_CI_TEST_CORE");
    if (corePath.isEmpty()) QSKIP("real core path not configured");
    QTemporaryDir isolated;
    QVERIFY(isolated.isValid());
    const QByteArray configDir = isolated.path().toUtf8();
    qputenv("HOME", configDir);
    qputenv("XDG_CONFIG_HOME", configDir);
    qputenv("APPDATA", configDir);
    qputenv("LOCALAPPDATA", configDir);
    qputenv("USERPROFILE", configDir);
    Preferences preferences;
    PreferencesHelper helper;
    QGraphicsScene scene;
    auto *window = new ConnectWindow::ConnectWindowItem(nullptr, &preferences, &helper);
    scene.addItem(window);
    window->setClickable(false); // no unsupported controls in this smoke test
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    bindOriginalHomeWindow(window, &binder);
    QSignalSpy states(&binder, &HomeBinder::stateChanged);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    QVERIFY(bridge.start(corePath));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 10000);
    const auto state = states.last().at(0).toJsonObject();
    QCOMPARE(state.value("version").toString(), QStringLiteral("1.19.0"));
    QVERIFY(!state.value("signed_in").toBool());
    QVERIFY(!state.value("live").toBool());
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 10000);
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
