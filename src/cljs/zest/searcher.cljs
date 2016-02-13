(ns zest.searcher
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]))

(def cur-searchers (atom {}))
(def cur-searchers-lines (atom nil))
(def searching (atom #{}))

(defn new-searcher [path]
  (let [child-process (.require js/window "child_process")
        last (atom "")
        ch (async/chan 100)
        jspath (.require js/window "path")
        searcher (.spawn child-process
                         (zest.core/get-binary-path "searcher")
                         (array path)
                         (js-obj
                           "env"
                           (js-obj
                             "PATH"
                             (str
                               (.dirname jspath (.dirname jspath (.-__dirname js/window)))
                               (.-delimiter jspath)
                               (.-PATH (.-env (.-process js/window)))))))]
    (reset! cur-searchers (assoc @cur-searchers path searcher))
    (reset! cur-searchers-lines (assoc @cur-searchers-lines path ch))
    (.on
      (.-stdout searcher)
      "data"
      (fn [data]
        (let [lines (.split (str @last (.toString data "utf8")) "\n")]
          (go-loop []
                   (if (> (.-length lines) 1)
                     (do
                       (async/>! ch (.shift lines))
                       (recur))
                     (reset! last (.shift lines)))))))))

(def search-num (atom 0))

(defn search [index query]
  (let [res (async/chan)
        res-data (array)
        cur-search-num (+ 1 @search-num)]
    (reset! search-num cur-search-num)
    (if (contains? @searching index)
      (do (.kill (get @cur-searchers index) "SIGKILL")
          (new-searcher index)))
    (reset! searching (conj @searching index))
    (.write (.-stdin (get @cur-searchers index)) (str query "\n"))
    (go-loop
      []
      ; avoid old queries hijacking new queries' lines
      ; or overwriting new queries' results
      (if (= cur-search-num @search-num)
        (let [line (async/<! (get @cur-searchers-lines index))]
          (if (not= 0 (.indexOf line "END"))
            (do (.push res-data line)
                (recur))
            ; again, avoid old queries overwriting later results
            (if (= cur-search-num @search-num)
              (async/>! res res-data)
              (reset! searching (disj @searching index)))))))
    res))

(defn stop-all-searchers []
  (doall (for [s (vals @cur-searchers)]
           (.kill s "SIGKILL"))))
