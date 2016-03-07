call "%VS120COMNTOOLS%\..\..\VC\vcvarsall.bat" x86_amd64


REM ## 1. zlib
REM ##########
c:\msys64\usr\bin\curl --silent -O http://zlib.net/zlib-1.2.8.tar.gz
c:\msys64\usr\bin\pacman --noconfirm -S tar gzip unzip wget
c:\msys64\usr\bin\gzip -d zlib-1.2.8.tar.gz
c:\msys64\usr\bin\tar -xvf zlib-1.2.8.tar


REM ## 2. 64bit Boost
REM #################
cd \Libraries\boost_1_59_0

call bootstrap
b2 --prefix=c:\Libraries\boost_1_59_0 --with-filesystem --with-regex --with-system --with-thread --with-chrono --with-date_time address-model=64 link=shared variant=release runtime-link=shared
b2 --prefix=c:\Libraries\boost_1_59_0 -sZLIB_SOURCE=c:\zest_build\zlib-1.2.8 --with-iostreams address-model=64 link=static variant=release runtime-link=shared

set BOOST_ROOT=C:\Libraries\boost_1_59_0
set BOOST_LIBRARYDIR=C:\Libraries\boost_1_59_0\stage\lib

REM ## 3. LucenePlusPlus
REM ####################
cd \zest_build

if not exist "LucenePlusPlus\build\src\core\Release\lucene++.dll" goto build_lucene
if not exist "LucenePlusPlus\build\src\contrib\Release\lucene++-contrib.dll" goto build_lucene
goto skip_lucene

:build_lucene
git clone https://github.com/jkozera/LucenePlusPlus.git
copy /y LucenePlusPlusCMakeLists.txt.appveyor LucenePlusPlus\CMakeLists.txt
cd LucenePlusPlus
mkdir build
cd build

cmake .. -DCMAKE_GENERATOR_PLATFORM=x64 -DCMAKE_CXX_FLAGS="/D NOMINMAX /D LPP_HAVE_DLL"

msbuild src\core\lucene++.vcxproj /p:Configuration=Release
msbuild src\contrib\lucene++-contrib.vcxproj /p:Configuration=Release
cd ..\..

:skip_lucene

mkdir bzip2_prefix\include
mkdir bzip2_prefix\lib

copy bzip2.v120.1.0.6.2\build\native\include\bzlib.h bzip2_prefix\include
copy bzip2.v120.1.0.6.2\build\native\lib\x64\Release\libbz2.lib bzip2_prefix\lib

if not exist libarchive\zest_build\bin\Release\archive.dll goto build_libarchive
goto skip_libarchive

:build_libarchive
git clone https://github.com/libarchive/libarchive.git
cd libarchive
git checkout 884f82a93ee5eb932b1e0fb74b6893708a43dc6d
mkdir zest_build
cd zest_build
cmake .. -DCMAKE_GENERATOR_PLATFORM=x64 -DCMAKE_PREFIX_PATH=c:/zest_build/bzip2_prefix -DBZIP2_LIBRARIES=c:/zest_build/bzip2_prefix/lib/libbz2.lib -DENABLE_OPENSSL=OFF
msbuild libarchive\archive.vcxproj /p:Configuration=Release
cd ..\..

:skip_libarchive

copy /y LevelDB.1.16.0.5\lib\native\src\util\crc32c.cc LevelDB.1.16.0.5\lib\native\src\port\win\crc32c_win.cc
cd sogrep-src
copy /y CMakeLists.txt.appveyor CMakeLists.txt
cmake . -DCMAKE_GENERATOR_PLATFORM=x64 -DCMAKE_CXX_FLAGS="/D LEVELDB_PLATFORM_WINDOWS"
msbuild sogrep.vcxproj /p:Configuration=Release
msbuild extractor.vcxproj /p:Configuration=Release

cd ..
c:\msys64\usr\bin\wget --quiet https://github.com/atom/electron/releases/download/v0.36.7/electron-v0.36.9-win32-x64.zip
mkdir electron
cd electron
c:\msys64\usr\bin\unzip ..\electron-v0.36.9-win32-x64.zip

cd ..\nodelucene
copy /y CMakeLists.txt.appveyor CMakeLists.txt
cmake . -DCMAKE_GENERATOR_PLATFORM=x64
msbuild searcher.vcxproj /p:Configuration=Release

cd ..
copy /y binding.gyp.appveyor binding.gyp
set GYP_MSVS_VERSION=2013
call npm install --no-optional
call npm install -g node-gyp

set USERPROFILE=%USERPROFILE%\.electron-gyp
REM ## rebuild leveldown:
cd node_modules\leveldown
call node-gyp rebuild --target=0.36.9 --arch=x64 --dist-url=https://atom.io/download/atom-shell
REM ## rebuild nodelucene:
cd ..\..
call node-gyp rebuild --target=0.36.9 --arch=x64 --dist-url=https://atom.io/download/atom-shell

copy build\Release\nodelucene.node node_modules

git clone https://github.com/jkozera/node-sqlite3.git

cd node-sqlite3\deps
copy sqlite-autoconf-3090100.tar.gz sqlite-autoconf-3090100.2.tar.gz
c:\msys64\usr\bin\gzip -d sqlite-autoconf-3090100.2.tar.gz
c:\msys64\usr\bin\tar -xvf sqlite-autoconf-3090100.2.tar
cd c:\zest_build\sqlite_score
cl score.c /I ..\node-sqlite3\deps\sqlite-autoconf-3090100 -link -dll -out:zest_score.sqlext

cd ..
call npm install -g grunt-cli
call npm install -g bower
cd node-sqlite3
call npm install
cd ..
call npm install .\node-sqlite3

cd node_modules\sqlite3
call node-gyp configure --module_name=node_sqlite3 --module_path=../lib/binding/node-v47-win32-x64
call node-gyp rebuild --target=0.36.9 --arch=x64 --target_platform=win32 --dist-url=https://atom.io/download/atom-shell --module_name=node_sqlite3 --module_path=../lib/binding/node-v47-win32-x64
cd ..\..
copy /y node_modules\sqlite3\build\Release\node_sqlite3.node node_modules\sqlite3\lib\binding\node-v47-win32-x64

call grunt setup
call bower install

c:\msys64\usr\bin\wget --quiet https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein.bat
