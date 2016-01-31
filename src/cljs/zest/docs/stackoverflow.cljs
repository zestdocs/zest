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

(defn process-so-post [data with-users]
  (let
    [process-tags
     (fn [tags]
       (if tags (.map
                  (.match tags (js/RegExp. "<[^>]+>" "g"))
                  #(.substring % 1 (- (.-length %) 1)))))

     process-answer
     (fn [answer]
       (let [ret-data answer
             ret (async/chan)
             cStream (.createReadStream
                       @zest.core/so-db
                       (js-obj "gt" (str "c_" (.-Id answer) "_")
                               "lt" (str "c_" (.-Id answer) "_a")))

             all (atom 0)
             done (atom 0)
             ended (atom false)

             check-finished
             (fn []
               (if (and @ended (= @all @done)) (go (async/>! ret ret-data))))]

         (aset ret-data "comments" (array))
         (aset ret-data "Tags" (process-tags (aget ret-data "Tags")))
         (if (and (aget data "AcceptedAnswerId")
                  (= (aget ret-data "Id") (aget data "AcceptedAnswerId")))
           (aset ret-data "Accepted" true))

         (.on cStream "data"
              (fn [v]
                (reset! all (inc @all))
                (let [comment (.parse js/JSON (.-value v))]
                  (.push (aget ret-data "comments") comment)
                  (if (and with-users (nil? (.-UserDisplayName comment)))
                    (.get @zest.core/so-db (str "u_" (.-UserId comment))
                          (fn [e ret]
                            (.log js/console ret)
                            (aset comment "UserDisplayName"
                                  (.-DisplayName (.parse js/JSON ret)))
                            (reset! done (inc @done))
                            (check-finished)))
                    (reset! done (inc @done))))))
         (.on cStream "end" (fn []
                              (reset! ended true)
                              (check-finished)))
         ret))

     ret-data data
     ret (async/chan)
     started (atom 0)
     finished (atom 0)
     ended (atom false)
     cStream (.createReadStream
               @zest.core/so-db
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
                           @zest.core/so-db
                           (js-obj "gt" (str "a_" (.-Id data) "_")
                                   "lt" (str "a_" (.-Id data) "_a")))]
             (.on aStream "data"
                  (fn [v]
                    (reset! started (inc @started))
                    (let [answer (.parse js/JSON (.-value v))]
                      (go
                        (let [ans-data (async/<!
                                         (process-answer answer))]
                          (.push (aget ret-data "answers") ans-data)
                          (reset! finished (inc @finished))
                          (check-finished))))))
             (.on aStream "end"
                  (fn []
                    (reset! ended true)
                    (check-finished))))))
    ret))