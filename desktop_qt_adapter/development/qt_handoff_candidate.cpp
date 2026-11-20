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
#include "gozarpreferences.h"
#include "dpiscalemanager.h"
#include "graphicresources/fontmanager.h"
#include "originalpowerbinding.h"
#include "uicloseguard.h"
#include "uilanguage.h"
#include "uireadiness.h"
#include "connectwindow/connectwindowitem.h"
#include "graphicresources/imageresourcessvg.h"
#include "locations/locationsmodel_manager.h"
#include "locationswindow/locationswindow.h"

#include <QApplication>
#include <QDir>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QLabel>
#include <QMouseEvent>
#include <QScreen>
#include <QShortcut>
#include <QWindow>
#include <QtMath>
#include <QSettings>
#include <QTemporaryDir>
#include <QTimer>
#include <QVBoxLayout>
#include <cstdio>
#ifdef GOZAR_HANDOFF_CI_FIXTURE
#include <QElapsedTimer>
#include <QTest>
#endif

namespace {
class HomeView final : public QGraphicsView {
public:
    using QGraphicsView::QGraphicsView;
protected:
    void mousePressEvent(QMouseEvent *event) override {
        // Only the empty part of the original header is a drag target; don't
        // steal graphics-scene clicks from menu, minimize, or close.
        const auto point = mapToScene(event->position().toPoint());
        if (event->button() == Qt::LeftButton && point.y() < 42 * G_SCALE &&
            point.x() > 54 * G_SCALE && point.x() < 240 * G_SCALE) {
            if (const auto handle = window()->windowHandle(); handle && handle->startSystemMove()) {
                event->accept();
                return;
            }
        }
        QGraphicsView::mousePressEvent(event);
    }
};
}


