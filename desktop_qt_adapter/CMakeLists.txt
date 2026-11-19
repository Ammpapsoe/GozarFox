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
  add_test(NAME corebridge_test COMMAND corebridge_test $<TARGET_FILE:fake_core>)
  add_executable(realcore_smoke tests/realcore_smoke.cpp)
  target_link_libraries(realcore_smoke PRIVATE gozar_core_adapter Qt6::Test)
  set(GOZAR_REAL_CORE "" CACHE FILEPATH "Optional real Gozar core for isolated read-only smoke test")
  if(GOZAR_REAL_CORE)
    add_test(NAME realcore_smoke COMMAND realcore_smoke "${GOZAR_REAL_CORE}")
  endif()
endif()

# Deliberately optional: this smoke test compiles real upstream GUI code, but
# does not build a full application or claim distribution readiness.
option(BUILD_UPSTREAM_SMOKE_TEST "Build test with pinned Windscribe sources" OFF)
set(WINDSCRIBE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vendor/windscribe" CACHE PATH "Windscribe Desktop-App source root")
if(BUILD_UPSTREAM_SMOKE_TEST)
  if(NOT BUILD_TESTING OR NOT EXISTS "${WINDSCRIBE_ROOT}/src/client/frontend/gui/images.qrc")
    message(FATAL_ERROR "BUILD_TESTING and WINDSCRIBE_ROOT are required")
  endif()
  find_package(Qt6 REQUIRED COMPONENTS Widgets Svg)
  set(CMAKE_AUTORCC ON)
  set(WS_GUI "${WINDSCRIBE_ROOT}/src/client/frontend/gui")
  set(WS_COMMON "${WINDSCRIBE_ROOT}/src/client/client-common")
  add_executable(upstream_background_test
    homebinder.cpp homebinder.h originalhomebinding.h
    tests/upstream_background_test.cpp tests/upstream_shims/categories.cpp
    tests/upstream_pingtime.cpp
    tests/upstream_shims/backend/preferences/preferences.h
    tests/upstream_shims/languagecontroller.h
    ${WS_GUI}/images.qrc
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
  target_include_directories(upstream_background_test BEFORE PRIVATE tests/upstream_shims ${WS_GUI} ${WS_COMMON})
  target_link_libraries(upstream_background_test PRIVATE gozar_core_adapter Qt6::Widgets Qt6::Svg Qt6::Test)
  # Compile the binding against the real upstream window declaration, using a
  # backend stub. This does not compile or instantiate the entire window.
  add_library(original_window_header_compile OBJECT tests/original_window_header_compile.cpp)
  target_include_directories(original_window_header_compile BEFORE PRIVATE tests/upstream_shims ${WS_GUI} ${WS_COMMON})
  target_link_libraries(original_window_header_compile PRIVATE gozar_core_adapter Qt6::Widgets Qt6::Svg)
  add_test(NAME upstream_background_test COMMAND upstream_background_test $<TARGET_FILE:fake_core>)
  set_tests_properties(upstream_background_test PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")
endif()
