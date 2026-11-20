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
#include "gozartray.h"
#include "gozartelemetry.h"
#include "languagecontroller.h"
#include "dpiscalemanager.h"
#include "graphicresources/fontmanager.h"
#include "originalpowerbinding.h"
#include "uicloseguard.h"
#include "uilanguage.h"
#include "uireadiness.h"
#include "connectwindow/connectwindowitem.h"
#include "graphicresources/imageresourcessvg.h"
#include "locations/locationsmodel_manager.h"
#include "locations/locationsmodel_roles.h"
#include "locationswindow/locationswindow.h"
#include "locations/view/locationsview.h"

#include <QApplication>
#include <QDir>
#include <QDialog>
#include <QProcess>
#include <QFileInfo>
#include <QPushButton>
#include <QMessageBox>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QGraphicsProxyWidget>
#include <QLineEdit>
#include <QListWidget>
#include <QClipboard>
#include <QLabel>
#include <QMouseEvent>
#include <QPainterPath>
#include <QRegion>
#include <QScreen>
#include <QShortcut>
#include <QWindow>
#include <QtMath>
#include <QSettings>
#include <QTemporaryDir>
#include <QTimer>
#include <QVBoxLayout>
#include <QVariantAnimation>
#include <cstdio>
#ifdef GOZAR_HANDOFF_CI_FIXTURE
#include <QElapsedTimer>
#include <QTest>
#include "preferenceswindow/toggleitem.h"
#endif

namespace {
// Match the 9 logical px Van Gogh radius used by Windscribe’s preferences.
qreal panelRadius(int height) { return qMin(9.0 * G_SCALE, height / 2.0); }

class PanelOutline final : public QWidget {
public:
    explicit PanelOutline(QWidget *parent) : QWidget(parent) {
        setAttribute(Qt::WA_TransparentForMouseEvents);
        setAttribute(Qt::WA_NoSystemBackground);
    }
protected:
    void paintEvent(QPaintEvent *) override {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setPen(QColor(255, 255, 255, 29));
        painter.setBrush(Qt::NoBrush);
        const auto radius = panelRadius(height());
        painter.drawRoundedRect(QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5), radius, radius);
    }
};

void matchPanelFrame(QWidget &panel, PanelOutline &outline) {
    if (panel.width() <= 0 || panel.height() <= 0) return;
    const qreal radius = panelRadius(panel.height());
    QPainterPath shape;
    shape.addRoundedRect(QRectF(panel.rect()), radius, radius);
    const QRegion mask(shape.toFillPolygon().toPolygon());
    if (panel.mask() != mask) panel.setMask(mask);
    if (outline.geometry() != panel.rect()) outline.setGeometry(panel.rect());
    outline.raise();
    outline.show();
}

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
    QSettings::setDefaultFormat(QSettings::IniFormat);
    if (isolate) QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, isolated.path());
    app.setOrganizationName(QStringLiteral("Gozar"));
    app.setApplicationName(QStringLiteral("OriginalQtExperimental"));
    Q_INIT_RESOURCE(images);
    FontManager::instance();
    app.setWindowIcon(QIcon(QStringLiteral(":/gozar/app.png")));
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
    homeView.setRenderHints(QPainter::Antialiasing | QPainter::TextAntialiasing | QPainter::SmoothPixmapTransform);
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
    QWidget locationsClip;
    gui_locations::LocationsModelManager manager;
    LocationsWindow locations(nullptr, &preferences, &manager);
    locations.useHostViewport();
    for (auto *view : locations.findChildren<gui_locations::LocationsView *>()) view->setGozarPresentation();
    // Reveal the original window as one panel, rather than resizing its rows on
    // each frame. The child keeps its full viewport and footer geometry.
    locationsClip.setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    locations.setParent(&locationsClip);
    layout.addWidget(&locationsClip);
    locationsClip.hide();
    bool locationsExpanded = false;
    qreal locationsProgress = 0;
    QVariantAnimation locationsMotion;
    constexpr int locationsMotionMs = 250; // Windscribe EXPAND_ANIMATION_DURATION

    GozarPreferences settingsView(&binder, &language, &helper, probe);
    PanelOutline locationsOutline(&locationsClip);
    PanelOutline settingsOutline(&settingsView);
    enum class FlutterReturn { None, Login, AddAccount, SwitchedAccount };
    FlutterReturn flutterReturn = FlutterReturn::None;