int main(int argc, char **argv) {
    if (argc != 2) return 2;
    const QByteArray argument(argv[1]);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
    const bool scripted = argument == "--ci-scripted";
    const bool layoutProbe = argument == "--ci-layout";
    const bool probe = false;
    if (!scripted && !layoutProbe && argument != "--handoff-stdio-v1") return 2;
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
    FontManager::instance();
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
    settings.setValue(QStringLiteral("OriginalQtUI/language"), layoutProbe && qEnvironmentVariableIsSet("GOZAR_VISUAL_FARSI")
        ? QStringLiteral("fa") : QStringLiteral("en"));
    if (layoutProbe) qputenv("GOZAR_FAKE_LAYOUT_CATALOG", "1");
#else
    QSettings settings(QSettings::IniFormat, QSettings::UserScope,
                       QStringLiteral("Gozar"), QStringLiteral("OriginalQtExperimental"));
#endif
    UiLanguage language(&settings);
    QWidget shell;
    shell.setObjectName(QStringLiteral("gozarDesktop"));
    // The original graphics use physical coordinates. Keep their layout stable
    // while the application locale and text retain native bidi shaping.
    shell.setLayoutDirection(Qt::LeftToRight);
    shell.setWindowTitle(QStringLiteral("Gozar — Experimental"));
    shell.setWindowFlags(Qt::Window | Qt::FramelessWindowHint | Qt::WindowMinimizeButtonHint);
#ifdef Q_OS_WIN
    shell.setWindowFlag(Qt::MSWindowsFixedSizeDialogHint, true);
#endif
    shell.setAttribute(Qt::WA_TranslucentBackground);
    QVBoxLayout layout(&shell);
    layout.setContentsMargins(0, 0, 0, 0);
    layout.setSpacing(0);
    QGraphicsScene homeScene;
    auto *home = new ConnectWindow::ConnectWindowItem(nullptr, &preferences, &helper);
    homeScene.addItem(home);
    HomeView homeView(&homeScene);
    homeView.setFrameShape(QFrame::NoFrame);
    homeView.setAlignment(Qt::AlignTop | Qt::AlignLeft);
    homeView.setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    homeView.setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    homeView.setStyleSheet(QStringLiteral("background: transparent;"));
    layout.addWidget(&homeView);
    QLabel status;
    status.setWordWrap(true);
    status.setTextFormat(Qt::PlainText);
    status.setContentsMargins(12, 6, 12, 6);
    status.setStyleSheet(QStringLiteral("color: #ffe2a7; background: #182232;"));
    status.hide();
    layout.addWidget(&status);
    gui_locations::LocationsModelManager manager;
    LocationsWindow locations(nullptr, &preferences, &manager);
    layout.addWidget(&locations);
    locations.hide();
    GozarPreferences settingsView(&binder, &language, probe);
    layout.addWidget(&settingsView);
    settingsView.hide();

    const auto fitWindow = [&] {
        const auto rect = home->boundingRect();
        const int width = qCeil(rect.width());
        const int homeHeight = qCeil(rect.height());
        homeScene.setSceneRect(rect);
        homeView.setFixedSize(width, homeHeight);
        status.setFixedWidth(width);
        const auto *screen = shell.screen();
        const int available = screen ? screen->availableGeometry().height() : 720;
        const int statusHeight = status.isHidden() ? 0 : status.heightForWidth(width);
        const int room = qMax(1, available - homeHeight - qMax(0, statusHeight) - 12);
        locations.setFixedSize(width, qMin(room, qCeil(locations.tabAndFooterHeight() * G_SCALE)));
        if (!settingsView.isHidden()) settingsView.fitPanel(width, room);
        layout.invalidate();
        shell.setFixedSize(width, layout.sizeHint().height());
        if (screen && shell.isVisible() && !shell.isMinimized()) {
            const QRect area = screen->availableGeometry();
            shell.move(qBound(area.left(), shell.x(), qMax(area.left(), area.right() - width + 1)),
                       qBound(area.top(), shell.y(), qMax(area.top(), area.bottom() - shell.height() + 1)));
        }
    };
    const auto setStatus = [&](const QString &message) {
        status.setText(message);
        status.setVisible(!message.isEmpty());
        fitWindow();
    };
    QObject::connect(&locations, &LocationsWindow::heightChanged, &shell, fitWindow);
    auto &dpi = DpiScaleManager::instance();
    const auto rescale = [&] { home->updateScaling(); locations.updateScaling(); fitWindow(); };
    QObject::connect(&dpi, &DpiScaleManager::scaleChanged, &shell, rescale);
    QObject::connect(&dpi, &DpiScaleManager::newScreen, &shell, [&](QScreen *) { fitWindow(); });

    // Connectivity is not independently monitored by this candidate. This
    // only permits operation of the original widget; it is not a network test.
    home->setInternetConnectivity(true);
    home->setClickable(false);
    if (!probe) home->setGozarNavigationClickable(true);
    bindOriginalHomeWindow(home, &binder);
    if ((!probe && !bindOriginalPowerControl(home, &binder)) ||
        !bindOriginalLocationControls(home, &locations, &binder)) return 4;
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::closeClick, &shell, &QWidget::close);
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::minimizeClick, &shell, &QWidget::showMinimized);
    QShortcut escape(QKeySequence(Qt::Key_Escape), &shell);
    escape.setEnabled(false);
    const auto setLocations = [&](bool expand) {
        locations.setVisible(expand);
        home->updateLocationsState(expand);
        if (expand) binder.loadServers();
        fitWindow();
    };
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::preferencesClick, &shell, [&] {
        const bool open = settingsView.isHidden();
        setLocations(false);
        settingsView.setVisible(open);
        escape.setEnabled(open);
        fitWindow();
    });
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationsClick, &shell, [&] {
        settingsView.hide();
        escape.setEnabled(false);
        setLocations(locations.isHidden());
    });
    QObject::connect(&escape, &QShortcut::activated, &shell, [&] {
        // Keep Escape available to the original locations search widget.
        if (!settingsView.isHidden()) { settingsView.hide(); escape.setEnabled(false); fitWindow(); }
    });
    QObject::connect(&binder, &HomeBinder::serversReady, &shell, [&] { setStatus({}); });
    bindOriginalLocations(&locations, &manager, &binder, &catalog);
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
        setStatus(message);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
        failed = true;
        shell.close();
