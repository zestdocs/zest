(ns zest.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as reagent]
            [cljs.core.async :as async]
            [cljsjs.react]
            [goog.net.XhrIo]
            [zest.settings]
            [zest.docs.registry]
            [zest.docs.stackoverflow]
            [zest.searcher]
            [zest.docs.devdocs]))

(def so-db
  (let [levelup (.require js/window "levelup")
        path (.require js/window "path")]
    (levelup (.join path (zest.docs.registry/get-so-root)
                    "leveldb"))))

(def so-index
  (let
    [mkdirp (.require js/window "mkdirp")
     path (.require js/window "path")]
    (.sync mkdirp (zest.docs.registry/get-so-root))
    (.join path (zest.docs.registry/get-so-root) "lucene")))


(def query (reagent/atom ""))
(def results (reagent/atom []))
(def search-results (reagent/atom []))
(def index (reagent/atom 0))


(defn set-query [q]
  (reset! query q)
  (if (= (count q) 0)
    (reset! results [])
    (do
      (let [prep-query (.replace @query #"\s+$" "")]
        (go (reset! search-results
                    (async/<! (zest.searcher/search so-index prep-query)))
            (if (= (count @search-results) 0)
              (reset!
                search-results
                (async/<!
                  (zest.searcher/search so-index (str prep-query "*")))))))
      (reset!
        results
        (concat
          (take 10 (filter
                     (fn [r]
                       (=
                         (.indexOf (.-name (.-contents r)) q)
                         0))
                     @zest.docs.devdocs/entries))
          [(js-obj "contents" (js-obj "path" "__FTS__"
                                      "name" "More DevDocs results..."))]))
      (reset! index 0))))

(defn render-so-post [data]
  (let [render-template
        (fn [data]
          (let [handlebars (.require js/window "handlebars")
                fs (.require js/window "fs")
                tpl (.compile
                      handlebars
                      (.readFileSync fs "app/templates/post.handlebars"
                                     "utf8"))]
            (.registerHelper
              handlebars
              "ifPlural"
              (fn [var options]
                (.log js/console var)
                (if (not (= 1 (int var)))
                  (this-as js-this (.fn options js-this)))))
            (.registerPartial
              handlebars
              "renderComments"
              (.readFileSync fs "app/templates/comments.handlebars"
                             "utf8"))
            (.log js/console data)
            (tpl (js-obj "post" data))))]
    (go (render-template (async/<!
                           (zest.docs.stackoverflow/process-so-post data))))))

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

     LuceneIndex
     (.-LuceneIndex (.require js/window "../build/Release/nodelucene"))
     path (.require js/window "path")

     get-search-index #(LuceneIndex.
                        (.join path (zest.docs.registry/get-devdocs-root)
                               "lucene"))

     fts-results
     (fn [files cb]
       (let
         [res (reagent/atom "")
          i (reagent/atom 0)
          search-index (get-search-index)

          next
          (fn next []
            (let [docset (nth (.split (nth files @i) "/") 0)
                  path (.join (.slice (.split (nth files @i) "/") 1) "/")]
              (zest.docs.devdocs/get-from-cache docset)
              (let [db-cache (aget zest.docs.devdocs/docset-db-cache docset)]
                (reset!
                  res
                  (str
                    @res
                    "<h5><a href='javascript:openDoc(\""
                    docset "\", \"" path "\""
                    ")'>"
                    (nth files @i)
                    "</a></h5>"
                    (.highlight
                      search-index
                      (escape-html
                        (zest.docs.stackoverflow/unfluff
                          (aget db-cache path)))
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
       (.log js/console docset entry)
       (if (= docset "stackoverflow")
         (let []
           (set! (.-scrollTop (.getElementById js/document "right")) 0)
           (.get
             so-db
             (str "p_" entry)
             (fn [e json]
               (let [data (.parse js/JSON json)]
                 (go (async-set-html (async/<! (render-so-post data)) #()))))))
         (let [response (aget (aget zest.docs.devdocs/docset-db-cache docset)
                              (nth (.split (.-path entry) "#") 0))]
           (let [new-hash (nth (.split (.-path entry) "#") 1)]
             (if (= @html response)
               (do
                 (set-hash new-hash)
                 (set-600ms-focus))

               (do
                 (reset! hash new-hash)
                 (aset (.getElementById js/document "right") "className"
                       (str "_" docset))
                 (async-set-html response #(set-hash new-hash))
                 (set-600ms-focus)))))))

     section
     (fn [docset type]
       [:div {:class "collection"}
        (for
          [entry (get-in @docset-type-items [docset type])]
          ^{:key (str docset "_" (.-name type) "_" (.-path entry) "_" (.-name entry))}

          [:a
           {:class    "collection-item"
            :href     "#"
            :on-click #(activate-item docset entry)}
           (.-name entry)])])

     refresh
     (fn []
       (let [entry (nth @results @index)
             entry-path (.-path (.-contents entry))]
         (if (= "__FTS__" entry-path)
           (fts-results (.search (get-search-index) @query)
                        #(async-set-html % (fn [])))
           (activate-item (.-docset entry) (.-contents entry)))))

     fts-suggestions
     (fn []
       (if (> (count @search-results) 0)
         (do [:div {:class "collection with-header"}
              [:div {:class "collection-header z-depth-1"}
               [:strong "Stack Overflow"]]
              (map
                (fn [res] ^{:key res}

                [:a
                 {:href     "#"
                  :class    "collection-item"
                  :on-click #(activate-item "stackoverflow"
                                            (nth (.split res ";") 0))}
                 (nth (.split res ";") 1)])
                @search-results)])
         [:div]))]

    (set! (.-openDoc js/window)
          (fn [docset item]
            (activate-item docset (js-obj "path" item))))

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
           [:div {:class "collapsible tree"}
            (if (= 0 (count @results))
              (doall (for [docset
                           (map (fn [x] {:name x :label x}) @docsets-list)]
                       ^{:key (:name docset)}
                       [:div
                        [:div {:class
                               "collapsible-header"
                               :on-click
                               (fn []
                                 (if (nil? (get @docset-types (:name docset)))
                                   (reset!
                                     docset-types
                                     (assoc @docset-types
                                       (:name docset)
                                       (.-types (zest.docs.devdocs/get-from-cache (:name docset)))))
                                   (reset! docset-types (dissoc @docset-types (:name docset)))))}
                         (:label docset)]
                        (doall (for [type (get @docset-types (:name docset))]
                                 (let []
                                   ^{:key (str (:name docset) "/" (.-name type))}
                                   [:div {:class "collapsible level2"}
                                    [:div {:class "collapsible-header"
                                           :on-click
                                                  (fn []
                                                    (if (nil? (get-in @docset-type-items [(:name docset) type]))
                                                      (reset! docset-type-items
                                                              (assoc-in @docset-type-items [(:name docset) type]
                                                                        (filter
                                                                          #(= (.-type %) (.-name type))
                                                                          (.-entries (zest.docs.devdocs/get-from-cache (:name docset))))))
                                                      (reset! docset-type-items
                                                              (dissoc (get @docset-type-items (:name docset)) type))))}

                                     (.-name type)]
                                    [section (:name docset) type]])))]))
              [:div {:class "collection"} (doall (for [[i item] (map-indexed vector @results)]
                                                   ^{:key (str @query (str i))}
                                                   [:a
                                                    {:class    (if (= @index i) "collection-item active"
                                                                                "collection-item")
                                                     :href     "#"
                                                     :on-click (fn []
                                                                 (reset! index i)
                                                                 (refresh)
                                                                 (.focus (.getElementById js/document "searchInput")))}
                                                    (.-name (.-contents item))]
                                                   ))])
            (if (> (count @query) 0) (fts-suggestions))]]
          [right-class]
          [zest.settings/register-settings]])})))

(defn mount-root
  []
  (set! (.-onkeydown js/document)
        (fn [e]
          (if (= (.-keyCode e) 27)
            (do
              (.closeModal (.$ js/window ".modal"))))))
  (reagent/render
    [main-page]
    (.getElementById js/document "app")))

(defn make-devdocs-loop []
  (.log js/console "make-devdocs-loop")
  (go-loop []
           (let [data (async/<! zest.docs.registry/installed-devdocs-chan)]
             (reset! zest.docs.devdocs/entries
                     (zest.docs.devdocs/get-all-entries data))
             (if (> (count @query) 0)
               (set-query @query))
             (recur))))

(def devdocs-loop (make-devdocs-loop))

(defn before-figwheel-reload []
  (.log js/console "before")
  (.close so-db)
  (zest.searcher/stop-all-searchers)
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

(defn init!
  []
  (add-figwheel-handler)
  (zest.searcher/new-searcher so-index)
  (mount-root))

(defn on-figwheel-reload []
  (init!))