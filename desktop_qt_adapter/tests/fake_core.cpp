#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QFile>
#include <cstdio>

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    if (app.arguments().size() != 2 || app.arguments()[1] != QStringLiteral("--rpc")) return 2;
    char line[65536];
    QString countryCode;
    QString mode = QStringLiteral("tun");
    bool killSwitch = false;
    bool live = false;
    while (std::fgets(line, sizeof(line), stdin)) {
        const auto request = QJsonDocument::fromJson(line).object();
        const auto method = request.value("method").toString();
        if (method == "crash") return 7;
        if (method == "invalid") { std::fputs("not-json\n", stdout); std::fflush(stdout); continue; }
        const QJsonObject params = request.value("params").toObject();
        if (method == "server") countryCode = params.value("code").toString();
        if (method == "connect") live = true;
        if (method == "disconnect") live = false;
        if (method == "mode") mode = params.value("mode").toString();
        if (method == "kill_switch") killSwitch = params.value("enabled").toBool();
        const QJsonObject state{{"signed_in", true}, {"live", live}, {"connecting", false}, {"mode", mode}, {"kill_switch", killSwitch}, {"server", QJsonObject{{"code", countryCode}}}};
        QJsonValue payload = request.value("params");
        if (method == "servers") payload = QJsonArray{QJsonObject{{"code", "DE"}, {"host", "test.example"}}};
        const QJsonObject response{{"id", request.value("id")}, {"state", state}, {"reply", payload}};
        const auto bytes = QJsonDocument(response).toJson(QJsonDocument::Compact) + '\n';
        std::fwrite(bytes.constData(), 1, size_t(bytes.size()), stdout);
        std::fflush(stdout);
        if (method == "quit") {
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
