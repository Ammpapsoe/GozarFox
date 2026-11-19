#include "corebridge.h"
#include "homebinder.h"
#include "uilanguage.h"
#include "languagecontroller.h"
#include "originalhomebinding.h"
#include "originalpowerbinding.h"
#include "originalnavigationbinding.h"
#include "originalmodebinding.h"
#include "originallanguagebinding.h"
#include "originallocationsbinding.h"
#include "originallocationcontrols.h"
#include "connectwindow/background.h"
#include "connectwindow/connectbutton.h"
#include "connectwindow/connectwindowitem.h"
#include "locations/locationsmodel_manager.h"
#include "locationswindow/locationswindow.h"
#include "preferenceswindow/comboboxitem.h"
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
    QString displayedIp;
    CONNECT_STATE state = CONNECT_STATE_DISCONNECTED;
    void updateConnectState(const types::ConnectState &value) { state = value.connectState; }
    void updateLocationInfo(const QString &, const QString &, const QString &code, PingTime, bool) { countryCode = code; }
    void updateMyIp(const QString &ip) { displayedIp = ip; }
    void click() { emit connectClick(); }
signals:
    void connectClick();
};

// Test-only control stand-in; original ComboBoxItem declaration is compiled
// separately. It imitates the original signal ordering, not its graphics.
class FakeModeItem : public QObject {
    Q_OBJECT
public:
    void clear() { current_.clear(); populated_ = false; }
    void setClickable(bool value) { clickable_ = value; }
    void setLabelCaption(const QString &) {}
    void setItems(const QList<QPair<QString, QVariant>> &, const QVariant &selected) {
        current_ = selected.toString(); populated_ = true;
    }
    void setCurrentItem(const QVariant &value) { current_ = value.toString(); }
    void choose(const QString &value) { current_ = value; emit currentItemChanged(value); }
    QString current_;
    bool clickable_ = false;
    bool populated_ = false;
signals:
    void currentItemChanged(QVariant value);
};

// Test-only controller stand-in; the production adapter invokes the original
// MainWindowController API, but this scenario does not instantiate it.
class FakeNavigationController : public QObject {
public:
    enum WINDOW_ID { WINDOW_ID_CONNECT, WINDOW_ID_OTHER };
    explicit FakeNavigationController(ConnectWindow::ConnectWindowItem *home) : home_(home) {}
    ConnectWindow::ConnectWindowItem *getConnectWindow() { return home_; }
    WINDOW_ID currentWindow() const { return current_; }
    bool isLocationsExpanded() const { return expanded_; }
    void expandLocations() { expanded_ = true; }
    void collapseLocations() { expanded_ = false; }
    WINDOW_ID current_ = WINDOW_ID_CONNECT;
private:
    ConnectWindow::ConnectWindowItem *home_;
    bool expanded_ = false;
};

class UpstreamBackgroundTest : public QObject {
    Q_OBJECT
private slots:
    void sourceFlagFollowsSimulatedCore();
    void upstreamPowerButtonTriggersSimulatedCore();
    void serverAndSettingsUseCoreResponses();
    void originalModeControlWithFakeCore();
    void originalWindowPublicApiBindingWithFakeWindow();
    void originalWindowConnectWithFakeCore();
    void locationsMapAndSelection();
    void originalWindowReadsRealCoreState();
    void incompleteStateDisablesMutations();
    void originalLanguagesAndDirection();
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
    FakeModeItem modeItem;
    Preferences preferences;
    ConnectWindow::Background background(nullptr, &preferences);
    ConnectWindow::ConnectButton button;
    CoreBridge bridge;
    HomeBinder binder(&bridge, &background, &button);
    OriginalModeBinding<FakeModeItem> modeBinding(&modeItem, &binder);
    QVERIFY(!modeItem.populated_);
    QVERIFY(!modeItem.clickable_);
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
#ifdef Q_OS_WIN
    const QString selectedMode = QStringLiteral("system-proxy");
#else
    const int oldErrors = errors.size();
    binder.setMode(QStringLiteral("system-proxy"));
    QCOMPARE(errors.size(), oldErrors + 1);
    QVERIFY(states.isEmpty());
    const QString selectedMode = QStringLiteral("proxy-only");
#endif
    const QString previousMode = modeItem.current_;
    QVERIFY(modeItem.populated_);
    QVERIFY(modeItem.clickable_);
    modeItem.choose(selectedMode);
    QCOMPARE(modeItem.current_, previousMode); // no optimistic selection
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(states.last().at(0).toJsonObject().value("mode").toString(), selectedMode);
    QCOMPARE(modeItem.current_, selectedMode);
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
    errors.clear();
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
    QVERIFY(!modeItem.clickable_);
    states.clear();
    modeItem.choose(QStringLiteral("tun"));
    QCOMPARE(modeItem.current_, selectedMode);
    binder.setMode(QStringLiteral("tun"));
    binder.selectServer(QStringLiteral("other.example"), QStringLiteral("CA"));
    QVERIFY(bridge.request(QStringLiteral("state")) > 0);
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    const auto locked = states.last().at(0).toJsonObject();
    QCOMPARE(locked.value("mode").toString(), selectedMode);
    QCOMPARE(locked.value("server").toObject().value("code").toString(), QStringLiteral("DE"));
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
    QVERIFY(!modeItem.clickable_);
    QVERIFY(!modeItem.populated_);
}

