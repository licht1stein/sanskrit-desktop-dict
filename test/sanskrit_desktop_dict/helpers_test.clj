(ns sanskrit-desktop-dict.helpers-test
  (:require [clojure.test :as t]
            [sanskrit-desktop-dict.helpers :as helpers]))

(t/deftest perc->long
  (t/is (= 100 (helpers/perc->long "100%"))))

(t/deftest long->perc
  (t/is (= "100%" (helpers/long->perc 100))))

(t/deftest iast->deva
  (t/is (= "नर" (helpers/iast->deva "nara"))))

(t/deftest devanagari?
  (t/is (false? (helpers/devanagari? "foo")))
  (t/is (true? (helpers/devanagari? "नर"))))

(t/deftest word->deva
  (t/is (= "नर" (helpers/word->deva "नर")))
  (t/is (= "नर" (helpers/word->deva "nara"))))
