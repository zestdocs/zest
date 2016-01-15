(ns zest.settings
  (:require [reagent.core :as reagent]
            [zest.docs.registry]))

(def visible (reagent/atom false))
(def downloading (reagent/atom {}))

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
          (reset! zest.docs.registry/installed-devdocs-atom
                  (zest.docs.registry/get-installed-devdocs)))]

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
                          (.log js/console @visible)
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
                              (fn []
                                (reset! zest.docs.registry/installed-devdocs-atom
                                        (zest.docs.registry/get-installed-devdocs)))))}
                         "remove"]]))]]
            [:div {:class "half"}
             [:h3 "Available documentation"]
             [:p "from " [:a {:on-click
                              #(.openExternal shell "http://devdocs.io/")}
                          "DevDocs - http://devdocs.io/"]]
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
                                 (.-slug doc)])])))]]]]]])
      [:div])))