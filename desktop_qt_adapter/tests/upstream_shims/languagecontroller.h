#pragma once
#include <QObject>
// Test-only stand-in; actual translations remain upstream and unmodified.
class LanguageController : public QObject {
    Q_OBJECT
public:
    static LanguageController &instance() { static LanguageController controller; return controller; }
signals:
    void languageChanged();
private:
    LanguageController() = default;
};
