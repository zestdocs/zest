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
        rimraf (.require js/window "rimraf")
        fs (.require js/window "fs-extra")
        LuceneIndex
        (.-LuceneIndex (.require js/window "../build/Release/nodelucene"))
        idx (LuceneIndex. (.join path (zest.docs.registry/get-devdocs-root)
                                 "new_lucene"))
        all-count (count @zest.docs.registry/installed-devdocs-atom)
        indexed-count (atom 0)

        check-done
        (fn []
          (reset! indexed-count (inc @indexed-count))
          (if (= @indexed-count all-count)
            (do (reset! devdocs-reindexing false)
                (.endWriting idx)
                (.sync rimraf (.join path (zest.docs.registry/get-devdocs-root) "lucene"))
                (.move
                  fs
                  (.join path (zest.docs.registry/get-devdocs-root) "new_lucene")
                  (.join path (zest.docs.registry/get-devdocs-root) "lucene")
                  (fn [])))
            (reset! devdocs-reindexing-progress
                    (str @indexed-count "/" all-count))))]
    (if (> all-count 0)
      (do
        (.startWriting idx)
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
                                           idx
                                           (str docset "/" (nth keys @i))
                                           (zest.docs.stackoverflow/unfluff
                                             (aget db-cache (nth keys @i)))))
                               (reset! i (inc @i))
                               (recur))
                           (check-done))))))))
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

(defn run-so-extractor [additional-args pipe-stdout-to]
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
      (if (nil? pipe-stdout-to)
        (js-obj)
        (js-obj
          "stdio"
          (array "ignore" (.-stdin pipe-stdout-to) "ignore"))))))

(defn get-so-archive-sizes-sum []
  (let [ret (async/chan)
        ret-str (atom "")
        process (run-so-extractor (array "sizes") nil)]
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
                (fn [])))))]
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
    (go (let [so-archives-total-val (async/<! (get-so-archive-sizes-sum))
              child-process (.require js/window "child_process")
              Lazy (.require js/window "lazy")
              sogrep (.spawn child-process
                             (zest.core/get-binary-path "sogrep")
                             (apply array @so-index-tags)
                             (js-obj "cwd"
                                     (.join path
                                            (zest.docs.registry/get-so-root)
                                            "new_index")))
              extractor (run-so-extractor (array) nil)]
          (.pipe (.-stdout extractor)
                 (.-stdin sogrep))
          (reset! so-archives-total so-archives-total-val)
          (reset! so-indexing true)
          (reset! so-grep-progress 0)
          (.on sogrep "close"
               (fn [code]
                 (.log js/console "sogrep exited with code" code)
                 (if (= 0 code) (start-so-indexing))))
          (.forEach (.-lines (Lazy. (.-stdout sogrep)))
                    (fn [line]
                      (reset! so-grep-progress
                              (.parseFloat
                                js/window
                                (.toString line "utf8")))))))))

(defn so-widget []
  (if @so-archives-available
    [:div
     [:ul {:class "collection with-header"}
      [:li {:class "collection-header"}
       "Suggested tags (select ones you want in your index):"]
      (doall (for [tag suggested-tags]
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
                   )))]
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
        doc-dir (.join path (zest.docs.registry/get-devdocs-docs-root) slug)

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

(def available-devdocs (reagent/atom []))
(def installed-devdocs zest.docs.registry/installed-devdocs-atom)

(defn settings-modal []
  (let [path (.require js/window "path")
        rimraf (.require js/window "rimraf")
        shell (.require js/window "shell")]
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
             (str "Rebuilding index... " @devdocs-reindexing-progress)]
            [:a {:href     "#"
                 :class    "secondary-content"
                 :on-click #(devdocs-rebuild-index)}
             "Rebuild FTS index"])]
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
                    [:i {:class "material-icons"} "delete"]]]))]]
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
                           [:i {:class "material-icons"} "cloud_download"]]]))))])

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
         (if @so-visible (so-widget))]]]]

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
