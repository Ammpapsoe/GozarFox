// SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
#pragma once

#include "homebinder.h"
#include "uilanguage.h"
#include "languagecontroller.h"
#include "utils/languagesutil.h"

#include <QCoreApplication>
#include <QList>
#include <QPointer>
#include <QStringList>
#include <QTimer>
#include <QVariant>

// Experimental adapter for the original preferences ComboBoxItem. The owner
// keeps UiLanguage and its QSettings alive longer than this item. Do not keep
// GeneralWindowItem's original Windscribe preference handler attached to the
// same control: it writes a different backend and can overwrite this choice.
// This connects one original control, not the complete preferences page.
template<class Item>
class OriginalLanguageBinding final : public QObject {
public:
    OriginalLanguageBinding(Item *item, UiLanguage *language, HomeBinder *errors)
        : QObject(item), item_(item), language_(language), errors_(errors) {
        Q_ASSERT(item && language && errors);
        item_->clear();
        item_->setClickable(false);
        QString reason;
        const QString initial = language_->choice();
        if (!language_->apply(initial, &reason)) {
            emit errors_->error(reason);
            return;
        }
        confirmed_ = initial;
        QObject::connect(item, &Item::currentItemChanged, this,
                         [this](const QVariant &value) { choose(value.toString()); });
        QObject::connect(&LanguageController::instance(), &LanguageController::languageChanged,
                         this, [this] { sync(); });
        sync();
        item_->setClickable(true);
    }

private:
    static QStringList codes() {
        return {QStringLiteral("en"), QStringLiteral("ar"), QStringLiteral("be"),
            QStringLiteral("cs"), QStringLiteral("de"), QStringLiteral("el"),
            QStringLiteral("es"), QStringLiteral("fa"), QStringLiteral("fr"),
            QStringLiteral("hi"), QStringLiteral("id"), QStringLiteral("it"),
            QStringLiteral("ja"), QStringLiteral("ko"), QStringLiteral("pl"),
            QStringLiteral("pt"), QStringLiteral("ru"), QStringLiteral("sk"),
            QStringLiteral("tr"), QStringLiteral("uk"), QStringLiteral("vi"),
            QStringLiteral("zh-CN"), QStringLiteral("zh-TW")};
    }

    void sync() {
        if (!item_) return;
        const bool fa = LanguageController::instance().getLanguage() == QLatin1String("fa");
        QList<QPair<QString, QVariant>> options;
        options.append({fa ? QStringLiteral("\u067e\u06cc\u0634\u200c\u0641\u0631\u0636 \u0633\u06cc\u0633\u062a\u0645") : QStringLiteral("System default"),
                        QStringLiteral("system")});
        for (const QString &code : codes())
            options.append({LanguagesUtil::convertCodeToNative(code), code});
        item_->setLabelCaption(QCoreApplication::translate("PreferencesWindow::GeneralWindowItem", "Language"));
        item_->setItems(options, confirmed_);
    }

    void choose(const QString &choice) {
        if (!item_ || !language_) return;
        item_->setCurrentItem(confirmed_); // undo upstream's optimistic label
        if (pending_) return;
        pending_ = true;
        item_->setClickable(false);
        // setItems deletes menu buttons; defer the language change until the
        // original button's clicked signal has returned to the event loop.
        QTimer::singleShot(0, this, [this, choice] {
            QString reason;
            const bool applied = language_->apply(choice, &reason);
            if (applied) confirmed_ = choice;
            sync();
            if (item_) item_->setClickable(true);
            pending_ = false;
            if (!applied && errors_) emit errors_->error(reason);
        });
    }

    QPointer<Item> item_;
    UiLanguage *language_;
    QPointer<HomeBinder> errors_;
    QString confirmed_;
    bool pending_ = false;
};
