(ns zest.core
  (:require [reagent.core :as reagent]
            [cljsjs.react]))

(defn main-page
  []
  (let [splitjs (.require js/window "split.js")]
    (reagent/create-class
      {:component-did-mount
       (fn []
         (splitjs
           (array "#left", "#right")
           (js-obj
             "minSize" 200
             "sizes" (array 25 75))))

       :reagent-render
       (fn []
         [:div {:style {:height "100%"}}
          [:div {:id "left"}
           [:input {:type "text"}]
           [:ul
            [:li "Hello"]]]
          [:div {:id "right"} "asdfx"]])})))

(defn mount-root
  []
  (reagent/render [main-page] (.getElementById js/document "app")))

(defn init!
  []
  (mount-root))
