(ns sanskrit-desktop-dict.helpers-test
  (:require [clojure.test :as t]
            [sanskrit-desktop-dict.helpers :as helpers]))

(t/deftest iast->deva
  (t/is (= "नर" (helpers/iast->deva "nara"))))

(t/deftest devanagari?
  (t/is (false? (helpers/devanagari? "foo")))
  (t/is (true? (helpers/devanagari? "नर"))))
