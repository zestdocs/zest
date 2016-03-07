(defproject zest "0.1.1"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src/cljs"]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"]
                 [cljsjs/react "0.13.3-1"]
                 [cljsjs/nodejs-externs "1.0.4-1"]
                 [reagent "0.5.1"]
                 [medley "0.7.0"]]

  :plugins [[lein-cljsbuild "1.1.2"]]

  :min-lein-version "2.5.3"

  :cljsbuild {:builds {:app {:source-paths ["src/cljs"]
                             :compiler {:output-to     "app/js/p/app.js"
                                        :output-dir    "app/js/p/out"
                                        :asset-path    "js/p/out"
                                        :optimizations :none
                                        :pretty-print  true
                                        :cache-analysis true}}}}

  :clean-targets ^{:protect false} [:target-path "out" "app/js/p"]

  :figwheel {:css-dirs ["app/css"]}

  :profiles {:dev {:cljsbuild {:builds {:app {:source-paths ["env/dev/cljs"]
                                              :compiler {:source-map true
                                                         :main       "zest.dev"
                                                         :verbose true}
                                              :figwheel {:on-jsload "zest.core/on-figwheel-reload"}}}}
                   :source-paths ["env/dev/cljs"]

                   :dependencies [[figwheel-sidecar "0.5.0-3"]]

                   :plugins [[lein-ancient "0.6.8"]
                             [lein-kibit "0.1.2"]
                             [lein-cljfmt "0.3.0"]
                             [lein-figwheel "0.5.0-3"]]}

             :production {:cljsbuild {:builds {:app {:compiler {:optimizations :whitespace
                                                                :main          "zest.prod"
                                                                :parallel-build true
                                                                :cache-analysis false
                                                                :closure-defines {"goog.DEBUG" false}
                                                                :externs ["externs/misc.js"]
                                                                :pretty-print false}
                                                     :source-paths ["env/prod/cljs"]}}}}}
  )
