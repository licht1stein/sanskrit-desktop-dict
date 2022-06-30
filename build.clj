(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]))

(def package-name "Sanskrit Dictionary by MB")
(def lib 'net.clojars.licht1stein/sanskrit-desktop-dict)
(def version "1.0.0")
(def main 'sanskrit-desktop-dict.core)

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      #_(bb/run-tests)
      (bb/clean)
      (bb/uber)))

(defn replace-icon [opts]
  (let [icon-file (io/file "resources/app-icon.icns")]
    (io/copy icon-file (io/file (str package-name ".app/Contents/Resources/" package-name ".icns")))))

(defn package-image [opts]
  (println (sh  "jpackage"
                "--name" package-name
                "--app-image" (str  "./" package-name ".app"))))

(defn package "Package the application with jpackage" [opts]
  (let [uberjar (str "sanskrit-desktop-dict-" version ".jar")
        uberjar-exists? (.exists (io/file (str "target/" uberjar)))
        icon-file (io/file "resources/app-icon.icns")
        icon-exists? (.exists icon-file)]
    (when-not uberjar-exists?
      (println  (str "Error: uberjar target/" uberjar " not found. Did you forget to run the ci command?\n\nRun before packaging:\nclj -T:build ci\n"))
      (System/exit 1))
    (println (str "Packaging target/" uberjar " as image"))
    (let [result (-> (sh  "jpackage"
                          "--name" package-name
                          "--input" "target"
                          "--main-jar" uberjar
                          "--app-version" version
                          "--copyright" "Mikhail Beliansky"
                          "--type" "app-image"))]
      (println result)
      (println "Replacing icon...")
      (replace-icon nil)
      (println "Packaging image...")
      (package-image nil)
      (println "Cleaning up...")
      (println (sh "rm" "-rf" (str package ".app"))))))
