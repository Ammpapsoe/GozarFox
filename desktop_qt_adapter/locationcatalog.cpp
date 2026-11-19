#include "locationcatalog.h"

#include <QCryptographicHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMap>

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

bool LocationCatalog::replace(const QJsonArray &servers, QString *error) {
    // On invalid snapshots, never keep clickable entries from the old snapshot.
    locations_.clear();
    selections_.clear();
    if (error) error->clear();
    QMap<QString, types::Location> grouped;
    QHash<int, QString> countriesById;
    QHash<int, QString> citiesById;
    QHash<LocationID, Selection> selections;
    auto fail = [error](const QString &message) {
        if (error) *error = message;
        return false;
    };
    for (const auto &value : servers) {
        if (!value.isObject()) return fail(QStringLiteral("invalid server entry"));
        const QJsonObject object = value.toObject();
        const QString code = object.value(QStringLiteral("code")).toString().trimmed().toUpper();
        const QString country = object.value(QStringLiteral("country")).toString().trimmed();
        const QString city = object.value(QStringLiteral("city")).toString().trimmed();
        const QString host = object.value(QStringLiteral("host")).toString().trimmed();
        if (code.isEmpty() || host.isEmpty()) return fail(QStringLiteral("server missing country code or hostname"));
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
            location.countryCode = code.toLower();
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
        item.city = city.isEmpty() ? host : city;
        item.nick = host;
        item.id = LocationID::createApiLocationId(countryId, item.city, item.nick);
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
