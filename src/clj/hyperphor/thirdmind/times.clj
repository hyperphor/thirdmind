(ns hyperphor.thirdmind.times
  "Block time ranges from git blame of the graph, stored on block objects as :created/:updated (epoch ms).
  `lein times <graph-dir> [db-path]` backfills; the watcher keeps synced files current."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [hyperphor.thirdmind.config :as config]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.parse :as parse]
            [hyperphor.tunamelt.core :as t]))

;;; A block's range is the earliest and latest blame time of its content lines (property lines
;;; are excluded, so Logseq adding an id:: isn't an edit). So "created" is really the age of its
;;; oldest surviving line. Blocks carrying Roam-era created-at::/updated-at:: that are unchanged
;;; since the repo's first commit (the import) use those instead.

(defn git-repo? [dir]
  (zero? (:exit (sh/sh "git" "rev-parse" "--is-inside-work-tree" :dir (str dir)))))

(defn- first-commit-ms
  "Time of the repo's earliest root commit, in ms."
  [dir]
  (let [roots (str/split-lines (:out (sh/sh "git" "rev-list" "--max-parents=0" "HEAD" :dir (str dir))))]
    (some->> (seq (remove str/blank? roots))
             (map #(parse-long (str/trim (:out (sh/sh "git" "show" "-s" "--format=%at" % :dir (str dir))))))
             (apply min)
             (* 1000))))

(def ^:private uncommitted "0000000000000000000000000000000000000000")

(defn parse-blame
  "Per-line times (ms) from `git blame --porcelain` output, as a vector indexed by 0-based line.
  Uncommitted lines get uncommitted-ms."
  [out uncommitted-ms]
  (loop [[l & more] (str/split-lines out), sha nil, line nil, sha-ms {}, acc (transient {})]
    (cond
      (nil? l)
      (let [m (persistent! acc)]
        (mapv #(get m %) (range (if (seq m) (inc (apply max (keys m))) 0))))

      (str/starts-with? l "\t")
      (recur more sha line sha-ms
             (assoc! acc (dec line) (if (= sha uncommitted) uncommitted-ms (sha-ms sha))))

      :else
      (if-let [[_ s final] (re-matches #"([0-9a-f]{40}) \d+ (\d+)(?: \d+)?" l)]
        (recur more s (parse-long final) sha-ms acc)
        (if-let [[_ secs] (re-matches #"author-time (\d+)" l)]
          (recur more sha line (assoc sha-ms sha (* 1000 (parse-long secs))) acc)
          (recur more sha line sha-ms acc))))))

(defn line-times
  "Per-line times (ms) for graph file rel. Untracked files get their mtime for every line."
  [dir rel]
  (let [f     (io/file dir rel)
        mtime (.lastModified f)
        {:keys [exit out]} (sh/sh "git" "blame" "--porcelain" "--" rel :dir (str dir))]
    (if (zero? exit)
      (parse-blame out mtime)
      (vec (repeat (count (str/split-lines (slurp f))) mtime)))))

(defn- prop-ms [b k]
  (some-> (get-in b [:props k]) parse-long))

(defn block-times
  "{block-id {:created ms :updated ms}} for a parsed file, given its line times and the import time."
  [parsed times import-ms]
  (into {}
        (for [b (:blocks parsed)
              :let [ts  (keep #(get times %) (:line-nos b))
                    pc  (prop-ms b "created-at")
                    pu  (prop-ms b "updated-at")
                    lo  (when (seq ts) (apply min ts))
                    hi  (when (seq ts) (apply max ts))
                    unchanged-import? (and pc import-ms (or (nil? hi) (<= hi (+ import-ms 86400000))))
                    created (cond unchanged-import? pc pc (min pc lo) :else lo)
                    updated (if unchanged-import? (or pu pc) hi)]
              :when created]
          [(ingest/block-id (:name parsed) b) {:created created :updated (max created updated)}])))

(defn- import-ms* [dir] (first-commit-ms dir))
(def ^:private import-ms (memoize import-ms*))

(defn file-times
  "[[block-uri {:created :updated}] ...] for graph file rel, or nil if it's gone."
  [dir realm rel]
  (let [f (io/file dir rel)]
    (when (.isFile f)
      (let [parsed (parse/parse-file f (str/starts-with? rel "journals/") rel)]
        (for [[id ts] (block-times parsed (line-times dir rel) (import-ms dir))]
          [(ingest/block-uri realm id) ts])))))

(defn- store!
  "Merge times into existing block objects and mark the file record as timed at its mtime."
  [realm rel times]
  (let [furi (ingest/file-uri realm rel)
        frec (t/get furi)]
    (t/put-many (concat (for [[uri ts] times :let [o (t/get uri)] :when o] [uri (merge o ts)])
                        (when frec [[furi (assoc frec :times-mtime (:mtime frec))]])))))

(defn update-file-times!
  "Recompute and store block times for graph file rel (after it has been ingested)."
  [dir realm rel]
  (when-let [times (file-times dir realm rel)]
    (store! realm rel times)))

(defn backfill!
  "Time every file whose record isn't timed at its current mtime. Blames in parallel; logs progress."
  [dir realm log]
  (let [todo  (vec (for [{o :obj} (t/find "file" {:realm realm})
                         :when (not= (:times-mtime o) (:mtime o))]
                     (:path o)))
        n     (count todo)]
    (log "Timing" n "files")
    ;; blame (slow, parallel) ahead of the single-threaded db writes
    (doseq [[i [rel times]] (map-indexed vector
                                         (pmap (fn [rel] [rel (try (file-times dir realm rel)
                                                                   (catch Exception e (log "error timing" rel (ex-message e))))])
                                               todo))]
      (when times (store! realm rel times))
      (when (zero? (mod (inc i) 250)) (log (inc i) "/" n)))
    n))

(defn -main
  "Usage: lein times [config.edn | <graph-dir> [db-path]]"
  [& args]
  (let [[cfg [dir db]] (config/from-args args)
        dir (or dir (:graph cfg))]
  (when-not dir
    (println "Usage: lein times [config.edn | <graph-dir> [db-path]]")
    (System/exit 1))
  (let [realm (or (:realm cfg) (core/realm-for dir))
        db    (or db (:db cfg) (str realm ".db"))
        log   (fn [& xs] (println (str/join " " xs)) (flush))]
    (when-not (.exists (io/file db))
      (println "No database at" db "- run `lein run" dir "` first")
      (System/exit 1))
    (when-not (git-repo? dir)
      (println dir "is not a git repository; no history to time blocks from")
      (System/exit 1))
    (t/configure! {:store db :realm realm})
    (let [start (System/currentTimeMillis)
          n     (backfill! dir realm log)]
      (log (format "Timed %d files in %.0fs" n (/ (- (System/currentTimeMillis) start) 1000.0))))
    (shutdown-agents))))
