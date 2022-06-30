(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]))

(def lib 'net.clojars.licht1stein/sanskrit-desktop-dict)
(def version "0.1.0-SNAPSHOT")
(def main 'sanskrit-desktop-dict.core)

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      #_(bb/run-tests)
      (bb/clean)
      (bb/uber)))
