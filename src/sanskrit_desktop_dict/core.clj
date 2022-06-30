(ns sanskrit-desktop-dict.core
  (:require [cljfx.api :as fx]
            [clojure.pprint :refer [pprint]])
  (:import [javafx.scene.input KeyCode KeyEvent])
  (:gen-class))

(def *state (atom {:settings {:size :normal}
                   :title "Sanskrit Dictionary by MB"
                   :input {:current ""
                           :history []}}))

(defn root [{:keys [input title]}]
  {:fx/type :stage
   :title (if (not= "" (:current input)) (str  (:current input) " | " title) title)
   :showing true
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :pref-width 800
                  :pref-height 600
                  :children [{:fx/type :combo-box
                              :v-box/margin 5
                              :editable true
                              :items (-> input :history reverse)
                              :prompt-text "Type and press Enter"
                              ;; :on-text-changed {:event/type ::type}
                              ;; :on-key-pressed {:event/type ::press}
                              :on-action {:event/type ::action}}

                             {:fx/type :text
                              :text (with-out-str (pprint input))}]}}})


(defmulti event-handler :event/type)

(defmethod event-handler ::type [event]
  (swap! *state assoc-in [:input :current] (:fx/event event)))

(defmethod event-handler ::press [event]
  (when (= KeyCode/ENTER (.getCode ^KeyEvent (:fx/event event)))
    (swap! *state #(-> %
                       (update-in [:input :history] conj (-> % :input :current))))))

(def last-action (atom nil))
(defmethod event-handler ::action [event]
  (reset! last-action event)
  (let [value (-> event :fx/event .getTarget .getValue)]
    (swap! *state #(-> %
                       (update-in [:input :history] conj value)
                       (assoc-in [:input :current] value)))))

(comment
  (-> @last-action :fx/event .getTarget .getValue))



(fx/mount-renderer
 *state
 (fx/create-renderer
  :middleware (fx/wrap-map-desc assoc :fx/type root)
  :opts {:fx.opt/map-event-handler event-handler}))
