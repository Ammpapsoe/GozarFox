#pragma once

#include <QHash>
#include <QJsonArray>
#include <QString>
#include <QVector>
#include "types/location.h"

// Maps the Gozar server snapshot to the original Windscribe location-model
// types. This is an adapter, not the locations screen or a production app.
// A fresh snapshot replaces the entire mapping; unrecognized IDs fail closed.
class LocationCatalog {
public:
    struct Selection { QString host; QString countryCode; };

    bool replace(const QJsonArray &servers, QString *error = nullptr);
    const QVector<types::Location> &locations() const { return locations_; }
    bool selection(const LocationID &id, Selection *result) const;

private:
    QVector<types::Location> locations_;
    QHash<LocationID, Selection> selections_;
};
