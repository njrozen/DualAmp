cmake_minimum_required(VERSION 3.22)
project(dualamp_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# projectM: open-source MilkDrop reimplementation (LGPL-2.1). Built as a static library and
# linked into the JNI bridge. GLES3 is selected automatically when targeting Android.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
# projectM otherwise appends "d" to every library name in debug builds (libmilkbridged.so).
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/projectm EXCLUDE_FROM_ALL)

add_library(milkbridge SHARED milk_bridge.cpp albumartpreset.cpp)
target_link_libraries(milkbridge PRIVATE libprojectM::projectM GLESv3 EGL log android)
target_compile_options(milkbridge PRIVATE -Wall -O2)
set_target_properties(milkbridge PROPERTIES DEBUG_POSTFIX "")
