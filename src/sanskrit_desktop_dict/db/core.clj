(ns sanskrit-desktop-dict.db.core
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]
            [clojure.core.memoize :as memo]
            [honey.sql :as sql]
            [taoensso.timbre :as timbre]
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
  (group-dictionaries d)
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

(defn lookup-by-id [ds word-id {:keys [dict]}]
  (->> {:select :*
        :from [:article_word]}))

(comment
  (def words (all-words ds))
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
  ([ds word]
   (lookup ds word {}))
  ([ds word & {:keys [fav? all? dict lang]}]
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
        (query! ds))))

(def ext-lookup (partial lookup ds))

(def memoized-lookup
  (memo/lru
   ext-lookup
   {} :lru/threshold 256))

(comment
  (memoized-lookup ds "nara")
  (memoized-lookup ds "nara" {:dict ["ap90" "mw"]})
  (lookup ds "nara" {:dict ["ap90" "mw"]}))

(defn group-translation
  "Takes a translation produced by lookup and groups it first by direction [Sanskrit English], then by the code of dictionary."
  [t]
  (let [by-special (group-by :is_special t)
        normal (by-special 0)
        special (by-special 1)]
    {:normal (->> (for [[a b] (group-by (juxt :lfrom :lto) normal)]
                    [{a (group-by :name b)}])
                  flatten
                  (apply merge))
     :special (group-by :name special)}))

(defn format-normal [t]
  (let [mapped (->> (for [[dir trans] (:normal (group-translation t))]
                      {(str "<b>" (first dir) " -> " (last dir) "</b>")
                       (for [[d-name articles] trans]
                         (str "\n\n📖\n<b>" d-name "</b>\n" (str/join "\n\n* * *\n\n" (map :article articles))))})
                    (reduce merge))]
    (str/join "" (for [[direction trans] mapped]
                   (str (str/upper-case direction) (str/join trans) "\n\n")))))

(defn format-special [t]
  (if (:special t)
    (str "<b>Specialized Dictionaries</b>" (str/join (for [[d-name trans] (:special (group-translation t))]
                                                       (str "\n\n📖\n<b>" d-name "</b>\n" (str/join "\n\n* * *\n\n" (map :article trans))))))
    ""))

(defn format-translations [t]
  (str (format-normal t) "\n\n" (format-special t)))