#endif
    });
    QObject::connect(&bridge, &CoreBridge::transportError, &shell, [&](const QString &message) {
        setStatus(message);
#ifdef GOZAR_HANDOFF_CI_FIXTURE
        failed = true;
        shell.close();
#endif
    });
    QObject::connect(&bridge, &CoreBridge::stopped, &shell, [&] {
        if (!shutdownConfirmed) setStatus(QStringLiteral("Core stopped; cleanup is not confirmed."));
    });
    QObject::connect(&closeGuard, &UiCloseGuard::shutdownUnconfirmed, &shell, [&](const QString &reason) {
        setStatus(QStringLiteral("Shutdown unconfirmed: ") + reason);
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
        } else if (layoutProbe) {
            QTimer::singleShot(100, &shell, [&] {
                const auto test = [&]() -> bool {
                    if (!(shell.windowFlags() & Qt::FramelessWindowHint) || shell.size() != homeView.size()) return false;
                    const QString screenshots = qEnvironmentVariable("GOZAR_VISUAL_DIR");
                    if (!screenshots.isEmpty()) shell.grab().save(QDir(screenshots).filePath("compact-home.png"));
                    emit home->preferencesClick();
                    if (!settingsView.isVisible() || locations.isVisible() || settingsView.y() != homeView.height()) return false;
                    const auto connection = settingsView.mapFromScene(QPointF(28 * G_SCALE, 68 * G_SCALE));
                    QTest::mouseClick(settingsView.viewport(), Qt::LeftButton, Qt::NoModifier, connection);
                    if (settingsView.property("gozarPage").toString() != QStringLiteral("connection")) return false;
                    if (!screenshots.isEmpty()) {
                        QTest::qWait(300); // capture the final state of the original tab animation
                        shell.grab().save(QDir(screenshots).filePath("connection-settings.png"));
                    }
                    emit home->locationsClick();
                    if (!locations.isVisible() || settingsView.isVisible() || locations.y() != homeView.height()) return false;
                    QElapsedTimer wait;
                    wait.start();
                    while (catalog.locations().isEmpty() && wait.elapsed() < 3000) QTest::qWait(20);
                    if (catalog.locations().size() != 4) return false;
                    if (!screenshots.isEmpty()) shell.grab().save(QDir(screenshots).filePath("server-list.png"));
                    emit home->locationsClick();
                    return !locations.isVisible() && shell.size() == homeView.size();
                };
                if (!test()) {
                    failed = true;
                    std::fputs("Qt layout/navigation regression\n", stderr);
                }
                shell.close();
            });
        } else {
            // Keep the fixture alive long enough for Flutter's readiness check.
            QTimer::singleShot(2000, &shell, [&] { shell.close(); });
        }
#endif
    });
    // No timeout can silently kill a real core or report cleanup as successful.
    QTimer::singleShot(25000, &shell, [&] {
        if (!ready && !(probe && sawSignedOut)) {
            setStatus(QStringLiteral("Core readiness not confirmed; close cautiously."));
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
    fitWindow();
    shell.show();
    if (dpi.setMainWindow(&shell)) rescale();
    readiness.markUiInitialized();
    if (!bridge.start(corePath)) return 6;
    const int result = app.exec();
    ImageResourcesSvg::instance().finishGracefully();
#ifdef GOZAR_HANDOFF_CI_FIXTURE
    if (scripted && stage != Closing) return 1;
#endif
    return result == 0 && (probe ? sawSignedOut : ready) && shutdownConfirmed && !failed ? 0 : 1;
}
