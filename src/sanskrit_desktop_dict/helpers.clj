(ns sanskrit-desktop-dict.helpers
  (:require [clojure.string :as str]))

(defn long->perc [number]
  (str number "%"))

(defn perc->long [s]
  (-> s
      (str/replace #"%" "")
      parse-long))
