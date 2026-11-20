// Development executable, not the production UI or a distributable package.
// It uses compatibility shims and an isolated, signed-out configuration. It
// never accepts a login or enables network-changing controls.
#include "corebridge.h"
#include "homebinder.h"
#include "originalhomebinding.h"
#include "uicloseguard.h"
#include "connectwindow/connectwindowitem.h"
#include "graphicresources/imageresourcessvg.h"

#include <QApplication>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QJsonObject>
#include <QTemporaryDir>
#include <QTimer>

int main(int argc, char **argv) {
    if (argc != 3 || QByteArray(argv[1]) != "--ci-readonly") return 2;
    const QString corePath = QString::fromLocal8Bit(argv[2]);
    QTemporaryDir isolated;
    if (!isolated.isValid()) return 3;
    const QByteArray profile = isolated.path().toUtf8();
    for (const char *key : {"HOME", "XDG_CONFIG_HOME", "APPDATA", "LOCALAPPDATA", "USERPROFILE"})
        qputenv(key, profile);
    int appArgc = 1;
    QApplication app(appArgc, argv);
    Q_INIT_RESOURCE(images);
    Preferences preferences;
    PreferencesHelper helper;
    QGraphicsScene scene;
    auto *home = new ConnectWindow::ConnectWindowItem(nullptr, &preferences, &helper);
    scene.addItem(home);
    scene.setSceneRect(home->boundingRect());
    QGraphicsView view(&scene);
    view.setWindowTitle(QStringLiteral("Gozar — development read-only probe"));
    view.resize(380, 300);
    home->setClickable(false); // no connection, preferences or other actions
    CoreBridge bridge;
    HomeBinder binder(&bridge, nullptr, nullptr);
    bindOriginalHomeWindow(home, &binder);
    UiCloseGuard closeGuard(&view, &bridge);
    bool sawSafeState = false;
    bool confirmedShutdown = false;
    bool failure = false;
    QObject::connect(&binder, &HomeBinder::stateChanged, &view, [&](const QJsonObject &state) {
        if (sawSafeState || failure) return;
        const auto signedIn = state.value(QStringLiteral("signed_in"));
        const auto live = state.value(QStringLiteral("live"));
        const auto connecting = state.value(QStringLiteral("connecting"));
        if (!signedIn.isBool() || !live.isBool() || !connecting.isBool() ||
            signedIn.toBool() || live.toBool() || connecting.toBool()) {
            failure = true;
        } else {
            sawSafeState = true;
        }
        // Render at least one event-loop turn, then request a graceful shutdown.
        QTimer::singleShot(250, &view, [&view] { view.close(); });
    });
    QObject::connect(&bridge, &CoreBridge::shutdownFinished, &view,
                     [&](bool confirmed, const QString &) { confirmedShutdown = confirmed; });
    QObject::connect(&closeGuard, &UiCloseGuard::shutdownUnconfirmed, &app,
                     [&](const QString &) { failure = true; app.exit(4); });
    QObject::connect(&bridge, &CoreBridge::transportError, &app,
                     [&](const QString &) { failure = true; if (bridge.running()) view.close(); else app.exit(5); });
    // The outer CI process has a timeout. No timer force-kills a running core.
    QTimer::singleShot(20000, &view, [&] {
        failure = true;
        if (bridge.running()) view.close(); else app.exit(6);
    });
    view.show();
    if (!bridge.start(corePath)) return 7;
    const int result = app.exec();
    ImageResourcesSvg::instance().finishGracefully();
    return result == 0 && sawSafeState && confirmedShutdown && !failure ? 0 : 1;
}
