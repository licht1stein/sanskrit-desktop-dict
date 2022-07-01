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

(t/deftest new-search!
  (let [new-context (core/new-search! test-context "foo")
        input (:input new-context)
        current (:current input)
        history (:history input)]
    ;; Check that only :input has changed
    (t/is (= (dissoc test-context :input) (dissoc new-context :input))
          "new-search! has changed context outside of :input")
    (t/is (= current {:original "foo"
                      :translation ""
                      :transliteration ""
                      :dictionaries []}))
    (t/is (= history ["foo"]))))
