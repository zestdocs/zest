(ns zest.settings
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async]
            [reagent.core :as reagent]
            [zest.docs.registry]))

(def visible (reagent/atom false))
(def devdocs-visible (reagent/atom false))
(def so-visible (reagent/atom false))
(def downloading (reagent/atom {}))
(def so-indexing (reagent/atom false))
(def so-downloading (reagent/atom false))
(def so-download-progress (reagent/atom 0))
(def so-download-total (reagent/atom 0))
(def so-download-peers (reagent/atom 0))
(def so-dl-speed (reagent/atom 0))
(def so-ul-speed (reagent/atom 0))
(def so-grep-progress (reagent/atom nil))
(def so-index-progress (reagent/atom nil))
(def so-index-tags (reagent/atom #{}))
(def so-archives-total (reagent/atom nil))
(def so-archives-available
  (let [fs (.require js/window "fs")
        path (.require js/window "path")
        done-filename (.join path (zest.docs.registry/get-so-root)
                             "archive" "zest_done")]
    (reagent/atom
      (and
        (.existsSync fs done-filename)
        (.isFile (.statSync fs done-filename))))))

(def
  SO-URL
  "https://archive.org/download/stackexchange/stackexchange_archive.torrent")

(def suggested-tags
  ["javascript"
   "java"
   "c#"
   "php"
   "android"
   "jquery"
   "python"
   "django"
   "html"
   "c++"
   "ios"
   "objective-c"
   "clojure"])

(defn start-so-download []
  (let [WebTorrent (.require js/window "webtorrent")
        fs (.require js/window "fs")
        path (.require js/window "path")
        client (WebTorrent.)
        archive-dir (.join path (zest.docs.registry/get-so-root) "archive")]
    (reset! so-downloading true)
    (.add
      client
      SO-URL
      (js-obj "path" archive-dir)
      (fn [torrent]
        (.pause torrent)
        ;; torrent.deselect is broken - see https://github.com/feross/webtorrent/issues/164#issuecomment-107915164
        (aset torrent "_selections" (make-array 0))
        (doall
          (for [f (.-files torrent)]
            (if
              (contains?
                #{"stackoverflow.com-Posts.7z"
                  "stackoverflow.com-Comments.7z"
                  "stackoverflow.com-Users.7z"}
                (.-name f))
              (do (.select f)
                  (reset! so-download-total
                          (+ @so-download-total
                             (.-length f)))))))
        (.resume torrent)
        (.on torrent "wire"
             (fn []
               (reset! so-download-peers
                       (count (.-wires (.-swarm torrent))))))
        (.on torrent "download"
             (fn [chunkSize]
               (reset! so-download-progress
                       (.round js/Math (.-downloaded torrent)))
               (reset! so-dl-speed
                       (.round js/Math (/ (.-downloadSpeed torrent) 1000)))
               (reset! so-ul-speed
                       (.round js/Math (/ (.-uploadSpeed torrent) 1000)))))
        (.on torrent "done"
             (fn []
               (.writeFileSync
                 fs
                 (.join path archive-dir "zest_done")
                 "")
               (reset! so-archives-available true)))))))

(defn open-so-archive [name op]
  (let [child-process (.require js/window "child_process")
        path (.require js/window "path")]
    (.spawn
      child-process
      "7z"
      (array
        op
        "-so"
        (.join path (zest.docs.registry/get-so-root)
               "archive" "stackexchange" (str "stackoverflow.com-" name ".7z"))
        (str name ".xml")))))

(defn archive-size [name]
  (let [Lazy (.require js/window "lazy")
        ret (async/chan)]
    (.forEach (.-lines (Lazy. (.-stdout (open-so-archive name "l"))))
              (fn [line-buf]
                (if (not (undefined? line-buf))
                  (let [line (.toString line-buf "utf8")]
                    (if (not (= -1 (.indexOf line (str name ".xml"))))
                      (go
                        (.log js/console line)
                        (async/>! ret
                                  (.parseFloat
                                    js/window
                                    (nth (.split line #"\s+") 3)))))))))
    ret))

(defn start-so-indexing []
  (reset! so-index-progress 0)
  (let [levelup (.require js/window "levelup")
        path (.require js/window "path")
        fs (.require js/window "fs-extra")
        rimraf (.require js/window "rimraf")
        db (levelup.
             (.join path (zest.docs.registry/get-so-root) "new_index" "leveldb"))
        rStream (.createReadStream
                  db
                  (js-obj "gt" "p_"
                          "lt" "p_a"))

        LuceneIndex
        (.-LuceneIndex (.require js/window "../build/Release/nodelucene"))
        idx (LuceneIndex. (.join path (zest.docs.registry/get-so-root)
                                 "new_index" "lucene"))

        done (atom 0)
        started (atom 0)
        ended (atom false)
        check-all-done
        (fn []
          (if (and @ended (= @started @done))
            (do
              (.endWriting idx)
              (.sync rimraf (.join path (zest.docs.registry/get-so-root) "lucene"))
              (.sync rimraf (.join path (zest.docs.registry/get-so-root) "leveldb"))
              (.move
                fs
                (.join path (zest.docs.registry/get-so-root) "new_index" "lucene")
                (.join path (zest.docs.registry/get-so-root) "lucene")
                (fn []))
              (.move
                fs
                (.join path (zest.docs.registry/get-so-root) "new_index" "leveldb")
                (.join path (zest.docs.registry/get-so-root) "leveldb")
                (fn [])))))

        auf
        (fn [answer]
          (let [ret-data (atom (zest.core/unfluff (.-Body answer)))
                ret (async/chan)
                cStream (.createReadStream
                          db
                          (js-obj "gt" (str "c_" (.-Id answer) "_")
                                  "lt" (str "c_" (.-Id answer) "_a")))]
            (.on cStream "data"
                 (fn [v]
                   (let [comment (.parse js/JSON (.-value v))]
                     (reset! ret-data (str @ret-data " " (.-Text comment))))))

            (.on cStream "end"
                 (fn []
                   (go (async/>! ret @ret-data))))
            ret))

        quf
        (fn [data]
          (let [ret-data (atom (str (.-Title data) " "
                                    (zest.core/unfluff (.-Body data))))
                ret (async/chan)
                started (atom 0)
                finished (atom 0)
                ended (atom false)
                cStream (.createReadStream
                          db
                          (js-obj "gt" (str "c_" (.-Id data) "_")
                                  "lt" (str "c_" (.-Id data) "_a")))
                check-finished
                (fn []
                  (if (and (= @started @finished) @ended)
                    (go (async/>! ret @ret-data))))]

            (.on cStream "data"
                 (fn [v]
                   (let [comment (.parse js/JSON (.-value v))]
                     (reset! ret-data (str @ret-data " " (.-Text comment))))))

            (.on cStream "end"
                 (fn []
                   (let [aStream (.createReadStream
                                   db
                                   (js-obj "gt" (str "a_" (.-Id data) "_")
                                           "lt" (str "a_" (.-Id data) "_a")))]
                     (.on aStream "data"
                          (fn [v]
                            (reset! started (+ @started 1))
                            (let [answer (.parse js/JSON (.-value v))]
                              (go
                                (let [ans-data (async/<! (auf answer))]
                                  (reset! ret-data (str @ret-data ans-data))
                                  (reset! finished (+ @finished 1))
                                  (check-finished))))))
                     (.on aStream "end"
                          (fn []
                            (reset! ended true)
                            (check-finished))))))
            ret))]
    (.startWriting idx)
    (.on rStream "data"
         (fn [v]
           (let [data (.parse js/JSON (.-value v))]
             (reset! started (+ @started 1))
             (go (.addFile
                   idx
                   (str (.-Id data) ";" (.-Title data))
                   (async/<! (quf data)))
                 (reset! done (+ @done 1))
                 (if (= 0 (mod @done 100))
                   (reset! so-index-progress @done))
                 (check-all-done)))))
    (.on rStream "end"
         (fn []
           (reset! ended true)
           (check-all-done)))))

(defn start-so-grepping []
  (let [mkdirp (.require js/window "mkdirp")
        rimraf (.require js/window "rimraf")
        path (.require js/window "path")]
    (.sync rimraf (.join path (zest.docs.registry/get-so-root) "new_index"))
    (.sync mkdirp (.join path (zest.docs.registry/get-so-root) "new_index"))
    (go (let [comments-size (async/<! (archive-size "Comments"))
              posts-size (async/<! (archive-size "Posts"))
              child-process (.require js/window "child_process")
              Lazy (.require js/window "lazy")
              Buffer (.-Buffer (.require js/window "buffer"))
              sogrep (.spawn child-process "sogrep"
                             (apply array @so-index-tags)
                             (js-obj "cwd"
                                     (.join path
                                            (zest.docs.registry/get-so-root)
                                            "new_index")))
              p7zip-posts (open-so-archive "Posts" "x")]
          (reset! so-indexing true)
          (.pipe (.-stdout p7zip-posts)
                 (.-stdin sogrep)
                 (js-obj "end" false))
          (reset! so-archives-total (+ posts-size comments-size))
          (reset! so-grep-progress 0)
          (.on (.-stderr p7zip-posts) "data"
               (fn [data]
                 (.log js/console (.toString data "utf8"))))
          (.on sogrep "close"
               (fn [code]
                 (.log js/console "sogrep exited with code" code)
                 (if (= 0 code) (start-so-indexing))))
          (.on p7zip-posts "close"
               (fn [code]
                 (.log js/console "7z x posts exited with code" code)
                 (let [p7zip-comments (open-so-archive "Comments" "x")]
                   (.pipe (.-stdout p7zip-comments)
                          (.-stdin sogrep))
                   (.on p7zip-comments "close"
                        (fn [code]
                          (.log js/console
                                "7z x comments exited with code" code)))
                   (.write (.-stdin sogrep) (Buffer. (array 0))) ; null separator
                   )))
          (.forEach (.-lines (Lazy. (.-stdout sogrep)))
                    (fn [line]
                      (reset! so-grep-progress
                              (.parseFloat
                                js/window
                                (.toString line "utf8")))))))))

(defn so-widget []
  (if @so-archives-available
    [:div
     [:p "Suggested tags (select ones you want in your index):"]
     [:ul
      (doall (for [tag suggested-tags]
               (do ^{:key (str "so_tags_" tag)}
                   [:li
                    [:label
                     [:input {:type     "checkbox"
                              :checked  (get @so-index-tags tag)
                              :disabled @so-indexing
                              :on-change
                                        (fn []
                                          (if (nil? (get @so-index-tags tag))
                                            (reset! so-index-tags
                                                    (conj @so-index-tags tag))
                                            (reset! so-index-tags
                                                    (disj @so-index-tags tag))))}]
                     [:span {:class "checkable"} tag]]]
                   )))]
     (if @so-indexing
       [:p (str
             "Indexing : "
             (.toFixed
               (* 100 (/ (or @so-grep-progress 0)
                         (or @so-archives-total 1)))
               2)
             "% filtering, "
             (or @so-index-progress 0)
             " indexing")]
       [:button {:on-click (fn []
                             (reset! so-indexing true)
                             (start-so-grepping))}
        "Create index"])]

    [:div
     [:p "~8GB BitTorrent download required. (provided by archive.org)"]
     (if @so-downloading
       [:div
        [:p (str "Progress: " (if (= 0 @so-download-total)
                                0
                                (min 99
                                     ; never show 100% while downloading
                                     (.round js/Math
                                             (* 100 (/ @so-download-progress
                                                       @so-download-total)))))
                 "%")]
        [:p (str "Peers: " @so-download-peers
                 ", DL " @so-dl-speed "kB/s, UL " @so-ul-speed " kB/s")]]
       [:a
        {:on-click #(start-so-download)}
        "Click here to start."])]))

(defn download-devdocs [slug db-size]
  (let [request (.require js/window "request")
        db-request (.get request
                         (str "http://maxcdn-docs.devdocs.io/"
                              slug "/db.json"))
        mkdirp (.require js/window "mkdirp")
        fs (.require js/window "fs")
        path (.require js/window "path")
        index-done (atom false)
        index-data (atom)
        db-done (atom false)
        db-data (atom "")
        db-bytes (atom 0)
        doc-dir (.join path (zest.docs.registry/get-devdocs-root) slug)

        on-done
        (fn []
          (.sync mkdirp doc-dir)
          (.writeFileSync fs (.join path doc-dir "index.json") @index-data)
          (.writeFileSync fs (.join path doc-dir "db.json") @db-data)
          (reset! downloading (dissoc @downloading slug))
          (zest.docs.registry/update-installed-devdocs))]

    (reset! downloading (assoc @downloading slug 0))
    (request (str "http://maxcdn-docs.devdocs.io/" slug "/index.json")
             (fn [error response body]
               (reset! index-data body)
               (if @db-done (on-done)
                            (reset! index-done true))))
    (.on db-request "data"
         (fn [chunk]
           (reset! db-bytes (+ @db-bytes (.-length chunk)))
           (reset! db-data (str @db-data chunk))
           (reset! downloading
                   (assoc
                     @downloading
                     slug
                     (.round js/Math
                             (/ (* 100 @db-bytes) db-size))))))
    (.on db-request "end"
         (fn []
           (if @index-done (on-done)
                           (reset! db-done true))))))

(defn register-settings []
  (let [available-devdocs (reagent/atom [])
        installed-devdocs zest.docs.registry/installed-devdocs-atom
        path (.require js/window "path")
        rimraf (.require js/window "rimraf")
        shell (.require js/window "shell")]
    (set!
      (.-showSettings js/window)
      (fn []
        (reset! visible false)                              ; without setting to false, the sequence
        ; "show -> hide with ESC -> show" doesn't work
        (reset! visible true)))

    (if @visible
      (do
        (zest.docs.registry/get-available-devdocs
          (fn [docs] (reset! available-devdocs docs)))

        [:div
         {:class "modal"}
         [:input
          {:type      "checkbox"
           :checked   @visible
           :id        "settingsModal"
           :on-change (fn [e]
                        (let [v (-> e .-target .-checked)]
                          (reset! visible v)))}]
         [:label {:for "settingsModal" :class "overlay"}]
         [:article
          [:header
           [:h3 "Settings"]
           [:label {:for                     "settingsModal" :class "close"
                    :dangerouslySetInnerHTML {:__html "&times;"}}]]
          [:section
           [:div {:class "row"}
            [:div {:class "half"}
             [:h3 "Your documentation"]
             [:ul
              (doall (for [doc @installed-devdocs]
                       ^{:key (str "installeddoc_" doc)}
                       [:li (str doc " | ")
                        [:a
                         {:on-click
                          (fn []
                            (rimraf
                              (.join path
                                     (zest.docs.registry/get-devdocs-root)
                                     doc)
                              #(zest.docs.registry/update-installed-devdocs)))}
                         "remove"]]))]]
            [:div {:class "half"}
             [:h3 "Available documentation"]

             [:p "from " [:a {:on-click
                              #(.openExternal shell "http://devdocs.io/")}
                          "DevDocs - http://devdocs.io/"]]
             [:a {:on-click #(reset! devdocs-visible
                                     (not @devdocs-visible))}
              (if @devdocs-visible "hide" "show")]
             (if @devdocs-visible
               [:ul (doall (for [doc @available-devdocs]
                             (if (= -1 (.indexOf @installed-devdocs
                                                 (.-slug doc)))
                               ^{:key (str "availabledoc_" (.-slug doc))}
                               [:li
                                (if (contains? @downloading (.-slug doc))
                                  [:span (str "downloading " (.-slug doc) "... "
                                              (get @downloading (.-slug doc))
                                              "%")]
                                  [:a
                                   {:on-click #(download-devdocs
                                                (.-slug doc)
                                                (aget doc "db_size"))}
                                   (.-slug doc)])])))])

             [:p "from " [:a {:on-click
                              #(.openExternal shell "http://stackoverflow.com/")}
                          "Stack Overflow - http://stackoverflow.com/"]]
             [:a {:on-click #(reset! so-visible
                                     (not @so-visible))}
              (if @so-visible "hide" "show")]
             (if @so-visible (so-widget))]]]]])
      [:div])))