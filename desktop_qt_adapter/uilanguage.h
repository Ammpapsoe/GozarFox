// SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
#pragma once

#include <QString>

class QSettings;

// Experimental UI-only language selection. It does not write the core's
// language setting or touch sign-in/session state. The caller owns QSettings.
class UiLanguage final {
public:
    explicit UiLanguage(QSettings *settings);
    QString choice() const;
    bool apply(const QString &choice, QString *error = nullptr);

private:
    QSettings *settings_;
};
