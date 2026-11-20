#pragma once

#include <QGraphicsView>
#include <QGraphicsScene>
#include <QList>
#include <functional>

class HomeBinder;
class UiLanguage;
namespace PreferencesWindow { class PreferenceGroup; class ComboBoxItem; class TitleItem; class TabButton; }

// Gozar's settings model, rendered with Windscribe's original preference rows.
class GozarPreferences : public QGraphicsView {
public:
    GozarPreferences(HomeBinder *binder, UiLanguage *language, bool readOnly);
    void fitPanel(int width, int maxHeight);
    void retranslate();

private:
    QGraphicsScene scene_;
    HomeBinder *binder_;
    PreferencesWindow::TitleItem *title_;
    PreferencesWindow::PreferenceGroup *connection_;
    PreferencesWindow::PreferenceGroup *general_;
    PreferencesWindow::PreferenceGroup *appearance_;
    PreferencesWindow::ComboBoxItem *theme_;
    QList<PreferencesWindow::TabButton *> tabs_;
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
