cmake_minimum_required(VERSION 3.22)
project(EP133SampleTool VERSION 1.2.0)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Fetch JUCE 8 from GitHub at configure time
# ---------------------------------------------------------------------------
include(FetchContent)

FetchContent_Declare(
    JUCE
    GIT_REPOSITORY https://github.com/juce-framework/JUCE.git
    GIT_TAG        8.0.4
    GIT_SHALLOW    TRUE
)

FetchContent_MakeAvailable(JUCE)

# ---------------------------------------------------------------------------
# Plugin target
# ---------------------------------------------------------------------------
juce_add_plugin(EP133SampleTool
    COMPANY_NAME                "Bombest Audio"
    PLUGIN_MANUFACTURER_CODE    Bomb   # 4 chars, must be unique to your company
    PLUGIN_CODE                 EP13   # 4 chars, must be unique to this plugin
    FORMATS                     AU VST3
    PRODUCT_NAME                "EP-133 Sample Tool"
    DESCRIPTION                 "EP-133 / EP-1320 sample management tool"
    IS_MIDI_EFFECT              FALSE
    EDITOR_WANTS_KEYBOARD_FOCUS FALSE
    COPY_PLUGIN_AFTER_BUILD     FALSE
)

target_sources(EP133SampleTool
    PRIVATE
        Source/PluginProcessor.cpp
        Source/PluginEditor.cpp
)

target_compile_definitions(EP133SampleTool
    PUBLIC
        JUCE_WEB_BROWSER=1
        JUCE_USE_CURL=0
        JUCE_VST3_CAN_REPLACE_VST2=0
)

target_link_libraries(EP133SampleTool
    PRIVATE
        juce::juce_audio_utils
        juce::juce_gui_extra
    PUBLIC
        juce::juce_recommended_config_flags
        juce::juce_recommended_warning_flags
)

# ---------------------------------------------------------------------------
# Copy web assets into each plugin bundle after build (macOS only)
# The data/ directory sits one level above this CMakeLists.txt.
# ---------------------------------------------------------------------------
set(WEB_DATA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../data")

if(APPLE)
    foreach(FORMAT AU VST3)
        add_custom_command(
            TARGET EP133SampleTool_${FORMAT} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory
                "$<TARGET_BUNDLE_DIR:EP133SampleTool_${FORMAT}>/Contents/Resources"
            COMMAND ${CMAKE_COMMAND} -E copy_directory
                "${WEB_DATA_DIR}"
                "$<TARGET_BUNDLE_DIR:EP133SampleTool_${FORMAT}>/Contents/Resources/data"
            COMMENT "Copying web assets to EP133SampleTool_${FORMAT} bundle"
            VERBATIM
        )
    endforeach()
endif()