#ifdef Q_OS_WIN
    const auto flutterPath = QDir(app.applicationDirPath()).filePath(QStringLiteral("gozar.exe"));
#elif defined(Q_OS_MACOS)
    const auto flutterPath = QDir(app.applicationDirPath()).filePath(QStringLiteral("Gozar"));
#else
    const auto flutterPath = QDir(app.applicationDirPath()).filePath(QStringLiteral("gozar"));
#endif
#ifndef GOZAR_HANDOFF_CI_FIXTURE
    const auto returnToFlutter = [&](FlutterReturn kind) {
        if (!QFileInfo(flutterPath).isFile()) {
            QMessageBox::warning(&shell, QStringLiteral("Gozar"), QStringLiteral("The bundled Flutter sign-in program could not be found."));
            return;
        }
        if (flutterReturn != FlutterReturn::None) return;
        flutterReturn = kind;
        shell.close(); // waits for acknowledged cleanup AND exit via UiCloseGuard
    };
    QObject::connect(&settingsView, &GozarPreferences::addAccountRequested, &shell,
        [&] { returnToFlutter(FlutterReturn::AddAccount); });
    QObject::connect(&binder, &HomeBinder::signedOutConfirmed, &shell,
        [&] { returnToFlutter(FlutterReturn::Login); });
#ifdef Q_OS_WIN
    QObject::connect(&binder, &HomeBinder::accountSwitchConfirmed, &shell,
        [&] { returnToFlutter(FlutterReturn::SwitchedAccount); }, Qt::QueuedConnection);
