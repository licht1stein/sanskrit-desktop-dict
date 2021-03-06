(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [tupelo.misc :as tm]
            [clojure.java.io :as io]
            [amazonica.aws.s3 :as s3]
            [morse.api :as t]
            [blaster.clj-fstring :refer [f-str]]
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
         (spit "resources/version.edn"))
    (println new-version)))

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

(def bucket-name "mb-sanskrit-desktop-dict")
(def release-name  (str (str package-name "-" ver ".pkg")))

(defn uploaded? [& {:keys [dir]}]
  (let [files (->> (s3/list-objects :bucket-name bucket-name) :object-summaries (map :key) (into #{}))
        release-name (str (when dir (str dir "/")) (str package-name "-" ver ".pkg"))]
    (files release-name)))

(comment
  (uploaded? :dir "macos-11"))

(defn upload-release [& {:keys [dir]}]
  (let [pkg-files (filter #(.endsWith (.getName %) ".pkg") (file-seq (io/file "target/mac-release")))
        filename (-> pkg-files first .getName)]
    (println (str "Uploading " filename) "to S3. Directory " dir "...")
    (s3/put-object :bucket-name bucket-name
                   :key (if dir (str dir "/" filename) filename)
                   :file (str "target/mac-release/" filename))
    (println "Uploaded succesfully!")))

(defn time+ [seconds]
  (+ (/ (System/nanoTime) 1000) seconds))

(defn publish-release-mac [& {:keys [mac-version]}]
  (let [token (System/getenv "BOT_TOKEN")
        channel (parse-long (System/getenv "RELEASE_CHANNEL"))
        name (str/replace release-name #" " "+")
        url (f-str "https://mb-sanskrit-desktop-dict.s3.eu-central-1.amazonaws.com/{mac-version}/{name}")]
    (println "Sending release to Telegram...")
    (t/send-text token channel
                 {:parse_mode "HTML"}
                 (f-str "Platform: {mac-version}\nVersion: {ver}\n\n<a href=\"{url}\">{release-name}</a>"))))

(comment
  (uploaded? :dir "macos-11")
  (publish-release-mac {:mac-version "macos-11"}))

(defn ci-build-package-upload [opts]
  (if (uploaded? opts)
    (println "This version is already released. Skpping")
    (do
      (ci opts)
      (mac opts)
      (upload-release opts)
      (publish-release-mac opts))))
