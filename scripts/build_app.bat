call "%VS120COMNTOOLS%\..\..\VC\vcvarsall.bat" x86_amd64

call lein self-install
call grunt cljsbuild-prod
call grunt prepare-release

mkdir builds\app\node_modules\sqlite3\lib\binding\node-v47-win32-x64
copy node_modules\sqlite3\lib\binding\node-v47-win32-x64\node_sqlite3.node builds\app\node_modules\sqlite3\lib\binding\node-v47-win32-x64\node_sqlite3.node
copy node_modules\sqlite3\lib\*.js builds\app\node_modules\sqlite3\lib\
copy node_modules\sqlite3\package.json builds\app\node_modules\sqlite3
copy node_modules\sqlite3\sqlite3.js builds\app\node_modules\sqlite3
cd builds\app\node_modules\sqlite3
call npm install
cd c:\zest_build

mkdir builds\app\node_modules\leveldown\build\Release
copy node_modules\leveldown\build\Release\leveldown.node builds\app\node_modules\leveldown\build\Release
copy node_modules\leveldown\*.js builds\app\node_modules\leveldown

copy node_modules\nodelucene.node builds\app\node_modules

call grunt prepare-win

cd builds\zest-v*
mkdir zest-win32-x64\resources\sqlite_score

copy ..\..\sqlite_score\zest_score.sqlext zest-win32-x64\resources\sqlite_score
copy ..\..\sogrep-src\Release\*.exe zest-win32-x64\resources
copy ..\..\nodelucene\Release\searcher.exe zest-win32-x64\resources
copy "..\..\LucenePlusPlus\build\src\core\Release\lucene++.dll" zest-win32-x64
copy "..\..\LucenePlusPlus\build\src\contrib\Release\lucene++-contrib.dll" zest-win32-x64
copy ..\..\xercesc.redist.3.1.1\build\native\bin\x64\v110\Release\xerces-c_3_1.dll zest-win32-x64
copy ..\..\libarchive\zest_build\bin\Release\archive.dll zest-win32-x64
copy ..\..\bzip2.v120.1.0.6.2\build\native\bin\x64\Release\bzip2.dll zest-win32-x64

copy "C:\Program Files (x86)\Microsoft Visual Studio 11.0\VC\redist\x64\Microsoft.VC110.CRT\msvcr110.dll" zest-win32-x64
copy C:\Libraries\boost_1_59_0\stage\lib\boost_filesystem-vc120-mt-1_59.dll zest-win32-x64
copy C:\Libraries\boost_1_59_0\stage\lib\boost_regex-vc120-mt-1_59.dll zest-win32-x64
copy C:\Libraries\boost_1_59_0\stage\lib\boost_system-vc120-mt-1_59.dll zest-win32-x64
copy C:\Libraries\boost_1_59_0\stage\lib\boost_thread-vc120-mt-1_59.dll zest-win32-x64
copy C:\Libraries\boost_1_59_0\stage\lib\boost_chrono-vc120-mt-1_59.dll zest-win32-x64
copy C:\Libraries\boost_1_59_0\stage\lib\boost_date_time-vc120-mt-1_59.dll zest-win32-x64

cd ..\..

call choco install nsis.install -pre -y
set PATH=C:\Program Files (x86)\NSIS\Bin;%PATH%
call grunt release-win
