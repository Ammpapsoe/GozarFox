#pragma once

#include <QGraphicsView>
#include <QGraphicsScene>
#include <QList>
#include <functional>

class HomeBinder;
class UiLanguage;
class QDialog;
class PreferencesHelper;
namespace PreferencesWindow { class PreferenceGroup; class ComboBoxItem; class TitleItem; class LinkItem; class PreferencesTabControlItem; }

// Gozar's settings model, rendered with Windscribe's original preference rows.
class GozarPreferences : public QGraphicsView {
    Q_OBJECT
public:
    GozarPreferences(HomeBinder *binder, UiLanguage *language, PreferencesHelper *helper, bool readOnly);
    void fitPanel(int width, int maxHeight);
    void retranslate();
signals:
    void quitRequested();
    void addAccountRequested();
    void accountDialogOpened(QDialog *dialog);

private:
    QGraphicsScene scene_;
    HomeBinder *binder_;
    PreferencesWindow::TitleItem *title_;
    PreferencesWindow::PreferenceGroup *connection_;
    PreferencesWindow::PreferenceGroup *general_;
    PreferencesWindow::PreferenceGroup *appearance_;
    PreferencesWindow::PreferenceGroup *account_;
    PreferencesWindow::LinkItem *email_;
    PreferencesWindow::LinkItem *switch_;
    PreferencesWindow::LinkItem *logout_;
    PreferencesWindow::LinkItem *usage_;
    void showAccounts();
    void syncAccount();
    PreferencesWindow::ComboBoxItem *theme_;
    PreferencesWindow::PreferencesTabControlItem *tabs_;
    int page_ = 0;
    void showPage(int page);
    void editProxyChain();
    void syncTheme();
    void addToggle(PreferencesWindow::PreferenceGroup *group, const char *key,
                   const char *en, const char *fa, std::function<void(bool)> change,
                   bool tunnelOnly = false);
    bool readOnly_;
    QString themeLanguage_;
    bool themePopulated_ = false;
};
