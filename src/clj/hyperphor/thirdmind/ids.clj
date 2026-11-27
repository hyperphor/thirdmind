(ns hyperphor.thirdmind.ids
  "ThirdMind's own block ids (:tmid), kept in a store of their own that rebuilds don't touch.
  One record per graph file: {:file rel :seen ms :gone ms? :blocks [[tmid content path] ...]}."
  (:require [clojure.edn]
            [clojure.java.io]
            [clojure.string :as str]
            [hyperphor.thirdmind.identity :as ident]
            [hyperphor.tunamelt.sqlite :as sqlite]
            [hyperphor.tunamelt.store :as store])
  (:import [java.net URLEncoder]
           [java.security SecureRandom]))

(defonce ^:private the-store (atom nil))

(defn path-for
  "The ids store path that goes with main db path db."
  [db]
  (str (str/replace (str db) #"\.db$" "") ".ids.db"))

(defn open!
  "Use the ids store at path (created if new)."
  [path]
  (reset! the-store (sqlite/open path)))

(defn- st [] (or @the-store (throw (ex-info "ids store not open (ids/open!)" {}))))

(def ^:private alphabet "0123456789abcdefghjkmnpqrstvwxyz")
(def ^:private rng (SecureRandom.))

(defn mint
  "A new random id: 12 base32 characters (60 bits)."
  []
  (apply str (repeatedly 12 #(.charAt alphabet (.nextInt rng 32)))))

(defn- k [realm rel] {:realm realm :type "ids" :id (str/replace (URLEncoder/encode (str rel) "UTF-8") "+" "%20")})

(defn record [realm rel] (:obj (store/get* (st) (k realm rel))))

(defn- records [realm] (map :obj (store/find* (st) "ids" {:realm realm})))

(defn- now [] (System/currentTimeMillis))

;;; Orphans: records of files that went away. A page with no record of its own (a renamed file)
;;; takes back ids from orphan blocks whose non-trivial content is unique among them and on the page.

(defn- unique-content [entries content-of]
  (->> entries (remove (comp ident/trivial? content-of)) (group-by content-of)
       (keep (fn [[c es]] (when (= 1 (count es)) [c (first es)]))) (into {})))

(defn- rescue
  "[blocks-with-tmid taken-tmids] for a page without a record, reusing orphan ids where unambiguous."
  [orphans blocks]
  (let [pool (unique-content (for [r orphans, [tmid c] (:blocks r)] [tmid c]) second)
        mine (set (keys (unique-content blocks :content)))
        bs   (mapv (fn [b] (assoc b :tmid (or (when (mine (:content b)) (first (pool (:content b)))) (mint))))
                   blocks)]
    [bs (set (keep #(when (mine (:content %)) (first (pool (:content %)))) blocks))]))

(defn- assign [rec blocks]
  (let [old (vec (:blocks rec))
        m   (ident/match (map (fn [[_ c p]] {:content c :path (vec p)}) old) blocks)]
    (mapv (fn [b i] (assoc b :tmid (if i (first (old i)) (mint)))) blocks m)))

(defn- rec-for [rel blocks]
  {:file rel :seen (now) :blocks (mapv (juxt :tmid :content :path) blocks)})

(defn- drop-taken
  "orphans without the blocks whose tmid is in taken."
  [orphans taken]
  (for [r orphans] (update r :blocks #(vec (remove (comp taken first) %)))))

(defn- put-all! [realm recs]
  (store/put-many* (st) (for [r recs] [(k realm (:file r)) r {}])))

(defn assign-page!
  "parsed page (from file rel) with :tmid on its blocks; records them."
  [realm rel page]
  (let [rec (record realm rel)]
    (if rec
      (let [bs (assign rec (:blocks page))]
        (put-all! realm [(rec-for rel bs)])
        (assoc page :blocks bs))
      (let [orphans (filter :gone (records realm))
            [bs taken] (rescue orphans (:blocks page))]
        (put-all! realm (cons (rec-for rel bs)
                              (drop-taken (filter #(some (comp taken first) (:blocks %)) orphans) taken)))
        (assoc page :blocks bs)))))

(defn assign-all!
  "Parsed pages with :tmid on their blocks, for a full ingest: records for files no longer present
  become orphans, and pages without a record rescue ids from them."
  [realm pages]
  (let [recs    (into {} (map (juxt :file identity)) (records realm))
        present (set (map :file pages))
        orphans (for [[rel r] recs :when (not (present rel))] (cond-> r (not (:gone r)) (assoc :gone (now))))
        kept    (for [p pages :when (recs (:file p))] (assoc p :blocks (assign (recs (:file p)) (:blocks p))))
        [fresh taken] (reduce (fn [[acc taken] p]
                                (let [[bs t] (rescue (drop-taken orphans taken) (:blocks p))]
                                  [(conj acc (assoc p :blocks bs)) (into taken t)]))
                              [[] #{}] (remove #(recs (:file %)) pages))
        out     (concat kept fresh)]
    (put-all! realm (concat (for [p out] (rec-for (:file p) (:blocks p))) (drop-taken orphans taken)))
    (let [by-file (into {} (map (juxt :file identity)) out)]
      (mapv (comp by-file :file) pages))))

(defn gone!
  "Mark rel's record as an orphan (its file went away)."
  [realm rel]
  (when-let [r (record realm rel)]
    (when-not (:gone r) (put-all! realm [(assoc r :gone (now))]))))

;;; Backup as text: one EDN record per line, sorted by file, so a git-tracked copy diffs small.

(defn export!
  "Write every record of realm to file, one per line. Returns the count."
  [realm file]
  (let [rs  (sort-by :file (records realm))
        tmp (java.io.File. (str file ".tmp"))]
    (with-open [w (clojure.java.io/writer tmp)]
      (doseq [r rs] (.write w (pr-str r)) (.write w "\n")))
    (.renameTo tmp (java.io.File. (str file)))
    (count rs)))

(defn import!
  "Restore records from an export! file into realm (overwriting same-file records). Returns the count."
  [realm file]
  (let [rs (with-open [r (clojure.java.io/reader file)]
             (doall (map clojure.edn/read-string (remove str/blank? (line-seq r)))))]
    (put-all! realm rs)
    (count rs)))
