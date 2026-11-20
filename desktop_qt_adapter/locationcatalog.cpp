#include "locationcatalog.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QSet>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMap>
#include "languagecontroller.h"

namespace {
// Original location models require stable positive numeric ids. Gozar supplies
// hostnames instead; hash a namespaced JSON tuple and reject collisions rather
// than ever directing a click to a different server.
int stableId(const QJsonArray &parts) {
    const QByteArray digest = QCryptographicHash::hash(QJsonDocument(parts).toJson(QJsonDocument::Compact), QCryptographicHash::Sha256);
    const auto *p = reinterpret_cast<const unsigned char *>(digest.constData());
    const quint32 value = (quint32(p[0]) << 24 | quint32(p[1]) << 16 | quint32(p[2]) << 8 | quint32(p[3])) & 0x7fffffffU;
    return value == 0 ? 1 : int(value);
}
}

QString LocationCatalog::automaticLabel() {
    return LanguageController::instance().getLanguage() == QLatin1String("fa") ? QStringLiteral("انتخاب خودکار")
        : QCoreApplication::translate("GozarLocations", "Automatic");
}

bool LocationCatalog::replace(const QJsonArray &servers, QString *error) {
    // On invalid snapshots, never keep clickable entries from the old snapshot.
    locations_.clear();
    selections_.clear();
    if (error) error->clear();
    QMap<QString, types::Location> grouped;
    QHash<int, QString> countriesById;
    QHash<int, QString> citiesById;
    QHash<QString, QByteArray> targetsByHost;
    QHash<LocationID, Selection> selections;
    auto fail = [error](const QString &message) {
        if (error) *error = message;
        return false;
    };
    QSet<QString> ambiguousHosts;
    QSet<QString> automaticHosts;
    QSet<QString> countryKeys{QStringLiteral("auto"), QStringLiteral("rec")};
    for (const auto &value : servers) {
        if (!value.isObject()) return fail(QStringLiteral("invalid server entry"));
        const auto row = value.toObject();
        const QString code = row.value(QStringLiteral("code")).toString().trimmed().toUpper();
        const QString host = row.value(QStringLiteral("host")).toString().trimmed().toCaseFolded();
        if (code.isEmpty() || host.isEmpty()) return fail(QStringLiteral("server missing country code or hostname"));
        // REC is an alias for automatic selection, not a manual endpoint.
        if (code == QLatin1String("REC") || code == QLatin1String("AUTO") || row.value(QStringLiteral("recommended")).toBool()) {
            automaticHosts.insert(host);
            continue;
        }
        countryKeys.insert(code.toCaseFolded());
        const auto target = QJsonDocument(QJsonArray{code,
            row.value(QStringLiteral("country")).toString().trimmed(),
            row.value(QStringLiteral("city")).toString().trimmed(),
            row.value(QStringLiteral("port"))}).toJson(QJsonDocument::Compact);
        const auto existing = targetsByHost.constFind(host);
        if (existing != targetsByHost.cend() && *existing != target) ambiguousHosts.insert(host);
        targetsByHost.insert(host, target);
    }
    QJsonArray rows = servers;
    if (!automaticHosts.isEmpty()) rows.append(QJsonObject{{"code", "AUTO"}});
    for (qsizetype index = 0; index < rows.size(); ++index) {
        const auto object = rows.at(index).toObject();
        const bool automatic = index == servers.size();
        const QString code = object.value(QStringLiteral("code")).toString().trimmed().toUpper();
        if (!automatic && (code == QLatin1String("REC") || code == QLatin1String("AUTO") ||
                           object.value(QStringLiteral("recommended")).toBool())) continue;
        const QString country = automatic ? automaticLabel()
                                          : object.value(QStringLiteral("country")).toString().trimmed();
        const QString city = object.value(QStringLiteral("city")).toString().trimmed();
        const QString host = object.value(QStringLiteral("host")).toString().trimmed();
        const auto key = host.toCaseFolded();
        // The 1.19.0 core resolves and persists only a hostname. Do not show
        // a manual choice when that hostname can resolve to an automatic alias.
        if (!automatic && (automaticHosts.contains(key) || ambiguousHosts.contains(key) || countryKeys.contains(key))) continue;
        const int countryId = stableId(QJsonArray{QStringLiteral("country"), code});
        if (countriesById.contains(countryId) && countriesById.value(countryId) != code)
            return fail(QStringLiteral("country identifier collision"));
        countriesById.insert(countryId, code);
        auto it = grouped.find(code);
        if (it == grouped.end()) {
            types::Location location;
            location.id = LocationID::createTopApiLocationId(countryId);
            location.idNum = countryId;
            location.name = country.isEmpty() ? code : country;
            location.countryCode = automatic ? QString() : code.toLower();
            location.shortName = code;
            it = grouped.insert(code, location);
        } else if (!country.isEmpty() && it->name != country) {
            return fail(QStringLiteral("inconsistent country names"));
        }
        const QJsonArray identity{QStringLiteral("server"), code, city, host.toLower()};
        const QString identityKey = QString::fromUtf8(QJsonDocument(identity).toJson(QJsonDocument::Compact));
        const int cityId = stableId(identity);
        if (citiesById.contains(cityId) && citiesById.value(cityId) != identityKey)
            return fail(QStringLiteral("server identifier collision"));
        citiesById.insert(cityId, identityKey);
        types::City item;
        item.idNum = cityId;
        item.city = automatic ? country : (city.isEmpty() ? host : city);
        item.nick = host;
        item.id = LocationID::createApiLocationId(countryId, automatic ? QStringLiteral("AUTO") : item.city, item.nick);
        item.health = -1; // Gozar does not supply server load.
        const Selection choice{host, code};
        if (selections.contains(item.id)) {
            const Selection old = selections.value(item.id);
            if (old.host.compare(host, Qt::CaseInsensitive) != 0 || old.countryCode != code)
                return fail(QStringLiteral("ambiguous server identifier"));
            continue; // An identical entry has no distinct selectable target.
        }
        it->cities.append(item);
        selections.insert(item.id, choice);
    }
    locations_.reserve(grouped.size());
    for (auto it = grouped.cbegin(); it != grouped.cend(); ++it) locations_.append(it.value());
    selections_ = selections;
    return true;
}

bool LocationCatalog::selection(const LocationID &id, Selection *result) const {
    const auto it = selections_.constFind(id);
    if (it == selections_.cend()) return false;
    if (result) *result = it.value();
    return true;
}
