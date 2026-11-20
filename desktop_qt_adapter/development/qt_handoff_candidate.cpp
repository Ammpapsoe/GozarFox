// EXPERIMENTAL, NOT A RELEASE: limited original Qt widgets and test backend
// shims, not Windscribe's full MainWindow/controller. The normal build starts
// the sibling real Gozar core after Flutter's handoff; the CI build starts only
// fake_core in an isolated profile. It has no login and must not be distributed
// as a complete VPN client. In particular, process exclusivity and independent
// OS network-cleanup verification are not implemented here.
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
#include "connectwindow/connectwindowitem.h"
#include "graphicresources/imageresourcessvg.h"
#include "locations/locationsmodel_manager.h"
#include "locationswindow/locationswindow.h"
#include "preferenceswindow/comboboxitem.h"

#include <QApplication>
#include <QDir>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QLabel>
#include <QSettings>
#include <QTemporaryDir>
#include <QTimer>
#include <QVBoxLayout>
#include <cstdio>
#include <memory>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    const QByteArray argument(argv[1]);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
    const bool scripted = argument == "--ci-scripted";
    const bool probe = false;
    if (!scripted && argument != "--handoff-stdio-v1") return 2;
    const bool isolate = true;
#else
    const bool probe = argument == "--ci-signed-out";
    if (!probe && argument != "--handoff-stdio-v1") return 2;
    const bool isolate = probe;
#endif
    QTemporaryDir isolated;
    if (!isolated.isValid()) return 3;
    if (isolate) {
        for (const char *key : {"HOME", "XDG_CONFIG_HOME", "APPDATA", "LOCALAPPDATA", "USERPROFILE"})
            qputenv(key, isolated.path().toUtf8());
    }
    int appArgc = 1;
    QApplication app(appArgc, argv);
    Q_INIT_RESOURCE(images);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
    const QString coreName = QStringLiteral("fake_core");
#else
    const QString coreName = QStringLiteral("gozar-core");
#endif
#ifdef Q_OS_WIN
    const QString corePath = QDir(QCoreApplication::applicationDirPath()).filePath(coreName + QStringLiteral(".exe"));
#else
    const QString corePath = QDir(QCoreApplication::applicationDirPath()).filePath(coreName);
#endif
    Preferences preferences;
    PreferencesHelper helper;
    LocationCatalog catalog;
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
    QSettings settings(isolated.filePath(QStringLiteral("ui.ini")), QSettings::IniFormat);
    settings.setValue(QStringLiteral("OriginalQtUI/language"), QStringLiteral("en"));
#else
    QSettings settings(QSettings::IniFormat, QSettings::UserScope,
                       QStringLiteral("Gozar"), QStringLiteral("OriginalQtExperimental"));
