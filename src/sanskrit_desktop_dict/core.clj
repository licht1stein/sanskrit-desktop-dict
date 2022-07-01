(ns sanskrit-desktop-dict.core
  (:require [cljfx.api :as fx]
            [cljfx.prop :as fx.prop]
            [cljfx.mutator :as fx.mutator]
            [cljfx.lifecycle :as fx.lifecycle]
            [cljfx.css :as css]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.math :as math]
            [clojure.java.io :as io]
            [clojure.core.cache :as cache]
            [clojure.pprint :refer [pprint]]
            [hiccup.core :refer [html]]
            [taoensso.timbre :as timbre]
            [sanskrit-desktop-dict.helpers :as helpers])
  (:import [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.web WebView])
  (:gen-class))

(def sample "Benfey Sanskrit-English Dictionary - 1866 नर नर, i. e. नृ + अ, m. 1. A man; pl. Men, Man. 1, 96. 2. The Eternal, the divine imperishable spirit pervading the universe, Man. 1, 10. 3. pl. Cer tain fabulous beings, MBh. 2, 396. 4. A proper name, Bhāg. P. 8, 1, 27. — Cf. Lat. Nero, Neriene.")

(def default-settings {:zoom 100
                       :history {:max-size 20}})

(defn save-settings [settings]
  (let [settings-dir (str (System/getProperty "user.home") "/.sanskrit-dict")
        settings-file (str settings-dir "/settings.edn")]
    (spit settings-file settings)))

(defn load-settings []
  (timbre/info ::load-settings :start)
  (let [settings-dir (str (System/getProperty "user.home") "/.sanskrit-dict")
        settings-file (str settings-dir "/settings.edn")]
    (when-not (.exists (io/file settings-dir))
      (timbre/warn ::load-settings "Creating settings directory")
      (.mkdir (io/file settings-dir)))
    (when-not (.exists (io/file settings-file))
      (timbre/warn ::load-settings "Creating settings file with defaults")
      (spit settings-file default-settings))
    (-> settings-file slurp edn/read-string)))


(def *state
  (atom
   (fx/create-context
    {:settings (load-settings)
     :title "Sanskrit Dictionaries"
     :status "Ready!"
     :input {:current {:original ""
                       :transliteration ""
                       :translation ""
                       :dictionaries []}
             :history []}}
    cache/lru-cache-factory)))

;; Subscription functions
(defn sub:title [context]
  (fx/sub-val context get-in [:title]))


;; Event handlers with helpers
(defn history-conj [{:keys [settings input] :as state}  value]
  (let [new-history (-> (take (-  (-> settings :history :max-size) 1) (:history input))
                        (conj value))]
    (assoc-in state [:input :history] new-history)))

(defn new-search! [context word]
  (-> context
      (history-conj word)
      (assoc-in [:input :current :original] word)))

(defmulti event-handler :event/type)

(defmethod event-handler ::new-search [{:keys [value fx/context] :as data}]
  {:context (fx/swap-context context new-search! value)})

(defmethod event-handler ::word-selected [{:keys [fx/event]}]
  {:dispatch {:event/type ::new-search
              :value event}})

(defmethod event-handler ::action [event & args]
  (let [value (-> event :fx/event .getTarget .getValue)]
    {:dispatch {:event/type ::new-search
                :value value}}))

(defmethod event-handler ::save-settings [{:keys [fx/context]}]
  (let [settings (-> context :cljfx.context/m :settings)]
    (timbre/info ::event-handler :save-settings)
    (save-settings settings)
    {:dispatch {:event/type ::temp-status
                :value "Settings saved!"
                :timeout 1}}))

(defmethod event-handler ::temp-status [event]
  (timbre/info ::temp-status event)
  (swap! *state fx/swap-context assoc-in [:status] (:value event))
  (future
    (Thread/sleep (* 1000 (or (:timeout event) 1)))
    (swap! *state fx/swap-context assoc-in [:status] "Ready!")))


(defmethod event-handler ::zoom-change [{:keys [fx/event fx/context]}]
  {:context (fx/swap-context context assoc-in [:settings :zoom] (helpers/perc->long event))
   :dispatch {:event/type ::save-settings}})


(def final-event-handler
  (-> event-handler
      (fx/wrap-co-effects
       {:fx/context (fx/make-deref-co-effect *state)})
      (fx/wrap-effects
       {:context (fx/make-reset-effect *state)
        :dispatch fx/dispatch-effect
        :log #(println "DEBUG |" (str %))})))

(comment
  (final-event-handler {:event/type ::zoom-change
                        :fx/event 166}))

;; Views

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

(defn component:dictionaries-combo [& {:keys [row column]}]
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

(defn stage:main [{:keys [fx/context]}]
  (let [title (fx/sub-ctx context sub:title) ;; sample use of sub function
        input (fx/sub context :input)
        settings (fx/sub context :settings)
        input? (not= "" (-> input :current :original))
        zoom (:zoom settings)
        status (fx/sub context :status)
        style (css/register ::style {".root" {:-fx-font-size (str (* zoom 0.01) "em")}})]
    {:fx/type :stage
     :showing true
     :title title
     :scene {:fx/type :scene
             :stylesheets [(::css/url style)]
             :root {:fx/type :grid-pane
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
                                                    (component:dictionaries-combo :row 0 :column 1)
                                                    {:fx/type :separator}
                                                    {:fx/type :label
                                                     :text "Zoom"}
                                                    {:fx/type :combo-box
                                                     :value (helpers/long->perc zoom)
                                                     :items (map helpers/long->perc [100 125 150 200])
                                                     :on-value-changed {:event/type ::zoom-change}}
                                                    #_{:fx/type :slider
                                                       :value zoom
                                                       :min 100
                                                       :max 200
                                                       :major-tick-unit 25
                                                       :block-increment 5
                                                       :show-tick-marks true
                                                       :on-value-changed {:event/type ::zoom-change}}]}]}

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
                                           (component:translation (if input?
                                                                    (str (-> input :current :original) "<br><br>" sample)
                                                                    "Type and press enter to start searching...") :column 1 :row 0)]}

                               {:fx/type :label
                                :text status
                                :grid-pane/column 0
                                :grid-pane/row 2}]}}}))


(def renderer
  (fx/create-renderer
   :middleware (comp
                ;; Pass context to every lifecycle as part of option map
                fx/wrap-context-desc
                (fx/wrap-map-desc (fn [_] {:fx/type stage:main})))
   :opts {:fx.opt/map-event-handler final-event-handler
          :fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                       ;; For functions in `:fx/type` values, pass
                                       ;; context from option map to these functions
                                       (fx/fn->lifecycle-with-context %))}))

(defn -main [& args]
  (fx/mount-renderer
   *state
   renderer))

(-main)

(comment
  (renderer))
