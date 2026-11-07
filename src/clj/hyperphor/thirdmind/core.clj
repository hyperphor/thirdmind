(ns hyperphor.thirdmind.core
  "Read a Logseq graph into a fresh Tunamelt database and report what it looks like."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.tunamelt.core :as t]))

(comment
  (t/configure! {:store "ammdi.db"}))

(defn fresh-store!
  "Delete any existing database at path and configure Tunamelt to use a new one there."
  [path realm]
  (doseq [suffix ["" "-wal" "-shm"]]
    (io/delete-file (str path suffix) true))
  (t/configure! {:store path :realm realm}))

(defn realm-for
  "Realm name from a graph directory name."
  [dir]
  (-> (.getName (io/file dir)) str/lower-case (str/replace #"[^a-z0-9]+" "-")))

(defn outline
  "Lines of a page's block outline, rebuilt from stored blocks via :parent/:order."
  [page-uri]
  (let [blocks   (t/find "block" {:attrs {:page page-uri}})
        children (group-by (comp :parent :obj) blocks)
        walk     (fn walk [parent depth]
                   (mapcat (fn [{:keys [uri obj]}]
                             (cons (str (apply str (repeat depth "  ")) "- "
                                        (first (str/split-lines (str (:content obj)))))
                                   (walk uri (inc depth))))
                           (sort-by (comp :order :obj) (children parent))))]
    (walk page-uri 0)))

(defn most-referenced
  "Top n pages by number of referring blocks (via :refs), as {:name :uri :stub :n}."
  [n]
  (->> (t/find "page")
       (map (fn [{:keys [uri obj]}]
              {:name (:name obj) :uri uri :stub (:stub obj) :n (count (t/referrers uri {:attr :refs}))}))
       (sort-by (comp - :n))
       (take n)))

(defn -main
  "Usage: lein run <graph-dir> [db-path]"
  [& [dir db]]
  (when-not dir
    (println "Usage: lein run <graph-dir> [db-path]")
    (System/exit 1))
  (let [realm  (realm-for dir)
        db     (or db (str realm ".db"))
        _      (fresh-store! db realm)
        start  (System/currentTimeMillis)
        report (ingest/ingest! dir realm)
        secs   (/ (- (System/currentTimeMillis) start) 1000.0)]
    (println (format "Ingested %s into %s (realm %s) in %.1fs" dir db realm secs))
    (pp/pprint (-> report
                   (update :name-collisions #(into {} (take 10 %)))
                   (update :unresolved-block-refs #(vec (take 10 %)))
                   (assoc :unresolved-block-ref-count (count (:unresolved-block-refs report)))))
    (println "\nTypes:" (t/types {:counts? true}))
    (println "\nMost referenced pages:")
    (let [top  (most-referenced 20)
          _    (doseq [{:keys [name n stub]} (take 10 top)]
                 (println (format "  %5d  %s%s" n name (if stub "  (stub)" ""))))
          page (:uri (first (remove :stub top)))]
      (println "\nSample property values:"
               (->> (t/find "block" {:limit 2000}) (mapcat (comp keys :obj))
                    (filter #(= "prop" (namespace %))) frequencies (sort-by (comp - val)) (take 8)))
      (println "\nOutline of" page "(first 25 lines):")
      (doseq [l (take 25 (outline page))] (println " " l)))
    (shutdown-agents)))
