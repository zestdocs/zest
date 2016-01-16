(ns zest.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as reagent]
            [cljs.core.async :as async]
            [cljsjs.react]
            [goog.net.XhrIo]
            [zest.settings]
            [zest.docs.registry]))

(defn unfluff
  [html]
  (let
    [parse5 (.require js/window "parse5")
     res (reagent/atom "")
     SAXParser (.-SAXParser parse5)
     parser (SAXParser. ())]

    (.on parser "text" (fn [text] (reset! res (str @res text))))
    (.write parser html)
    @res))

(def search-index
  (let [path (.require js/window "path")
        LuceneIndex
        (.-LuceneIndex (.require js/window "../build/Release/nodelucene"))]
    (LuceneIndex. (.join path (zest.docs.registry/get-so-root) "lucene"))))

(def so-db
  (let [levelup (.require js/window "levelup")
        path (.require js/window "path")]
    (levelup (.join path (zest.docs.registry/get-so-root)
                    "leveldb"))))

(def query (reagent/atom ""))
(def results (reagent/atom []))
(def search-results (reagent/atom []))
(def index (reagent/atom 0))

(def docset-db-cache (js-obj))
(def docset-cache (js-obj))

(defn get-from-cache [dir]
  (if (nil? (aget docset-cache dir))
    (let [path (.require js/window "path")
          fs (.require js/window "fs")
          devdocs-root (zest.docs.registry/get-devdocs-root)
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
       (.-entries (get-from-cache dir))))

(defn get-all-entries [data]
  (apply concat (map (fn [x] (get-entries x)) data)))

(def entries
  (reagent/atom (get-all-entries @zest.docs.registry/installed-devdocs-atom)))

(defn set-query [q]
  (reset! query q)
  (if (= (count q) 0)
    (reset! results [])
    (do
      (let [prep-query (.replace @query #"\s+$" "")]
        (reset! search-results (.search search-index prep-query))
        (if (and (= (count @search-results) 0)
                 ; poor performance with short strings followed by '*'
                 (>= (count prep-query) 3))
          (reset! search-results (.search search-index (str prep-query "*")))))
      (reset!
        results
        (concat
          (take 10 (filter
                     (fn [r]
                       (=
                         (.indexOf (.-name (.-contents r)) q)
                         0))
                     @entries))))
      (reset! index 0))))

(defn render-so-post [data]
  (let [process-answer
        (fn [answer]
          (let [ret-data (atom (.-Body answer))
                ret (async/chan)
                cStream (.createReadStream
                          so-db
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

        ret-data (atom (str (.-Title data) " " (.-Body data)))
        ret (async/chan)
        started (atom 0)
        finished (atom 0)
        ended (atom false)
        cStream (.createReadStream
                  so-db
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
                           so-db
                           (js-obj "gt" (str "a_" (.-Id data) "_")
                                   "lt" (str "a_" (.-Id data) "_a")))]
             (.on aStream "data"
                  (fn [v]
                    (reset! started (+ @started 1))
                    (let [answer (.parse js/JSON (.-value v))]
                      (go
                        (let [ans-data (async/<!
                                         (process-answer answer))]
                          (reset! ret-data (str @ret-data ans-data)))
                          (reset! finished (+ @finished 1))
                          (check-finished)))))
             (.on aStream "end"
                  (fn []
                    (reset! ended true)
                    (check-finished))))))
    ret))

(defn main-page
  []
  (let
    [escape-html (.require js/window "escape-html")
     docset-types (reagent/atom (hash-map))
     docsets-list zest.docs.registry/installed-devdocs-atom
     docset-type-items (reagent/atom (hash-map))
     splitjs (.require js/window "split.js")
     html (reagent/atom "")
     input-focus (reagent/atom false)
     focus-id (reagent/atom nil)

     fts-results
     (fn [files cb]
       (let
         [res (reagent/atom "")
          i (reagent/atom 0)
          path (.require js/window "path")

          next
          (fn next []
            (.get
              so-db
              (nth (.split (nth files @i) ";") 0)
              (fn [e ret]
                (reset!
                  res
                  (str
                    @res
                    "<h2>" (nth (.split (nth files @i) ";") 1) "</h2>"
                    (.highlight
                      search-index
                      (escape-html (unfluff (.-Body (.parse js/JSON ret))))
                      @query)))
                (if (< @i (- (count files) 1))
                  (do (reset! i (+ @i 1)) (next))
                  (do (cb @res))))))]
         (next)))

     set-600ms-focus
     (fn []
       (reset! input-focus true)
       (if focus-id (.clearTimeout js/window @focus-id))
       (reset! focus-id (.setTimeout js/window #(reset! input-focus false) 600)))
     hash (reagent/atom "#")
     right-class (reagent/create-class
                   {:component-did-mount
                    (fn []
                      (splitjs
                        (array "#left", "#right")
                        (js-obj
                          "sizes" (array 25 75))))


                    :reagent-render
                    (fn []
                      [:div
                       {:id "right"}])})

     async-num (atom 0)
     async-set-html
     (fn [new-html done-cb]
       (let
         [target (.getElementById js/document "right")
          temp (atom (.createElement js/document "div"))
          frag (.createDocumentFragment js/document)
          cur-num (+ @async-num 1)

          proceed
          (fn proceed []
            (if (= cur-num @async-num)
              (if (.-firstChild @temp)
                (do (.appendChild frag (.-firstChild @temp))
                    (.setImmediate js/window proceed))
                (do (set! (.-innerHTML target) "")
                    (.appendChild target frag)
                    (done-cb)))))]

         (reset! async-num (+ @async-num 1))
         (reset! html new-html)
         (set! (.-innerHTML @temp) new-html)
         (proceed)))

     set-hash
     (fn [new-hash]
       (if (nil? new-hash)
         (set! (.-scrollTop (.getElementById js/document "right")) 0)
         (.setImmediate
           js/window
           (fn []
             ; in case new-hash = current hash:
             (set! (.-hash js/location) "")
             (.setImmediate
               js/window
               #(set! (.-hash js/location) new-hash))))))

     activate-item
     (fn [docset entry]
       (if (= docset "stackoverflow")
         (let []
           (.log js/console entry)
           (.get
             so-db
             (str "p_" entry)
             (fn [e json]
               (let [data (.parse js/JSON json)]
                 (go (async-set-html (async/<! (render-so-post data))#()))))))
         (let [response (aget (aget docset-db-cache docset)
                              (nth (.split (.-path entry) "#") 0))]
           (let [new-hash (nth (.split (.-path entry) "#") 1)]
             (if (= @html response)
               (do
                 (set-hash new-hash)
                 (set-600ms-focus))

               (do
                 (reset! hash new-hash)
                 (async-set-html response #(set-hash new-hash))
                 (set-600ms-focus)))))))

     section
     (fn [docset type]
       [:ul (for
              [entry (get-in @docset-type-items [docset type])]
              ^{:key (str docset "_" (.-name type) "_" (.-path entry) "_" (.-name entry))}
              [:li
               [:a
                {:on-click #(activate-item docset entry)}
                (.-name entry)]])])

     refresh
     (fn []
       (let [entry (nth @results @index)
             entry-path (.-path (.-contents entry))]
         (if (= "__FTS__" entry-path)
           (fts-results (.search search-index @query) #(async-set-html % (fn [])))
           (activate-item (.-docset entry) (.-contents entry)))))

     fts-suggestions
     (fn []
       (if (> (count @search-results) 0)
         (do [:div {:class "fts"}
              [:h2 "See also"]
              [:h3 "Stack Overflow:"]
              [:ul {:class "fts-results"}
               (map
                 (fn [res] ^{:key res}
                 [:li
                  [:a
                   {:on-click #(activate-item "stackoverflow"
                                              (nth (.split res ";") 0))}
                   (nth (.split res ";") 1)]])
                 @search-results)]])
         [:div]))]

    (reagent/create-class
      {
       ;(.addEventListener
       ;  js/window
       ;  "resize"
       ;  (fn [event]
       ;    (let [w (.-innerWidth js/window)
       ;          h (.-innerHeight js/window)]
       ;      (do
       ;        (reset! width
       ;                (str (- w
       ;                        (js/parseInt (-> (.getElementById js/document "left") .-style .-width))
       ;                        10)
       ;                     "px"))
       ;        (.log js/console @width))))))

       :reagent-render
       (fn []
         [:div {:style {:height "100%"}}
          [:div {:id "left"}
           [:input
            {:id   "searchInput"
             :type "text"
             :on-blur
                   (fn [e] (if @input-focus
                             (.focus (.getElementById js/document "searchInput"))))
             :on-key-down
                   (fn [e]
                     (case (.-key e)
                       "ArrowDown" (reset! index (mod (+ @index 1) (count @results)))
                       "ArrowUp" (reset! index (mod (- @index 1) (count @results)))
                       "Escape" (do (set! (.-value (.-target e)) "") (set-query ""))
                       "Enter" ())
                     (refresh)
                     (set-600ms-focus)
                     false)
             :on-change
                   (fn [e]
                     (let [q (-> e .-target .-value)]
                       (set-query q)))}]
           [:div {:class "tree"}
            (if (= 0 (count @results))
              (doall (for [docset
                           (map (fn [x] {:name x :label x}) @docsets-list)]
                       ^{:key (:name docset)}
                       [:div
                        [:h2 [:a
                              {:on-click
                               (fn []
                                 (if (nil? (get @docset-types (:name docset)))
                                   (reset! docset-types (assoc @docset-types (:name docset)
                                                                             (.-types (get-from-cache (:name docset)))))
                                   (reset! docset-types (dissoc @docset-types (:name docset)))))}
                              (:label docset)]]
                        (doall (for [type (get @docset-types (:name docset))]
                                 (let []
                                   ^{:key (str (:name docset) "/" (.-name type))}
                                   [:ul
                                    [:h3 [:a {:on-click
                                              (fn []
                                                (if (nil? (get-in @docset-type-items [(:name docset) type]))
                                                  (reset! docset-type-items (assoc-in @docset-type-items [(:name docset) type]
                                                                                      (filter
                                                                                        #(= (.-type %) (.-name type))
                                                                                        (.-entries (get-from-cache (:name docset))))))
                                                  (reset! docset-type-items (dissoc (get @docset-type-items (:name docset)) type))))}

                                          (.-name type)]]
                                    [section (:name docset) type]])))]))
              (doall (for [[i item] (map-indexed vector @results)]
                       ^{:key (str @query (str i))}
                       [:label {:class "stack"}
                        [:input
                         {:type     "radio"
                          :name     "stack"
                          :checked  (= @index i)
                          :on-click (fn []
                                      (reset! index i)
                                      (refresh)
                                      (.focus (.getElementById js/document "searchInput")))}]
                        [:span {:class "button toggle"} (.-name (.-contents item))]])))
            (if (> (count @query) 0) (fts-suggestions))]]
          [right-class]
          [zest.settings/register-settings]])})))

(defn mount-root
  []
  (set! (.-onkeydown js/document)
        (fn [e]
          (if (= (.-keyCode e) 27)
            (let [modals (.querySelectorAll js/document ".modal > [type=checkbox]")]
              (doall (for [modal (aclone modals)]
                       (do
                         (set! (.-checked modal) false))))))))
  (reagent/render
    [main-page]
    (.getElementById js/document "app")))

(defn make-devdocs-loop []
  (.log js/console "make-devdocs-loop")
  (go-loop []
           (let [data (async/<! zest.docs.registry/installed-devdocs-chan)]
             (reset! entries (get-all-entries data))
             (if (> (count @query) 0)
               (set-query @query))
             (recur))))

(def devdocs-loop (make-devdocs-loop))

(defn before-figwheel-reload []
  (.log js/console "before")
  (.close so-db)
  (.removeEventListener
    (.-body js/document)
    "figwheel.before-js-reload"
    before-figwheel-reload)
  (async/close! devdocs-loop))

(defn add-figwheel-handler []
  (.addEventListener
    (.-body js/document)
    "figwheel.before-js-reload"
    before-figwheel-reload))

(defn on-figwheel-reload []
  (add-figwheel-handler)
  (mount-root))

(defn init!
  []
  (add-figwheel-handler)
  (mount-root))