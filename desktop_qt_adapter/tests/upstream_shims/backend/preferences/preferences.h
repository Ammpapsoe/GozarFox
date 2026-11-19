#pragma once
// Test-only substitute for the Windscribe preferences/backend, not a UI rewrite.
#include <QObject>
#include "types/backgroundsettings.h"
class Preferences : public QObject {
    Q_OBJECT
public:
    explicit Preferences(QObject *parent = nullptr) : QObject(parent) {}
    types::BackgroundSettings backgroundSettings() const { return settings_; }
    APP_SKIN appSkin() const { return APP_SKIN_VAN_GOGH; }
signals:
    void backgroundSettingsChanged(const types::BackgroundSettings &settings);
    void appSkinChanged(APP_SKIN skin);
private:
    types::BackgroundSettings settings_;
};
