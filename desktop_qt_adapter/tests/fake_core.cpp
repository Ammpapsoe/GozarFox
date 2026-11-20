#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QFile>
#include <QDateTime>
#include <QThread>
#include <QDir>
#include <QTemporaryDir>
#include <cstdio>

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    if (app.arguments().size() != 2) return 2;
    if (app.arguments()[1] == QStringLiteral("--handoff-stdio-v1")) {
        // Test-only process fixture for the Dart launcher's bounded protocol.
        // It is not a Qt UI and must never be packaged as one.
        const QByteArray mode = qgetenv("FAKE_UI_MODE");
        if (mode == "exit") return 0;
        if (mode == "invalid") std::fputs("NOT_READY\n", stdout);
        else if (mode == "oversize") std::fputs(QByteArray(140, 'x').constData(), stdout);
        else if (mode == "ready") std::fputs("GOZAR_QT_READY_V1\n", stdout);
        else return 2;
        std::fflush(stdout);
        QThread::msleep(1200);
        return 0;
    }
    if (app.arguments()[1] != QStringLiteral("--rpc")) return 2;
    char line[65536];
    QString countryCode;
    QString activeId = QStringLiteral("work");
    bool signedIn = true;
    QString mode = QStringLiteral("tun");
    QString language;
    QString theme = QStringLiteral("system");
    bool recents = false;
    bool warpUdp = false;
    QJsonArray chain;
    bool killSwitch = false;
    bool live = false;
    int serverRequests = 0;
    int updateChecks = 0;
    QJsonValue pendingCheck;
    QTemporaryDir updateDir;
    const auto emitResponse = [](const QJsonObject &response) {
        const auto bytes = QJsonDocument(response).toJson(QJsonDocument::Compact) + '\n';
        std::fwrite(bytes.constData(), 1, size_t(bytes.size()), stdout);
        std::fflush(stdout);
    };
    const bool failLaterServers = qEnvironmentVariableIsSet("FAKE_CORE_SERVERS_FAIL_AFTER_FIRST");
    const bool quitError = qEnvironmentVariableIsSet("FAKE_CORE_QUIT_ERROR");
    while (std::fgets(line, sizeof(line), stdin)) {
        const auto request = QJsonDocument::fromJson(line).object();
        const auto method = request.value("method").toString();
        if (method == "crash") return 7;
        if (qEnvironmentVariableIsSet("GOZAR_FAKE_LAYOUT_CATALOG")) {
            const auto id = request.value("id");
            if (method == "check_update") {
                if (++updateChecks == 2) { pendingCheck = id; continue; }
                emitResponse({{"id", id}, {"reply", QJsonObject{{"version", "1.20.0-demo"}, {"newer", true},
                    {"artifacts", QJsonArray{QJsonObject{{"id", "fixture-package"}, {"name", "Gozar-desktop-demo.zip"},
                                                       {"kind", "zip"}, {"size", 15728640}}}}}}});
                continue;
            }
            if (method == "download_update") {
                const auto path = QDir(updateDir.path()).filePath(QStringLiteral("Gozar-desktop-demo.zip"));
                QFile file(path);
                if (request.value("params").toObject().value("id").toString() != QLatin1String("fixture-package") || !file.open(QIODevice::WriteOnly))
                    emitResponse({{"id", id}, {"error", "signed update operation failed"}});
                else {
                    file.write("Fixture only. Not an installation archive.");
                    file.close();
                    emitResponse({{"id", id}, {"reply", QJsonObject{{"downloaded_path", path}}}});
                }
                continue;
            }
            if (method == "cancel_update") {
                if (pendingCheck.isDouble()) {
                    // The original operation can complete before cancel's ACK.
                    emitResponse({{"id", pendingCheck}, {"error", "update operation cancelled"}});
                    pendingCheck = {};
                }
                emitResponse({{"id", id}});
                continue;
            }
        }
        if (method == "invalid") { std::fputs("not-json\n", stdout); std::fflush(stdout); continue; }
        const QJsonObject params = request.value("params").toObject();
        if (method == "switch_account") { activeId = params.value("id").toString(); live = false; }
        if (method == "logout") { signedIn = false; live = false; activeId.clear(); }
        if (method == "server") countryCode = params.value("code").toString();
        if (method == "connect") live = true;
        if (method == "disconnect" || method == "quit") live = false;
        if (method == "mode") mode = params.value("mode").toString();
        if (method == "kill_switch") killSwitch = params.value("enabled").toBool();
        if (method == "language") language = params.value("language").toString();
        if (method == "theme") theme = params.value("theme").toString();
        if (method == "recents") recents = params.value("enabled").toBool();
        QString error;
        if (method == "quit" && quitError) error = QStringLiteral("cleanup failed");
        if (method == "warp_udp") {
            if (params.value("enabled").toBool() && !params.value("consent").toBool())
                error = QStringLiteral("Cloudflare WARP terms must be accepted");
            else warpUdp = params.value("enabled").toBool();
        }
        if (method == "chain") chain = params.value("hops").toArray();
        QJsonObject state{{"signed_in", signedIn}, {"live", live}, {"connecting", false}, {"mode", mode},
                                {"exit", live ? QStringLiteral("203.0.113.10") : QString()},
                                {"kill_switch", killSwitch}, {"language", language}, {"theme", theme},
                                {"recents", recents}, {"warp_udp", warpUdp}, {"chain", chain},
                                {"server", QJsonObject{{"code", countryCode}}}};
        if (qEnvironmentVariableIsSet("GOZAR_FAKE_LAYOUT_CATALOG")) {
            const auto email = activeId == QLatin1String("work") ? QStringLiteral("work@example.test") :
                activeId == QLatin1String("personal") ? QStringLiteral("personal@example.test") : QString();
            state.insert("email", email);
            state.insert("version", "1.19.0");
            if (live) {
                state.insert("device", "gozar-demo0");
                state.insert("down", 14680064);
                state.insert("up", 3145728);
            }
            state.insert("limit", 5368709120LL);
            state.insert("remaining", 2684354560LL);
            state.insert("accounts", QJsonArray{QJsonObject{{"id", "work"}, {"email", "work@example.test"}, {"active", activeId == QLatin1String("work")}},
                                                 QJsonObject{{"id", "personal"}, {"email", "personal@example.test"}, {"active", activeId == QLatin1String("personal")}}});
            if (!signedIn) state.insert("accounts", QJsonArray{QJsonObject{{"id", "work"}, {"email", "work@example.test"}, {"active", false}}});
            auto place = state.value("server").toObject();
            if (countryCode == QLatin1String("GB")) {
                place.insert("country", "United Kingdom");
                place.insert("city", "London");
                place.insert("port", 2499);
            }
            state.insert("server", place);
            state.insert("rate_down", live ? 1572864 : 0);
            state.insert("rate_up", live ? 262144 : 0);
            state.insert("since", live ? QDateTime::currentSecsSinceEpoch() - 154 : 0);
        }
        if (qEnvironmentVariableIsSet("FAKE_CORE_OMIT_LIVE")) state.remove(QStringLiteral("live"));
        QJsonValue payload = request.value("params");
        if (method == "servers") {
            ++serverRequests;
            if (failLaterServers && serverRequests > 1) error = QStringLiteral("server refresh failed");
            else if (qEnvironmentVariableIsSet("GOZAR_FAKE_LAYOUT_CATALOG")) {
                payload = QJsonArray{
                    QJsonObject{{"code", "REC"}, {"country", "Recommended"}, {"city", "Recommended"}, {"host", "shared.example"}, {"port", 2499}, {"recommended", true}},
                    QJsonObject{{"code", "US"}, {"country", "CatchAll Anycast"}, {"city", "USA"}, {"host", "shared.example"}, {"port", 2499}},
                    QJsonObject{{"code", "DE"}, {"country", "Germany"}, {"city", "Berlin"}, {"host", "de.example"}, {"port", 2499}},
                    QJsonObject{{"code", "NL"}, {"country", "Netherlands"}, {"city", "Netherlands"}, {"host", "nl.example"}, {"port", 2499}},
                    QJsonObject{{"code", "GB"}, {"country", "United Kingdom"}, {"city", "United Kingdom"}, {"host", "gb.example"}, {"port", 2499}}};
            } else payload = QJsonArray{QJsonObject{{"code", "DE"}, {"host", "test.example"}}};
        }
        QJsonObject response{{"id", request.value("id")}, {"state", state}, {"reply", payload}};
        if (!error.isEmpty()) response.insert("error", error);
        emitResponse(response);
        if (method == "quit") {
            const int delay = qEnvironmentVariableIntValue("FAKE_CORE_QUIT_DELAY_MS");
            if (delay > 0 && delay <= 5000) QThread::msleep(static_cast<unsigned long>(delay));
            const QString markerPath = qEnvironmentVariable("FAKE_CORE_QUIT_MARKER");
            if (!markerPath.isEmpty()) {
                QFile marker(markerPath);
                if (!marker.open(QIODevice::WriteOnly)) return 4;
                marker.write("quit received");
            }
            return 0;
        }
    }
    return 0;
}
