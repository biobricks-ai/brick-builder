(ns biobricks.brick-builder.util
  (:require [clojure.tools.logging :as log]))

(def retry-recur-val ::retry-recur)

(defmacro retry
  "Retries body up to n times, doubling interval-ms each time
   and adding jitter.
   
   If throw-pred is provided, it will be called on the exception. If
   throw-pred returns true, the exception is re-thrown and the body is
   not retried."
  [opts & body]
  `(let [opts# ~opts
         throw-pred# (or (:throw-pred opts#) (constantly false))]
     (loop [interval-ms# (:interval-ms opts#)
            n# (:n opts#)]
     ;; Can't recur from inside the catch, so we use a special return
     ;; value to signal the need to recur.
       (let [ret#
             (try
               ~@body
               (catch Exception e#
                 (if (and (pos? n#) (not (throw-pred# e#)))
                   (do
                     (log/info e# "Retrying after" interval-ms# "ms due to Exception")
                     retry-recur-val)
                   (throw e#))))]
         (if (= ret# retry-recur-val)
           (do
             (Thread/sleep interval-ms#)
             (recur (+ interval-ms# interval-ms# (.longValue ^Integer (rand-int 100))) (dec n#)))
           ret#)))))
