(ns sanskrit-desktop-dict.helpers
  (:require [clojure.string :as str])
  (:import
   (com.sktutilities.transliteration IASTToSLP SLPToDevanagari)))

(defn long->perc [number]
  (str number "%"))

(defn perc->long [s]
  (-> s
      (str/replace #"%" "")
      parse-long))

(defn devanagari?
  "Returns true if s matches the devanagari regex."
  [s]
  (let [matcher (re-matcher #"[\u0900-\u097F]" s)]
    (some? (re-find matcher))))

(defn iast->deva
  "Transliterate a word from IAST to Devanagari using com.skutilities.transliteration"
  [word]
  (let [slp (IASTToSLP/transform word)]
    (-> (SLPToDevanagari.) (.transform slp))))

(def word->deva
  "Transliterates a word to Devanagari if passed IAST or returns as is if passed a Devanagari."
  (memoize
   (fn [word]
     (if (devanagari? word)
       word
       (iast->deva (str/lower-case word))))))

(comment
  (word->deva "nara"))
