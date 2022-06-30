(ns sanskrit-desktop-dict.core
  (:require [cljfx.api :as fx]
            [cljfx.prop :as fx.prop]
            [cljfx.mutator :as fx.mutator]
            [cljfx.lifecycle :as fx.lifecycle]
            [cljfx.css :as css]
            [clojure.pprint :refer [pprint]]
            [hiccup.core :refer [html]])
  (:import [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.web WebView]
)
  (:gen-class))


(def *state (atom {:settings {:size 1
                              :history {:max-size 20}}
                   :title "Sanskrit Dictionaries"
                   :status "Ready!"
                   :input {:current {:original ""
                                     :translation ""}
                           :history []}}))

(defn history-conj [{:keys [settings input] :as state}  value]
  (let [new-history (-> (take (-  (-> settings :history :max-size) 1) (:history input))
                        (conj value))]
    (assoc-in state [:input :history] new-history)))


(defn component:word-input-combo [value items]
  {:fx/type :combo-box
   :editable true
   :max-width 500
   :pref-width 350
   ;; :grid-pane/column column
   ;; :grid-pane/row row
   :value value
   :items items
   :prompt-text "Type and press Enter"
   ;; :on-text-changed {:event/type ::type}
   ;; :on-key-pressed {:event/type ::press}
   :on-action {:event/type ::action}})

(defn component:dictionaries-combo [state & {:keys [row column]}]
  {:fx/type :combo-box
   :max-width 600
   :pref-width 500
   ;; :grid-pane/column column
   ;; :grid-pane/row row
   :items ["afdaf dafasav" "de"]})

(def ext-with-html
  (fx/make-ext-with-props
   {:html (fx.prop/make
           (fx.mutator/setter #(.loadContent (.getEngine ^WebView %1) %2))
           fx.lifecycle/scalar)}))

(defn component:translation [html & {:keys [row column]}]
  {:fx/type ext-with-html
   :props {:html html}
   :grid-pane/column column
   :grid-pane/row row
   :desc {:fx/type :web-view}})

(defn component:word-list [items & {:keys [row column]}]
  {:fx/type :list-view
   :items items
   :grid-pane/column column
   :grid-pane/row row
   :on-selected-item-changed {:event/type ::word-selected}})

(defn component:dictionaries-toolbar [items]
  {:fx/type :tool-bar
   :grid-pane/column 1
   :grid-pane/row 0
   :items [{:fx/type :button
            :text "btn"}]})


(defn stage:main [{:keys [input title status] :as state}]
  {:fx/type :stage
   :showing true
   :title title
   :scene {:fx/type :scene
           :root {:fx/type :grid-pane
                  :grid-lines-visible true
                  :padding 1
                  :column-constraints [{:fx/type :column-constraints
                                        :percent-width 100}]
                  :row-constraints [{:fx/type :row-constraints
                                     :percent-height 10}
                                    {:fx/type :row-constraints
                                     :percent-height 85}
                                    {:fx/type :row-constraints
                                     :percent-height 5}]
                  :children [;; Top row: input, dictionaries, text size
                             {:fx/type :v-box
                              :grid-pane/column 0
                              :grid-pane/row 0
                              :children [{:fx/type :tool-bar
                                          :items [(component:word-input-combo (-> input :current :original) (:history input))
                                                  {:fx/type :separator}
                                                  {:fx/type :label
                                                   :text "Dictionaries"}
                                                  (component:dictionaries-combo state :row 0 :column 1)
                                                  {:fx/type :separator}
                                                  {:fx/type :label
                                                   :text "Font"}
                                                  {:fx/type :button
                                                   :text "+"}
                                                  {:fx/type :button
                                                   :text "-"}]}]}

                             ;; Middle row: word selector and translation
                             {:fx/type :grid-pane
                              :grid-pane/column 0
                              :grid-pane/row 1
                              :column-constraints [{:fx/type :column-constraints
                                                    :percent-width 30} ;; word list
                                                   {:fx/type :column-constraints
                                                    :percent-width 70}] ;; translation
                              :row-constraints [{:fx/type :row-constraints
                                                 :percent-height 100}]
                              :children [; word list
                                         (component:word-list (:history input)
                                                              :column 0
                                                              :row 0)
                                         ;; translation
                                         (component:translation "translation" :column 1 :row 0)]}

                             {:fx/type :label
                              :text (str "History: " (-> input :history str))
                              :grid-pane/column 0
                              :grid-pane/row 2}]}}})

(defn new-search! [state word]
  (swap! state #(-> %
                    (history-conj word)
                    (assoc-in [:input :current :original] word))))

(defmulti event-handler :event/type)

(defmethod event-handler ::type [event]
  (swap! *state assoc-in [:input :current] (:fx/event event)))

(defmethod event-handler ::press [event]
  (when (= KeyCode/ENTER (.getCode ^KeyEvent (:fx/event event)))
    (swap! *state #(-> %
                       (update-in [:input :history] conj (-> % :input :current))))))

(defmethod event-handler ::word-selected [event]
  (new-search! *state (:fx/event event)))

(defmethod event-handler ::action [event]
  (let [value (-> event :fx/event .getTarget .getValue)]
    (new-search! *state value)))


(def renderer
  (fx/create-renderer
   :middleware (fx/wrap-map-desc assoc :fx/type stage:main)
   :opts {:fx.opt/map-event-handler event-handler}))

(defn -main [& args]
  (fx/mount-renderer
   *state
   renderer))

(-main)
