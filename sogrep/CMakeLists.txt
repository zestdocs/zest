cmake_minimum_required(VERSION 3.3)
project(sogrep)

find_package(PkgConfig)
pkg_search_module(XERCES REQUIRED xerces-c)
pkg_search_module(LIBARCHIVE REQUIRED libarchive)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11 -L${XERCES_LIBRARY_DIRS} -L${LIBARCHIVE_LIBRARY_DIRS}")

link_directories(
        "/usr/local/lib"
        ${LIBARCHIVE_LIBRARY_DIRS}
        ${XERCES_LIBRARY_DIRS}
)
add_executable(sogrep main.cpp)
add_executable(extractor extractor.cpp)
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

target_link_libraries(
        extractor
        ${LIBARCHIVE_LIBRARIES}
)
target_include_directories(
        extractor
        PUBLIC
        "/usr/local/include"
        ${LIBARCHIVE_INCLUDE_DIRS}
)
target_compile_options(
        extractor
        PUBLIC
        ${LIBARCHIVE_CFLAGS_OTHER}
)
