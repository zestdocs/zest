#!/bin/bash

# exit on errors
set -e

cd "`dirname $0`/.."

echo; echo "Installing node dependencies..."
if [[ "$OSTYPE" == "darwin"* ]]; then
  npm install
else
  # "grunt-appdmg" is a mac-only dependency that will fail to build on linux.
  # So we are including it as an optionalDependency in package.json
  # and preventing its installation with npm's --no-optional flag.
  npm install --no-optional
fi

ELECTRON_VERSION=$(grep 'electron_version = ' Gruntfile.js  | cut -d '"' -f 2)
HOME=~/.electron-gyp node-gyp rebuild --target=$ELECTRON_VERSION --arch=x64 --dist-url=https://atom.io/download/atom-shell
pushd node_modules/leveldown
HOME=~/.electron-gyp node-gyp rebuild --target=$ELECTRON_VERSION --arch=x64 --dist-url=https://atom.io/download/atom-shell
popd

echo; echo "Installing grunt and bower..."

npm install -g grunt-cli bower

grunt setup

echo; echo "Setup complete."
