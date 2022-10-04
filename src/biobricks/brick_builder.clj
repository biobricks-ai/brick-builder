(ns biobricks.brick-builder
  (:require [babashka.fs :as fs]
            [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn brick-def [id]
  (some-> (str "brick-builder/bricks/" id ".edn")
          io/resource
          slurp
          edn/read-string))

(defn check-sha256 [file sha256]
  (let [{:keys [exit out] :as proc} (sh/sh "sha256sum" "-b" (str file))
        exdata {:expected sha256 :file file :process proc}]
    (if (not= 0 exit)
      (throw (ex-info (str "sha256sum exited with code" exit) exdata))
      (let [result (str/split out #"\s+")]
        (if (= sha256 (first result))
          sha256
          (throw (ex-info (str "Hash mismatch. Expected: " sha256 ". Actual: " (first result) ".")
                          (assoc exdata :actual (first result)))))))))

(defn load-data [{:keys [sha256 uri]}]
  (let [path (fs/path "data" sha256)]
    (if (fs/exists? path)
      path
      (fs/with-temp-dir [dir {:prefix "brick-builder"}]
        (let [{:keys [body status] :as response} (http/get uri)
              file (fs/create-file (fs/path dir sha256))]
          (if (not= 200 status)
            (throw (ex-info (str "Unexpected status: " status)
                            {:response response}))
            (do
              (io/copy body (fs/file file))
              (check-sha256 file sha256)
              (io/make-parents (fs/file path))
              (fs/move file path)
              path)))))))

(comment
  (-> (brick-def "comptox")
      :data
      (nth 2)
      load-data))
