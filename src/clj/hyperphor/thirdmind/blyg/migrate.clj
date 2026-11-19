(ns hyperphor.thirdmind.blyg.migrate
  "Import goddinpotty's blyg state (keyed by Logseq block uuid) into ThirdMind's (keyed by block
  :tmid). Entries keep their content frozen and gain :legacy-uuid; unmatched ones are carried
  under their uuid, untouched (served, never re-derived)."
  (:require [clojure.string :as str]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.blyg.publish :as p]
            [hyperphor.thirdmind.identity :as ident]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.tunamelt.core :as t]))

;;; Passes, each over what earlier ones left: the block's id:: is the uuid; threads by their own
;;; text (unique on both sides); a matched thread's children aligned like a diff; fragments by text;
;;; a thread from where its matched children now sit; an entry that is only an attribution line,
;;; to its page's one unclaimed block; then near-identical or appended-to text
;;; (similarity ≥ 0.9, or ours starts with theirs), best pairs first.

(def similar 0.9)

(defn- unique-by [f xs]
  (into {} (keep (fn [[k vs]] (when (and (= 1 (count vs)) (not (ident/trivial? k))) [k (first vs)]))) (group-by f xs)))

(defn- exact [matched ours theirs]
  (let [o (unique-by :text (remove (comp (set (vals matched)) :uri) ours))
        t (unique-by :text (remove (comp matched :uuid) theirs))]
    (into matched (for [[txt e] t :let [b (o txt)] :when b] [(:uuid e) (:uri b)]))))

