(ns sanskrit-desktop-dict.core-test
  (:require [clojure.test :as t]
            [sanskrit-desktop-dict.core :as core]
))

(def test-context
  {:settings {:zoom 100
              :history {:max-size 20}}
   :title "Sanskrit Dictionaries"
   :status "Ready!"
   :input {:current {:original ""
                     :transliteration ""
                     :translation ""
                     :dictionaries []}
           :history []}})
