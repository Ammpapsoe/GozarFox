# Consolidated essential checks. Fail immediately and preserve each tool's
# diagnostic output; never substitute the fake core for a real-core check.
function(run_check name executable)
  message(STATUS "Integration check: ${name}")
  execute_process(COMMAND "${executable}" ${ARGN} RESULT_VARIABLE result TIMEOUT 45)
  if(NOT "${result}" STREQUAL "0")
    message(FATAL_ERROR "${name} failed: ${result}")
  endif()
endfunction()

run_check("core transport and cleanup" "${COREBRIDGE_TEST}" "${FAKE_CORE}")
if(DEFINED REAL_CORE AND NOT REAL_CORE STREQUAL "")
  run_check("real core state and shutdown" "${REALCORE_TEST}" "${REAL_CORE}")
endif()
if(DEFINED UPSTREAM_TEST AND NOT UPSTREAM_TEST STREQUAL "")
  run_check("upstream UI and server model" "${UPSTREAM_TEST}" "${FAKE_CORE}")
endif()
if(DEFINED QT_READONLY_PROBE AND NOT QT_READONLY_PROBE STREQUAL "" AND DEFINED REAL_CORE AND NOT REAL_CORE STREQUAL "")
  run_check("standalone original Qt home and signed-out real core" "${QT_READONLY_PROBE}" "--ci-readonly" "${REAL_CORE}")
endif()
if(DEFINED QT_SIMULATOR AND NOT QT_SIMULATOR STREQUAL "")
  run_check("simulated original Qt home, locations, mode and connection lifecycle" "${QT_SIMULATOR}" "--ci-simulate")
  run_check("interactive simulation startup and guarded close" "${QT_SIMULATOR}" "--ci-demo")
  # The fake reports signed-in. The signed-out-only harness must reject it,
  # close gracefully and exit with its explicit failure status, not crash.
  execute_process(COMMAND "${QT_SIMULATOR}" "--ci-real-signed-out" "${FAKE_CORE}"
    RESULT_VARIABLE rejected TIMEOUT 45)
  if(NOT "${rejected}" STREQUAL "1")
    message(FATAL_ERROR "signed-out-only harness did not reject signed-in state cleanly: ${rejected}")
  endif()
  if(DEFINED REAL_CORE AND NOT REAL_CORE STREQUAL "")
    run_check("integrated original widgets with isolated signed-out real core" "${QT_SIMULATOR}" "--ci-real-signed-out" "${REAL_CORE}")
  endif()
endif()
