cmake_minimum_required(VERSION 2.8.12)
project(searcher)

# from https://cmake.org/pipermail/cmake/2008-January/019321.html
MACRO (APPEND_CMAKE_INSTALL_RPATH RPATH_DIRS)
   IF (NOT ${ARGC} EQUAL 1)
     MESSAGE(SEND_ERROR "APPEND_CMAKE_INSTALL_RPATH takes 1 argument")
   ENDIF (NOT ${ARGC} EQUAL 1)
   FOREACH ( RPATH_DIR ${RPATH_DIRS} )
     IF ( NOT ${RPATH_DIR} STREQUAL "" )
        FILE( TO_CMAKE_PATH ${RPATH_DIR} RPATH_DIR )
        STRING( SUBSTRING ${RPATH_DIR} 0 1 RPATH_FIRST_CHAR )
        IF ( NOT ${RPATH_FIRST_CHAR} STREQUAL "/" )
          # relative path; CMake handling for these is unclear,
          # add them directly to the linker line. Add both $ORIGIN
          # and $$ORIGIN to ensure correct behavior for exes and
          # shared libraries.
          SET ( RPATH_DIR "$ORIGIN/${RPATH_DIR}:$$ORIGIN/${RPATH_DIR}" )
          SET ( CMAKE_EXE_LINKER_FLAGS
                "${CMAKE_EXE_LINKER_FLAGS} -Wl,-rpath,'${RPATH_DIR}'" )
          SET ( CMAKE_SHARED_LINKER_FLAGS
                "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-rpath,'${RPATH_DIR}'" )
        ELSE ( NOT ${RPATH_FIRST_CHAR} STREQUAL "/" )
          # absolute path
          SET ( CMAKE_INSTALL_RPATH "${CMAKE_INSTALL_RPATH}:${RPATH_DIR}" )
        ENDIF ( NOT ${RPATH_FIRST_CHAR} STREQUAL "/" )
     ENDIF ( NOT ${RPATH_DIR} STREQUAL "" )
   ENDFOREACH ( RPATH_DIR )
ENDMACRO ( APPEND_CMAKE_INSTALL_RPATH )

APPEND_CMAKE_INSTALL_RPATH(".")

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
