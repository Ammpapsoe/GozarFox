cmake_minimum_required(VERSION 3.22)
project(gozar_qt_core_adapter LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
find_package(Qt6 REQUIRED COMPONENTS Core Test)
add_library(gozar_core_adapter STATIC corebridge.cpp corebridge.h)
target_link_libraries(gozar_core_adapter PUBLIC Qt6::Core)
target_include_directories(gozar_core_adapter PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
include(CTest)
if(BUILD_TESTING)
  add_executable(fake_core tests/fake_core.cpp)
  target_link_libraries(fake_core PRIVATE Qt6::Core)
  add_executable(corebridge_test tests/corebridge_test.cpp)
  target_link_libraries(corebridge_test PRIVATE gozar_core_adapter Qt6::Test)
  add_executable(realcore_smoke tests/realcore_smoke.cpp)
  target_link_libraries(realcore_smoke PRIVATE gozar_core_adapter Qt6::Test)
  set(GOZAR_REAL_CORE "" CACHE FILEPATH "Optional real Gozar core for isolated read-only smoke test")
endif()

# Deliberately optional: this smoke test compiles real upstream GUI code, but
# does not build a full application or claim distribution readiness.
option(BUILD_UPSTREAM_SMOKE_TEST "Build test with pinned Windscribe sources" OFF)
set(WINDSCRIBE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vendor/windscribe" CACHE PATH "Windscribe Desktop-App source root")
if(BUILD_UPSTREAM_SMOKE_TEST)
  if(NOT BUILD_TESTING OR NOT EXISTS "${WINDSCRIBE_ROOT}/src/client/frontend/gui/images.qrc")
    message(FATAL_ERROR "BUILD_TESTING and WINDSCRIBE_ROOT are required")
  endif()
  find_package(Qt6 REQUIRED COMPONENTS Widgets Svg Network LinguistTools)
  set(CMAKE_AUTORCC ON)
  set(WS_GUI "${WINDSCRIBE_ROOT}/src/client/frontend/gui")
  set(WS_COMMON "${WINDSCRIBE_ROOT}/src/client/client-common")
  set(WS_FRONTEND_COMMON "${WINDSCRIBE_ROOT}/src/client/frontend/frontend-common")
  # Preserve the pinned sources and generate a resource map with Gozar's own
  # wordmark. This only addresses known marks, not licensing of all assets.
  find_package(Python3 REQUIRED COMPONENTS Interpreter)
  set(GOZAR_TEST_QRC "${CMAKE_CURRENT_BINARY_DIR}/images.qrc")
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${WS_GUI}/images.qrc" "${CMAKE_CURRENT_SOURCE_DIR}/branding/create_resources.py"
    "${CMAKE_CURRENT_SOURCE_DIR}/branding/gozar_logo.svg" "${WS_GUI}/resources/fonts/ibmplexsans.ttf")
  execute_process(COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/branding/create_resources.py"
    --source "${WS_GUI}/images.qrc"
    --logo "${CMAKE_CURRENT_SOURCE_DIR}/branding/gozar_logo.svg"
    --font "${WS_GUI}/resources/fonts/ibmplexsans.ttf"
    --output "${GOZAR_TEST_QRC}"
    RESULT_VARIABLE resource_result ERROR_VARIABLE resource_error)
  if(NOT resource_result EQUAL 0)
    message(FATAL_ERROR "Failed to generate Gozar resource map: ${resource_error}")
  endif()
  add_executable(upstream_background_test
    homebinder.cpp homebinder.h originalhomebinding.h
    uireadiness.cpp uireadiness.h uicloseguard.cpp uicloseguard.h
    uilanguage.cpp uilanguage.h originallanguagebinding.h
    ${WS_FRONTEND_COMMON}/languagecontroller.cpp ${WS_FRONTEND_COMMON}/languagecontroller.h
    ${WS_COMMON}/utils/languagesutil.cpp
    locationcatalog.cpp locationcatalog.h originallocationsbinding.h
    ${WS_COMMON}/types/locationid.cpp
    tests/upstream_background_test.cpp tests/upstream_shims/categories.cpp
    tests/upstream_pingtime.cpp
    tests/upstream_shims/backend/preferences/preferences.h
    ${GOZAR_TEST_QRC}
    ${WS_GUI}/dpiscalemanager.cpp ${WS_GUI}/dpiscalemanager.h
    ${WS_GUI}/mainwindowstate.cpp ${WS_GUI}/mainwindowstate.h
    ${WS_GUI}/commongraphics/scalablegraphicsobject.cpp ${WS_GUI}/commongraphics/scalablegraphicsobject.h
    ${WS_GUI}/commongraphics/clickablegraphicsobject.cpp ${WS_GUI}/commongraphics/clickablegraphicsobject.h
    ${WS_GUI}/commongraphics/imageitem.cpp ${WS_GUI}/commongraphics/imageitem.h
    ${WS_GUI}/utils/imagewithshadow.cpp
    ${WS_GUI}/graphicresources/imageresourcespng.cpp
    ${WS_GUI}/connectwindow/connectbutton.cpp ${WS_GUI}/connectwindow/connectbutton.h
    ${WS_GUI}/graphicresources/independentpixmap.cpp
    ${WS_GUI}/graphicresources/imageresourcessvg.cpp ${WS_GUI}/graphicresources/imageresourcessvg.h
    ${WS_GUI}/connectwindow/background.cpp ${WS_GUI}/connectwindow/background.h
    ${WS_GUI}/connectwindow/backgroundimage/backgroundimage.cpp ${WS_GUI}/connectwindow/backgroundimage/backgroundimage.h
    ${WS_GUI}/connectwindow/backgroundimage/imagechanger.cpp ${WS_GUI}/connectwindow/backgroundimage/imagechanger.h
    ${WS_GUI}/connectwindow/backgroundimage/simpleimagechanger.cpp ${WS_GUI}/connectwindow/backgroundimage/simpleimagechanger.h)
  # Exercise the real upstream ConnectWindowItem and its original visible
  # children. Only preferences, persistence and tooltips are test shims.
  target_sources(upstream_background_test PRIVATE
    tests/upstream_shims/backend/backend.h tests/upstream_shims/tooltip_service.cpp
    ${WS_GUI}/tooltips/tooltipcontroller.h
    tests/upstream_protocol.cpp
    ${WS_GUI}/graphicresources/fontdescr.cpp ${WS_GUI}/graphicresources/fontmanager.cpp
    ${WS_GUI}/utils/textshadow.cpp
    ${WS_GUI}/commongraphics/commongraphics.cpp
    ${WS_GUI}/commongraphics/iconbutton.cpp ${WS_GUI}/commongraphics/textbutton.cpp
    ${WS_GUI}/commongraphics/togglebutton.cpp
    # Actual original preferences combo and its menu, tested with a fake core.
    ${WS_GUI}/preferenceswindow/comboboxitem.cpp ${WS_GUI}/preferenceswindow/comboboxitem.h
    ${WS_GUI}/commongraphics/baseitem.cpp ${WS_GUI}/commongraphics/baseitem.h
    ${WS_GUI}/commongraphics/texticonbutton.cpp ${WS_GUI}/commongraphics/texticonbutton.h
    ${WS_GUI}/commonwidgets/combomenuwidget.cpp ${WS_GUI}/commonwidgets/combomenuwidget.h
    ${WS_GUI}/commonwidgets/combomenuwidgetbutton.cpp ${WS_GUI}/commonwidgets/combomenuwidgetbutton.h
    ${WS_GUI}/commonwidgets/verticalscrollbarwidget.cpp ${WS_GUI}/commonwidgets/verticalscrollbarwidget.h
    ${WS_GUI}/utils/makecustomshadow.cpp
    ${WS_GUI}/loginwindow/blockableqlineedit.cpp
    ${WS_GUI}/commonwidgets/custommenulineedit.cpp ${WS_GUI}/commonwidgets/custommenuwidget.cpp
    ${WS_GUI}/commonwidgets/iconbuttonwidget.cpp
    ${WS_GUI}/connectwindow/connectwindowitem.cpp ${WS_GUI}/connectwindow/connectwindowitem.h
    ${WS_GUI}/connectwindow/iputilsmenu.cpp
    ${WS_GUI}/connectwindow/locationsbutton.cpp ${WS_GUI}/connectwindow/locationsmenu.cpp
    ${WS_GUI}/connectwindow/logonotificationsbutton.cpp ${WS_GUI}/connectwindow/networktrustbutton.cpp
    ${WS_GUI}/connectwindow/connectstateprotocolport/badgepixmap.cpp
    ${WS_GUI}/connectwindow/connectstateprotocolport/connectionbadgedots.cpp
    ${WS_GUI}/connectwindow/connectstateprotocolport/connectstateprotocolport.cpp
    ${WS_GUI}/connectwindow/ipaddressitem/ipaddressitem.cpp
    ${WS_GUI}/connectwindow/ipaddressitem/numberitem.cpp
    ${WS_GUI}/connectwindow/ipaddressitem/numberspixmap.cpp
    ${WS_GUI}/connectwindow/ipaddressitem/octetitem.cpp
    ${WS_GUI}/commongraphics/footerbackground.cpp
    ${WS_GUI}/commonwidgets/scrollbar.cpp
    ${WS_GUI}/commonwidgets/textbuttonwidget.cpp
    ${WS_GUI}/locationswindow/locationswindow.cpp
    ${WS_GUI}/locationswindow/widgetlocations/configfooterinfo.cpp
    ${WS_GUI}/locationswindow/widgetlocations/emptylistwidget.cpp
    ${WS_GUI}/locationswindow/widgetlocations/locationstab.cpp
    ${WS_GUI}/locationswindow/widgetlocations/staticipdeviceinfo.cpp
    ${WS_GUI}/locationswindow/widgetlocations/upgradebanner.cpp
    ${WS_GUI}/locationswindow/widgetlocations/widgetswitcher.cpp
    ${WS_GUI}/locations/view/cityitemdelegate.cpp
    ${WS_GUI}/locations/view/countryitemdelegate.cpp
    ${WS_GUI}/locations/view/cursorupdatehelper.cpp
    ${WS_GUI}/locations/view/expandableitemswidget.cpp
    ${WS_GUI}/locations/view/locationsview.cpp
    ${WS_GUI}/locations/view/textpixmap.cpp
    ${WS_COMMON}/types/location.cpp
    ${WS_COMMON}/utils/simplecrypt.cpp ${WS_COMMON}/utils/networkingvalidation.cpp
    ${WS_FRONTEND_COMMON}/locations/locationsmodel_manager.cpp
    ${WS_FRONTEND_COMMON}/locations/model/favoritelocationsstorage.cpp
    tests/upstream_renamedlocations.cpp ${WS_FRONTEND_COMMON}/locations/model/renamedlocationsstorage.h
    tests/upstream_locationsmodel.cpp ${WS_FRONTEND_COMMON}/locations/model/locationsmodel.h
    ${WS_FRONTEND_COMMON}/locations/model/locationsmodel_utils.cpp
    ${WS_FRONTEND_COMMON}/locations/model/locationitem.cpp
    ${WS_FRONTEND_COMMON}/locations/model/proxymodels/cities_proxymodel.cpp
    ${WS_FRONTEND_COMMON}/locations/model/proxymodels/customconfigs_proxymodel.cpp
    ${WS_FRONTEND_COMMON}/locations/model/proxymodels/favoritecities_proxymodel.cpp
    ${WS_FRONTEND_COMMON}/locations/model/proxymodels/sortedcities_proxymodel.cpp
    ${WS_FRONTEND_COMMON}/locations/model/proxymodels/sortedlocations_proxymodel.cpp
    ${WS_FRONTEND_COMMON}/locations/model/proxymodels/staticips_proxymodel.cpp)
  # Compile the shared upstream widgets once for both the test runner and the
  # isolated read-only development executable. These still use test shims.
  get_target_property(gozar_widget_sources upstream_background_test SOURCES)
  # On MSVC, AUTORCC objects generated inside an object library are not
  # reliably exposed via TARGET_OBJECTS. Compile the small qrc per executable.
  list(REMOVE_ITEM gozar_widget_sources tests/upstream_background_test.cpp "${GOZAR_TEST_QRC}")
  set_property(TARGET upstream_background_test PROPERTY SOURCES tests/upstream_background_test.cpp "${GOZAR_TEST_QRC}")
  add_library(gozar_test_widgets OBJECT ${gozar_widget_sources})
  target_include_directories(gozar_test_widgets BEFORE PRIVATE tests/upstream_shims ${WS_GUI} ${WS_COMMON} ${WS_FRONTEND_COMMON})
  target_compile_definitions(gozar_test_widgets PRIVATE WS_APP_IDENTIFIER="GozarQtTest")
  target_link_libraries(gozar_test_widgets PRIVATE gozar_core_adapter Qt6::Widgets Qt6::Svg Qt6::Network)
  target_sources(upstream_background_test PRIVATE $<TARGET_OBJECTS:gozar_test_widgets>)
  target_include_directories(upstream_background_test BEFORE PRIVATE tests/upstream_shims ${WS_GUI} ${WS_COMMON} ${WS_FRONTEND_COMMON})
  target_compile_definitions(upstream_background_test PRIVATE WS_APP_IDENTIFIER="GozarQtTest")
  target_link_libraries(upstream_background_test PRIVATE gozar_core_adapter Qt6::Widgets Qt6::Svg Qt6::Network Qt6::Test)
  add_executable(gozar_qt_readonly_probe development/qt_readonly_probe.cpp "${GOZAR_TEST_QRC}" $<TARGET_OBJECTS:gozar_test_widgets>)
  target_include_directories(gozar_qt_readonly_probe BEFORE PRIVATE tests/upstream_shims ${WS_GUI} ${WS_COMMON} ${WS_FRONTEND_COMMON})
  target_compile_definitions(gozar_qt_readonly_probe PRIVATE WS_APP_IDENTIFIER="GozarQtTest")
  target_link_libraries(gozar_qt_readonly_probe PRIVATE gozar_core_adapter Qt6::Widgets Qt6::Svg Qt6::Network)
  # CI-only integrated simulated shell; it is deliberately coupled to the
  # fake core and test backend shims, never install or release this target.
  add_executable(gozar_qt_simulator development/qt_simulator.cpp "${GOZAR_TEST_QRC}" $<TARGET_OBJECTS:gozar_test_widgets>)
  target_include_directories(gozar_qt_simulator BEFORE PRIVATE tests/upstream_shims ${WS_GUI} ${WS_COMMON} ${WS_FRONTEND_COMMON})
  target_compile_definitions(gozar_qt_simulator PRIVATE WS_APP_IDENTIFIER="GozarQtTest")
  target_link_libraries(gozar_qt_simulator PRIVATE gozar_core_adapter Qt6::Widgets Qt6::Svg Qt6::Network Qt6::Test)
  # Compile upstream translations unchanged and embed them at the exact path
  # expected by the original LanguageController.
  file(GLOB WS_TRANSLATIONS CONFIGURE_DEPENDS "${WS_GUI}/translations/ws_desktop_*.ts")
  qt_add_translations(TARGETS upstream_background_test gozar_qt_simulator
    TS_FILES ${WS_TRANSLATIONS} RESOURCE_PREFIX "/translations")
  # Compile the binding and original window implementation against test-only
  # backend stubs. This does not link or instantiate the whole application.
  add_library(original_window_header_compile OBJECT
    tests/original_window_header_compile.cpp tests/original_locations_header_compile.cpp
    tests/original_mode_header_compile.cpp
    ${WS_GUI}/connectwindow/connectwindowitem.cpp)
  target_include_directories(original_window_header_compile BEFORE PRIVATE tests/upstream_shims ${WS_GUI} ${WS_COMMON} ${WS_FRONTEND_COMMON})
  target_link_libraries(original_window_header_compile PRIVATE gozar_core_adapter Qt6::Widgets Qt6::Svg)
endif()

# One focused CTest entry per platform; it runs the existing bridge, real-core
# and upstream-window checks as one scenario, without hiding failures.
if(BUILD_TESTING)
  set(integration_args
    "-DCOREBRIDGE_TEST=$<TARGET_FILE:corebridge_test>"
    "-DFAKE_CORE=$<TARGET_FILE:fake_core>"
    "-DREALCORE_TEST=$<TARGET_FILE:realcore_smoke>"
    "-DREAL_CORE=${GOZAR_REAL_CORE}")
  if(BUILD_UPSTREAM_SMOKE_TEST)
    list(APPEND integration_args "-DUPSTREAM_TEST=$<TARGET_FILE:upstream_background_test>"
      "-DQT_READONLY_PROBE=$<TARGET_FILE:gozar_qt_readonly_probe>"
      "-DQT_SIMULATOR=$<TARGET_FILE:gozar_qt_simulator>")
  endif()
  add_test(NAME gozar_integration_smoke COMMAND "${CMAKE_COMMAND}" ${integration_args}
    -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/integration_smoke.cmake")
  set(integration_environment "QT_QPA_PLATFORM=offscreen")
  if(GOZAR_REAL_CORE)
    list(APPEND integration_environment "GOZAR_CI_TEST_CORE=${GOZAR_REAL_CORE}")
  endif()
  set_tests_properties(gozar_integration_smoke PROPERTIES ENVIRONMENT "${integration_environment}")
endif()
