cmake_minimum_required(VERSION 3.22.1)
project(letramc_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

# whisper.cpp se descarga en la carpeta whisper.cpp (lo hace el workflow de GitHub)
add_subdirectory(whisper.cpp ${CMAKE_CURRENT_BINARY_DIR}/whisper_build)

add_library(letramc_whisper SHARED whisper_jni.cpp)

target_include_directories(letramc_whisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/ggml/include)

find_library(log-lib log)
target_link_libraries(letramc_whisper PRIVATE whisper ${log-lib})
target_link_options(letramc_whisper PRIVATE -Wl,-z,max-page-size=16384)
