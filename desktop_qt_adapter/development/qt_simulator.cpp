// Internal development integration only. This is not the full original
// MainWindow, a production launcher, an installer, or a VPN application.
// Normal modes start only the sibling fake core. The CI-only signed-out mode
// uses an isolated real core and never enables connection controls.
#include "corebridge.h"
#include "homebinder.h"
#include "locationcatalog.h"
#include "originalhomebinding.h"
#include "originallocationcontrols.h"
#include "originallocationsbinding.h"
#include "originallanguagebinding.h"
#include "originalmodebinding.h"
#include "originalpowerbinding.h"
#include "uicloseguard.h"
#include "uilanguage.h"
#include "uireadiness.h"
#include "connectwindow/background.h"
#include "connectwindow/connectbutton.h"
#include "connectwindow/connectwindowitem.h"
#include "graphicresources/imageresourcessvg.h"
#include "locations/locationsmodel_manager.h"
#include "locationswindow/locationswindow.h"
#include "preferenceswindow/comboboxitem.h"

#include <QApplication>
#include <QDir>
#include <QFile>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QLabel>
#include <QSettings>
#include <QTemporaryDir>
#include <QTest>
#include <QTimer>
#include <QVBoxLayout>
#include <cstdio>
#include <memory>

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    const QByteArray argument(argv[1]);
    const bool realSignedOut = argument == "--ci-real-signed-out";
    if ((realSignedOut && argc != 3) || (!realSignedOut && argc != 2)) return 2;
    const bool handoff = argument == "--handoff-stdio-v1";
    const bool demo = argument == "--demo" || argument == "--ci-demo";
    const bool ciDemo = argument == "--ci-demo";
    if (!realSignedOut && !handoff && !demo && argument != "--ci-simulate") return 2;
    const QString realCorePath = realSignedOut ? QString::fromLocal8Bit(argv[2]) : QString();
    QTemporaryDir isolated;
    if (!isolated.isValid()) return 3;
    const QByteArray profile = isolated.path().toUtf8();
    for (const char *key : {"HOME", "XDG_CONFIG_HOME", "APPDATA", "LOCALAPPDATA", "USERPROFILE"})
        qputenv(key, profile);
    int appArgc = 1;
    QApplication app(appArgc, argv);
    Q_INIT_RESOURCE(images);
    if (!QFile::exists(QStringLiteral(":/resources/fonts/ibmplexsans.ttf"))) return 9;
#ifdef Q_OS_WIN
    const QString fakePath = QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("fake_core.exe"));
#else
    const QString fakePath = QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("fake_core"));
