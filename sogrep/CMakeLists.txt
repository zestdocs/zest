cmake_minimum_required(VERSION 3.3)
project(sogrep)

find_package(PkgConfig)
pkg_search_module(XERCES REQUIRED xerces-c)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")

set(SOURCE_FILES main.cpp)

link_directories(
        "/usr/local/lib"
        ${XERCES_LIBRARY_DIRS}
)
add_executable(sogrep ${SOURCE_FILES})
target_link_libraries(
        sogrep
        leveldb
        ${XERCES_LIBRARIES}
)
target_include_directories(
        sogrep
        PUBLIC
        "/usr/local/include"
        ${XERCES_INCLUDE_DIRS}
)
target_compile_options(
        sogrep
        PUBLIC
        ${XERCES_CFLAGS_OTHER}
)