var electron = require('electron'),
    fs = require('fs-extra'),
    path = require('path'),
    shell = require('shell'),
    packageJson = require(__dirname + '/package.json');

var ipc = electron.ipcMain;
var app = electron.app;
var dialog = electron.dialog;
var BrowserWindow = electron.BrowserWindow;
var Menu = electron.Menu;

// Report crashes to atom-shell.
require('crash-reporter').start();

const devConfigFile = __dirname + '/config.json';
var devConfig = {};
if (fs.existsSync(devConfigFile)) {
  devConfig = require(devConfigFile);
}


const isDev = (packageJson.version.indexOf("DEV") !== -1);
const onMac = (process.platform === 'darwin');
const acceleratorKey = onMac ? "Command" : "Control";
const isInternal = (devConfig.hasOwnProperty('internal') && devConfig['internal'] === true);



// Keep a global reference of the window object, if you don't, the window will
// be closed automatically when the javascript object is GCed.
var mainWindow = null;

// make sure app.getDataPath() exists
// https://github.com/oakmac/cuttle/issues/92
fs.ensureDirSync(app.getPath('userData'));


//------------------------------------------------------------------------------
// Main
//------------------------------------------------------------------------------

const versionString = "Version   " + packageJson.version + "\nDate       " + packageJson["build-date"] + "\nCommit  " + packageJson["build-commit"];


function showVersion() {
  dialog.showMessageBox({type: "info", title: "Version", buttons: ["OK"], message: versionString});
}

var fileMenu = {
  label: 'File',
  submenu: [
  {
    label: 'Settings...',
    accelerator: acceleratorKey + '+,',
    click: function () {
      mainWindow.webContents.executeJavaScript('window.showSettings()');
    }
  },
  { type: 'separator' },
  {
    label: 'Quit',
    accelerator: acceleratorKey + '+Q',
    click: function ()
    {
      app.quit();
    }
  }]
};

var editMenu = {
 label: "Edit",
 submenu: [
   { label: "Undo", accelerator: "CmdOrCtrl+Z", selector: "undo:" },
   { label: "Redo", accelerator: "Shift+CmdOrCtrl+Z", selector: "redo:" },
   { type: "separator" },
   { label: "Cut", accelerator: "CmdOrCtrl+X", selector: "cut:" },
   { label: "Copy", accelerator: "CmdOrCtrl+C", selector: "copy:" },
   { label: "Paste", accelerator: "CmdOrCtrl+V", selector: "paste:" },
   { label: "Select All", accelerator: "CmdOrCtrl+A", selector: "selectAll:" },
   { type: "separator" },
   { label: "Find", accelerator: "CmdOrCtrl+F", selector: "find:" ,
     click: function () {
       mainWindow.webContents.executeJavaScript('window.showFind()');
     }
   }
 ]
};


var helpMenu = {
  label: 'Help',
  submenu: [
  {
    label: 'Version',
    click: showVersion
  }]
};

var debugMenu = {
  label: 'Debug',
  submenu: [
  {
    label: 'Toggle DevTools',
    click: function ()
    {
      mainWindow.toggleDevTools();
    }
  }
  ]
};

var menuTemplate = [fileMenu, editMenu, debugMenu, helpMenu];


// NOTE: not all of the browserWindow options listed on the docs page work
// on all operating systems
const browserWindowOptions = {
  height: 850,
  title: 'zest',
  width: 1400,
  icon: __dirname + '/img/logo_96x96.png'
};


//------------------------------------------------------------------------------
// Register IPC Calls from the Renderers
//------------------------------------------------------------------------------


//------------------------------------------------------------------------------
// Ready
//------------------------------------------------------------------------------

app.processCmdLine = function(commandLine) {
  if (commandLine.length > 2 &&
        commandLine[commandLine.length - 2] == '--query') {
    var query = commandLine[commandLine.length - 1];
    var docsets = query.split(':', 2)[0];
    query = query.split(':', 2)[1];
    mainWindow.webContents.executeJavaScript('setQuery(' +
        JSON.stringify(query) + ', ' +
        JSON.stringify(docsets) +
    ')');
  } else if (commandLine[commandLine.length - 1].indexOf('dash-plugin://') == 0) {
    var url = commandLine[commandLine.length - 1];
    if (url.indexOf('?') != url.indexOf('//') + 2) {
        // fix for missing question mark from some plugins
        url = url.replace('//', '//?');
    }
    var parsed = require('url').parse(url, true);
    mainWindow.webContents.executeJavaScript('setQuery(' +
        JSON.stringify(parsed.query.query) + ', ' +
        (parsed.query.keys ? JSON.stringify(parsed.query.keys) : 'null') +
    ')');
  }
}

var shouldQuit = app.makeSingleInstance(function(commandLine, workingDirectory) {
  // Someone tried to run a second instance, we should focus our window.

  app.processCmdLine(commandLine);

  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  }
  return true;
});

if (shouldQuit) {
  app.quit();
  return;
}


// This method will be called when atom-shell has done everything
// initialization and ready for creating browser windows.
app.on('ready', function() {
  // Create the browser window.
  mainWindow = new BrowserWindow(browserWindowOptions);

  // and load the index.html of the app.
  mainWindow.loadURL('file://' + __dirname + '/index.html');

  var menu = Menu.buildFromTemplate(menuTemplate);

  Menu.setApplicationMenu(menu);

  // Emitted when the window is closed.
  mainWindow.on('closed', function() {
    // Dereference the window object, usually you would store windows
    // in an array if your app supports multi windows, this is the time
    // when you should delete the corresponding element.
    mainWindow = null;
    app.quit();
  });

  if (devConfig.hasOwnProperty('dev-tools') && devConfig['dev-tools'] === true) {
    mainWindow.openDevTools();
  }

});
