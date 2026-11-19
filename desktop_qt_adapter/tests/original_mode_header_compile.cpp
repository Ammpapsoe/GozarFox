// Compile the mode adapter against the original Windscribe combo declaration.
// Runtime coverage uses a stand-in, not a complete preferences window.
#include "preferenceswindow/comboboxitem.h"
#include "originalmodebinding.h"

void compileOriginalModeBinding(PreferencesWindow::ComboBoxItem *item, HomeBinder *binder) {
    new OriginalModeBinding<PreferencesWindow::ComboBoxItem>(item, binder);
}
