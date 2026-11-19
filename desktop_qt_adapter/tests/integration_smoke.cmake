# Consolidated essential checks. Fail immediately and preserve each tool's
# diagnostic output; never substitute the fake core for a real-core check.
function(run_check name executable argument)
  message(STATUS "Integration check: ${name}")
  execute_process(COMMAND "${executable}" "${argument}" RESULT_VARIABLE result)
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