#endif
#endif
    QObject::connect(&settingsView, &GozarPreferences::quitRequested, &shell, &QWidget::close);
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
        const int visibleLocationsHeight = qRound(locations.height() * locationsProgress);
        locationsClip.setFixedSize(width, visibleLocationsHeight);
        locations.move(0, visibleLocationsHeight - locations.height());
        if (visibleLocationsHeight > 0) matchPanelFrame(locationsClip, locationsOutline);
        if (!settingsView.isHidden()) {
            settingsView.fitPanel(width, room);
            matchPanelFrame(settingsView, settingsOutline);
        }
        layout.invalidate();
        shell.setFixedSize(width, layout.sizeHint().height());
        if (screen && shell.isVisible() && !shell.isMinimized()) {
            const QRect area = screen->availableGeometry();
            shell.move(qBound(area.left(), shell.x(), qMax(area.left(), area.right() - width + 1)),
                       qBound(area.top(), shell.y(), qMax(area.top(), area.bottom() - shell.height() + 1)));
        }
    };
    QObject::connect(&settingsView, &GozarPreferences::pageChanged, &shell, fitWindow);
    QString commandNotice, stateNotice;
    const auto showStatus = [&] {
        const auto message = commandNotice.isEmpty() ? stateNotice : commandNotice;
        if (status.text() == message && status.isHidden() == message.isEmpty()) return;
        status.setText(message);
        status.setVisible(!message.isEmpty());
        fitWindow();
    };
    const auto setStatus = [&](const QString &message) { commandNotice = message; showStatus(); };
    QObject::connect(&binder, &HomeBinder::stateChanged, &shell, [&](const QJsonObject &state) {
        const auto fault = state.value(QStringLiteral("fault")).toString();
        const bool admin = state.value(QStringLiteral("needs_admin")).toBool();
        const bool fa = LanguageController::instance().getLanguage() == QLatin1String("fa");
        stateNotice = !fault.isEmpty() ? fault : admin ? QString::fromUtf8(fa
            ? "این حالت اتصال به دسترسی مدیر سیستم نیاز دارد." : "This connection mode requires administrator privileges.") : QString();
        showStatus();
    });
    QObject::connect(&locations, &LocationsWindow::heightChanged, &shell, fitWindow);
    auto &dpi = DpiScaleManager::instance();
    const auto rescale = [&] {
        ImageResourcesSvg::instance().invalidateDpiCache();
        home->updateScaling();
        locations.updateScaling();
        fitWindow();
    };
    QObject::connect(&dpi, &DpiScaleManager::scaleChanged, &shell, rescale);
    QObject::connect(&dpi, &DpiScaleManager::newScreen, &shell, [&](QScreen *) { fitWindow(); });

    // Connectivity is not independently monitored by this candidate. This
    // only permits operation of the original widget; it is not a network test.
    home->setInternetConnectivity(true);
    home->setClickable(false);
    if (!probe) home->setGozarNavigationClickable(true);
    bindOriginalHomeWindow(home, &binder);
    CONNECT_STATE visualState = CONNECT_STATE_DISCONNECTED;
    const auto refreshTelemetry = [&] {
        const auto stats = gozarTelemetry(binder.currentState(), visualState, LanguageController::instance().getLanguage() == QLatin1String("fa"));
        home->setGozarTelemetry(stats.status, stats.rates, stats.details);
    };
    QObject::connect(&binder, &HomeBinder::visualStateChanged, &shell, [&](CONNECT_STATE state) {
        visualState = state;
        refreshTelemetry();
    });
    QObject::connect(&binder, &HomeBinder::stateChanged, &shell, refreshTelemetry);
    QObject::connect(&binder, &HomeBinder::availabilityChanged, &shell, refreshTelemetry);
    QObject::connect(&LanguageController::instance(), &LanguageController::languageChanged, &shell, refreshTelemetry);
    QTimer clock;
    clock.setInterval(1000);
    QObject::connect(&clock, &QTimer::timeout, &shell, refreshTelemetry);
    clock.start();
    refreshTelemetry();
    if ((!probe && !bindOriginalPowerControl(home, &binder)) ||
        !bindOriginalLocationControls(home, &locations, &binder, true)) return 4;
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::minimizeClick, &shell, &QWidget::showMinimized);
    QShortcut escape(QKeySequence(Qt::Key_Escape), &shell);
    escape.setEnabled(false);
    const auto renderLocations = [&](qreal progress) {
        locationsProgress = progress;
        locationsClip.setVisible(locationsExpanded || progress > 0);
        fitWindow();
    };
    QObject::connect(&locationsMotion, &QVariantAnimation::valueChanged, &shell,
        [&](const QVariant &value) { renderLocations(value.toReal()); });
    const auto setLocations = [&](bool expand, bool animated) {
        locationsMotion.stop();
        locationsExpanded = expand;
        home->updateLocationsState(expand);
        locations.setEnabled(expand);
        if (expand) binder.loadServers();
        const qreal target = expand ? 1.0 : 0.0;
        if (!animated || qFuzzyCompare(locationsProgress, target)) {
            renderLocations(target);
            return;
        }
        renderLocations(locationsProgress);
        locationsMotion.setDuration(qMax(1, qRound(locationsMotionMs * qAbs(target - locationsProgress))));
        locationsMotion.setStartValue(locationsProgress);
        locationsMotion.setEndValue(target);
        locationsMotion.start();
    };
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::preferencesClick, &shell, [&] {
        const bool open = settingsView.isHidden();
        setLocations(false, false);
        settingsView.setVisible(open);
        escape.setEnabled(open);
        fitWindow();
    });
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationsClick, &shell, [&] {
        settingsView.hide();
        escape.setEnabled(false);
        setLocations(!locationsExpanded, true);
    });
    QObject::connect(&escape, &QShortcut::activated, &shell, [&] {
        // Keep Escape available to the original locations search widget.
        if (!settingsView.isHidden()) { settingsView.hide(); escape.setEnabled(false); fitWindow(); }
    });
    QObject::connect(&binder, &HomeBinder::serversReady, &shell, [&] { setStatus({}); });
    bindOriginalLocations(&locations, &manager, &binder, &catalog);
    GozarTray tray(&binder, &catalog, &manager);
    QObject::connect(home, &ConnectWindow::ConnectWindowItem::closeClick, &shell, [&] {
        const bool fa = LanguageController::instance().getLanguage() == QLatin1String("fa");
        const auto text = [fa](const char *en, const char *persian) { return QString::fromUtf8(fa ? persian : en); };
        QMessageBox choice(QMessageBox::Question, text("Close Gozar", "بستن گذر"),
            text("Hide the window and keep Gozar running, or quit and disconnect?",
                 "پنجره پنهان شود و گذر فعال بماند، یا اتصال قطع و برنامه بسته شود؟"),
            QMessageBox::Cancel, &shell);
        const auto hideLabel = tray.isVisible() ? text("Hide to tray", "پنهان کردن در سینی سیستم") : text("Minimize", "کوچک کردن پنجره");
        auto *hide = choice.addButton(hideLabel, QMessageBox::ActionRole);
        auto *quit = choice.addButton(text("Quit Gozar", "خروج از گذر"), QMessageBox::DestructiveRole);
        choice.setDefaultButton(QMessageBox::Cancel);
        choice.exec();
        if (choice.clickedButton() == hide) {
            if (tray.isVisible()) shell.hide();
            else shell.showMinimized();
        } else if (choice.clickedButton() == quit) shell.close();
    });
    const auto showMainWindow = [&] {
        if (shell.isMinimized() || shell.isHidden()) shell.showNormal();
        shell.raise();
        shell.activateWindow();
    };
    QObject::connect(&tray, &GozarTray::showRequested, &shell, showMainWindow);
    QObject::connect(&tray, &GozarTray::preferencesRequested, &shell, [&] {
        showMainWindow();
        if (settingsView.isHidden()) emit home->preferencesClick();
    });
    QObject::connect(&tray, &GozarTray::quitRequested, &shell, &QWidget::close);

    UiCloseGuard closeGuard(&shell, &bridge);
    UiReadiness readiness(&shell, &binder);
    QObject::connect(&readiness, &UiReadiness::readyForHandoff, &binder, &HomeBinder::loadServers);
