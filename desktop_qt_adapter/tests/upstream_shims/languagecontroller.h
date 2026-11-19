#pragma once
#include <QObject>
#include <QString>
// Test-only stand-in; actual translations remain upstream and unmodified.
class LanguageController : public QObject {
    Q_OBJECT
public:
    static LanguageController &instance() { static LanguageController controller; return controller; }
    QString getLanguage() const { return QStringLiteral("en"); }
signals:
    void languageChanged();
private:
    LanguageController() = default;
};
