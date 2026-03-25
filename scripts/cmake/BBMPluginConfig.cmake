# BBMPluginConfig.cmake
# Bombest Build Manager: dev/alpha builds get versioned plugin IDs for side-by-side loading in DAWs.
# Include this before juce_add_plugin. Requires BBM_BUILD_TYPE and BBM_VERSION (passed by BBM via -D).
# Optional: set BBM_BASE_PRODUCT_NAME and BBM_BASE_PLUGIN_CODE before include to override auto-derivation.

if(NOT DEFINED BBM_BASE_PRODUCT_NAME)
  string(REGEX REPLACE "([a-z])([A-Z])" "\\1 \\2" BBM_BASE_PRODUCT_NAME "${PROJECT_NAME}")
endif()

if(NOT DEFINED BBM_BASE_PLUGIN_CODE)
  string(SUBSTRING "${PROJECT_NAME}" 0 4 BBM_BASE_PLUGIN_CODE)
endif()

if(NOT DEFINED BBM_BUILD_TYPE OR NOT DEFINED BBM_VERSION)
  set(BBM_PLUGIN_CODE "${BBM_BASE_PLUGIN_CODE}")
  set(BBM_PRODUCT_NAME "${BBM_BASE_PRODUCT_NAME}")
elseif(BBM_BUILD_TYPE STREQUAL "dev" OR BBM_BUILD_TYPE STREQUAL "alpha")
  string(REGEX REPLACE "^[0-9]+\\.([0-9]+)\\.([0-9]+)$" "\\2" _BBM_PATCH "${BBM_VERSION}")
  string(LENGTH "${_BBM_PATCH}" _BBM_PATCH_LEN)
  if(_BBM_PATCH_LEN EQUAL 1)
    set(_BBM_PATCH "0${_BBM_PATCH}")
  endif()
  string(SUBSTRING "${PROJECT_NAME}" 0 1 _BBM_FIRST)
  string(TOUPPER "${_BBM_FIRST}" _BBM_FIRST)
  if(BBM_BUILD_TYPE STREQUAL "dev")
    set(BBM_PLUGIN_CODE "${_BBM_FIRST}${_BBM_PATCH}d")
  else()
    set(BBM_PLUGIN_CODE "${_BBM_FIRST}${_BBM_PATCH}a")
  endif()
  set(BBM_PRODUCT_NAME "${BBM_BASE_PRODUCT_NAME} ${BBM_VERSION}-${BBM_BUILD_TYPE}")
else()
  set(BBM_PLUGIN_CODE "${BBM_BASE_PLUGIN_CODE}")
  set(BBM_PRODUCT_NAME "${BBM_BASE_PRODUCT_NAME}")
endif()