#endif
    Preferences preferences;
    PreferencesHelper helper;
    LocationCatalog catalog;
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    QSettings settings(isolated.filePath(QStringLiteral("ui.ini")), QSettings::IniFormat);
    settings.setValue(QStringLiteral("OriginalQtUI/language"), QStringLiteral("en"));
    UiLanguage language(&settings);
    QWidget shell;
    shell.setWindowTitle(realSignedOut ? QStringLiteral("Gozar — signed-out core development probe")
                                       : QStringLiteral("Gozar — simulated integration, not a VPN"));
    QVBoxLayout layout(&shell);
    QLabel warning(realSignedOut ? QStringLiteral("READ-ONLY — isolated signed-out core; no connection controls")
                                 : QStringLiteral("SIMULATION ONLY — fake account and fake connection; no VPN"));
    warning.setWordWrap(true);
    layout.addWidget(&warning);
    QGraphicsScene homeScene;
    auto *home = new ConnectWindow::ConnectWindowItem(nullptr, &preferences, &helper);
    homeScene.addItem(home);
    homeScene.setSceneRect(home->boundingRect());
    QGraphicsView homeView(&homeScene);
    homeView.setFixedSize(380, 300);
    layout.addWidget(&homeView);
    gui_locations::LocationsModelManager manager;
    LocationsWindow locations(nullptr, &preferences, &manager);
    locations.setFixedSize(350, 430);
    layout.addWidget(&locations);
    locations.hide();
    QGraphicsScene settingsScene;
    auto *settingsBackground = new ConnectWindow::Background(nullptr, &preferences);
    settingsScene.addItem(settingsBackground);
    settingsScene.setSceneRect(0, 0, 350, 150);
    auto *modeItem = new PreferencesWindow::ComboBoxItem(settingsBackground);
    auto *languageItem = new PreferencesWindow::ComboBoxItem(settingsBackground);
    modeItem->setPos(0, 0);
    languageItem->setPos(0, 70);
    QGraphicsView settingsView(&settingsScene);
    settingsView.setFixedSize(380, 160);
    layout.addWidget(&settingsView);
    settingsView.hide();
    home->setInternetConnectivity(true);
    home->setClickable(false);
    if (!realSignedOut) home->setGozarNavigationClickable(true);
    bindOriginalHomeWindow(home, &binder);
    if ((!realSignedOut && !bindOriginalPowerControl(home, &binder)) ||
        !bindOriginalLocationControls(home, &locations, &binder)) return 4;
    bindOriginalLocations(&locations, &manager, &binder, &catalog);
    // The real-core probe is strictly read-only even if a malformed or
    // unexpectedly signed-in state is reported before it can be rejected.
    std::unique_ptr<OriginalModeBinding<PreferencesWindow::ComboBoxItem>> modeBinding;
    std::unique_ptr<OriginalLanguageBinding<PreferencesWindow::ComboBoxItem>> languageBinding;
    if (realSignedOut) {
        modeItem->setClickable(false);
        languageItem->setClickable(false);
    } else {
        modeBinding = std::make_unique<OriginalModeBinding<PreferencesWindow::ComboBoxItem>>(modeItem, &binder);
        languageBinding = std::make_unique<OriginalLanguageBinding<PreferencesWindow::ComboBoxItem>>(languageItem, &language, &binder);
    }
    ConnectWindow::ConnectButton *power = nullptr;
    for (auto *item : home->childItems())
        if (auto *candidate = dynamic_cast<ConnectWindow::ConnectButton *>(item)) power = candidate;
    if (!power) return 5;
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::closeClick, &shell, &QWidget::close);
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::minimizeClick, &shell, &QWidget::showMinimized);
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::preferencesClick, &shell, [&] {
        settingsView.setVisible(!settingsView.isVisible());
        shell.adjustSize();
    });
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationsClick, &shell, [&] {
        const bool expand = !locations.isVisible();
        locations.setVisible(expand);
        home->updateLocationsState(expand);
        if (expand) binder.loadServers();
        shell.adjustSize();
    });
    UiCloseGuard closeGuard(&shell, &bridge);
    UiReadiness readiness(&shell, &binder);
    enum Stage { Initial, Servers, Selection, Mode, Connecting, Disconnecting, Closing };
    Stage stage = Initial;
    bool failed = false;
    bool finished = false;
    bool sawSignedOutRealCore = false;
    bool confirmedShutdown = false;
    auto fail = [&] {
        failed = true;
        if (bridge.running()) shell.close();
        else app.exit(6);
    };
    QObject::connect(&closeGuard, &UiCloseGuard::shutdownUnconfirmed, &app,
                     [&](const QString &) { failed = true; app.exit(7); });
    QObject::connect(&binder, &HomeBinder::error, &app, [&](const QString &) { fail(); });
    QObject::connect(&bridge, &CoreBridge::transportError, &app, [&](const QString &) { fail(); });
    QObject::connect(&bridge, &CoreBridge::shutdownFinished, &app,
                     [&](bool ok, const QString &) { confirmedShutdown = ok; });
    QObject::connect(&readiness, &UiReadiness::readyForHandoff, &shell, [&] {
        if (realSignedOut || stage != Initial) return;
        if (handoff) {
            std::fputs("GOZAR_QT_READY_V1\n", stdout);
            std::fflush(stdout);
        }
        if (demo) {
            // The interactive demo waits for the user. CI exercises its
            // startup and guarded shutdown without requiring human input.
            if (ciDemo) QTimer::singleShot(300, &shell, [&] { shell.close(); });
            return;
        }
        stage = Servers;
        QTimer::singleShot(50, &shell, [&] { emit home->locationsClick(); });
    });
    QObject::connect(&binder, &HomeBinder::serversReady, &shell, [&](const QJsonArray &) {
        if (stage != Servers) return;
        if (catalog.locations().isEmpty() || catalog.locations().first().cities.isEmpty()) { fail(); return; }
        const LocationID id = catalog.locations().first().cities.first().id;
        stage = Selection;
        QTimer::singleShot(0, &shell, [&, id] { emit locations.selected(id); });
    });
    QObject::connect(&binder, &HomeBinder::stateChanged, &shell, [&](const QJsonObject &state) {
        if (realSignedOut) {
            if (sawSignedOutRealCore || failed) return;
            const auto signedIn = state.value(QStringLiteral("signed_in"));
            const auto live = state.value(QStringLiteral("live"));
            const auto connecting = state.value(QStringLiteral("connecting"));
            if (!signedIn.isBool() || !live.isBool() || !connecting.isBool() ||
                signedIn.toBool() || live.toBool() || connecting.toBool() || power->isClickable()) {
                fail();
                return;
            }
            sawSignedOutRealCore = true;
            finished = true;
            QTimer::singleShot(300, &shell, [&] { shell.close(); });
            return;
        }
        if (stage == Selection && state.value(QStringLiteral("server")).toObject().value(QStringLiteral("code")).toString() == QLatin1String("DE")) {
            stage = Mode;
            QTimer::singleShot(0, &shell, [&] {
                emit home->preferencesClick();
                if (!QMetaObject::invokeMethod(modeItem, "onMenuItemSelected", Qt::DirectConnection,
                    Q_ARG(QString, QStringLiteral("Proxy only")), Q_ARG(QVariant, QVariant(QStringLiteral("proxy-only"))))) fail();
            });
        } else if (stage == Mode && state.value(QStringLiteral("mode")).toString() == QLatin1String("proxy-only")) {
            stage = Connecting;
            QTimer::singleShot(50, &shell, [&] {
                if (!power->isClickable()) { fail(); return; }
                const QPoint pos = homeView.mapFromScene(power->mapToScene(power->boundingRect().center()));
                QTest::mouseClick(homeView.viewport(), Qt::LeftButton, Qt::NoModifier, pos);
            });
        } else if (stage == Connecting && state.value(QStringLiteral("live")).toBool(false)) {
            stage = Disconnecting;
            QTimer::singleShot(50, &shell, [&] {
                if (!power->isClickable()) { fail(); return; }
                const QPoint pos = homeView.mapFromScene(power->mapToScene(power->boundingRect().center()));
                QTest::mouseClick(homeView.viewport(), Qt::LeftButton, Qt::NoModifier, pos);
            });
        } else if (stage == Disconnecting && !state.value(QStringLiteral("live")).toBool(true) &&
                   !state.value(QStringLiteral("connecting")).toBool(true)) {
            stage = Closing;
            finished = true;
            QTimer::singleShot(300, &shell, [&] { shell.close(); });
        }
    });
    if (!demo || ciDemo)
        QTimer::singleShot(25000, &shell, [&] { if (!confirmedShutdown) fail(); });
    shell.show();
    readiness.markUiInitialized(); // this marks the development shell, not a production UI
    if (!bridge.start(realSignedOut ? realCorePath : fakePath)) return 8;
    const int result = app.exec();
    ImageResourcesSvg::instance().finishGracefully();
    if (result != 0 || (!demo && !finished) || (realSignedOut && !sawSignedOutRealCore) ||
        !confirmedShutdown || failed) return 1;
    if (!handoff) std::fputs("GOZAR_SIMULATION_OK\n", stdout);
    return 0;
}
