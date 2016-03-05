(ns zest.settings
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [reagent.core :as reagent]
            [zest.docs.registry]
            [zest.docs.devdocs]))

(def visible (reagent/atom false))
(def devdocs-visible (reagent/atom false))
(def devdocs-reindexing (reagent/atom false))
(def devdocs-reindexing-progress (reagent/atom ""))
(def so-visible (reagent/atom false))
(def downloading (reagent/atom {}))
(def downloading-error (reagent/atom {}))
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

(defn async-rimraf [path]
  (let [res (async/chan)
        rimraf (js/require "rimraf")]
    (go (rimraf path #(go (async/>! res (or % true)))))
    res))

(defn async-add-file [idx name contents]
  (let [ret (async/chan)]
    (.setImmediate
      js/window #(go (.addFile idx name contents)
                     (async/>! ret true)))
    ret))

(defn devdocs-rebuild-index []
  (reset! devdocs-reindexing true)
  (reset! devdocs-reindexing-progress "")
  (let [path (.require js/window "path")
        fs (.require js/window "fs-extra")
        LuceneIndex
        (.-LuceneIndex (.require js/window "nodelucene"))
        idx-chan (let [new-lucene-path (.join path
                                              (zest.docs.registry/get-devdocs-root)
                                              "new_lucene")]
                   (go (async/<! (async-rimraf new-lucene-path))
                       (LuceneIndex. new-lucene-path)))
        idx (atom nil)
        docs-count (count @zest.docs.registry/installed-devdocs-atom)
        all-count (if (> docs-count 0)
                    (inc docs-count) 0)
        indexed-count (atom 0)

        check-done
        (fn []
          (reset! indexed-count (inc @indexed-count))
          (if (= @indexed-count all-count)
            (go (.endWriting @idx)
                (async/<! (async-rimraf (.join path (zest.docs.registry/get-devdocs-root) "lucene")))
                (.move
                  fs
                  (.join path (zest.docs.registry/get-devdocs-root) "new_lucene")
                  (.join path (zest.docs.registry/get-devdocs-root) "lucene")
                  (fn []))
                (.close
                  @zest.core/symbol-db
                  (fn [] (go
                           (async/<! (async-rimraf (.join path (zest.docs.registry/get-devdocs-root) "symbols")))
                           (.move
                             fs
                             (.join path (zest.docs.registry/get-devdocs-root) "new_symbols")
                             (.join path (zest.docs.registry/get-devdocs-root) "symbols")
                             (fn []
                               (zest.core/open-symbol-db    ; update query results
                                 #(zest.docs.registry/update-installed-devdocs)))))))
                (doall (for [docset @zest.docs.registry/installed-devdocs-atom]
                         (.writeFileSync
                           fs
                           (.join path
                                  (zest.docs.registry/get-devdocs-docs-root)
                                  docset
                                  "is_indexed")
                           "")))
                (reset! devdocs-reindexing false))
            (reset! devdocs-reindexing-progress
                    (str @indexed-count "/" all-count))))]
    (if (> all-count 0)
      (do                                                   ; rebuild sqlite and lucene indices in parallel
        (go
          (async/<! (zest.core/rebuild-symbol-db))
          (check-done))
        (go
          (reset! idx (async/<! idx-chan))
          (.startWriting @idx)
          (doall
            (for [docset @zest.docs.registry/installed-devdocs-atom]
              (do
                (zest.docs.devdocs/get-from-cache docset)
                (let [db-cache (aget zest.docs.devdocs/docset-db-cache docset)
                      keys (.keys js/Object db-cache)
                      i (atom 0)]
                  (go-loop []
                           (if (< @i (.-length keys))
                             (do (async/<! (async-add-file
                                             @idx
                                             (str docset "/" (nth keys @i))
                                             (zest.docs.stackoverflow/unfluff
                                               (aget db-cache (nth keys @i)))))
                                 (reset! i (inc @i))
                                 (recur))
                             (check-done)))))))))
      (reset! devdocs-reindexing false))))


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

(defn top-tags []
  (let [fs (.require js/window "fs")
        path (.require js/window "path")
        filename (.join path (.-__dirname js/window) "sotags.json")]
    (.parse js/JSON (.readFileSync fs filename "utf8"))))

(def user-tags (reagent/atom []))

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

(defn run-so-extractor [additional-args]
  (let [child-process (.require js/window "child_process")
        path (.require js/window "path")]
    (.spawn
      child-process
      (zest.core/get-binary-path "extractor")
      (.concat
        (array
          (.join path (zest.docs.registry/get-so-root)
                 "archive" "stackexchange" (str "stackoverflow.com-Posts.7z"))
          (.join path (zest.docs.registry/get-so-root)
                 "archive" "stackexchange" (str "stackoverflow.com-Comments.7z"))
          (.join path (zest.docs.registry/get-so-root)
                 "archive" "stackexchange" (str "stackoverflow.com-Users.7z")))
        additional-args)
      (js-obj
        "env" (js-obj
                "PATH"
                (.dirname path (.dirname path (.-__dirname js/window))))))))

(defn get-so-archive-sizes-sum []
  (let [ret (async/chan)
        ret-str (atom "")
        process (run-so-extractor (array "sizes"))]
    (.on (.-stdout process) "data"
         #(reset! ret-str (+ @ret-str (.toString % "utf8"))))
    (.on (.-stdout process) "end"
         #(let [vals (.split @ret-str " ")]
           (go (async/>! ret (+ (.parseFloat js/window (nth vals 0))
                                (.parseFloat js/window (nth vals 1))
                                (.parseFloat js/window (nth vals 2)))))))
    ret))

(defn start-so-indexing []
  (reset! so-index-progress 0)
  (let [levelup (.require js/window "levelup")
        path (.require js/window "path")
        fs (.require js/window "fs-extra")
        db (levelup.
             (.join path (zest.docs.registry/get-so-root) "new_index" "leveldb"))
        rStream (.createReadStream
                  db
                  (js-obj "gt" "p_"
                          "lt" "p_a"))

        LuceneIndex
        (.-LuceneIndex (.require js/window "nodelucene"))
        idx (LuceneIndex. (.join path (zest.docs.registry/get-so-root)
                                 "new_index" "lucene"))

        render-answer-blob
        (fn [data]
          (str (.-Title data) " "
               (zest.docs.stackoverflow/unfluff (.-Body data))
               (apply str (.map (.-comments data) #(str " " (.-Text %))))))

        render-blob
        (fn [data]
          (str (.-Title data) " "
               (zest.docs.stackoverflow/unfluff (.-Body data))
               (apply str (.map (.-comments data)
                                #(str " " (.-Text %))))
               (apply str (.map (.-answers data)
                                #(str " " (render-answer-blob %))))))

        done (atom 0)
        started (atom 0)
        ended (atom false)
        check-all-done
        (fn []
          (if (and @ended (= @started @done))
            (do
              (.endWriting idx)
              (zest.searcher/stop-all-searchers)
              (.close
                @zest.core/so-db
                (.close
                  db
                  #(go
                    (async/<! (async-rimraf (.join path (zest.docs.registry/get-so-root) "lucene")))
                    (async/<! (async-rimraf (.join path (zest.docs.registry/get-so-root) "leveldb")))
                    (.move
                      fs
                      (.join path (zest.docs.registry/get-so-root) "new_index" "lucene")
                      (.join path (zest.docs.registry/get-so-root) "lucene")
                      (fn []))
                    (.move
                      fs
                      (.join path (zest.docs.registry/get-so-root) "new_index" "leveldb")
                      (.join path (zest.docs.registry/get-so-root) "leveldb")
                      (fn []
                        (zest.core/set-so-db)
                        (reset! so-indexing false)))))))))]
    (.startWriting idx)
    (.on rStream "data"
         (fn [v]
           (let [data (.parse js/JSON (.-value v))]
             (reset! started (+ @started 1))
             (go (.addFile
                   idx
                   (str (.-Id data) ";" (.-Title data))
                   (render-blob
                     (async/<!
                       (zest.docs.stackoverflow/process-so-post data false))))
                 (reset! done (+ @done 1))
                 (if (= 0 (mod @done 15))
                   (reset! so-index-progress @done))
                 (check-all-done)))))
    (.on rStream "end"
         (fn []
           (reset! ended true)
           (check-all-done)))))

(defn start-so-grepping []
  (let [mkdirp (.require js/window "mkdirp")
        path (.require js/window "path")]
    (go
      (async/<! (async-rimraf (.join path (zest.docs.registry/get-so-root) "new_index")))
      (.sync mkdirp (.join path (zest.docs.registry/get-so-root) "new_index"))
      (let [so-archives-total-val (async/<! (get-so-archive-sizes-sum))
            child-process (.require js/window "child_process")
            Lazy (.require js/window "lazy")
            sogrep (if (= (.-platform js/process) "linux")
                     (let [shell-escape (js/require "shell-escape")]
                       (.spawn child-process
                               "sh"
                               (array "-c"
                                      (str (shell-escape
                                             (array
                                               (zest.core/get-binary-path "extractor")
                                               (.join path (zest.docs.registry/get-so-root)
                                                      "archive" "stackexchange" (str "stackoverflow.com-Posts.7z"))
                                               (.join path (zest.docs.registry/get-so-root)
                                                      "archive" "stackexchange" (str "stackoverflow.com-Comments.7z"))
                                               (.join path (zest.docs.registry/get-so-root)
                                                      "archive" "stackexchange" (str "stackoverflow.com-Users.7z"))))
                                           " | "
                                           (shell-escape
                                             (.concat (array (zest.core/get-binary-path "sogrep"))
                                                      (apply array @so-index-tags)))))
                               (js-obj
                                 "cwd"
                                 (.join path
                                        (zest.docs.registry/get-so-root)
                                        "new_index"))))
                     (.spawn child-process
                             (zest.core/get-binary-path "sogrep")
                             (apply array @so-index-tags)
                             (js-obj "env" (js-obj
                                             "PATH"
                                             (.dirname path (.dirname path (.-__dirname js/window))))
                                     "cwd"
                                     (.join path
                                            (zest.docs.registry/get-so-root)
                                            "new_index"))))
            extractor (if (= (.-platform js/process) "linux")
                        nil
                        (run-so-extractor (array)))]
        (if (not (nil? extractor))
          (do
            (.pipe (.-stdout extractor)
                   (.-stdin sogrep))
            (.on extractor "close" #(.end (.-stdin sogrep)))))

        (reset! so-archives-total so-archives-total-val)
        (reset! so-indexing true)
        (reset! so-grep-progress 0)
        (.on sogrep "close"
             (fn [code]
               (.log js/console "sogrep exited with code" code)
               (if (= 0 code) (start-so-indexing))))
        (.forEach (.-lines (Lazy. (.-stdout sogrep)))
                  (fn [line]
                    (.log js/console (.toString line "utf8"))
                    (reset! so-grep-progress
                            (.parseFloat
                              js/window
                              (.toString line "utf8")))))))))

(def so-widget
  (reagent/create-class
    {:component-did-mount
     (fn []
       (.autocomplete
         (js/jQuery "#so-tags-autocomplete")
         (.map (top-tags) #(js-obj "value" (js/unescape %))))
       (.on
         (js/jQuery "#so-tags-autocomplete")
         "autocompleted"
         (fn [event name]
           (reset! so-index-tags
                   (conj @so-index-tags name))
           (reset! user-tags
                   (conj @user-tags name)))))

     :reagent-render
     (fn []
       (if @so-archives-available
         [:div
          [:ul {:class "collection with-header"}
           [:li {:class "collection-header"}
            "Suggested tags (select ones you want in your index):"]
           (doall (for [tag (concat suggested-tags @user-tags)]
                    (do ^{:key (str "so_tags_" tag)}
                        [:li {:class "collection-item"}
                         [:input {:type     "checkbox"
                                  :id       (str "so_tag_cb_" tag)
                                  :checked  (get @so-index-tags tag)
                                  :disabled @so-indexing
                                  :on-change
                                            (fn []
                                              (if (nil? (get @so-index-tags tag))
                                                (reset! so-index-tags
                                                        (conj @so-index-tags tag))
                                                (reset! so-index-tags
                                                        (disj @so-index-tags tag))))}]
                         [:label {:for   (str "so_tag_cb_" tag)
                                  :class "checkable"} tag]]
                        )))
           [:li {:class "collection-item"}
            "Enter custom tag (click or press Enter to add):"
            [:br]
            [:div {:class "input-field"
                   :style {"margin-top" 0}}
             [:input {:id "so-tags-autocomplete"
                      :on-key-down
                          (fn [e]
                            (if (= (.-key e) "Enter")
                              (do
                                (reset! so-index-tags
                                        (conj @so-index-tags (.-value (.-target e))))
                                (reset! user-tags
                                        (conj @user-tags (.-value (.-target e))))
                                (set! (.-value (.-target e)) ""))))}]]]]
          (if @so-indexing
            [:p (str
                  "Indexing: "
                  (.toFixed (* 100 (/ (or @so-grep-progress 0)
                                      (or @so-archives-total 1)))
                            2)
                  "% filtering, "
                  (or @so-index-progress 0)
                  " indexing")]
            [:button {:on-click (fn []
                                  (start-so-grepping))
                      :class    "btn center-align"}
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
             "Click here to start."])]))}))

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
        parsing-succeeded (atom false)
        db-done (atom false)
        db-data (atom "")
        db-bytes (atom 0)
        doc-dir (.join path (zest.docs.registry/get-devdocs-docs-root) slug)

        on-done
        (fn []
          (.sync mkdirp doc-dir)
          (try
            (.parse js/JSON @index-data)
            (.parse js/JSON @db-data)
            (reset! parsing-succeeded true)
            (catch js/Object e
              (reset! downloading (dissoc @downloading slug))
              (reset! downloading-error (assoc @downloading-error
                                          slug (str "Parse error: " e)))))
          (if @parsing-succeeded
            (do
              (.writeFileSync fs (.join path doc-dir "index.json") @index-data)
              (.writeFileSync fs (.join path doc-dir "db.json") @db-data)
              (reset! downloading (dissoc @downloading slug))
              (reset! downloading-error (dissoc @downloading slug))
              (zest.docs.registry/update-installed-devdocs))))]

    (reset! downloading (assoc @downloading slug 0))
    (request (str "http://maxcdn-docs.devdocs.io/" slug "/index.json")
             (fn [error response body]
               (if error
                 (do (reset! downloading (dissoc @downloading slug))
                     (reset! downloading-error (assoc @downloading-error
                                                 slug error)))
                 (do (reset! index-data body)
                     (if @db-done (on-done)
                                  (reset! index-done true))))))

    (.on db-request "data"
         (fn [chunk]
           (reset! db-bytes (+ @db-bytes (.-length chunk)))
           (reset! db-data (str @db-data chunk))
           (if (contains? @downloading slug)
             (reset! downloading
                     (assoc
                       @downloading
                       slug
                       (.round js/Math
                               (/ (* 100 @db-bytes) db-size)))))))

    (.on db-request "error"
         (fn [err]
           (reset! downloading (dissoc @downloading slug))
           (reset! downloading-error (assoc @downloading-error
                                       slug err))))

    (.on db-request "end"
         (fn []
           (if @index-done (on-done)
                           (reset! db-done true))))))

(def available-devdocs (reagent/atom []))
(def installed-devdocs zest.docs.registry/installed-devdocs-atom)

(defn settings-modal []
  (let [path (.require js/window "path")
        rimraf (.require js/window "rimraf")
        shell (.require js/window "shell")
        fs (.require js/window "fs")]
    [:div
     {:class "modal"
      :id    "settings-modal"}

     [:div {:class "modal-content"}
      [:h4 "Settings"]
      [:div {:class "row"}
       [:div {:class "col s6"}
        [:h5 "Your documentation"]
        [:ul {:class "collection with-header"}
         [:li {:class "collection-header"}
          [:b "DevDocs:"]
          (if @devdocs-reindexing
            [:span {:class "secondary-content"}
             (str "Building index... " @devdocs-reindexing-progress)]
            [:a {:href     "#"
                 :class    "secondary-content"
                 :on-click #(devdocs-rebuild-index)}
             "Build index"])]
         (doall (for [doc @installed-devdocs]
                  ^{:key (str "installeddoc_" doc)}
                  [:li {:class "collection-item"} doc
                   [:a
                    {:class "secondary-content"
                     :href  "#"
                     :on-click
                            (fn []
                              (rimraf
                                (.join path
                                       (zest.docs.registry/get-devdocs-docs-root)
                                       doc)
                                #(zest.docs.registry/update-installed-devdocs)))}
                    [:i {:class "material-icons"} "delete"]]
                   (if
                     (not
                       (.existsSync
                         fs
                         (.join path
                                (zest.docs.registry/get-devdocs-docs-root)
                                doc
                                "is_indexed")))
                     [:div {:class "error"}
                      "Not indexed. Click 'Build index' above."])]))]]
       [:div {:class "col s6"}
        [:h5 "Available documentation"]
        [:ul {:class "collapsible"}
         [:li {:class    "collapsible-header"
               :on-click #(reset! devdocs-visible
                                  (not @devdocs-visible))} "from "
          [:a {:on-click
               #(.openExternal shell "http://devdocs.io/")}
           "DevDocs - http://devdocs.io/"]
          [:a
           {:class "secondary-content"
            :href  "#"}
           [:i {:class "material-icons"} "expand_more"]]]
         (if @devdocs-visible
           [:ul {:class "collection"}
            (doall (for [doc @available-devdocs]
                     (if (= -1 (.indexOf @installed-devdocs
                                         (.-slug doc)))
                       (if (contains? @downloading (.-slug doc))
                         ^{:key (str "availabledoc_" (.-slug doc))}
                         [:li {:class "collection-item"}
                          (str "downloading " (.-slug doc) "... "
                               (get @downloading (.-slug doc))
                               "%")]
                         ^{:key (str "availabledoc_" (.-slug doc))}
                         [:li {:class "collection-item"}
                          [:a
                           {:on-click #(download-devdocs
                                        (.-slug doc)
                                        (aget doc "db_size"))
                            :href     "#"}
                           (.-slug doc)]
                          [:a
                           {:class    "secondary-content"
                            :on-click #(download-devdocs
                                        (.-slug doc)
                                        (aget doc "db_size"))
                            :href     "#"}
                           [:i {:class "material-icons"} "cloud_download"]]
                          (if (contains? @downloading-error (.-slug doc))
                            [:div
                             {:class "error"}
                             (str "Download failed: "
                                  (get @downloading-error (.-slug doc)))])]))))])

         [:li {:class    "collapsible-header"
               :on-click #(reset! so-visible
                                  (not @so-visible))}
          "from " [:a {:on-click
                       #(.openExternal shell "http://stackoverflow.com/")}
                   "Stack Overflow - http://stackoverflow.com/"]
          [:a
           {:class "secondary-content"
            :href  "#"}
           [:i {:class "material-icons"} "expand_more"]]]
         (if @so-visible [so-widget])]]]]

     [:div {:class "modal-footer"}
      [:a {:class "modal-action modal-close btn-flat"}
       "Close"]]]))

(defn register-settings []
  (let [modal-class
        (reagent/create-class
          {:component-did-mount
           (fn []
             (if (and
                   @visible
                   (not
                     (.is (.$ js/window "#settings-modal") ":visible")))
               (.openModal
                 (.$ js/window "#settings-modal"))))

           :reagent-render
           settings-modal})]
    (set!
      (.-showSettings js/window)
      (fn []
        (do
          ; showing again doesn't work after hiding unless we re-set to false
          (if (not
                (.is (.$ js/window "#settings-modal") ":visible"))
            (do
              (reset! visible false)
              (reset! visible true))))))

    (if @visible
      (do
        (zest.docs.registry/get-available-devdocs
          (fn [docs] (reset! available-devdocs docs)))
        [modal-class])
      [:div])))
