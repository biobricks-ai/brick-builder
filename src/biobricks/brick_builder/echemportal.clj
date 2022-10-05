(ns biobricks.brick-builder.echemportal
  (:require [babashka.fs :as fs]
            [etaoin.api :as ea]
            [lambdaisland.uri :as uri]))

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
          (loop [[i & more] (range 60)]
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
    (doseq [i (range 10 9999999)]
      (fs/with-temp-dir [dir {:prefix "brick-builder"}]
        (if-let [results (substance-search-results dir (str i "-*-*"))]
          (do
            (fs/move results (fs/path target-dir (str i ".csv")))
            (prn "echemportal: Saved results for" i))
          (prn "echemportal: No results for" i))))))

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
  )
