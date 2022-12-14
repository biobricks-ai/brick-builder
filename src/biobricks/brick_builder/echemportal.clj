(ns biobricks.brick-builder.echemportal
  (:require [babashka.fs :as fs]
            [biobricks.brick-builder.util :as util]
            [clojure-csv.core :as csv]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [etaoin.api :as ea]
            [lambdaisland.uri :as uri]))

;; Crawl

(def substance-search-uri "https://www.echemportal.org/echemportal/substance-search")

(defn click-csv-button? [driver]
  (let [csv-query [{:tag :echem-export-buttons}
                   {:tag :button}
                   {:fn/text "CSV"}]
        no-matches-query {:fn/text "Your search criteria didn't match any substance."}]
    (loop [[_ & more] (range 60)]
      (cond
        (ea/visible? driver csv-query)
        (do (ea/click-visible driver csv-query) true)

        (ea/visible? driver no-matches-query)
        false

        more
        (do (Thread/sleep 500) (recur more))

        :else
        (throw (RuntimeException. "Failed to get search results"))))))

(defn substance-search-results [download-dir search-term]
  (let [uri (str substance-search-uri "?"
                 (uri/map->query-string {:query_term search-term}))
        opts {:download-dir download-dir
              :headless true
              :user-agent "biobricks.ai brick builder"}]
    (ea/with-wait-timeout 30
      (ea/with-driver :chrome opts driver
        (ea/go driver uri)
        (when (click-csv-button? driver)
          (loop [[_ & more] (range 60)]
            (let [results-file (->> (fs/list-dir download-dir)
                                    (filter #(= "result.csv" (fs/file-name %)))
                                    first)]
              (or
               results-file
               (when more
                 (Thread/sleep 500)
                 (recur more))))))))))

(defn crawl-substances []
  (let [target-dir (fs/path "target" "echemportal")]
    (fs/create-dirs target-dir)
    (doseq [i (range 10 999)]
      (util/retry
       {:interval-ms 1000
        :n 10
        :throw-pred (partial instance? InterruptedException)}
       (fs/with-temp-dir [dir {:prefix "brick-builder"}]
         (if-let [results (substance-search-results
                           dir
                           (str i (when (<= 100 i) "*") "-*-*"))]
           (do
             (fs/move results (fs/path target-dir (str i ".csv")))
             (log/info "echemportal: Saved results for" i))
           (log/info "echemportal: No results for" i)))))))

(comment
  ;; Download search results
  (fs/with-temp-dir [dir {:prefix "brick-builder"}]
    (-> (substance-search-results (str dir) "108-*-*")
        fs/file slurp))

  ;; Empty results return nil
  (fs/with-temp-dir [dir {:prefix "brick-builder"}]
    (substance-search-results (str dir) "05-*-*"))

  ;; Run crawler
  (def crawler (future (crawl-substances)))
  (future-cancel crawler))

;; Convert search results to parquet

(def csv-header
  ["Substance Name"
   "Name type"
   "Substance Number"
   "Number type"
   "Remark"
   "Level"
   "Result link"
   "Source"
   "GHS data"
   "Property data"
   ""])

(defn fixup-row [row]
  (cond
    (= 10 (count row)) row

    ; Fix unquoted urls with commas in them
    (-> (nth row 6) uri/uri :scheme)
    (let [i (-> row count (- 10) (+ 7))]
      (-> (subvec row 0 6)
          (into [(str/join "," (subvec row 6 i))])
          (into (subvec row i))))

    :else row))

(defn collect-results [dir]
  (loop [[file & more] (fs/list-dir dir)
         results (transient #{})]
    (if-not file
      (persistent! results)
      (let [text (-> file fs/file slurp)
            [header & rows] (try
                              (-> text csv/parse-csv doall)
                              (catch Exception e
                                (throw (ex-info (str "Error parsing " file ": " (ex-message e))
                                                {:cause e
                                                 :file file
                                                 :text text}))))
            rows (map fixup-row rows)]
        (when-not (= header csv-header)
          (throw (ex-info "Unexpected CSV header"
                          {:actual header :expected csv-header})))
        (doseq [row rows]
          (when (not= 10 (count row))
            (throw (ex-info (str "Wrong number of columns (" (count row) "), expected 10.")
                            {:actual (count row)
                             :expected 10
                             :file file
                             :row row}))))
        (recur more (reduce conj! results rows))))))

(defn write-csv [file results]
  (->> results
       (sort-by
        (fn [[_ _ num]]
          (or (some-> num (str/replace "-" "") parse-long)
              Long/MAX_VALUE)))
       (cons (pop csv-header))
       csv/write-csv
       (spit (fs/file file)))
  file)

(defn write-parquet [file results]
  (fs/with-temp-dir [dir {:prefix "brick-builder"}]
    (let [csv (fs/path dir "echemportal.csv")
          parquet (fs/path dir "echemportal.parquet")]
      (write-csv csv results)
      (sh/sh "csv2parquet" (str csv) (str parquet))
      (fs/move parquet file {:replace-existing true})))
  file)

(comment
  (do
    (def results (collect-results (fs/path "target" "echemportal")))
    (count results))
  (write-csv (fs/path "target" "echemportal.csv") results)
  (write-parquet (fs/path "target" "echemportal.parquet") results)
  )
