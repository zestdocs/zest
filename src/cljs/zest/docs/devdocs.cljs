(ns zest.docs.devdocs
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as reagent]
            [cljs.core.async :as async]
            [zest.docs.registry]
            [medley.core :refer [interleave-all]]))

(def docset-cache (js-obj))
(def docset-db-cache (js-obj))

(defn get-from-cache [dir]
  (if (nil? (aget docset-cache dir))
    (let [path (.require js/window "path")
          fs (.require js/window "fs")
          devdocs-root (zest.docs.registry/get-devdocs-docs-root)
          json (.parse js/JSON
                       (.readFileSync
                         (.require js/window "fs")
                         (.join path devdocs-root dir "index.json") "utf8"))]


      (aset docset-cache dir json)
      (aset docset-db-cache dir
            (.parse js/JSON (.readFileSync
                              fs
                              (.join path devdocs-root dir "db.json")
                              "utf8")))
      json)
    (aget docset-cache dir)))


(defn get-entries [dir]
  (map #(js-obj "docset" dir "contents" %)
       (.-entries (zest.docs.devdocs/get-from-cache dir))))

(defn get-all-entries [data]
  ; interleave to give each docset equal chance of being found
  (apply interleave-all (map (fn [x] (get-entries x)) data)))

(def entries
  (reagent/atom (get-all-entries @zest.docs.registry/installed-devdocs-atom)))
