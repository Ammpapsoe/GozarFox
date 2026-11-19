#pragma once
// Compile-only stub for the real upstream window's backend dependency.
// Not included in a production build and not a replacement for the UI.
#include "backend/preferences/preferences.h"
#include "types/pingtime.h"
#include "types/protocol.h"
class PreferencesHelper : public QObject {
    Q_OBJECT
public:
    bool isDockedToTray() const { return false; }
    bool isExternalConfigMode() const { return false; }
    void setCurrentProtocol(const types::Protocol &) {}
    QString apiSuggestedAmneziawgPreset() const { return {}; }
signals:
    void isDockedModeChanged(bool docked);
    void isExternalConfigModeChanged(bool external);
    void apiSuggestedAmneziawgPresetChanged(const QString &preset);
};
