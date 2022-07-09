(ns sanskrit-desktop-dict.core
  (:require [cljfx.api :as fx]
            [cljfx.prop :as fx.prop]
            [cljfx.mutator :as fx.mutator]
            [cljfx.lifecycle :as fx.lifecycle]
            [cljfx.ext.list-view :as fx.ext.list-view]
            [cljfx.css :as css]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.core.cache :as cache]
            [taoensso.timbre :as timbre]
            [sanskrit-desktop-dict.db.core :as db]
            [sanskrit-desktop-dict.helpers :as helpers])
  (:import [javafx.scene.web WebView])
  (:gen-class))

(def app-name "Sanskrit Dictionaries by MB")
(def app-version (-> "version.edn" io/resource slurp edn/read-string))
(def app-date (->> app-version :date (.format (java.text.SimpleDateFormat. "MMM dd, yyyy"))))
(def app-about-text (-> "about.txt" io/resource slurp))

(def default-settings {:settings {:zoom 100
                                  :history {:max-size 100}}
                       :dictionaries {:selected (->> (db/all-dictionaries db/ds) (mapv :code) (into #{}))}})

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

(comment
  (load-settings))

(def *state
  (let [settings (load-settings)]
    (atom
     (fx/create-context
      {:settings (:settings settings)
       :current-view {:translation :translation}
       :title "Sanskrit Dictionaries"
       :status (str "Version: " (:version app-version))
       :selected-tab "translation"
       :dictionaries {:all (db/all-dictionaries db/ds)
                      :selected (-> settings :dictionaries :selected)}
       :input {:current {:original ""
                         :transliteration ""
                         :translation ""
                         :similar []
                         :dictionaries []}
               :history (or (-> settings :input :history) [])}}
      cache/lru-cache-factory))))

(defn zoom->% [zoom & {:keys [modifier]}]
  (-> zoom
      (* (or modifier 1))
      (str "%")))

;; Subscription functions
(defn sub:title [context]
  (let [word (fx/sub-val context get-in [:input :current :original])
        title (fx/sub-val context get-in [:title])]
    (if (= word "")
      title
      (str word " | " title))))

(defn sub:translation [context]
  (fx/sub-val context get-in [:input :current :translation]))

;; Event handlers with helpers
(defn history-conj [{:keys [settings input] :as state}  value]
  (let [filtered-history (filter (every-pred #(not= (:word %) value) map?) (:history input))
        new-history (-> (take (-  (-> settings :history :max-size) 1) filtered-history)
                        (conj {:word value
                               :ts (quot (System/currentTimeMillis) 1000)}))]
    (assoc-in state [:input :history] (->> new-history (filter map?) (sort-by :ts) reverse))))

(defn new-search! [context word translation similar]
  (-> context
      (history-conj word)
      (assoc-in [:input :current :original] word)
      (assoc-in [:input :current :translation] translation)
      (assoc-in [:input :current :similar] similar)))

(defmulti event-handler :event/type)

(defmethod event-handler ::new-search [{:keys [value fx/context]}]
  (timbre/debug ::new-search)
  (let [selected-dicts (-> context :cljfx.context/m :dictionaries :selected)
        translation (db/memoized-lookup value :dict selected-dicts)
        similar (into [] (db/memoized-starts-or-ends-with value))]
    {:context (fx/swap-context context new-search! value translation similar)
     :dispatch {:event/type ::save-settings}}))

(defmethod event-handler ::word-selected [{:keys [fx/event]}]
  {:dispatch {:event/type ::new-search
              :value event}})

(defmethod event-handler ::action [{:keys [^javafx.event.ActionEvent fx/event]}]
  (let [value (-> event .getTarget .getValue)]
    {:dispatch {:event/type ::new-search
                :value value}}))

(defmethod event-handler ::save-settings [{:keys [fx/context]}]
  (timbre/debug ::event-handler :save-settings)
  (let [state (-> context :cljfx.context/m)
        settings {:settings (->  state :settings)
                  :dictionaries {:selected (-> state :dictionaries :selected)}
                  :input {:history (-> state :input :history)}}]
    (save-settings settings)))

(defmethod event-handler ::temp-status [event]
  (timbre/debug ::temp-status event)
  (swap! *state fx/swap-context assoc-in [:status] (:value event))
  (future
    (Thread/sleep (* 1000 (or (:timeout event) 1)))
    (swap! *state fx/swap-context assoc-in [:status] "Ready!")))

(defmethod event-handler ::zoom-change [{:keys [fx/event fx/context]}]
  {:context (fx/swap-context context assoc-in [:settings :zoom] (helpers/perc->long event))
   :dispatch {:event/type ::save-settings}})

(defn replace-selected-dictionaries [context new-value]
  {:context (fx/swap-context context assoc-in [:dictionaries :selected] new-value)
   :dispatch {:event/type ::save-settings}})

(defmethod event-handler ::tab-selected [{:keys [fx/event fx/context value]}]
  (timbre/debug ::tab-selected {:event event :value value})
  {:context (fx/swap-context context assoc :selected-tab value)})

(defmethod event-handler ::dicionary-selected:all [{:keys [fx/event fx/context value]}]
  (let [dicts (-> context :cljfx.context/m :dictionaries :all)]
    (replace-selected-dictionaries context (if event (into #{} (map :code dicts)) #{}))))

(defmethod event-handler ::dictionary-selected:direction [{:keys [fx/event fx/context value]}]
  (let [[to from] (str/split value #" - ")
        dicts (-> context :cljfx.context/m :dictionaries :all)
        specialized? (= "Specialized" value)
        all-selected (-> context :cljfx.context/m :dictionaries :selected)
        filtered-codes (if-not specialized?
                         (->> dicts (db/filter-dicts to from) (map :code) (into #{}))
                         (->> dicts (filter #(= (:is_special %) 1)) (map :code) (into #{})))]
    (timbre/debug ::dictionary-selected:direction {:value value :event event})
    (replace-selected-dictionaries context (if event (set/union all-selected filtered-codes) (set/difference all-selected filtered-codes)))))

(defmethod event-handler ::dictionary-selected:code [{:keys [fx/event fx/context value]}]
  (let [all-selected (-> context :cljfx.context/m :dictionaries :selected)]
    (timbre/debug ::dictionary-selected:code {:event event :value value :all-selected all-selected})
    (replace-selected-dictionaries context (if event (conj all-selected value) (disj all-selected value)))))

(comment
  (def s #{1 2 3})
  (disj s 1))

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
(defn component:word-input-combo [value items disabled?]
  (cond-> {:fx/type :combo-box
           :editable true
           :max-width 450
           :pref-width 350
           :value value
           :items (map :word items)
           :disable disabled?
           :prompt-text (if-not disabled? "Type and press Enter" "Select at least one dictionary")
           :on-action {:event/type ::action}}))

(def ext-with-html
  (fx/make-ext-with-props
   {:html (fx.prop/make
           (fx.mutator/setter #(.loadContent (.getEngine ^WebView %1) %2))
           fx.lifecycle/scalar)}))

(defn component:html [html & {:keys [row column zoom]}]
  (cond-> {:fx/type ext-with-html
           :props {:html html}
           :desc {:fx/type :web-view
                  :font-scale (if zoom (/ zoom 100) 1.0)}}
    (some? column) (assoc :grid-pane/column column)
    (some? row) (assoc :grid-pane/row row)))

(defn count-rows [s & {:keys [max]}]
  (let [paragraphs (str/split s #"\n")
        para-count (count paragraphs)
        long-lines (->> paragraphs (map #(/ (count %) 100)) (filter #(> % 1)) (reduce +) int)]
    (min (+ para-count 1) (or max 30))))

(defn component:translations [translations]
  (timbre/debug ::component:translation)
  (let [grouped (db/group-translations translations)
        children (for [[direction dictionaries] grouped]
                   (into [{:fx/type :label
                           :style-class "translation-direction"
                           :text direction}
                          (for [[dict articles] dictionaries]
                            (into [{:fx/type :label
                                    :style-class "translation-dictionary"
                                    :text dict}
                                   (let [text (db/merge-translations articles)
                                         row-count (count-rows text)]
                                     {:fx/type :text-area
                                      :editable false
                                      :wrap-text true
                                      :pref-column-count 60
                                      :pref-row-count row-count
                                      :style-class "translation-article"
                                      :text text})]))]))]
    {:fx/type :scroll-pane
     :style-class "translation"
     :content
     {:fx/type :v-box
      :children (flatten children)}}))

(defn list-view [{:keys [items selection selection-mode]}]
  {:fx/type fx.ext.list-view/with-selection-props
   :props (case selection-mode
            :multiple {:selection-mode :multiple
                       :selected-items selection
                       :on-selected-items-changed {:event/type ::word-selected-multiple}}
            :single (cond-> {:selection-mode :single
                             :on-selected-item-changed {:event/type ::word-selected}}
                      (seq selection)
                      (assoc :selected-item (-> selection sort first))))
   :desc {:fx/type :list-view
          :cell-factory {:fx/cell-type :list-cell
                         :describe (fn [path]
                                     {:text path})}
          :items items}})

(defn component:word-list [items & {:keys [row column selection]}]
  {:fx/type list-view
   :items items
   :selection-mode :single
   :selection [selection]
   ;; :grid-pane/column column
   ;; :grid-pane/row row
   ;; :selection (first items)
   ;; :on-selected-item-changed {:event/type ::word-selected}
   })

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

(defn component:top-row [& {:keys [zoom input disable-input?]}]
  (let [history (:history input)
        current-input (-> input :current :original)]
    {:fx/type :v-box
     :grid-pane/column 0
     :grid-pane/row 0
     :children [{:fx/type :tool-bar
                 :items [(component:word-input-combo current-input history disable-input?)
                         {:fx/type :separator}
                         {:fx/type :label
                          :text "Zoom"}
                         (component:zoom-combo zoom)]}]}))

(defn every-selected?
  "Takes a set of dictionary codes `selected-dicts` and a list of `dicts` and checks that
  all of the dictionary codes of the dictionaries are present in the set."
  [selected-dicts dicts]
  (let [dicts-codes (->> dicts (map :code) (into #{}))
        intersection (set/intersection selected-dicts dicts-codes)]
    (= intersection dicts-codes)))

(defn components:dictionaries-checkboxes:children [dicts]
  (let [all-dicts (:all dicts)
        selected-dicts (:selected dicts)
        grouped (group-by :direction all-dicts)
        check-box (fn [text selected? event-type on-selected & {:keys [style-class]}]
                    (cond->
                     {:fx/type :check-box
                      :padding 5
                      :text text
                      :selected selected?
                      :on-selected-changed {:event/type event-type
                                            :value on-selected}}
                      (some? style-class) (assoc :style-class style-class)))]
    (->>
     (for [[direction dictionaries] grouped]
       (into [{:fx/type :separator :padding 5}
              {:fx/type :label :text direction :padding 5 :style-class "dictionaries-label"}
              (check-box (str "All " direction)
                         (every-selected? selected-dicts dictionaries) ::dictionary-selected:direction direction)]
             (for [d dictionaries]
               (check-box (:name d) (some? (selected-dicts (:code d))) ::dictionary-selected:code (:code d)))))
     flatten
     (into [(check-box "All Dictionaries" (= (count all-dicts) (count selected-dicts)) ::dicionary-selected:all "all-dicts")]))))

(comment
  (def dicts (db/all-dictionaries db/ds))
  (every? :selected? dicts)
  (components:dictionaries-checkboxes:children dicts))

(defn components:dictionaries-checkboxes [dicts & {:keys [row column]}]
  (cond-> {:fx/type :v-box
           :style-class "dictionaries"
           :children (components:dictionaries-checkboxes:children dicts)}
    (some? column) (assoc :grid-pane/column column)
    (some? row) (assoc :grid-pane/row 0)))

(comment
  (-> (db/lookup db/ds "nara" :dict ["mw"]) first))

(defn component:middle-row [& {:keys [dicts input row column translation settings]}]
  (timbre/debug ::component:middle-row:translation {:translation (count translation)})
  {:fx/type :grid-pane
   :grid-pane/column column
   :grid-pane/row row
   :column-constraints [{:fx/type :column-constraints
                         :percent-width 20} ;; word list
                        {:fx/type :column-constraints
                         :percent-width 80}] ;; translation
   :row-constraints [{:fx/type :row-constraints
                      :percent-height 100}]
   :children [; word list
              (component:word-list (-> input :current :similar)
                                   :column 0
                                   :row 0
                                   :selection (-> input :current :original helpers/word->deva))
              ;; translation
              {:fx/type :tab-pane
               :grid-pane/row 0
               :grid-pane/column 1
               :side :top
               :tabs [{:fx/type :tab
                       :graphic {:fx/type :label :text "Translation"}
                       :id "translation"

                       :on-selection-changed {:event/type ::tab-selected
                                              :value "translation"}
                       :closable false
                       :content (cond
                                  (not-empty translation) (component:translations translation) #_(component:translations-html translation :settings settings)
                                  (= "" translation) {:fx/type :label :text "Type and press enter to start searching..."}
                                  (empty? translation) (component:html "Nothing found. Try other dictionaries.") :zoom (:zoom settings))}
                      {:fx/type :tab
                       :id "dictionaries"
                       :graphic {:fx/type :label :text "Dictionaries"}
                       :on-selection-changed {:event/type ::tab-selected
                                              :value "dictionaries"}
                       :closable false
                       :content {:fx/type :scroll-pane
                                 :style-class "dictionaries"
                                 :content (components:dictionaries-checkboxes dicts)}}

                      {:fx/type :tab
                       :id "about"
                       :graphic {:fx/type :label :text "About"}
                       :closable false
                       :content {:fx/type :v-box
                                 :style-class "about"
                                 :children [{:fx/type :label
                                             :style-class "about-heading"
                                             :text app-name}
                                            {:fx/type :label
                                             :style-class "about-version"
                                             :text (str "Version " (:version app-version) " from " app-date)}
                                            {:fx/type :separator}
                                            {:fx/type :label
                                             :style-class "about-text"
                                             :wrap-text true
                                             :text app-about-text}]}}]}]})

(comment
  (zoom->% 1 :modifier 1.1))

(defn generate-style [settings]
  (let [zoom (:zoom settings)]
    {".root" {:-fx-font-size (zoom->% zoom)}
     ".toolbar" {:-fx-background-color :white}
     ".input" {:-fx-font-weight :bolder}
     ".about" {:-fx-background-color :white
               :-fx-padding 5
               "-heading" {:-fx-font-size (zoom->% zoom :modifier 1.5)}}
     ".translation" {:-fx-background-color :white
                     "-direction" {:-fx-font-size (zoom->% zoom :modifier 1.3)
                                   :-fx-padding [0 0 5 0]}
                     "-dictionary" {:-fx-font-size (zoom->% zoom :modifier 1.1)
                                    :-fx-padding [15 0 5 0]}
                     "-word" {:-fx-font-size (zoom->% zoom :modifier 1.2)
                              :-fx-font-weight :bolder
                              :-fx-padding [10 0 10 0]}
                     "-article" {:-fx-background-color :white
                                 :-fx-display-caret false
                                 :.scroll-bar:vertical {:-fx-opacity 0}}}
     ".dictionaries" {:-fx-background-color :white
                      "-label" {:-fx-font-weight :bolder
                                :-fx-font-size (zoom->% zoom :modifier 1.05)
                                :-fx-text-fill :black}}}))

(defn stage:main [{:keys [fx/context]}]
  (timbre/debug ::stage:main)
  (let [title (fx/sub-ctx context sub:title) ;; sample use of sub function
        input (fx/sub-val context :input)
        translation (fx/sub-ctx context sub:translation)
        settings (fx/sub-val context :settings)
        input? (not= "" (-> input :current :original))
        zoom (:zoom settings)
        dicts (fx/sub-val context :dictionaries)
        status (fx/sub-val context :status)
        style (css/register ::style (generate-style settings))]
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

                               (component:top-row :column 0 :row 0 :zoom zoom :input input :disable-input? (empty? (:selected dicts)))

                               ;; Middle row: word selector and translation
                               (component:middle-row :row 1 :column 0 :input input :input? input? :dicts dicts :translation translation :settings settings)

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

(comment
  (-main)
  (renderer))
