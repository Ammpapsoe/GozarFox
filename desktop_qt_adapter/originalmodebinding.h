// SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
#pragma once

#include "homebinder.h"
#include "languagecontroller.h"

#include <QList>
#include <QPointer>
#include <QVariant>

// Adapter for the original PreferencesWindow::ComboBoxItem. It does not
// create or rearrange the settings page. Selection is only shown after the
// unchanged Gozar core confirms it, and unknown state never defaults to TUN.
// The owning application must place the item in the original settings page.
template<class Item>
class OriginalModeBinding final : public QObject {
public:
    OriginalModeBinding(Item *item, HomeBinder *binder)
        : QObject(item), item_(item), binder_(binder) {
        Q_ASSERT(item && binder);
        item->clear();
        item->setClickable(false);
        QObject::connect(binder, &HomeBinder::stateChanged, this,
                         [this](const QJsonObject &) { sync(); });
        QObject::connect(binder, &HomeBinder::availabilityChanged, this,
                         [this](bool) { sync(); });
        QObject::connect(item, &Item::currentItemChanged, this,
                         [this](const QVariant &value) { choose(value.toString()); });
        QObject::connect(&LanguageController::instance(), &LanguageController::languageChanged,
                         this, [this] { populated_ = false; sync(); });
        QObject::connect(binder, &QObject::destroyed, this, [this] { sync(); });
        sync();
    }

private:
    static bool validMode(const QString &mode) {
        return mode == QLatin1String("tun") || mode == QLatin1String("proxy-only") ||
               mode == QLatin1String("system-proxy");
    }

    void sync() {
        if (!item_) return;
        const auto state = binder_ ? binder_->currentState() : QJsonObject();
        const QJsonValue value = state.value(QStringLiteral("mode"));
        const QString mode = value.toString();
        if (!value.isString() || !validMode(mode)) {
            if (populated_) item_->clear();
            populated_ = false;
            confirmed_.clear();
            item_->setClickable(false);
            return;
        }
        if (!populated_ || mode != confirmed_) {
            const bool fa = LanguageController::instance().getLanguage() == QLatin1String("fa");
            const QString tun = fa ? QStringLiteral("\u062a\u0648\u0646\u0644 \u0633\u06cc\u0633\u062a\u0645") : QStringLiteral("System tunnel");
            const QString proxy = fa ? QStringLiteral("\u0641\u0642\u0637 \u067e\u0631\u0627\u06a9\u0633\u06cc") : QStringLiteral("Proxy only");
#ifdef Q_OS_WIN
            const QString system = fa ? QStringLiteral("\u067e\u0631\u0627\u06a9\u0633\u06cc \u0633\u06cc\u0633\u062a\u0645") : QStringLiteral("System proxy");
#else
            const QString system = fa ? QStringLiteral("\u067e\u0631\u0627\u06a9\u0633\u06cc \u0633\u06cc\u0633\u062a\u0645 (\u0641\u0642\u0637 \u0648\u06cc\u0646\u062f\u0648\u0632)") : QStringLiteral("System proxy (Windows only)");
#endif
            QList<QPair<QString, QVariant>> choices{{tun, QStringLiteral("tun")}, {proxy, QStringLiteral("proxy-only")}};
#ifdef Q_OS_WIN
            choices.append({system, QStringLiteral("system-proxy")});
#else
            // Show a migrated value honestly, but don't offer it as a new choice.
            if (mode == QLatin1String("system-proxy")) choices.append({system, QStringLiteral("system-proxy")});
#endif
            item_->setLabelCaption(fa ? QStringLiteral("\u062d\u0627\u0644\u062a \u0627\u062a\u0635\u0627\u0644") : QStringLiteral("Connection mode"));
            item_->setItems(choices, mode);
            confirmed_ = mode;
            populated_ = true;
        }
        item_->setClickable(binder_ && binder_->canChangeMode());
    }

    void choose(const QString &mode) {
        if (!item_ || !binder_) return;
        if (!populated_) {
            emit binder_->error(QObject::tr("The connection mode is unknown."));
            return;
        }
        // The original combo switches labels before emitting the signal.
        // Restore the last confirmed value until the core responds.
        item_->setCurrentItem(confirmed_);
        if (mode != confirmed_) binder_->setMode(mode);
    }

    QPointer<Item> item_;
    QPointer<HomeBinder> binder_;
    QString confirmed_;
    bool populated_ = false;
};
