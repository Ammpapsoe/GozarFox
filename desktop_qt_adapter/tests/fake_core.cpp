#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QFile>
#include <QThread>
#include <cstdio>

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    if (app.arguments().size() != 2 || app.arguments()[1] != QStringLiteral("--rpc")) return 2;
    char line[65536];
    QString countryCode;
    QString mode = QStringLiteral("tun");
    QString language;
    QString theme = QStringLiteral("system");
    bool recents = false;
    bool warpUdp = false;
    QJsonArray chain;
    bool killSwitch = false;
    bool live = false;
    const bool quitError = qEnvironmentVariableIsSet("FAKE_CORE_QUIT_ERROR");
    while (std::fgets(line, sizeof(line), stdin)) {
        const auto request = QJsonDocument::fromJson(line).object();
        const auto method = request.value("method").toString();
        if (method == "crash") return 7;
        if (method == "invalid") { std::fputs("not-json\n", stdout); std::fflush(stdout); continue; }
        const QJsonObject params = request.value("params").toObject();
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
        QJsonObject state{{"signed_in", true}, {"live", live}, {"connecting", false}, {"mode", mode},
                                {"kill_switch", killSwitch}, {"language", language}, {"theme", theme},
                                {"recents", recents}, {"warp_udp", warpUdp}, {"chain", chain},
                                {"server", QJsonObject{{"code", countryCode}}}};
        if (qEnvironmentVariableIsSet("FAKE_CORE_OMIT_LIVE")) state.remove(QStringLiteral("live"));
        QJsonValue payload = request.value("params");
        if (method == "servers") payload = QJsonArray{QJsonObject{{"code", "DE"}, {"host", "test.example"}}};
        QJsonObject response{{"id", request.value("id")}, {"state", state}, {"reply", payload}};
        if (!error.isEmpty()) response.insert("error", error);
        const auto bytes = QJsonDocument(response).toJson(QJsonDocument::Compact) + '\n';
        std::fwrite(bytes.constData(), 1, size_t(bytes.size()), stdout);
        std::fflush(stdout);
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
