(ns sanskrit-desktop-dict.core
  (:require [cljfx.api :as fx]
            [cljfx.prop :as fx.prop]
            [cljfx.mutator :as fx.mutator]
            [cljfx.lifecycle :as fx.lifecycle]
            [cljfx.css :as css]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.core.cache :as cache]
            [hiccup.core :refer [html]]
            [taoensso.timbre :as timbre]
            [sanskrit-desktop-dict.db.core :as db]
            [sanskrit-desktop-dict.helpers :as helpers])
  (:import [javafx.scene.web WebView])
  (:gen-class))

(def sample "Benfey Sanskrit-English Dictionary - 1866 नर नर, i. e. नृ + अ, m. 1. A man; pl. Men, Man. 1, 96. 2. The Eternal, the divine imperishable spirit pervading the universe, Man. 1, 10. 3. pl. Cer tain fabulous beings, MBh. 2, 396. 4. A proper name, Bhāg. P. 8, 1, 27. — Cf. Lat. Nero, Neriene.")
`(def default-settings {:zoom 100
                        :history {:max-size 20}})

(defn save-settings [settings]
  (let [settings-dir (str (System/getProperty "user.home") "/.sanskrit-dict")
        settings-file (str settings-dir "/settings.edn")]
    (spit settings-file settings)))

(defn load-settings []
  (timbre/debug ::load-settings :start)
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
     :current-view {:translation :translation}
     :title "Sanskrit Dictionaries"
     :status "Ready!"
     :dictionaries (db/all-dictionaries db/ds)
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
    (assoc-in state [:input :history] (into [] new-history))))

(defn new-search! [context word]
  (-> context
      (history-conj word)
      (assoc-in [:input :current :original] word)))

(defmulti event-handler :event/type)

(defmethod event-handler ::toggle-dictionaries [{:keys [fx/event fx/context]}]
  (timbre/debug ::toggle-dictionaries)
  (let [mode (-> context :cljfx.context/m :current-view :translation)
        _ (timbre/debug ::toggle-dictionaries {:old-mode mode})
        new-mode (if (= mode :translation) :dict-selector :translation)]
    (timbre/debug ::toggle-dictionaries {:new-mode new-mode})
    {:context (fx/swap-context context assoc-in [:current-view :translation] new-mode)}))

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
    (timbre/debug ::event-handler :save-settings)
    (save-settings settings)
    {:dispatch {:event/type ::temp-status
                :value "Settings saved!"
                :timeout 1}}))

(defmethod event-handler ::temp-status [event]
  (timbre/debug ::temp-status event)
  (swap! *state fx/swap-context assoc-in [:status] (:value event))
  (future
    (Thread/sleep (* 1000 (or (:timeout event) 1)))
    (swap! *state fx/swap-context assoc-in [:status] "Ready!")))


(defmethod event-handler ::zoom-change [{:keys [fx/event fx/context]}]
  {:context (fx/swap-context context assoc-in [:settings :zoom] (helpers/perc->long event))
   :dispatch {:event/type ::save-settings}})

(defmethod event-handler ::dicionary-selected [{:keys [fx/event fx/context]}]
  (timbre/debug ::dicionary-selected event))

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

(def ext-with-html
  (fx/make-ext-with-props
   {:html (fx.prop/make
           (fx.mutator/setter #(.loadContent (.getEngine ^WebView %1) %2))
           fx.lifecycle/scalar)}))

(defn component:translation [html & {:keys [row column]}]
  (cond-> {:fx/type ext-with-html
           :props {:html html}
           :desc {:fx/type :web-view}}
    (some? column) (assoc :grid-pane/column column)
    (some? row) (assoc :grid-pane/row row)))

(defn component:word-list [items & {:keys [row column]}]
  {:fx/type :list-view
   :items items
   :grid-pane/column column
   :grid-pane/row row
   :on-selected-item-changed {:event/type ::word-selected}})

(defn component:zoom-combo [value]
  {:fx/type :combo-box
   :value (helpers/long->perc value)
   :visible-row-count 4
   :items (map helpers/long->perc [100 125 150 200])
   :on-value-changed {:event/type ::zoom-change}})

(defn component:statusbar [text & {:keys [row column]}]
  (cond-> {:fx/type :label
           :text text}
    (some? column) (assoc :grid-pane/column column)
    (some? row) (assoc :grid-pane/row row)))

(defn components:dictionaries-checkboxes [dicts & {:keys [row column]}]
  (cond-> {:fx/type :v-box
           :children (into [{:fx/type :label
                             :text "Dictionaries"}] (for [d dicts] {:fx/type :check-box
                                                                    :text (:name d)}))}
    (some? column) (assoc :grid-pane/column column)
    (some? row) (assoc :grid-pane/row 0)))

(defn component:dictionaries-tree [dicts]
  (let [items (for [d dicts]
                {:fx/type :tree-cell})]
    {:fx/type :tree-view
     :selection-mode :multiple
     :grid-pane/row 0
     :grid-pane/column 1
     :root {:fx/type :tree-item
            :value "All Dictionaries"
            :expanded true
            :children [{:fx/type :tree-item
                        :expanded true
                        :value "Sanskrit -> English"
                        :children [{:fx/type :tree-item
                                    :value "Apte"}
                                   {:fx/type :tree-item
                                    :value "MW"}]}]}}))

(defn component:top-row [& {:keys [zoom row column input]}]
  {:fx/type :v-box
   :grid-pane/column 0
   :grid-pane/row 0
   :children [{:fx/type :tool-bar
               :items [(component:word-input-combo (-> input :current :original) (:history input))
                       {:fx/type :separator}
                       {:fx/type :label
                        :text "Zoom"}
                       (component:zoom-combo zoom)]}]})

(defn component:middle-row:translation [& {:keys [input? dicts input row column]}]
  (timbre/debug ::component:middle-row:translation)
  {:fx/type :grid-pane
   :grid-pane/column column
   :grid-pane/row row
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
              {:fx/type :tab-pane
               :grid-pane/row 0
               :grid-pane/column 1
               :tabs [{:fx/type :tab
                       :text "Translation"
                       :closable false
                       :content (component:translation (if input? (str (-> input :current :original) "<br><br>" sample)
                                                           "Type and press enter to start searching..."))}
                      {:fx/type :tab
                       :text "Dictionaries"
                       :closable false
                       :content (components:dictionaries-checkboxes dicts)}]}]})


(defn stage:main [{:keys [fx/context]}]
  (timbre/debug ::stage:main)
  (let [title (fx/sub-ctx context sub:title) ;; sample use of sub function
        current-view (fx/sub-val context :current-view)
        input (fx/sub-val context :input)
        settings (fx/sub-val context :settings)
        input? (not= "" (-> input :current :original))
        zoom (:zoom settings)
        dicts (fx/sub-val context :dictionaries)
        status (fx/sub-val context :status)
        style (css/register ::style {".root" {:-fx-font-size (str (* zoom 0.01) "em")}})]
    (timbre/debug ::stage:main :loaded-subs {:settings settings})
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
                                       :percent-height 10} ;; toolbar row
                                      {:fx/type :row-constraints
                                       :percent-height 85} ;; word selector & translation row
                                      {:fx/type :row-constraints
                                       :percent-height 5}] ;; status bar row
                    :children [;; Top row: toolbar (input, dictionaries, zoom)
                               (component:top-row :column 0 :row 0 :zoom zoom :input input)

                               ;; Middle row: word selector and translation
                               (component:middle-row:translation :row 1 :column 0 :input input :input? input? :dicts dicts)

                               ;; Bottom row
                               (component:statusbar status :column 0 :row 2)]}}}))


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
