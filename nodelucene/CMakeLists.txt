cmake_minimum_required(VERSION 3.3)
project(searcher)

find_package(PkgConfig)
find_package(
        Boost COMPONENTS
        date_time
        filesystem
        iostreams
        regex
        system
        thread
        REQUIRED)
pkg_search_module(LUCENE REQUIRED liblucene++)
pkg_search_module(LUCENE_CONTRIB REQUIRED liblucene++-contrib)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")

set(SOURCE_FILES searcher.cc)

link_directories(
        ${LUCENE_LIBRARY_DIRS}
        ${LUCENE_CONTRIB_LIBRARY_DIRS}
)
add_executable(searcher ${SOURCE_FILES})
target_link_libraries(
        searcher
        ${LUCENE_LIBRARIES}
        ${LUCENE_CONTRIB_LIBRARIES}
        ${Boost_LIBRARIES}
        ${Boost_FILESYSTEM_LIBRARIES}
        ${Boost_IOSTREAMS_LIBRARIES}
        ${Boost_REGEX_LIBRARIES}
        ${Boost_SYSTEM_LIBRARIES}
        ${Boost_THREAD_LIBRARIES}
)
target_include_directories(
        searcher
        PUBLIC
        "/usr/local/include/node"
        ${LUCENE_INCLUDE_DIRS}
        ${LUCENE_CONTRIB_INCLUDE_DIRS}
        ${Boost_INCLUDE_DIRS}
)
target_compile_options(
        searcher
        PUBLIC
        ${LUCENE_CFLAGS_OTHER}
        ${LUCENE_CONTRIB_CFLAGS_OTHER}
)
