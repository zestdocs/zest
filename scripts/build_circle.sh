#!/bin/bash

ELECTRON_VERSION=$(grep 'electron_version = ' Gruntfile.js  | cut -d '"' -f 2)

cd nodelucene
PKG_CONFIG_PATH=$HOME/installprefix/lib/pkgconfig cmake .
make

cd ../sogrep-src
PKG_CONFIG_PATH=$HOME/installprefix/lib/pkgconfig cmake .
make

cd ..
npm install node-gyp
npm install --no-optional

# rebuild nodelucene:
HOME=~/.electron-gyp ./node_modules/.bin/node-gyp rebuild --target=$ELECTRON_VERSION --arch=x64 --dist-url=https://atom.io/download/atom-shell
# rebuild leveldown:
cd node_modules/leveldown
HOME=~/.electron-gyp ../.bin/node-gyp rebuild --target=$ELECTRON_VERSION --arch=x64 --dist-url=https://atom.io/download/atom-shell
cd ../..
# rebuild node_sqlite3:
npm install $HOME/node-sqlite3
cd node_modules/sqlite3
HOME=~/.electron-gyp ../.bin/node-gyp configure --module_name=node_sqlite3 --module_path=../lib/binding/node-v47-linux-x64
HOME=~/.electron-gyp ../.bin/node-gyp rebuild --target=$ELECTRON_VERSION --arch=x64 --target_platform=linux --dist-url=https://atom.io/download/atom-shell --module_name=node_sqlite3 --module_path=../lib/binding/node-v47-linux-x64
cd ../..
cp node_modules/sqlite3/build/Release/node_sqlite3.node node_modules/sqlite3/lib/binding/node-v47-linux-x64
# build zest_score.sqlext:
gcc -shared -fPIC -I$HOME/node-sqlite3/deps/sqlite-autoconf-3090100 -o zest_score.sqlext sqlite_score/score.c

npm install grunt-cli bower
./node_modules/.bin/grunt setup
./node_modules/.bin/bower install

./node_modules/.bin/grunt cljsbuild-prod
./node_modules/.bin/grunt prepare-release
cp build/Release/nodelucene.node builds/app/node_modules
cp -r node_modules/{sqlite3,leveldown} builds/app/node_modules
./node_modules/.bin/grunt release-linux

cp --no-dereference $HOME/installprefix/lib/lib*.so* builds/zest-v*/zest-linux-x64/resources
cp --no-dereference $HOME/installprefix/lib/x86_64-linux-gnu/lib*.so* builds/zest-v*/zest-linux-x64/resources
cp --no-dereference /usr/lib/x86_64-linux-gnu/libboost*.so* builds/zest-v*/zest-linux-x64/resources
cp --no-dereference /usr/lib/x86_64-linux-gnu/libleveldb*.so* builds/zest-v*/zest-linux-x64/resources
cp --no-dereference /usr/lib/x86_64-linux-gnu/libxerces*.so* builds/zest-v*/zest-linux-x64/resources
cp --no-dereference /usr/lib/x86_64-linux-gnu/libicuuc*.so* builds/zest-v*/zest-linux-x64/resources
cp --no-dereference /usr/lib/x86_64-linux-gnu/libicui18n*.so* builds/zest-v*/zest-linux-x64/resources
cp --no-dereference /usr/lib/x86_64-linux-gnu/libicudata*.so* builds/zest-v*/zest-linux-x64/resources
cp --no-dereference /usr/lib/libsnappy*.so* builds/zest-v*/zest-linux-x64/resources
cp sogrep-src/{extractor,sogrep} builds/zest-v*/zest-linux-x64/resources
cp nodelucene/searcher builds/zest-v*/zest-linux-x64/resources
cd builds/zest-v*
mkdir zest-linux-x64/resources/sqlite_score
cd ../..
cp zest_score.sqlext builds/zest-v*/zest-linux-x64/resources/sqlite_score

cd builds/zest-v*/zest-linux-x64
ln -s resources/liblucene++* .
ln -s resources/libicu* .
cd ..
mv zest-linux-x64 `basename $PWD`
tar -czvf `basename $PWD`-linux-x64.tar.gz `basename $PWD`

mv `basename $PWD`-linux-x64.tar.gz $CIRCLE_ARTIFACTS