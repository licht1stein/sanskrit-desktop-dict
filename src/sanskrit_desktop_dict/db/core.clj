(ns sanskrit-desktop-dict.db.core
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]
            [clojure.core.memoize :as memo]
            [honey.sql :as sql]
            [sanskrit-desktop-dict.helpers :as helpers]))

(def db-uri "jdbc:sqlite::resource:database/dict.db")
(def ds (jdbc/get-datasource {:jdbcUrl db-uri :read-only true :dbtype "sqlite"}))

(defn query! [ds honey-query]
  (jdbc/execute! ds (sql/format honey-query) {:builder-fn rs/as-unqualified-lower-maps}))

(defn assert-db! [ds]
  (->> {:select :id
        :from :dictionary
        :limit 1}
       (query! ds)
       seq
       boolean))

(comment
  (assert-db! ds))

(defn find-language [ds code]
  (let [query (cond-> {:select [:*]
                       :from [:language]}
                (some? code) (merge {:where [:= :code (str code)]}))]
    (query! ds query)))

(defn find-dictionary [ds code]
  (->> {:select [:*]
        :from :dictionary
        :where [:= :code code]}
       (query! ds)))

(defn assoc-direction
  "Takes one dictionary and assocs direction, like English - Sanskrit, or Specialized"
  [d]
  (case (:is_special d)
    0 (assoc d :direction (str (:lfrom d) " - " (:lto d)))
    1 (assoc d :direction "Specialized")))

(defn all-dictionaries [ds]
  (->> {:select [:d/* [:lang-to/name :lto] [:lang-from/name :lfrom]]
        :from [[:dictionary :d]]
        :join [[:language :lang-to] [:= :lang-to.id :d.to_language_id]
               [:language :lang-from] [:= :lang-from.id :d.from_language_id]]
        :order-by [:is_special :lfrom :lto :name]}
       (query! ds)
       (mapv assoc-direction)))

(defn filter-dicts
  "Takes a list of dicts and filters them by direction"
  [to from dicts]
  (filter #(and (= (:lfrom %) to) (= (:lto %) from) (= (:is_special %) 0)) dicts))

(comment
  (def d (all-dictionaries ds))
  (assoc-direction (last d))
  (find-dictionary ds "mw")
  ;; => [{:id 29,
  ;;      :code "mw",
  ;;      :name "Monier-Williams Sanskrit-English Dictionary - 1899",
  ;;      :from_language_id 1,
  ;;      :to_language_id 2,
  ;;      :is_special 0,
  ;;      :is_favorite 1}]
  )

(defn all-words [ds]
  (->> {:select [:*]
        :from :word
        :order-by [:word]}
       (query! ds)))

(defn start-or-ends-with
  ([word words]
   (start-or-ends-with 3 word words))
  ([count-diff word words]
   (let [deva (helpers/word->deva word)]
     (->> (filter #(and (< (count %) (+ (count deva) count-diff))
                        (or (str/starts-with? % deva)
                            (str/ends-with? % deva))) words)
          sort))))

(def memoized-starts-or-ends-with
  (memo/lru
   #(start-or-ends-with % (->> (all-words ds) (map :word)))
   {} :lru/threshold 256))

(comment
  (def words (all-words ds))
  (take 5 words)
  (count words)
  (memoized-starts-or-ends-with "नर")
  (memoized-starts-or-ends-with "raga")
  (find-dictionary ds "ap90")
  (find-language ds nil))

(defn sample [ds dict-code]
  (->> {:select :*
        :from [[:article :a]]
        :join [[:dictionary :d] [:= :d.id :a.dict_id]]
        :where [:= :d.code dict-code]
        :limit 100}
       sql/format
       (jdbc/execute! ds)
       (map :article/article)))

(comment
  (sample ds "mci"))

(defn lookup
  [ds word & {:keys [fav? all? dict lang]}]
  (->> {:select [:word/word :article/article :dictionary/code :dictionary/name :dictionary/is_special [:lang-from/name :lfrom] [:lang-to/name :lto]]
        :from :word
        :left-join [:article_word [:= :word.id :article_word.word_id]
                    :article [:= :article.id :article_word.article_id]
                    :dictionary [:= :dictionary.id :article.dict_id]
                    [:language :lang-to] [:= :lang-to.id :dictionary.to_language_id]
                    [:language :lang-from] [:= :lang-from.id :dictionary.from_language_id]]
        :where [:and [:or [:= :word (str/lower-case word)] [:= :word (helpers/word->deva word)]]
                (cond
                  all? nil
                  fav? [:= :dictionary.is_favorite true]
                  (string? dict) [:= :dictionary.code dict]
                  (seq dict) [:in :dictionary.code dict])

                (when lang [:= :lang-to.code lang])]}
       (query! ds)
       (map assoc-direction)))

(defn group-translations
  "Takes a list of translations and makes a hierarchical map like:
  `{direction {dictionary [articles]}}`"
  [translations]
  (let [by-direction (->> translations (group-by :direction) sort)]
    (->> (for [[direction articles] by-direction]
           {direction (->> articles  (group-by :name) sort (into {}))})
         (into {}))))

(defn merge-translations
  "Takes a coll of articles and str/joins the :article keys with \n"
  [translations]
  (let [texts (map :article translations)]
    (str/join "\n\n" texts)))

(def ext-lookup (partial lookup ds))

(def memoized-lookup
  (memo/lru
   ext-lookup
   {} :lru/threshold 256))

(comment
  (memoized-lookup ds "nara")
  (memoized-lookup ds "nara" {:dict ["ap90" "mw"]})
  (lookup ds "nara" {:dict ["ap90" "mw"]}))