void UpstreamBackgroundTest::originalModeControlWithFakeCore() {
    Preferences preferences;
    ConnectWindow::Background parent(nullptr, &preferences);
    PreferencesWindow::ComboBoxItem modeItem(&parent);
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    OriginalModeBinding<PreferencesWindow::ComboBoxItem> modeBinding(&modeItem, &binder);
    QSignalSpy states(&binder, &HomeBinder::stateChanged);
    QSignalSpy errors(&binder, &HomeBinder::error);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    QVERIFY(!modeItem.hasItems());
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE")));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QCOMPARE(modeItem.currentItem().toString(), QStringLiteral("tun"));

    // Exercise the original menu's selection slot and signal ordering. This is
    // not a visual mouse or complete preferences-window interaction test.
    QVERIFY(QMetaObject::invokeMethod(&modeItem, "onMenuItemSelected", Qt::DirectConnection,
        Q_ARG(QString, QStringLiteral("Proxy only")), Q_ARG(QVariant, QVariant(QStringLiteral("proxy-only")))));
    QCOMPARE(modeItem.currentItem().toString(), QStringLiteral("tun"));
    QTRY_COMPARE_WITH_TIMEOUT(modeItem.currentItem().toString(), QStringLiteral("proxy-only"), 5000);

#ifndef Q_OS_WIN
    const int beforeUnsupported = errors.size();
    QVERIFY(QMetaObject::invokeMethod(&modeItem, "onMenuItemSelected", Qt::DirectConnection,
        Q_ARG(QString, QStringLiteral("System proxy")), Q_ARG(QVariant, QVariant(QStringLiteral("system-proxy")))));
    QCOMPARE(errors.size(), beforeUnsupported + 1);
    QCOMPARE(modeItem.currentItem().toString(), QStringLiteral("proxy-only"));
#endif

    QVERIFY(bridge.request(QStringLiteral("connect")) > 0);
    QTRY_VERIFY_WITH_TIMEOUT(binder.currentState().value(QStringLiteral("live")).toBool(), 5000);
    const int beforeLocked = errors.size();
    QVERIFY(QMetaObject::invokeMethod(&modeItem, "onMenuItemSelected", Qt::DirectConnection,
        Q_ARG(QString, QStringLiteral("System tunnel")), Q_ARG(QVariant, QVariant(QStringLiteral("tun")))));
    QCOMPARE(errors.size(), beforeLocked + 1);
    QCOMPARE(modeItem.currentItem().toString(), QStringLiteral("proxy-only"));
    QCOMPARE(binder.currentState().value(QStringLiteral("mode")).toString(), QStringLiteral("proxy-only"));
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
    QVERIFY(!modeItem.hasItems());
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
    QTRY_COMPARE_WITH_TIMEOUT(window.displayedIp, QStringLiteral("203.0.113.10"), 5000);
    window.click();
    QTRY_COMPARE_WITH_TIMEOUT(window.state, CONNECT_STATE_DISCONNECTED, 5000);
    QTRY_VERIFY_WITH_TIMEOUT(window.displayedIp.isEmpty(), 5000);
    binder.setMode(QStringLiteral("proxy-only"));
    QTRY_COMPARE_WITH_TIMEOUT(binder.currentState().value(QStringLiteral("mode")).toString(), QStringLiteral("proxy-only"), 5000);
    window.click();
    QTRY_COMPARE_WITH_TIMEOUT(window.state, CONNECT_STATE_CONNECTED, 5000);
    QVERIFY(window.displayedIp.isEmpty()); // proxy exit is not the host's IP
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
    QVERIFY(bindOriginalPowerControl(window, &binder));
    QVERIFY(!power->isClickable()); // state is not known yet
    QSignalSpy states(&binder, &HomeBinder::stateChanged);
    QSignalSpy clicks(window, &ConnectWindow::ConnectWindowItem::connectClick);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    QVERIFY(bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE")));
    QTRY_VERIFY_WITH_TIMEOUT(!states.isEmpty(), 5000);
    QVERIFY(power->isClickable());
    binder.selectServer(QStringLiteral("de.example"), QStringLiteral("DE"));
    QTRY_COMPARE_WITH_TIMEOUT(states.last().at(0).toJsonObject().value("server").toObject().value("code").toString(), QStringLiteral("DE"), 5000);
    const QPoint clickPos = view.mapFromScene(power->mapToScene(power->boundingRect().center()));
    QTest::mouseClick(view.viewport(), Qt::LeftButton, Qt::NoModifier, clickPos);
    QTRY_COMPARE_WITH_TIMEOUT(clicks.size(), 1, 5000);
    QTRY_VERIFY_WITH_TIMEOUT(states.last().at(0).toJsonObject().value("live").toBool(), 5000);
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
    QVERIFY(!power->isClickable());
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
    QJsonObject sameHostOtherPort = germany;
    sameHostOtherPort.insert(QStringLiteral("port"), 444);
    QVERIFY(!catalog.replace(QJsonArray{germany, sameHostOtherPort}));
    QVERIFY(catalog.locations().isEmpty());
    QJsonObject codeAsHost = canada;
    codeAsHost.insert(QStringLiteral("host"), QStringLiteral("de"));
    QVERIFY(!catalog.replace(QJsonArray{germany, codeAsHost}));
    QVERIFY(catalog.locations().isEmpty());

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
    PreferencesHelper helper;
    QGraphicsScene homeScene;
    auto *home = new ConnectWindow::ConnectWindowItem(nullptr, &preferences, &helper);
    homeScene.addItem(home);
    LocationsWindow window(nullptr, &preferences, &manager);
    window.resize(350, 430);
    window.show();
    bindOriginalLocations(&window, &manager, &binder, &catalog);
    bindOriginalLocationControls(home, &window, &binder);
    FakeNavigationController navigation(home);
    bindOriginalLocationsNavigation(&navigation, &binder);
    QSignalSpy errors(&binder, &HomeBinder::error);
    QSignalSpy states(&binder, &HomeBinder::stateChanged);
    QSignalSpy selections(&window, &LocationsWindow::selected);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    QSignalSpy ready(&bridge, &CoreBridge::ready);
    qputenv("FAKE_CORE_SERVERS_FAIL_AFTER_FIRST", "1");
    const bool started = bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE"));
    QTRY_VERIFY_WITH_TIMEOUT(!ready.isEmpty(), 5000);
    qunsetenv("FAKE_CORE_SERVERS_FAIL_AFTER_FIRST");
    QVERIFY(started);
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
    QTRY_COMPARE_WITH_TIMEOUT(states.last().at(0).toJsonObject().value("server").toObject().value("code").toString(), QStringLiteral("DE"), 5000);
    // Exercise the original home-to-locations search signal, without a fake UI.
    emit home->searchFilterChanged(QStringLiteral("no-match-42"));
    QTRY_COMPARE_WITH_TIMEOUT(manager.filterLocationsProxyModel()->rowCount(), 0, 5000);
    emit home->searchFilterChanged(QStringLiteral("DE"));
    QTRY_COMPARE_WITH_TIMEOUT(manager.filterLocationsProxyModel()->rowCount(), 1, 5000);
    const int errorCount = errors.size();
    emit home->locationTabClicked(LOCATION_TAB_STATIC_IPS_LOCATIONS);
    QCOMPARE(errors.size(), errorCount + 1);
    emit home->locationsClick();
    QVERIFY(navigation.isLocationsExpanded());
    // Refresh begins by invalidating the previous entries. The second fake
    // fetch fails, so an old ID must never remain selectable afterward.
    QCOMPARE(manager.locationsModel()->rowCount(), 0);
    QVERIFY(!catalog.selection(selectedId, nullptr));
    QTRY_VERIFY_WITH_TIMEOUT(errors.size() > errorCount + 1, 5000);
    QCOMPARE(manager.locationsModel()->rowCount(), 0);
    emit home->locationsClick();
    QVERIFY(!navigation.isLocationsExpanded());
    navigation.current_ = FakeNavigationController::WINDOW_ID_OTHER;
    emit home->locationsClick();
    QVERIFY(!navigation.isLocationsExpanded());
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

void UpstreamBackgroundTest::incompleteStateDisablesMutations() {
    Preferences preferences;
    PreferencesHelper helper;
    QGraphicsScene scene;
    auto *window = new ConnectWindow::ConnectWindowItem(nullptr, &preferences, &helper);
    scene.addItem(window);
    window->setClickable(true);
    ConnectWindow::ConnectButton *power = nullptr;
    for (auto *item : window->childItems())
        if (auto *candidate = dynamic_cast<ConnectWindow::ConnectButton *>(item)) power = candidate;
    QVERIFY(power);
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    QVERIFY(bindOriginalPowerControl(window, &binder));
    QVERIFY(!power->isClickable());
    QSignalSpy ready(&bridge, &CoreBridge::ready), replies(&bridge, &CoreBridge::reply);
    QSignalSpy errors(&binder, &HomeBinder::error), availability(&binder, &HomeBinder::availabilityChanged);
    QSignalSpy stopped(&bridge, &CoreBridge::stopped);
    qputenv("FAKE_CORE_OMIT_LIVE", "1");
    const bool started = bridge.start(qEnvironmentVariable("GOZAR_FAKE_CORE"));
    QTRY_VERIFY_WITH_TIMEOUT(!ready.isEmpty(), 5000);
    qunsetenv("FAKE_CORE_OMIT_LIVE");
    QVERIFY(started);
    QTRY_VERIFY_WITH_TIMEOUT(!errors.isEmpty(), 5000);
    QVERIFY(!availability.isEmpty());
    QVERIFY(!availability.last().at(0).toBool());
    QVERIFY(!power->isClickable());
    const int count = replies.size();
    binder.toggleConnection();
    binder.setMode(QStringLiteral("system-proxy"));
    QTest::qWait(150);
    QCOMPARE(replies.size(), count);
    bridge.shutdown();
    QTRY_COMPARE_WITH_TIMEOUT(stopped.size(), 1, 5000);
}

void UpstreamBackgroundTest::originalLanguagesAndDirection() {
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("ui.ini"));
    QSettings settings(path, QSettings::IniFormat);
    UiLanguage uiLanguage(&settings);
    const QStringList codes{QStringLiteral("en"), QStringLiteral("ar"), QStringLiteral("be"),
        QStringLiteral("cs"), QStringLiteral("de"), QStringLiteral("el"), QStringLiteral("es"),
        QStringLiteral("fa"), QStringLiteral("fr"), QStringLiteral("hi"), QStringLiteral("id"),
        QStringLiteral("it"), QStringLiteral("ja"), QStringLiteral("ko"), QStringLiteral("pl"),
        QStringLiteral("pt"), QStringLiteral("ru"), QStringLiteral("sk"), QStringLiteral("tr"),
        QStringLiteral("uk"), QStringLiteral("vi"), QStringLiteral("zh-CN"), QStringLiteral("zh-TW")};
    for (const QString &code : codes) {
        QString error;
        QVERIFY2(uiLanguage.apply(code, &error), qPrintable(code + QStringLiteral(": ") + error));
        QCOMPARE(LanguageController::instance().getLanguage(), code);
        QCOMPARE(uiLanguage.choice(), code);
    }
    QVERIFY(uiLanguage.apply(QStringLiteral("fa")));
    QCOMPARE(QApplication::layoutDirection(), Qt::RightToLeft);
    QCOMPARE(QCoreApplication::translate("ConnectWindow::LocationsButton", "Locations"),
             QStringLiteral("\u0645\u06a9\u0627\u0646\u200c\u0647\u0627"));
    QSettings reloaded(path, QSettings::IniFormat);
    QCOMPARE(reloaded.value(QStringLiteral("OriginalQtUI/language")).toString(), QStringLiteral("fa"));
    QString error;
    QVERIFY(!uiLanguage.apply(QStringLiteral("not-a-language"), &error));
    QVERIFY(!error.isEmpty());
    QCOMPARE(uiLanguage.choice(), QStringLiteral("fa"));
    QCOMPARE(LanguageController::instance().getLanguage(), QStringLiteral("fa"));
    QVERIFY(uiLanguage.apply(QStringLiteral("de")));
    QCOMPARE(QApplication::layoutDirection(), Qt::LeftToRight);
    QCOMPARE(QCoreApplication::translate("ConnectWindow::LocationsButton", "Locations"), QStringLiteral("Orte"));
    QVERIFY(uiLanguage.apply(QStringLiteral("en")));

    // Exercise the original settings combo's real menu selection slot. This
    // deliberately does not instantiate the full Windscribe settings page.
    Preferences preferences;
    ConnectWindow::Background parent(nullptr, &preferences);
    PreferencesWindow::ComboBoxItem languageItem(&parent);
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    QSignalSpy errors(&binder, &HomeBinder::error);
    OriginalLanguageBinding<PreferencesWindow::ComboBoxItem> binding(&languageItem, &uiLanguage, &binder);
    QCOMPARE(languageItem.currentItem().toString(), QStringLiteral("en"));
    QVERIFY(QMetaObject::invokeMethod(&languageItem, "onMenuItemSelected", Qt::DirectConnection,
        Q_ARG(QString, QStringLiteral("Persian")), Q_ARG(QVariant, QVariant(QStringLiteral("fa")))));
    QTRY_COMPARE_WITH_TIMEOUT(uiLanguage.choice(), QStringLiteral("fa"), 5000);
    QCOMPARE(languageItem.currentItem().toString(), QStringLiteral("fa"));
    QCOMPARE(LanguageController::instance().getLanguage(), QStringLiteral("fa"));
    QCOMPARE(QApplication::layoutDirection(), Qt::RightToLeft);
    QVERIFY(QMetaObject::invokeMethod(&languageItem, "onMenuItemSelected", Qt::DirectConnection,
        Q_ARG(QString, QStringLiteral("Invalid")), Q_ARG(QVariant, QVariant(QStringLiteral("invalid")))));
    QTRY_COMPARE_WITH_TIMEOUT(errors.size(), 1, 5000);
    QCOMPARE(languageItem.currentItem().toString(), QStringLiteral("fa"));
    QCOMPARE(uiLanguage.choice(), QStringLiteral("fa"));
    QVERIFY(QMetaObject::invokeMethod(&languageItem, "onMenuItemSelected", Qt::DirectConnection,
        Q_ARG(QString, QStringLiteral("Deutsch")), Q_ARG(QVariant, QVariant(QStringLiteral("de")))));
    QTRY_COMPARE_WITH_TIMEOUT(languageItem.currentItem().toString(), QStringLiteral("de"), 5000);
    QCOMPARE(QApplication::layoutDirection(), Qt::LeftToRight);
    QSettings afterChoice(path, QSettings::IniFormat);
    QCOMPARE(afterChoice.value(QStringLiteral("OriginalQtUI/language")).toString(), QStringLiteral("de"));
    QVERIFY(uiLanguage.apply(QStringLiteral("en")));
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