#ifndef GOZAR_HANDOFF_CI_FIXTURE
    QObject::connect(&readiness, &UiReadiness::readyForHandoff, &tray, &GozarTray::attachIfAvailable);
#endif
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
                    const auto connection = settingsView.mapFromScene(QPointF(32 * G_SCALE, 136 * G_SCALE));
                    QTest::mouseClick(settingsView.viewport(), Qt::LeftButton, Qt::NoModifier, connection);
                    if (settingsView.property("gozarPage").toString() != QStringLiteral("connection")) return false;
                    if (!screenshots.isEmpty()) {
                        QTest::qWait(300); // capture the final state of the original tab animation
                        shell.grab().save(QDir(screenshots).filePath("connection-settings.png"));
                    }
                    // Fail instead of silently accepting rounded DPR or square corners.
                    const auto icon = ImageResourcesSvg::instance().getIndependentPixmap("locations/FAV_ICON_DESELECTED");
                    if (!icon || !qFuzzyCompare(dpi.curDevicePixelRatio(), shell.devicePixelRatioF()) ||
                        qAbs(icon->originalPixmapSize().width() - 16 * G_SCALE * shell.devicePixelRatioF()) > 1 ||
                        settingsView.mask().isEmpty() || settingsView.mask().contains(QPoint(0, 0))) return false;
                    const auto settle = [&] {
                        QElapsedTimer pending;
                        pending.start();
                        while (!binder.isAvailable() && pending.elapsed() < 3000) QTest::qWait(20);
                        return binder.isAvailable();
                    };
                    // Proxy modes reject Connect while either persisted TUN option
                    // is on. Validate they can be cleared without leaving proxy mode.
                    binder.setKillSwitch(true);
                    if (!settle()) return false;
                    binder.setWarpUdp(true, true);
                    if (!settle()) return false;
                    binder.setMode(QStringLiteral("proxy-only"));
                    if (!settle() || binder.currentState().value("mode").toString() != QLatin1String("proxy-only")) return false;
                    for (const auto *name : {"kill_switch", "warp_udp"}) {
                        PreferencesWindow::ToggleItem *toggle = nullptr;
                        for (auto *item : settingsView.scene()->items()) {
                            auto *candidate = qobject_cast<PreferencesWindow::ToggleItem *>(item->toGraphicsObject());
                            if (candidate && candidate->objectName() == QLatin1String(name)) { toggle = candidate; break; }
                        }
                        if (!toggle || !toggle->isEnabled() || !toggle->isChecked()) return false;
                        emit toggle->stateChanged(false);
                        if (!settle() || binder.currentState().value(QLatin1String(name)).toBool() || toggle->isEnabled()) return false;
                    }
                    binder.setMode(QStringLiteral("tun"));
                    if (!settle()) return false;
                    QTest::mouseClick(settingsView.viewport(), Qt::LeftButton, Qt::NoModifier,
                                      settingsView.mapFromScene(QPointF(32 * G_SCALE, 88 * G_SCALE)));
                    if (settingsView.property("gozarPage").toString() != QStringLiteral("account")) return false;
                    if (!screenshots.isEmpty()) {
                        QTest::qWait(300);
                        shell.grab().save(QDir(screenshots).filePath("account.png"));
                    }
                    bool accountSwitchConfirmed = false;
                    const auto switchObserved = QObject::connect(&binder, &HomeBinder::accountSwitchConfirmed, &shell,
                                      [&] { accountSwitchConfirmed = true; });
                    bool sawAccountPicker = false;
                    auto dialogReady = QObject::connect(&settingsView, &GozarPreferences::accountDialogOpened, &shell, [&](QDialog *dialog) {
                        const auto *active = dialog->findChild<QPushButton *>(QStringLiteral("activeAccount"));
                        auto *choice = dialog->findChild<QPushButton *>(QStringLiteral("savedAccount"));
                        sawAccountPicker = active && !active->isEnabled() && choice && choice->isEnabled();
                        if (!screenshots.isEmpty()) dialog->grab().save(QDir(screenshots).filePath("account-switcher.png"));
                        if (sawAccountPicker) choice->click();
                        else dialog->reject();
                    });
                    QTest::mouseClick(settingsView.viewport(), Qt::LeftButton, Qt::NoModifier,
                                      settingsView.mapFromScene(QPointF(262 * G_SCALE, 108 * G_SCALE)));
                    QObject::disconnect(dialogReady);
                    if (!sawAccountPicker) return false;
                    QElapsedTimer accountWait;
                    accountWait.start();
                    while (binder.currentState().value("email").toString() != QLatin1String("personal@example.test") && accountWait.elapsed() < 3000) QTest::qWait(20);
                    QObject::disconnect(switchObserved);
                    if (!accountSwitchConfirmed || binder.currentState().value("email").toString() != QLatin1String("personal@example.test")) return false;
                    emit home->locationsClick();
                    if (!locations.isVisible() || locationsClip.height() == locations.height()) return false;
                    QTest::qWait(locationsMotionMs + 70);
                    const auto *locationsTab = locations.findChild<GuiLocations::LocationsTab *>();
                    if (!locations.isVisible() || settingsView.isVisible() || locationsClip.y() != homeView.height() || locations.y() != 0 ||
                        !locationsTab || locationsTab->y() != 0 || locationsTab->height() >= locations.height()) return false;
                    QElapsedTimer wait;
                    wait.start();
                    while (catalog.locations().isEmpty() && wait.elapsed() < 3000) QTest::qWait(20);
                    if (catalog.locations().size() != 4) return false;
                    const auto id = catalog.locations().at(1).cities.at(0).id;
                    const auto index = manager.getIndexByLocationId(id);
                    if (!index.isValid() || !manager.locationsModel()->setData(index, true, gui_locations::kIsFavorite) ||
                        manager.favoriteCitiesProxyModel()->rowCount() != 1) return false;
                    tray.rebuild();
                    auto *trayLocations = tray.menu()->actions().at(1)->menu();
                    if (!trayLocations || !trayLocations->isEnabled() || trayLocations->actions().size() != 4) return false;
                    if (!screenshots.isEmpty()) {
                        tray.menu()->ensurePolished();
                        tray.menu()->resize(tray.menu()->sizeHint());
                        tray.menu()->grab().save(QDir(screenshots).filePath("tray-menu.png"));
                    }
                    if (!screenshots.isEmpty()) shell.grab().save(QDir(screenshots).filePath("server-list.png"));
                    const auto search = homeView.mapFromScene(QPointF(215 * G_SCALE, 211 * G_SCALE));
                    QTest::mouseClick(homeView.viewport(), Qt::LeftButton, Qt::NoModifier, search);
                    QTest::qWait(310);
                    // The QGraphicsProxyWidget owns the editor; use the active scene focus item.
                    auto *searchProxy = qgraphicsitem_cast<QGraphicsProxyWidget *>(homeScene.focusItem());
                    auto *searchEditor = searchProxy ? qobject_cast<QLineEdit *>(searchProxy->widget()) : nullptr;
                    if (!searchEditor || !searchEditor->isVisible()) return false;
                    QTest::keyClicks(searchEditor, "Berlin");
                    QTest::qWait(200);
                    if (searchEditor->text() != QLatin1String("Berlin") || manager.filterLocationsProxyModel()->rowCount() != 1) return false;
                    if (!screenshots.isEmpty()) shell.grab().save(QDir(screenshots).filePath("search.png"));
                    QTest::mouseClick(homeView.viewport(), Qt::LeftButton, Qt::NoModifier,
                                      homeView.mapFromScene(QPointF(285 * G_SCALE, 211 * G_SCALE)));
                    QTest::qWait(310);
                    bool favouritesOpened = false;
                    const auto tabSelected = QObject::connect(home, &ConnectWindow::ConnectWindowItem::locationTabClicked, &shell,
                        [&](LOCATION_TAB tab) { favouritesOpened = tab == LOCATION_TAB_FAVORITE_LOCATIONS; });
                    QTest::mouseClick(homeView.viewport(), Qt::LeftButton, Qt::NoModifier,
                                      homeView.mapFromScene(QPointF(180 * G_SCALE, 211 * G_SCALE)));
                    QObject::disconnect(tabSelected);
                    if (!favouritesOpened) return false;
                    if (!screenshots.isEmpty()) {
                        QTest::qWait(300);
                        shell.grab().save(QDir(screenshots).filePath("favourites.png"));
                    }
                    emit home->locationsClick();
                    QTest::qWait(locationsMotionMs + 70);
                    if (locations.isVisible() || shell.size() != homeView.size()) return false;
                    binder.selectServer(QStringLiteral("gb.example"), QStringLiteral("GB"));
                    wait.restart();
                    while ((!binder.isAvailable() || binder.currentState().value("server").toObject().value("code").toString() != QLatin1String("GB")) && wait.elapsed() < 3000) QTest::qWait(20);
                    if (!binder.isAvailable()) return false;
                    binder.toggleConnection();
                    wait.restart();
                    while (!binder.currentState().value("live").toBool() && wait.elapsed() < 3000) QTest::qWait(20);
                    const auto stats = gozarTelemetry(binder.currentState(), visualState, false);
                    if (!stats.rates.contains(QStringLiteral("1.5 MiB/s")) || !stats.details.contains(QStringLiteral("203.0.113.10"))) return false;
                    if (!screenshots.isEmpty()) {
                        QTest::qWait(450);
                        shell.grab().save(QDir(screenshots).filePath("connected.png"));
                    }
                    // Keep the mock connection live through all update-only RPCs.
                    emit home->preferencesClick();
                    const auto clickRow = [&](const QString &name) {
                        for (auto *item : settingsView.scene()->items()) {
                            const auto *object = item->toGraphicsObject();
                            if (!object || object->objectName() != name || !item->isVisible()) continue;
                            settingsView.ensureVisible(item, 0, 0);
                            QTest::mouseClick(settingsView.viewport(), Qt::LeftButton, Qt::NoModifier,
                                settingsView.mapFromScene(item->sceneBoundingRect().center()));
                            return true;
                        }
                        return false;
                    };
                    const auto tabAt = [&](qreal y) {
                        QTest::mouseClick(settingsView.viewport(), Qt::LeftButton, Qt::NoModifier,
                            settingsView.mapFromScene(QPointF(32 * G_SCALE, y * G_SCALE)));
                    };
                    bool sawSupport = false, sawSession = false, chosePackage = false, copiedPackage = false;
                    bool cancelNext = false, requestedCancel = false;
                    const auto detailsReady = QObject::connect(&settingsView, &GozarPreferences::detailDialogOpened, &shell, [&](QDialog *dialog) {
                        const auto name = dialog->objectName();
                        if (name == QLatin1String("updateProgress")) {
                            if (cancelNext) {
                                if (auto *cancel = dialog->findChild<QPushButton *>(QStringLiteral("cancelUpdate"))) {
                                    requestedCancel = true;
                                    cancel->click();
                                }
                            }
                            return;
                        }
                        const auto capture = [&](const QString &file) {
                            if (!screenshots.isEmpty()) dialog->grab().save(QDir(screenshots).filePath(file));
                        };
                        if (name == QLatin1String("sessionDetails")) {
                            for (const auto *label : dialog->findChildren<QLabel *>()) {
                                if (label->text().contains(QStringLiteral("gozar-demo0"))) sawSession = true;
                            }
                            capture(QStringLiteral("session.png"));
                        } else if (name == QLatin1String("supportGozar")) {
                            if (auto *copy = dialog->findChild<QPushButton *>(QStringLiteral("copyDonation"))) {
                                capture(QStringLiteral("support.png"));
                                copy->click();
                                sawSupport = QApplication::clipboard()->text().endsWith(QLatin1String("C2eCL3"));
                            }
                        } else if (name == QLatin1String("updatePackages")) {
                            auto *list = dialog->findChild<QListWidget *>();
                            auto *download = dialog->findChild<QPushButton *>(QStringLiteral("downloadUpdate"));
                            if (list && list->count() == 1 && download && !download->isEnabled()) {
                                list->setCurrentRow(0);
                                capture(QStringLiteral("updates.png"));
                                chosePackage = true;
                                download->click();
                                return;
                            }
                        } else if (name == QLatin1String("updateDownloaded")) {
                            if (auto *copy = dialog->findChild<QPushButton *>(QStringLiteral("copyUpdatePath"))) {
                                copy->click();
                                copiedPackage = QApplication::clipboard()->text().endsWith(QLatin1String("Gozar-desktop-demo.zip"));
                            }
                        }
                        dialog->reject();
                    });
                    tabAt(136);
                    if (!clickRow(QStringLiteral("sessionRow")) || !sawSession) return false;
                    settingsView.ensureVisible(QRectF(0, 0, 48 * G_SCALE, 280 * G_SCALE), 0, 0);
                    tabAt(232);
                    if (settingsView.property("gozarPage").toString() != QStringLiteral("about")) return false;
                    if (!screenshots.isEmpty()) {
                        QTest::qWait(300);
                        shell.grab().save(QDir(screenshots).filePath("about.png"));
                    }
                    if (!clickRow(QStringLiteral("supportRow")) || !sawSupport) return false;
                    if (!clickRow(QStringLiteral("updatesRow")) || !chosePackage || !copiedPackage) return false;
                    cancelNext = true;
                    if (!clickRow(QStringLiteral("updatesRow")) || !requestedCancel || !binder.currentState().value("live").toBool()) return false;
                    QObject::disconnect(detailsReady);
                    bool signedOut = false;
                    const auto observed = QObject::connect(&binder, &HomeBinder::signedOutConfirmed, &shell, [&] { signedOut = true; });
                    binder.signOut();
                    wait.restart();
                    while (!signedOut && wait.elapsed() < 3000) QTest::qWait(20);
                    QObject::disconnect(observed);
                    const auto finalState = binder.currentState();
                    const auto saved = finalState.value("accounts").toArray();
                    return signedOut && !finalState.value("live").toBool() && saved.size() == 1 &&
                        saved.first().toObject().value("id").toString() == QLatin1String("work");
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
    if (result != 0 || !(probe ? sawSignedOut : ready) || !shutdownConfirmed || failed) return 1;
#ifndef GOZAR_HANDOFF_CI_FIXTURE
    if (flutterReturn != FlutterReturn::None) {
        QProcess flutter;
        auto env = QProcessEnvironment::systemEnvironment();
        env.remove(QStringLiteral("GOZAR_QT_OPEN_ADD_ACCOUNT"));
        if (flutterReturn == FlutterReturn::AddAccount) env.insert(QStringLiteral("GOZAR_QT_OPEN_ADD_ACCOUNT"), QStringLiteral("1"));
        flutter.setProcessEnvironment(env);
        flutter.setProgram(flutterPath);
        flutter.setWorkingDirectory(app.applicationDirPath());
        if (!flutter.startDetached()) {
            QMessageBox::warning(nullptr, QStringLiteral("Gozar"), QStringLiteral("The connection is closed, but sign-in could not be reopened. Start Gozar again."));
            return 1;
        }
    }
#endif
    return 0;
}
