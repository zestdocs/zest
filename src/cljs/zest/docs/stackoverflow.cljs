(ns zest.docs.stackoverflow
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [reagent.core :as reagent]
            [cljs.core.async :as async]))


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

(defn process-so-post [data]
  (let
    [process-tags
     (fn [tags]
       (if tags (.map
                  (.match tags #"<[^>]+>")
                  #(.substring % 1 (- (.-length %) 1)))))

     process-answer
     (fn [answer]
       (let [ret-data answer
             ret (async/chan)
             cStream (.createReadStream
                       zest.core/so-db
                       (js-obj "gt" (str "c_" (.-Id answer) "_")
                               "lt" (str "c_" (.-Id answer) "_a")))]
         (aset ret-data "comments" (array))
         (aset ret-data "Tags" (process-tags (aget ret-data "Tags")))
         (if (and (aget data "AcceptedAnswerId")
                  (= (aget ret-data "Id") (aget data "AcceptedAnswerId")))
           (aset ret-data "Accepted" true))

         (.on cStream "data"
              (fn [v]
                (let [comment (.parse js/JSON (.-value v))]
                  (.push (aget ret-data "comments") comment))))

         (.on cStream "end"
              (fn []
                (go (async/>! ret ret-data))))
         ret))

     ret-data data
     ret (async/chan)
     started (atom 0)
     finished (atom 0)
     ended (atom false)
     cStream (.createReadStream
               zest.core/so-db
               (js-obj "gt" (str "c_" (.-Id data) "_")
                       "lt" (str "c_" (.-Id data) "_a")))
     check-finished
     (fn []
       (if (and (= @started @finished) @ended)
         (go (async/>! ret ret-data))))]

    (aset ret-data "answers" (array))
    (aset ret-data "comments" (array))
    (aset ret-data "Tags" (process-tags (aget ret-data "Tags")))

    (.on cStream "data"
         (fn [v]
           (let [comment (.parse js/JSON (.-value v))]
             (.push (aget ret-data "comments") comment))))

    (.on cStream "end"
         (fn []
           (let [aStream (.createReadStream
                           zest.core/so-db
                           (js-obj "gt" (str "a_" (.-Id data) "_")
                                   "lt" (str "a_" (.-Id data) "_a")))]
             (.on aStream "data"
                  (fn [v]
                    (reset! started (+ @started 1))
                    (let [answer (.parse js/JSON (.-value v))]
                      (go
                        (let [ans-data (async/<!
                                         (process-answer answer))]
                          (.push (aget ret-data "answers") ans-data)
                          (reset! finished (+ @finished 1))
                          (check-finished))))))
             (.on aStream "end"
                  (fn []
                    (reset! ended true)
                    (check-finished))))))
    ret))