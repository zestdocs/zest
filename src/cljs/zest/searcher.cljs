(ns zest.searcher
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]))

(def cur-searchers (atom {}))
(def cur-searcher-lines (atom {}))
(def searching (atom {}))

(defn new-searcher [path]
  (let [child-process (.require js/window "child_process")
        searcher (.spawn child-process "searcher" (array path))
        Lazy (.require js/window "lazy")]
    (reset! cur-searchers (assoc @cur-searchers path searcher))
    (reset! cur-searcher-lines
            (assoc @cur-searcher-lines
              path
              (.-lines (Lazy. (.-stdout searcher)))))))

(def search-num (atom 0))

(defn search [index query]
  (let [res (async/chan)
        cur-search-num (+ 1 @search-num)]
    (reset! search-num (+ 1 @search-num))
    (.join
      (.map (.takeWhile
              (get @cur-searcher-lines index)
              (fn [l] (not (= "END" (.toString l "utf8")))))
            (fn [l] (.toString l "utf8")))
      (fn [ret]
        (go
          ; avoid old queries overwriting later results
          (if (= cur-search-num @search-num)
            (async/>! res ret))
          (reset! searching (dissoc @searching index)))))

    (if (contains? @searching index)
      (do (.kill (get @cur-searchers index) "SIGKILL")
          ; avoid spawning too many searchers that are not killed
          (if (= cur-search-num @search-num)
            (new-searcher index))))
    (reset! searching (assoc @searching index res))
    (.write (.-stdin (get @cur-searchers index)) (str query "\n"))
    res))

(defn stop-all-searchers []
  (for [s (vals @cur-searchers)]
    (.kill s "SIGKILL")))