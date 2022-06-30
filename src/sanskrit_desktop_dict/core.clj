(ns sanskrit-desktop-dict.core
  (:require [cljfx.api :as fx]
            [cljfx.prop :as fx.prop]
            [cljfx.mutator :as fx.mutator]
            [cljfx.lifecycle :as fx.lifecycle]
            [cljfx.css :as css]
            [clojure.string :as str]
            [hiccup.core :refer [html]])
  (:import [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.web WebView])
  (:gen-class))

(def sample "Benfey Sanskrit-English Dictionary - 1866 नर नर, i. e. नृ + अ, m. 1. A man; pl. Men, Man. 1, 96. 2. The Eternal, the divine imperishable spirit pervading the universe, Man. 1, 10. 3. pl. Cer tain fabulous beings, MBh. 2, 396. 4. A proper name, Bhāg. P. 8, 1, 27. — Cf. Lat. Nero, Neriene.")

(def *state (atom {:settings {:zoom 100
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
   :max-width 450
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

(defn perc->long [s]
  (-> s
      (str/replace #"%" "")
      parse-long))

(defn long->perc [i]
  (str i "%"))

(comment
  (perc->long "110%")
  (long->perc 100))


(defn stage:main [{:keys [input title status settings] :as state}]
  (let [input? (not= "" (-> input :current :original))
        zoom (-> settings :zoom)
        style (css/register ::style
                            {".root" {:-fx-font-size (str (* zoom 0.01) "em")}})]
    {:fx/type :stage
     :showing true
     :title title
     :scene {:fx/type :scene
             :stylesheets [(::css/url style)]
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
                                                    {:fx/type :combo-box
                                                     :value (-> settings :zoom long->perc)
                                                     :items (map long->perc [90 95 100 110 125 150 175 200])
                                                     :on-action {:event/type ::zoom}}]}]}

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
                                           (component:translation (if input? sample "Type and press enter to start searching...") :column 1 :row 0)]}

                               {:fx/type :label
                                :text (str "History: " (-> input :history str))
                                :grid-pane/column 0
                                :grid-pane/row 2}]}}}))

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

(defmethod event-handler ::zoom [event]
  (let [value (-> event :fx/event .getTarget .getValue)]
    (swap! *state assoc-in [:settings :zoom] (perc->long value))))


(def renderer
  (fx/create-renderer
   :middleware (fx/wrap-map-desc assoc :fx/type stage:main)
   :opts {:fx.opt/map-event-handler event-handler}))

(defn -main [& args]
  (fx/mount-renderer
   *state
   renderer))

(-main)
