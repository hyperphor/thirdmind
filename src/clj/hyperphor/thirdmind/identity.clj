(ns hyperphor.thirdmind.identity
  "Block identity across edits: align a page's old parsed blocks to its new ones."
  (:require [clojure.string :as str]))

;;; Matching runs in passes, each over what earlier ones left unmatched:
;;; 1. common prefix/suffix and an LCS of contents (unchanged blocks, incl. re-indented)
;;; 2. contents unique on both sides (moved within the page)
;;; 3. similar contents within the same diff gap (edited), best pairs first
;;; 4. very similar contents anywhere on the page (moved and edited)

(defn- norm [s] (-> s str/lower-case (str/replace #"\s+" " ") str/trim))

(defn trivial?
  "Too little text to identify a block by (empty, a marker, a word)."
  [s]
  (< (count (str/replace (str s) #"[\s\p{Punct}]" "")) 5))

(defn- bigrams [s]
  (let [s (norm s)] (frequencies (for [i (range (dec (count s)))] (subs s i (+ i 2))))))

(defn- dice [x y]
  (let [total (+ (reduce + 0 (vals x)) (reduce + 0 (vals y)))]
    (if (zero? total) 0.0 (/ (* 2.0 (reduce + 0 (for [[g n] x] (min n (get y g 0))))) total))))

(defn similarity
  "Dice coefficient of character bigrams, 0..1."
  [a b]
  (dice (bigrams a) (bigrams b)))

(def ^:private max-lcs-cells 4000000)

(defn- lcs-pairs
  "[[i j] ...] of an LCS of (subvec a) and (subvec b) by =, offsets added; nil if too large."
  [a b ao bo]
  (let [n (count a) m (count b)]
    (when (<= (* n m) max-lcs-cells)
      (let [w (inc m)
            t (int-array (* (inc n) w))]
        (doseq [i (range (dec n) -1 -1), j (range (dec m) -1 -1)]
          (aset t (+ (* i w) j)
                (if (= (a i) (b j))
                  (inc (aget t (+ (* (inc i) w) (inc j))))
                  (max (aget t (+ (* (inc i) w) j)) (aget t (+ (* i w) (inc j)))))))
        (loop [i 0 j 0 acc []]
          (cond (or (= i n) (= j m)) acc
                (= (a i) (b j)) (recur (inc i) (inc j) (conj acc [(+ ao i) (+ bo j)]))
                (>= (aget t (+ (* (inc i) w) j)) (aget t (+ (* i w) (inc j)))) (recur (inc i) j acc)
                :else (recur i (inc j) acc)))))))

(defn- aligned
  "{new-idx old-idx} for unchanged blocks: common prefix and suffix, then an LCS of the middle."
  [oc nc]
  (let [n (count oc) m (count nc)
        pre (count (take-while true? (map = oc nc)))
        suf (count (take-while true? (map = (rseq (subvec oc pre)) (rseq (subvec nc pre)))))
        mid (lcs-pairs (subvec oc pre (- n suf)) (subvec nc pre (- m suf)) pre pre)]
    (into {} (concat (for [i (range pre)] [i i])
                     (for [k (range suf)] [(- m 1 k) (- n 1 k)])
                     (for [[i j] mid] [j i])))))

(defn- unique-moves [oc nc matched]
  (let [used  (set (vals matched))
        index (fn [cs skip?] (->> (keep-indexed (fn [i c] (when-not (or (skip? i) (trivial? c)) [c i])) cs)
                                  (group-by first)
                                  (keep (fn [[c is]] (when (= 1 (count is)) [c (second (first is))])))
                                  (into {})))
        olds  (index oc used)
        news  (index nc matched)]
    (into matched (for [[c j] news :let [i (olds c)] :when i] [j i]))))

(defn- score
  "Similarity, raised for an append (new text starts with the old), the commonest edit and how
  Logseq merges a block into the one before it."
  [o n ob nb]
  (let [s (dice ob nb)]
    (if (and (not (trivial? o)) (str/starts-with? (norm n) (norm o))) (max s 0.95) s)))

(defn- parent-index [blocks]
  (let [by-path (into {} (map-indexed (fn [i b] [(:path b) i]) blocks))]
    (mapv #(when (> (count (:path %)) 1) (by-path (pop (:path %)))) blocks)))

(def threshold "Minimum similarity for an edited block to keep its id, within its diff gap." 0.6)
(def far-threshold "The same, for a block that also moved out of its gap." 0.75)

(defn- greedy
  "Assign [score i j] candidates best first, each i and j once."
  [cands used matched]
  (loop [[[_ i j] & more] (sort-by (comp - first) cands), used used, m matched]
    (cond (nil? i) m
          (or (used i) (m j)) (recur more used m)
          :else (recur more (conj used i) (assoc m j i)))))

(defn- similar-pairs [old new oc nc matched]
  (let [used (set (vals matched))
        js   (sort (keys matched))
        op   (parent-index old)
        np   (parent-index new)
        ob   (mapv bigrams oc)
        nb   (mapv bigrams nc)
        gap  (fn [j] [(or (some-> (last (take-while #(< % j) js)) matched) -1)
                      (or (some-> (first (drop-while #(< % j) js)) matched) (count oc))])]
    (greedy (for [j (range (count nc)) :when (not (matched j))
                  :let [[lo hi] (gap j)]
                  i (range (inc (min lo hi)) (max lo hi)) :when (not (used i))
                  :let [s (cond-> (score (oc i) (nc j) (ob i) (nb j))
                            (and (np j) (= (op i) (matched (np j)))) (+ 0.1))]
                  :when (>= s threshold)]
              [s i j])
            used matched)))

(defn- far-pairs [oc nc matched]
  (let [used (set (vals matched))
        is   (remove used (range (count oc)))
        js   (remove matched (range (count nc)))]
    (if (> (* (count is) (count js)) 250000)
      matched
      (let [ob (into {} (for [i is] [i (bigrams (oc i))]))]
        (greedy (for [j js :let [nb (bigrams (nc j))]
                      i is :let [s (score (oc i) (nc j) (ob i) nb)] :when (>= s far-threshold)]
                  [s i j])
                used matched)))))

(defn match
  "For new blocks (parsed, with :content and :path), the index of the old block each continues,
  or nil for a new block. Ignores :props, so an id:: doesn't help."
  [old new]
  (let [old (vec old) new (vec new)
        oc  (mapv :content old)
        nc  (mapv :content new)
        m   (->> (aligned oc nc) (unique-moves oc nc) (similar-pairs old new oc nc) (far-pairs oc nc))]
    (mapv m (range (count new)))))
