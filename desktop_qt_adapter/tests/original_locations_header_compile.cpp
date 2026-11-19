// Compile the adapter against actual upstream declarations. No window or
// model is linked here; execution coverage lives in the isolated tests.
#include "locationswindow/locationswindow.h"
#include "locations/locationsmodel_manager.h"
#include "originallocationsbinding.h"

void compileOriginalLocationsBinding(LocationsWindow *window, gui_locations::LocationsModelManager *manager,
                                     HomeBinder *binder, LocationCatalog *catalog) {
    bindOriginalLocations(window, manager, binder, catalog);
}
