#pragma once
// Test-only non-visual persistence stub for the upstream location menu.
#include "types/enums.h"
class PersistentState {
public:
    static PersistentState &instance() { static PersistentState state; return state; }
    LOCATION_TAB lastLocationTab() const { return tab_; }
    void setLastLocationTab(LOCATION_TAB tab) { tab_ = tab; }
private:
    LOCATION_TAB tab_ = LOCATION_TAB_ALL_LOCATIONS;
};
