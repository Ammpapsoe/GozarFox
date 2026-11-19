#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <cstdio>

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    if (app.arguments().size() != 2 || app.arguments()[1] != QStringLiteral("--rpc")) return 2;
    char line[65536];
    QString countryCode;
    bool live = false;
    while (std::fgets(line, sizeof(line), stdin)) {
        const auto request = QJsonDocument::fromJson(line).object();
        const auto method = request.value("method").toString();
        if (method == "crash") return 7;
        if (method == "invalid") { std::fputs("not-json\n", stdout); std::fflush(stdout); continue; }
        if (method == "server") countryCode = request.value("params").toObject().value("code").toString();
        if (method == "connect") live = true;
        if (method == "disconnect") live = false;
        const QJsonObject state{{"signed_in", true}, {"live", live}, {"connecting", false}, {"server", QJsonObject{{"code", countryCode}}}};
        const QJsonObject response{{"id", request.value("id")}, {"state", state}, {"reply", request.value("params")}};
        const auto bytes = QJsonDocument(response).toJson(QJsonDocument::Compact) + '\n';
        std::fwrite(bytes.constData(), 1, size_t(bytes.size()), stdout);
        std::fflush(stdout);
        if (method == "quit") return 0;
    }
    return 0;
}