#endif
    UiLanguage language(&settings);
    QWidget shell;
    shell.setWindowTitle(QStringLiteral("Gozar — experimental incomplete Qt integration"));
    QVBoxLayout layout(&shell);
    QLabel warning(QStringLiteral("EXPERIMENTAL: limited interface; not a release."));
    warning.setWordWrap(true);
    warning.setTextFormat(Qt::PlainText);
    layout.addWidget(&warning);
    QLabel status;
    status.setWordWrap(true);
    status.setTextFormat(Qt::PlainText);
    layout.addWidget(&status);
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
    auto *background = new ConnectWindow::Background(nullptr, &preferences);
    settingsScene.addItem(background);
    settingsScene.setSceneRect(0, 0, 350, 150);
    auto *modeItem = new PreferencesWindow::ComboBoxItem(background);
    auto *languageItem = new PreferencesWindow::ComboBoxItem(background);
    modeItem->setPos(0, 0);
    languageItem->setPos(0, 70);
    QGraphicsView settingsView(&settingsScene);
    settingsView.setFixedSize(380, 160);
    layout.addWidget(&settingsView);
    settingsView.hide();

    // Connectivity is not independently monitored by this candidate. This
    // only permits operation of the original widget; it is not a network test.
    home->setInternetConnectivity(true);
    home->setClickable(false);
    if (!probe) home->setGozarNavigationClickable(true);
    bindOriginalHomeWindow(home, &binder);
    if ((!probe && !bindOriginalPowerControl(home, &binder)) ||
        !bindOriginalLocationControls(home, &locations, &binder)) return 4;
    bindOriginalLocations(&locations, &manager, &binder, &catalog);
    std::unique_ptr<OriginalModeBinding<PreferencesWindow::ComboBoxItem>> modeBinding;
    std::unique_ptr<OriginalLanguageBinding<PreferencesWindow::ComboBoxItem>> languageBinding;
    if (probe) {
        modeItem->setClickable(false);
        languageItem->setClickable(false);
    } else {
        modeBinding = std::make_unique<OriginalModeBinding<PreferencesWindow::ComboBoxItem>>(modeItem, &binder);
        languageBinding = std::make_unique<OriginalLanguageBinding<PreferencesWindow::ComboBoxItem>>(languageItem, &language, &binder);
    }

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
    bool ready = false;
    bool sawSignedOut = false;
    bool shutdownConfirmed = false;
    bool failed = false;
    QObject::connect(&binder, &HomeBinder::stateChanged, &shell, [&](const QJsonObject &state) {
        if (!probe || sawSignedOut || failed) return;
        const auto signedIn = state.value(QStringLiteral("signed_in"));
        const auto live = state.value(QStringLiteral("live"));
        const auto connecting = state.value(QStringLiteral("connecting"));
        if (!signedIn.isBool() || !live.isBool() || !connecting.isBool() ||
            signedIn.toBool() || live.toBool() || connecting.toBool()) {
            failed = true;
        } else {
            sawSignedOut = true;
        }
        QTimer::singleShot(300, &shell, [&] { shell.close(); });
    });
    QObject::connect(&binder, &HomeBinder::error, &shell, [&](const QString &message) {
        status.setText(message);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
        failed = true;
        shell.close();
#endif
    });
    QObject::connect(&bridge, &CoreBridge::transportError, &shell, [&](const QString &message) {
        status.setText(message);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
        failed = true;
        shell.close();
#endif
    });
    QObject::connect(&bridge, &CoreBridge::stopped, &shell, [&] {
        if (!shutdownConfirmed) status.setText(QStringLiteral("Core stopped; cleanup is not confirmed."));
    });
    QObject::connect(&closeGuard, &UiCloseGuard::shutdownUnconfirmed, &shell, [&](const QString &reason) {
        status.setText(QStringLiteral("Shutdown unconfirmed: ") + reason);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
        failed = true;
        app.exit(5);
#endif
    });
    QObject::connect(&bridge, &CoreBridge::shutdownFinished, &shell,
                     [&](bool ok, const QString &) { shutdownConfirmed = ok; });
#ifdef GOZAR_HANDOFF_CI_FIXTURE
    enum Stage { AwaitReady, Connecting, Disconnecting, Closing };
    Stage stage = AwaitReady;
    QObject::connect(&binder, &HomeBinder::stateChanged, &shell, [&](const QJsonObject &state) {
        if (!scripted) return;
        if (stage == Connecting && state.value(QStringLiteral("live")).toBool(false)) {
            stage = Disconnecting;
            QTimer::singleShot(0, &shell, [&] { binder.toggleConnection(); });
        } else if (stage == Disconnecting && state.value(QStringLiteral("live")).isBool() &&
                   !state.value(QStringLiteral("live")).toBool() &&
                   state.value(QStringLiteral("connecting")).isBool() &&
                   !state.value(QStringLiteral("connecting")).toBool()) {
            stage = Closing;
            QTimer::singleShot(0, &shell, [&] { shell.close(); });
        }
    });
#endif
    QObject::connect(&readiness, &UiReadiness::readyForHandoff, &shell, [&] {
        if (ready || probe) return;
        ready = true;
        std::fputs("GOZAR_QT_READY_V1\n", stdout);
        std::fflush(stdout);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
        if (scripted) {
            stage = Connecting;
            QTimer::singleShot(0, &shell, [&] { binder.toggleConnection(); });
        } else {
            // Keep the fixture alive long enough for Flutter's readiness check.
            QTimer::singleShot(2000, &shell, [&] { shell.close(); });
        }
#endif
    });
    // No timeout can silently kill a real core or report cleanup as successful.
    QTimer::singleShot(25000, &shell, [&] {
        if (!ready && !(probe && sawSignedOut)) {
            status.setText(QStringLiteral("Core readiness not confirmed; close cautiously."));
            if (probe) {
                failed = true;
                shell.close();
            }
#ifdef GOZAR_HANDOFF_CI_FIXTURE
            failed = true;
            shell.close();
#endif
        }
    });
    shell.show();
    readiness.markUiInitialized();
    if (!bridge.start(corePath)) return 6;
    const int result = app.exec();
    ImageResourcesSvg::instance().finishGracefully();
#ifdef GOZAR_HANDOFF_CI_FIXTURE
    if (scripted && stage != Closing) return 1;
#endif
    return result == 0 && (probe ? sawSignedOut : ready) && shutdownConfirmed && !failed ? 0 : 1;
}
