#!/bin/bash

cd $HOME
mkdir -p $HOME/installprefix/lib/pkgconfig

if [ ! -d $HOME/installprefix/include/rapidjson ]; then
    git clone https://github.com/miloyip/rapidjson.git
    cd rapidjson
    git checkout v1.0.2
    mkdir -p $HOME/installprefix/include
    cp -r include/rapidjson $HOME/installprefix/include
    cd ..
fi

if [ ! -d "$HOME/LucenePlusPlus" ]; then
    git clone https://github.com/jkozera/LucenePlusPlus.git
    cd LucenePlusPlus
    mkdir build
    cd build
    cmake .. -DCMAKE_INSTALL_PREFIX:PATH=$HOME/installprefix
    make install
else
    cd LucenePlusPlus/build
fi
make install
cp liblucene++*.pc $HOME/installprefix/lib/pkgconfig
cd ../..

if [ ! -d "$HOME/libarchive" ]; then
    git clone https://github.com/libarchive/libarchive.git
    cd libarchive
    git checkout 884f82a93ee5eb932b1e0fb74b6893708a43dc6d
    mkdir -p zest_build/installprefix
    cd zest_build
    cmake .. -DENABLE_OPENSSL=OFF -DCMAKE_INSTALL_PREFIX:PATH=$HOME/installprefix
else
    cd libarchive/zest_build
fi
make install
cd ../..

if [ ! -d "$HOME/node-sqlite3" ]; then
    git clone https://github.com/jkozera/node-sqlite3.git
    cd node-sqlite3
    npm install
    cd deps
    tar -zxvf sqlite-autoconf-3090100.tar.gz
    cd ../..
fi

if [ ! -d "$HOME/patchelf-0.9" ]; then
    wget https://github.com/NixOS/patchelf/archive/0.9.tar.gz
    tar -zxvf 0.9.tar.gz
    cd patchelf-0.9
    ./bootstrap.sh
    ./configure
    make
    cd ..
fi

~/patchelf-0.9/src/patchelf --set-rpath '$ORIGIN/resources:$ORIGIN' installprefix/lib/x86_64-linux-gnu/liblucene++.so
~/patchelf-0.9/src/patchelf --set-rpath '$ORIGIN/resources:$ORIGIN' installprefix/lib/x86_64-linux-gnu/liblucene++-contrib.so