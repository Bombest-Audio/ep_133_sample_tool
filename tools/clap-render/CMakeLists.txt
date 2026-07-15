cmake_minimum_required(VERSION 3.22)
project(clap-render VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# CLAP headers (MIT, header-only). Fetched at configure time; nothing is
# vendored into this repo.
include(FetchContent)
FetchContent_Declare(
    clap
    GIT_REPOSITORY https://github.com/free-audio/clap.git
    GIT_TAG 1.2.6
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(clap)

add_executable(clap-render
    src/main.cpp
    src/wav.cpp
)
target_link_libraries(clap-render PRIVATE clap)

if(APPLE)
    target_link_libraries(clap-render PRIVATE ${CMAKE_DL_LIBS})
elseif(UNIX)
    target_link_libraries(clap-render PRIVATE ${CMAKE_DL_LIBS})
endif()
