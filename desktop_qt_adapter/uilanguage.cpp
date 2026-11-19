#include "uilanguage.h"

#include "languagecontroller.h"
#include "utils/languagesutil.h"

#include <QApplication>
#include <QLocale>
#include <QSettings>
#include <QTranslator>

namespace {
constexpr auto kLanguageKey = "OriginalQtUI/language";
}

UiLanguage::UiLanguage(QSettings *settings) : settings_(settings) {
    Q_ASSERT(settings_);
}

QString UiLanguage::choice() const {
    return settings_->value(QLatin1String(kLanguageKey), QStringLiteral("system")).toString();
}

bool UiLanguage::apply(const QString &requested, QString *error) {
    if (error) error->clear();
    auto fail = [error](const QString &message) {
        if (error) *error = message;
        return false;
    };
    if (!qobject_cast<QApplication *>(QCoreApplication::instance()))
        return fail(QStringLiteral("Qt GUI application is not running"));
    const QString resolved = requested == QLatin1String("system")
        ? LanguagesUtil::systemLanguage() : requested;
    if (!LanguagesUtil::isSupportedLanguage(resolved))
        return fail(QStringLiteral("unsupported UI language"));
    if (resolved != QLatin1String("en")) {
        QTranslator probe;
        if (!probe.load(QStringLiteral(":/translations/ws_desktop_") + resolved + QStringLiteral(".qm")))
            return fail(QStringLiteral("UI translation is unavailable"));
    }

    // Keep UI language separate from the Go core's fa/en/system preference.
    // Persist only validated choices and do not silently accept storage errors.
    settings_->setValue(QLatin1String(kLanguageKey), requested);
    settings_->sync();
    if (settings_->status() != QSettings::NoError)
        return fail(QStringLiteral("could not persist UI language"));

    auto &controller = LanguageController::instance();
    controller.setLanguage(resolved);
    if (controller.getLanguage() != resolved)
        return fail(QStringLiteral("could not activate UI translation"));
    QApplication::setLayoutDirection(QLocale(resolved).textDirection());
    return true;
}
