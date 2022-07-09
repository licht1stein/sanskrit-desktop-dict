(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [tupelo.misc :as tm]
            [clojure.java.io :as io]))

(def package-name "Sanskrit Dictionaries by MB")
(def lib 'net.clojars.licht1stein/sanskrit-desktop-dict)
(def main 'sanskrit-desktop-dict.core)

(defn current-version []
  (-> "resources/version.edn" slurp edn/read-string :version))

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (-> opts
      (assoc :lib lib :version (current-version) :main main)
      (bb/run-tests)
      (bb/clean)
      (bb/uber)))

(defn print->
  "Prints v and returns it"
  [v]
  (println v)
  v)

(defn version
  "Bumps version in the .version file. Accepts :bump [:major :minor :patch]"
  [{:keys [bump]}]
  (let [[major minor patch] (map parse-long (str/split (current-version) #"\."))
        new-version (->> (case bump
                           :major [(inc major) 0 0]
                           :minor [major (inc minor) 0]
                           :patch [major minor (inc patch)])
                         (str/join "."))]
    (->> {:version new-version :date (java.util.Date.)}
         (spit "resources/version.edn"))))

(defn replace-icon [opts]
  (let [icon-file (io/file "resources/app-icon.icns")]
    (io/copy icon-file (io/file (str package-name ".app/Contents/Resources/" package-name ".icns")))))

(defn sh-print [& args]
  (let [res (tm/shell-cmd (str/join " " args))]
    (cond
      (not= "" (:out res)) (println (:out res))
      (not= "" (:err res)) (println (:err res)))))

;; Manual: https://centerkey.com/mac/java/
(defn mac "Package the Mac application with jpackage" [opts]
  (let [ver (current-version)
        uberjar (str "sanskrit-desktop-dict-" ver ".jar")
        uberjar-exists? (.exists (io/file (str "target/" uberjar)))]
    (when-not uberjar-exists?
      (println  (str "Error: uberjar target/" uberjar " not found. Did you forget to run the ci command?\n\nRun before packaging:\nclj -T:build ci\n"))
      (System/exit 1))
    (println (str "Packaging target/" uberjar))
    (sh "jpackage"
        "--name" package-name
        "--input" "."
        "--main-jar" (str "target/" uberjar)
        "--app-version" ver
        "--copyright" "Mikhail Beliansky"
        "--resource-dir" "resources/package/macos"
        "--mac-package-identifier" "SanskritDictionariesByMB"
        "--type" "pkg")
    (println "Moving result to /target")
    (sh-print "mv" "*.pkg" "target/")))
