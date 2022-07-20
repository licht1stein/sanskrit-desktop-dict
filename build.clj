(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [tupelo.misc :as tm]
            [clojure.java.io :as io]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3t]))

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
    (->> "README.md"
         slurp
         str/split-lines
         (#(assoc % 1 (str "Latest version: " new-version)))
         (str/join "\n")
         (spit "README.md"))
    (->> {:version new-version :date (java.util.Date.)}
         (spit "resources/version.edn"))))

(comment
  (slurp "README.md"))

(defn sh-print [& args]
  (let [res (tm/shell-cmd (str/join " " args))]
    (cond
      (not= "" (:out res)) (println (:out res))
      (not= "" (:err res)) (println (:err res)))))

(def ver (current-version))
(def uberjar (str "sanskrit-desktop-dict-" ver ".jar"))

;; Manual: https://centerkey.com/mac/java/
(defn mac "Package the Mac application with jpackage" [opts]
  (let [ver (current-version)
        release-dir (io/file "target/mac-release")
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
    (println "Creating target/mac-release dir")
    (when-not (.exists release-dir) (.mkdir release-dir))
    (println "Moving result to /target")
    (sh-print "mv" "*.pkg" "target/mac-release")))

(defn upload-release [opts]
  (s3/put-object :bucket-name "mb-sanskrit-desktop-dict"
                 :key "deps.edn" ;; uberjar
                 :file "deps.edn" #_(str "target/mac-release/" uberjar)))
