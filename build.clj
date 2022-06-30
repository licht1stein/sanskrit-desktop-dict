(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]))

(def package-name "Sanskrit Dictionary by MB")
(def lib 'net.clojars.licht1stein/sanskrit-desktop-dict)
(def version "0.1.0")
(def main 'sanskrit-desktop-dict.core)

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      #_(bb/run-tests)
      (bb/clean)
      (bb/uber)))

(defn package "Package the application" [opts]
  (let [uberjar (str "sanskrit-desktop-dict-" version ".jar")]
    (when-not (.exists (io/file (str "target/" uberjar)))
      (println  (str "Error: uberjar target/" uberjar " not found. Did you forget to run the ci command?\n\nRun before packaging:\nclj -T:build ci\n"))
      (System/exit 1))
    (println (str "Packaging target/" uberjar))
    (-> (sh  "jpackage" "--name" package-name "--input" "target" "--main-jar" uberjar)
        :out
        println)))