(defn- align
  "[[i j] ...] pairing old and new child texts: the block matcher's alignment, plus the lone old
  and new child left in the same gap between aligned ones."
  [old nw]
  (let [m    (ident/match (map-indexed (fn [i t] {:content t :path [i]}) old)
                          (map-indexed (fn [j t] {:content t :path [j]}) nw))
        hits (into {} (keep-indexed (fn [j i] (when i [j i])) m))
        gaps (partition-by #(boolean (hits %)) (range (count nw)))
        lo   (fn [j] (or (some hits (reverse (range j))) -1))
        hi   (fn [j] (or (some hits (range (inc j) (count nw))) (count old)))]
    (concat (for [[j i] hits] [i j])
            (for [g gaps :when (not (hits (first g))) :when (= 1 (count g))
                  :let [j (first g) olds (range (inc (lo j)) (hi j))]
                  :when (= 1 (count olds))]
              [(first olds) j]))))

(defn import-state
  "{:state :report} for goddinpotty state gstate, against the db through ctx."
  [ctx gstate]
  (let [realm   (:realm ctx)
        items   (:items gstate)
        {:keys [threads leaves children kids]} (p/candidates ctx (or (:remotes gstate) {}))
        thread? (set threads)
        ours    (for [u (distinct (concat threads leaves children))]
                  {:uri u :thread? (boolean (thread? u))
                   :text (p/norm-text (if (thread? u) (c/own-md ctx u) (c/item-md ctx u)))})
        live    (for [[uuid e] items :when (not= "withdrawn" (name (:kind e)))]
                  {:uuid uuid :thread? (= "thread" (name (:kind e))) :text (p/norm-text (:content-md e))})
        cand    (set (map :uri ours))
        method  (atom {})
        note    (fn [m how] (doseq [k (keys m) :when (not (@method k))] (swap! method assoc k how)) m)
        m       (note (into {} (for [{:keys [uuid]} live :let [u (ingest/block-uri realm uuid)] :when (cand u)] [uuid u]))
                      :id-prop)
        m       (note (exact m (filter :thread? ours) (filter :thread? live)) :thread-text)
        by-blyg (into {} (for [[uuid e] items] [(:blyg-id e) uuid]))
        children (fn [m]
                   (into m (for [[uuid u] m
                                 :let [old (->> (:transclusions (items uuid)) (remove :origin) (keep (comp by-blyg :id)) vec)
                                       nks (kids u)]
                                 [o n] (align (mapv #(p/norm-text (:content-md (items %))) old)
                                              (mapv #(p/norm-text (c/item-md ctx %)) nks))
                                 :let [o (old o) n (nks n)]
                                 :when (and (not (m o)) (not (some #{n} (vals m))))]
                             [o n])))
        m       (note (children m) :child-position)
        m       (note (exact m ours live) :text)
        parent  (fn [u] (:parent ((:fetch ctx) u)))
        m       (note (into m (for [{:keys [uuid] th :thread?} live :when (and th (not (m uuid)))
                                    :let [ks (keep #(when-not (:origin %) (m (by-blyg (:id %)))) (:transclusions (items uuid)))
                                          ps (distinct (map parent ks))]
                                    :when (and (seq ks) (= 1 (count ps)) (thread? (first ps))
                                               (not (some #{(first ps)} (vals m))))]
                                [uuid (first ps)]))
                      :thread-from-children)
        m       (note (children m) :child-position)
        page-of (fn [u] (:name ((:fetch ctx) (:page ((:fetch ctx) u)))))
        m       (note (into m (let [left-o (remove (comp (set (vals m)) :uri) ours)
                                    by-pg  (group-by (comp c/slug page-of :uri) left-o)
                                    attr   (for [e live :when (not (m (:uuid e)))
                                                 :let [[_ pg] (re-matches #"(?s)From \[[^\]]*\]\([^)#]*/([^/)#]+)(?:#[^)]*)?\)\s*"
                                                                          (:content-md (items (:uuid e))))]
                                                 :when pg]
                                             [pg e])
                                    per-pg (frequencies (map first attr))]
                                (for [[pg e] attr
                                      :let [os (by-pg pg)]
                                      :when (and (= 1 (per-pg pg)) (= 1 (count os)))]
                                  [(:uuid e) (:uri (first os))])))
                      :attribution-page)
        left-o  (remove (comp (set (vals m)) :uri) ours)
        left-t  (remove (comp m :uuid) live)
        scored  (for [e left-t, b left-o
                                                               :when (not (ident/trivial? (:text e)))
                                                               :let [s (if (str/starts-with? (:text b) (:text e))
                                                                         0.95
                                                                         (ident/similarity (:text e) (:text b)))]
                      :when (>= s similar)]
                  [s (:uuid e) (:uri b)])
        best    (fn [f] (into {} (for [[k xs] (group-by f scored)
                                       :let [top (apply max (map first xs))]]
                                   [k (count (filter #(= top (first %)) xs))])))
        ties-t  (best second)
        ties-o  (best #(nth % 2))
        m       (note (loop [[[_ uuid u] & more] (sort-by (comp - first)
                                                         (filter (fn [[_ t o]] (and (= 1 (ties-t t)) (= 1 (ties-o o)))) scored))
                                 m m]
                            (cond (nil? uuid) m
                                  (or (m uuid) (some #{u} (vals m))) (recur more m)
                                  :else (recur more (assoc m uuid u))))
                      :similar)
        tm      (fn [u] (or (:tmid ((:fetch ctx) u)) (throw (ex-info (str "no :tmid on " u) {:uri u}))))
        state   (assoc (dissoc gstate :items)
                       :items (into {} (for [[uuid e] items]
                                         (if-let [u (m uuid)]
                                           [(tm u) (assoc e :legacy-uuid uuid)]
                                           [uuid (assoc e :legacy-uuid uuid)]))))
        unmatched (remove (comp m :uuid) live)]
    {:state  state
     :report {:entries (count items)
              :withdrawn (- (count items) (count live))
              :matched (frequencies (vals @method))
              :unmatched (count unmatched)
              :unmatched-examples (vec (for [e (take 15 unmatched)]
                                         [(:uuid e) (name (:kind (items (:uuid e)))) (subs (:text e) 0 (min 90 (count (:text e))))]))
              :candidates-unclaimed (count (remove (comp (set (vals m)) :uri) ours))}}))
