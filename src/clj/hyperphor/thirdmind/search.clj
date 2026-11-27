(ns hyperphor.thirdmind.search
  "Full-text search over an ingested graph (Tunamelt's FTS index); reports matching pages."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hyperphor.thirdmind.config :as config]
            [hyperphor.tunamelt.core :as t]))

(defn search
  "Pages whose own text or any block matches query (see t/search; opts passed through), as
  [{:uri :name :page-match? :hits [{:uri :snippet} ...]}]: page matches first, then by hit count."
  ([query] (search query {}))
  ([query opts]
   (let [results (t/search query (dissoc opts :type))
         page?   (comp #{"page"} :type :meta)
         matched (into #{} (comp (filter page?) (map :uri)) results)
         by-page (group-by (comp :page :obj) (filter (comp #{"block"} :type :meta) results))]
     (->> (into matched (remove nil?) (keys by-page))
          (map (fn [uri]
                 {:uri         uri
                  :name        (or (:name (t/get uri)) uri)
                  :page-match? (contains? matched uri)
                  ;; t/search is best-first, so hits stay in rank order
                  :hits        (mapv #(select-keys % [:uri :snippet]) (by-page uri))}))
          (sort-by (juxt (complement :page-match?) (comp - count :hits) :name))))))

(defn- one-line [s] (str/trim (str/replace (str s) #"\s+" " ")))

(defn -main
  "Usage: lein search <query> [db-path] [--snippets] [--raw]; the db defaults to the config's."
  [& args]
  (let [flags      (set (filter #(str/starts-with? % "--") args))
        [query db] (remove flags args)
        db         (or db (some-> (config/default-file) config/load-config :db) "")]
    (when (str/blank? query)
      (println "Usage: lein search <query> [db-path] [--snippets] [--raw]")
      (System/exit 1))
    (when-not (.exists (io/file db))
      (println "No database at" db)
      (System/exit 1))
    (t/configure! {:store db})
    (let [results (search query {:raw? (contains? flags "--raw")})]
      (doseq [{:keys [name page-match? hits]} results]
        (println (format "%4d  %s%s" (count hits) name (if page-match? "  [page]" "")))
        (when (flags "--snippets")
          (doseq [h (take 3 hits)]
            (println "        " (one-line (:snippet h))))))
      (println (format "\n%d pages match \"%s\"" (count results) query)))
    (shutdown-agents)))
