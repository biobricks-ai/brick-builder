(ns biobricks.brick-builder.echemportal
  (:require [babashka.fs :as fs]
            [etaoin.api :as ea]
            [lambdaisland.uri :as uri]))

(def substance-search-uri "https://www.echemportal.org/echemportal/substance-search")

(defn substance-search-results [download-dir search-term]
  (let [uri (str substance-search-uri "?"
                 (uri/map->query-string {:query_term search-term}))
        opts {:download-dir download-dir}]
    (ea/with-wait-timeout 30
      (ea/with-driver :chrome opts driver
        (doto driver
          (ea/go uri)
          (ea/click-visible [{:tag :echem-export-buttons}
                             {:tag :button}
                             {:fn/text "CSV"}]))
        (loop [[i & more] (range 60)]
          (let [results-file (->> (fs/list-dir download-dir)
                                  (filter #(= "result.csv" (fs/file-name %)))
                                  first)]
            (or
             results-file
             (when more
               (Thread/sleep 500)
               (recur more)))))))))

(comment
  (fs/with-temp-dir [dir {:prefix "brick-builder"}]
    (-> (substance-search-results (str dir) "108-*-*")
        fs/file slurp)))
