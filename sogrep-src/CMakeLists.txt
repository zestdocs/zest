cmake_minimum_required(VERSION 2.8.12)
project(sogrep)

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
        # For rapidjson from Circle CI:
        "/home/ubuntu/installprefix/include"
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
