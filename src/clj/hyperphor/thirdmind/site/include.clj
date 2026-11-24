(ns hyperphor.thirdmind.site.include
  "What the site publishes, by goddinpotty's rules (database/compute-depths, batadase): a walk from
  the entry pages along parent, child and ref links in both directions, never entering an
  exit-tagged block (or anything under one) or a journal."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.parse :as parse]
            [hyperphor.tunamelt.core :as t]))

(defn load-objects
  "{uri obj} for every page and block of realm."
  [realm]
  (into {} (map (juxt :uri :obj)) (concat (t/find "page" {:realm realm}) (t/find "block" {:realm realm}))))

(defn- page-uri? [u] (str/includes? u "/page/"))

(defn- parent-name [name] (second (re-find #"^(.*)/(.*?)$" name)))

;;; A page with a file has a body: even a properties-only file has a block (Logseq's pre-block).
(defn- has-body? [o] (not (:stub o)))

(defn compute
  "{:pages #{published page uri} :included #{uri} :hidden #{block uri on a published page, left out}
  :via {included uri → uri it was reached from} :exit? fn}.
  opts: :realm, :aliases {alias-key page-key}, :exit-tags, :entry-tags, :entry-pages (names)."
  [objs {:keys [realm aliases exit-tags entry-tags entry-pages]}]
  (let [canon    (fn [name] (let [k (ingest/page-key name)] (ingest/page-uri realm (get aliases k k))))
        exit     (set (map canon exit-tags))
        entry    (set (map canon entry-tags))
        ;; stubs (pages with no file) aren't walked: goddinpotty can't resolve refs to empty pages, and a
        ;; tag stub would join everything carrying the tag. Exit and entry tag pages are the exception.
        node?    (fn [u] (when-let [o (objs u)] (or (not (:stub o)) (exit u) (entry u))))
        hparent  (fn [u] (when-let [p (some-> (objs u) :name parent-name canon)] (when (node? p) p)))
        ;; a page's refs: its tags, its namespace parent, and any alias naming a page with its own file
        refs     (fn [u] (let [o (objs u)]
                           (if (page-uri? u)
                             (cond-> (into (vec (:tags o)) (filter #(and (not= u %) (node? %))) (map canon (:alias o)))
                               (hparent u) (conj (hparent u)))
                             (:refs o))))
        children (reduce-kv (fn [m u o] (if (page-uri? u) m (update m (:parent o) (fnil conj []) u))) {} objs)
        ref-by   (reduce-kv (fn [m u _] (reduce #(update %1 %2 (fnil conj []) u) m (filter node? (refs u)))) {} objs)
        tagged?  (fn [refs tags u] (boolean (or (some tags (refs u)) (some #(some tags (refs %)) (children u)))))
        ;; exit tags also count where only goddinpotty's looser parse sees them
        exit-refs (fn [u] (concat (refs u) (map canon (some-> (:content (objs u)) parse/loose-tags))))
        ;; a block's container is its parent block or page; a page's is its namespace parent (a/b → a)
        container (fn [u] (if (page-uri? u) (hparent u) (:parent (objs u))))
        exit-tagged? (memoize (fn f [u] (and u (or (tagged? exit-refs exit u) (f (container u))))))
        journal? (fn [u] (let [o (objs u)] (:journal (if (page-uri? u) o (objs (:page o))))))
        exit?    (fn [u] (or (journal? u) (exit-tagged? u)))
        entry-p  (set (map canon entry-pages))
        starts   (for [[u o] objs :when (and (page-uri? u) (node? u) (or (entry-p u) (tagged? refs entry u)))] u)
        nbrs     (fn [u] (concat (children u) (filter node? (refs u)) (ref-by u)
                                 (when-not (page-uri? u) [(:parent (objs u))])))
        ;; via: {uri the node it was reached from}, entry pages mapping to nil
        via      (loop [via (zipmap starts (repeat nil)), frontier (vec starts)]
                   (if (empty? frontier)
                     via
                     (let [step (into {} (for [u frontier, n (nbrs u) :when (not (or (contains? via n) (exit? n)))] [n u]))]
                       (recur (merge step via) (vec (keys step))))))
        included (set (keys via))
        shown?   (fn [u] (or (has-body? (objs u)) (> (count (filter included (ref-by u))) 1)))
        pages    (set (filter #(and (page-uri? %) (not (journal? %)) (shown? %)) included))]
    {:pages    pages
     :included included
     :via      via
     :exit?    exit?
     :hidden   (set (for [[u o] objs :when (and (not (page-uri? u)) (pages (:page o)) (not (included u)))] u))}))

(defn published
  "compute over realm's db; settings as compute's opts, less :realm and :aliases."
  [realm settings]
  (compute (load-objects realm) (assoc settings :realm realm :aliases (ingest/db-aliases realm))))
