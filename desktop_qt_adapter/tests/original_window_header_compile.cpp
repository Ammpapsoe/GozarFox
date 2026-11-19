// Compile against the exact upstream window declaration, without linking the
// upstream backend. The runtime application is not implemented by this check.
#include "connectwindow/connectwindowitem.h"
#include "originalhomebinding.h"

void compileOriginalHomeBinding(ConnectWindow::ConnectWindowItem *window, HomeBinder *binder) {
    bindOriginalHomeWindow(window, binder);
}
