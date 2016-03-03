module.exports = function(grunt) {
'use strict';

var moment = require('moment'),
      path = require('path'),
  packager = require('electron-packager');

var os = (function(){
  var platform = process.platform;
  if (/^win/.test(platform))    { return "windows"; }
  if (/^darwin/.test(platform)) { return "mac"; }
  if (/^linux/.test(platform))  { return "linux"; }
  return null;
})();

var exe = {
  windows:  "electron.exe",
  mac:  "Electron.app/Contents/MacOS/Electron",
  linux:  "electron"
};

var electron_path = "electron";
var electron_version = "0.36.9";

var packageJson = require(__dirname + '/package.json');

//------------------------------------------------------------------------------
// ShellJS
//------------------------------------------------------------------------------

require('shelljs/global');
// shelljs/global makes the following imports:
//   cwd, pwd, ls, find, cp, rm, mv, mkdir, test, cat,
//   str.to, str.toEnd, sed, grep, which, echo,
//   pushd, popd, dirs, ln, exit, env, exec, chmod,
//   tempdir, error

var shellconfig = require('shelljs').config;
shellconfig.silent = false; // hide shell cmd output?
shellconfig.fatal = false;   // stop if cmd failed?

//------------------------------------------------------------------------------
// Grunt Config
//------------------------------------------------------------------------------


grunt.initConfig({

  'download-electron': {
    version: electron_version,
    outputDir: 'electron'
  }

});

//------------------------------------------------------------------------------
// Third-party tasks
//------------------------------------------------------------------------------


grunt.loadNpmTasks('grunt-download-electron');
if (os === "mac") {
  grunt.loadNpmTasks('grunt-appdmg');
}
grunt.loadNpmTasks('winresourcer');

//------------------------------------------------------------------------------
// Setup Tasks
//------------------------------------------------------------------------------

grunt.registerTask('setup', [
  'download-electron',
  'ensure-config-exists',
  'run-app-bower'
]);

grunt.registerTask('ensure-config-exists', function() {
  pushd("app");
  if (!test("-f", "config.json")) {
    grunt.log.writeln("Creating default config.json...");
    cp("example.config.json", "config.json");
  }
  popd();
});

grunt.registerTask('run-app-bower', function() {
  exec("bower install");
});

grunt.registerTask('cljsbuild-prod', function() {
  grunt.log.writeln("\nCleaning and building ClojureScript production files...");
  exec("lein do clean, with-profile production cljsbuild once");
});

grunt.registerTask('launch', function(async) {
  var IsAsync = (async == "true");
  grunt.log.writeln("\nLaunching development version...");
  var local_exe = exe[os];
  exec(path.join(electron_path, local_exe) + " app", {async:IsAsync});
});

grunt.registerTask('check-old', function() {
  grunt.log.writeln("\nChecking clojure dependencies");
  exec("lein ancient :all", {silent:false});
  grunt.log.writeln("\nChecking npm dependencies");
  exec("npm outdated", {silent:false});
  grunt.log.writeln("\nChecking bower dependencies");
  exec("bower list", {silent:false});
});

//------------------------------------------------------------------------------
// Test Tasks
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
// Release Helper functions
//------------------------------------------------------------------------------

function setReleaseConfig(build, paths) {
  grunt.log.writeln("\nRemoving config to force default release settings...");
  rm('-f', paths.releaseCfg);
  cp(paths.prodCfg, paths.releaseCfg);
}

function getBuildMeta() {
  grunt.log.writeln("Getting project metadata...");
  var tokens = cat("project.clj").split(" ");
  var build = {
    name:    tokens[1],
    version: tokens[2].replace(/"/g, "").trim(),
    date:    moment().format("YYYY-MM-DD")
  };
  var tag = exec("git tag --points-at HEAD", {silent:true}).output.trim();
  if (tag == 'v' + build.version) {
    build.commit = "";
  } else {
    build.commit = exec("git rev-list --max-count=1 HEAD", {silent:true}).output.trim();
  }
  build.releaseName = build.name + "-v" + build.version +
    (build.commit ? "-" + build.commit : "");
  return build;
}

function getReleasePaths(build) {
  var paths = {
    builds: "builds",
    devApp: "app",
    rootPkg: "package.json"
  };
  paths.release = path.join(paths.builds, build.releaseName);
  paths.devPkg = path.join(paths.devApp, "package.json");
  paths.prodCfg = path.join(paths.devApp, "prod.config.json");
  paths.releaseApp = path.join(paths.builds, paths.devApp);
  paths.releasePkg = path.join(paths.releaseApp, "package.json");
  paths.releaseCfg = path.join(paths.releaseApp, "config.json");
  paths.releaseResources = path.join(paths.releaseApp, "components");
  return paths;
}

function getBasicReleaseInfo(build, paths, platform) {
  var opts = {
    "dir": paths.releaseApp,
    "name": packageJson.name,
    "version": electron_version,
    "asar": true,
    "out": paths.release,
    "overwrite": true,
    "app-bundle-id": "org.zestdocs",
    "app-version": build.version,
    "version-string": {
      "ProductVersion": build.version,
      "ProductName": packageJson.name,
    }
  };
  if (platform == 'darwin' || platform == 'mas') {
    opts.name = opts.name.charAt(0).toUpperCase() + opts.name.slice(1);
  }
  return opts;
}

function stampRelease(build, paths) {
  grunt.log.writeln("\nStamping release with build metadata...");
  var pkg = grunt.file.readJSON(paths.releasePkg);
  pkg.version = build.version;
  pkg["build-commit"] = build.commit;
  pkg["build-date"] = build.date;
  JSON.stringify(pkg, null, "  ").to(paths.releasePkg);
}

function defineRelease(done, extra_opts, cb) {
  var callback = cb || (function () {});
  var build = getBuildMeta();
  var paths = getReleasePaths(build);
  var basic_opts = getBasicReleaseInfo(build, paths, extra_opts.platform);
  var opts = Object.assign(basic_opts, extra_opts);

  packager(opts, function(err, appPath) {
    if (err) {
      grunt.log.writeln("Error: ".red, err);
    }
    if (appPath) {
      if (Array.isArray(appPath)) {
        appPath.forEach(function(i) {
          callback(i);
          grunt.log.writeln("Build: " + i.cyan);
        });
      } else {
        callback(appPath);
        grunt.log.writeln("Build: " + appPath.cyan);
      }
    }
    done(err);
  });
}

function deleteExtraResources(paths) {
  rm('-rf', path.join(paths.releaseApp, "js", "p", "out"));
}


//------------------------------------------------------------------------------
// Tasks
//------------------------------------------------------------------------------

grunt.registerTask('release', ['cljsbuild-prod', 'prepare-release', 'release-linux']);

grunt.registerTask('cljsbuild-prod', function() {
  grunt.log.writeln("\nCleaning and building ClojureScript production files...");
  exec("lein do clean, with-profile production cljsbuild once");
});

grunt.registerTask('prepare-release', function() {
  var build = getBuildMeta();
  var paths = getReleasePaths(build);

  grunt.log.writeln("name:    "+build.name.cyan);
  grunt.log.writeln("version: "+build.version.cyan);
  grunt.log.writeln("date:    "+build.date.cyan);
  grunt.log.writeln("commit:  "+build.commit.cyan);
  grunt.log.writeln("release: "+build.releaseName.cyan);

  mkdir('-p', paths.builds);

  if (test("-d", paths.releaseApp)) {
    rm('-r', paths.releaseApp);
  }

  if (test("-d", paths.release)) {
    rm('-rf', paths.release);
  }

  //copy app folder
  cp('-r', paths.devApp, paths.builds);

  grunt.log.writeln("\nCopying node dependencies to release...");
  cp('-f', paths.rootPkg, paths.releaseApp);
  pushd(paths.releaseApp);
  exec('npm install --no-optional --production --silent');
  popd();
  cp('-f', paths.devPkg, paths.releaseApp);

  deleteExtraResources(paths);
  stampRelease(build, paths);
  setReleaseConfig(build, paths);
});

grunt.registerTask('release-linux', function() {
  var done = this.async();
  var opts = {
    "arch": ["x64"],
    "platform": "linux"
  }
  defineRelease(done, opts);
});

grunt.registerTask('makensis', function() {
  grunt.log.writeln("\nCreating installer...");
  var config = grunt.config.get("makensis");

  var ret = exec(["makensis",
                  "-DPRODUCT_VERSION=" + config.version,
                  "-DRELEASE_DIR=" + config.releaseDir,
                  "-DOUTFILE=" + config.outFile,
                  "scripts/build-windows-exe.nsi"].join(" "));

  if(ret.code === 0) {
    // grunt.log.writeln("\nInstaller created. Removing win32 folder:", config.releaseDir.cyan);
    // rm('-rf', config.releaseDir);
  }
});


grunt.registerTask('release-win', function() {
  var done = this.async();
  var build = getBuildMeta();
  var cb = function (appPath) {
    if (which("makensis")) {
      var dirName = path.join(appPath, "..");
      var exeName = path.join(dirName, path.basename(dirName) + ".exe");
      grunt.config.set("makensis", {
        version: build.version,
        releaseDir: path.resolve(appPath), // absolute paths required on linux
        outFile: path.resolve(exeName)
      });
      grunt.task.run("makensis");
    }
    else {
        grunt.log.writeln("\nSkipping windows installer creation:", "makensis not installed or not in path".cyan);
    }
  };
  cb('builds/'+build.releaseName+'/zest-win32-x64');
  done();
});
grunt.registerTask('prepare-win', function() {
  var done = this.async();
  var opts = {
    "arch": ["x64"],
    "platform": "win32",
    "icon": "app/img/logo.ico"
  }
  defineRelease(done, opts, function() { });
});

grunt.registerTask('release-mac', function() {
  var done = this.async();
  var cb = null;
  if (os === "mac") {
    cb = function (f) {
      var dirName = path.join(f, "..")
      var dmgName = path.join(dirName, path.basename(dirName) + ".dmg");
      grunt.config.set("appdmg", {
        options: {
          "title": "zest",
          "background": "scripts/dmg/TestBkg.png",
          "icon-size": 80,
          "contents": [
            { "x": 448, "y": 344, "type": "link", "path": "/Applications" },
            { "x": 192, "y": 344, "type": "file", "path": path.join(f, packageJson.name + ".app") }
          ]
        },
        target: {
          dest: dmgName
        }
      });
      grunt.task.run("appdmg");
    }
  }
  var opts = {
    "arch": "x64",
    "platform": "darwin",
    "icon": "app/img/logo.icns"
  }
  defineRelease(done, opts, cb);
});


//------------------------------------------------------------------------------
// Default Task
//------------------------------------------------------------------------------

grunt.registerTask('default', ['setup']);

// end module.exports
};
