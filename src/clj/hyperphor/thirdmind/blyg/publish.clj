(ns hyperphor.thirdmind.blyg.publish
  "The blyg publisher, after goddinpotty.export.blygger/publish!, over ThirdMind's db: #blyg blocks
  → state entries (keyed by block :tmid) → site files. A tagged block with children is a thread
  whose children are promoted to fragments; #blyg-pin pins; #blyg-reply (or #blyg-response) + one URL is a stub.

  Imported entries keep goddinpotty's content frozen: ThirdMind records its own rendering
  (:tm-base, [kind hash html-signature transclusions]) the first time it sees an entry and bumps
  the version only when that changes, so rendering differences alone never make a new version."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.blyg.wire :as w]
            [hyperphor.thirdmind.identity :as ident]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.tunamelt.core :as t]))

(def control-tags {:blyg ["blyg"] :pin ["blyg-pin"] :reply ["blyg-reply" "blyg-response"]})

(defn- tag-uris [realm k] (set (for [tg (control-tags k)] (ingest/page-uri realm (ingest/page-key tg)))))

(defn- has-tag? [realm o k] (boolean (some (tag-uris realm k) (:tags o))))

(defn- tmid [ctx uri]
  (or (:tmid ((:fetch ctx) uri))
      (throw (ex-info (str "block has no :tmid (rebuild the db with an ids store): " uri) {:uri uri}))))

(defn candidates
  "What the db says to publish. {:tagged [uri] :threads [uri] :leaves [uri] :children [uri]
  :kids {uri [child-uri]} :remote {uri snapshot} :unresolved [target-url] :skipped [[uri why]]}"
  [{:keys [realm] :as ctx} remotes]
  (let [tagged  (distinct (for [k (keys control-tags), tu (tag-uris realm k)
                                {:keys [uri]} (t/referrers tu {:attr :tags :type "block"})]
                            uri))
        target  (into {} (for [u tagged :let [o ((:fetch ctx) u)] :when (has-tag? realm o :reply)]
                           [u (c/reply-target (str (:content o)))]))
        skipped (for [[u tg] target :when (or (= tg :ambiguous) (and (string? tg) (not (remotes tg))))]
                  [u (if (= tg :ambiguous) "reply with more than one URL" (str "reply target couldn't be fetched as a blyg item: " tg))])
        tagged  (remove (set (map first skipped)) tagged)
        remote  (into {} (for [[u tg] target :when (and (string? tg) (remotes tg))] [u (remotes tg)]))
        kids    (into {} (for [u tagged] [u (vec (c/body-children ctx u))]))
        thread? #(or (seq (kids %)) (remote %))
        threads (filter thread? tagged)]
    {:tagged tagged :threads threads :leaves (remove thread? tagged)
     :children (distinct (mapcat kids threads)) :kids kids :remote remote
     :reply-url (into {} (for [[u tg] target :when (string? tg)] [u tg]))
     :unresolved (vec (distinct (for [[_ tg] target :when (and (string? tg) (not (remotes tg)))] tg)))
     :skipped (vec skipped)}))

(defn fetch-remotes
  "state with a snapshot in :remotes for each reply target that lacks one, via (resolve url) → snapshot
  or nil. Targets that don't resolve stay out (plan skips their replies)."
  [ctx state resolve]
  (let [urls (:unresolved (candidates ctx (or (:remotes state) {})))
        got  (into {} (for [u urls :let [s (resolve u)] :when s] [u s]))]
    (cond-> state (seq got) (update :remotes merge got))))

;;; Versions against ThirdMind's own baseline

(declare norm-text)

(defn change-note
  "Generated changelog note (§5.2) for existing → this rendering. Says what changed, never quotes
  the prior text."
  [existing kind html trans]
  (let [ids     #(set (map :id %))
        old     (:transclusions existing)
        added   (count (remove (ids old) (map :id trans)))
        removed (count (remove (ids trans) (map :id old)))
        revised (count (filter (fn [{:keys [id version]}] (some #(and (= id (:id %)) (not= version (:version %))) old)) trans))
        imgs    #(count (re-seq #"<img\b" (str %)))
        n       (fn [k what] (if (= 1 k) (str "a " what) (str k " " what "s")))]
    (cond
      (not= (name kind) (name (:kind existing))) (str "Now a " (name kind) ".")
      (or (pos? added) (pos? removed))
      (str/join " " (remove nil? [(when (pos? added) (str "Added " (n added "part") "."))
                                  (when (pos? removed) (str "Removed " (n removed "part") "."))]))
      (> (imgs html) (imgs (:content-html existing))) "Added an image."
      (let [no-src #(w/html-signature (str/replace (str %) #"src=\"[^\"]*\"" ""))]
        (and (pos? (imgs html)) (= (no-src html) (no-src (:content-html existing)))))
      "Updated an image."
      (and (pos? revised) (= (map :id trans) (map :id old))) (str "Revised " (n revised "part") ".")
      :else "Edited.")))

(defn- with-note
  "entry with note on its latest changelog event, marked generated."
  [entry note]
  (update entry :changelog #(conj (pop %) (assoc (peek %) :note note :generated true))))

(defn- advance
  "existing (or nil) moved to this rendering, bumping only when ThirdMind's rendering changed.
  blank-id? says which blyg ids are unmatched empty legacy items, which a first-sight comparison
  ignores. A frozen entry (:tm-frozen) still holds goddinpotty's content, and is judged by plain,
  a delay of [md html] rendered without media (images at the site's /assets/), so that moving
  images to blyg media alone doesn't bump it."
  [blank-id? existing kind md html trans stub-of now plain]
  (let [base   [(name kind) (w/content-hash md) (w/html-signature html) trans]
        [pm ph] (if plain @plain [md html])
        pbase  [(name kind) (w/content-hash pm) (w/html-signature ph) trans]
        seen #(-> % (assoc :tm-base base) (dissoc :tm-frozen))
        bump #(-> (w/next-entry (assoc existing :content-hash "") kind md html trans stub-of now)
                  seen (with-note (change-note existing kind html trans)))]
    (cond
      (nil? existing)
      (seen (w/next-entry nil kind md html trans stub-of now))

      ;; first sight of an imported entry: freeze if it is the same thing goddinpotty published,
      ;; text compared after normalizing rendering differences (and not counting empty items
      ;; goddinpotty published: a thread of only those is a fragment here); else a new version
      (nil? (:tm-base existing))
      (if (let [tr (some->> (:transclusions existing) (remove (comp blank-id? :id)) vec)
                k  (if (and (= :thread (keyword (:kind existing))) (empty? tr) (not (:stub-of existing)))
                     :fragment
                     (keyword (:kind existing)))]
            (and (= kind k) (= trans (when (= k :thread) tr))
                 (= (norm-text (:content-md existing)) (norm-text pm))))
        (assoc existing :tm-base pbase :tm-frozen true)
        (bump))

      (:tm-frozen existing)
      (if (= pbase (:tm-base existing)) existing (bump))

      ;; unchanged for readers; refresh presentation-only html (eg a title)
      (= base (:tm-base existing))
      (if (= html (:content-html existing)) existing (assoc existing :content-html html))

      :else
      (bump))))

(defn- stub-of-ref [remote]
  {:origin (:origin remote) :id (:id remote) :version (:version remote)
   :cited (cond-> {:retrieved (:retrieved remote)}
            (:source-title remote) (assoc :source (:source-title remote))
            (:author remote) (assoc :author (:author remote))
            (:content-md remote) (assoc :excerpt (-> (:content-md remote)
                                                     (str/replace #"(?m)^!\[\[[0-9a-z]{26}\]\]\s*$" "")
                                                     w/title-excerpt))
            (:page-url remote) (assoc :url (:page-url remote)))})

(defn norm-text
  "Text for matching: links to their text, images to their file name; no attribution, directives, emphasis."
  [md]
  (-> (str md)
      (str/replace #"(?s)^From \[[^\]]*\]\([^)]*\)\n\n" "")
      (str/replace #"!\[\[[0-9a-z]+\]\]" "")
      (str/replace #"!\[[^\]]*\]\((?:[^)]*/)?([^)/]*)\)(\{[^}]*\})?" " $1 ")   ; images: file name
      (str/replace #"\[([^\]]*)\]\([^)]*\)" "$1")
      (str/replace #"[*_]" "")
      (str/replace #"\s+" " ")
      str/trim str/lower-case))

(defn- like?
  "Similarity of two normalized texts, with appends counted as near-certain."
  [old-text new-text]
  (cond (or (ident/trivial? old-text) (ident/trivial? new-text)) 0.0
        (str/starts-with? new-text old-text) 0.95
        :else (ident/similarity old-text new-text)))

(defn- rekey-moved
  "items with entries whose block went away moved onto new candidate blocks with like text, when
  the pairing is unique both ways (a block that lost its id: moved to another page, say)."
  [ctx {:keys [threads]} id uris items]
  (let [thread? (set threads)
        current (set (map id uris))
        gone    (for [[k e] items :when (and (not (current k)) (not= "withdrawn" (name (:kind e))))]
                  [k (norm-text (:content-md e))])
        fresh   (for [u uris :when (not (contains? items (id u)))]
                  [(id u) (norm-text (if (thread? u) (c/own-md ctx u) (c/item-md ctx u)))])
        pairs   (for [[nk nt] fresh, [ok ot] gone :let [s (like? ot nt)] :when (>= s 0.8)] [nk ok])
        once    (fn [f] (set (keep (fn [[k n]] (when (= 1 n) k)) (frequencies (map f pairs)))))
        one-n   (once first)
        one-o   (once second)]
    (reduce (fn [items [nk ok]]
              (if (and (one-n nk) (one-o ok)) (-> items (dissoc ok) (assoc nk (items ok))) items))
            items pairs)))

(defn plan
  "New state for state, given ctx (content/ctx) at time now. Reads the db, writes nothing.
  opts :withdraw? withdraws items no longer tagged. Returns {:state :report}."
  [ctx state now & {:keys [withdraw?]}]
  (let [realm   (:realm ctx)
        remotes (or (:remotes state) {})
        {:keys [tagged threads leaves children kids remote skipped] :as cand} (candidates ctx remotes)
        id      (memoize #(tmid ctx %))
        child?  (set children)
        current (set (map id (concat tagged children)))
        items0  (rekey-moved ctx cand id (distinct (concat tagged children)) (:items state))
        ;; empty legacy items still unmatched (keyed by their uuid): ThirdMind doesn't publish those
        blank?  (set (for [[k e] items0 :when (and (= k (:legacy-uuid e)) (str/blank? (:content-md e)))] (:blyg-id e)))
        advance (partial advance blank?)
        ;; the same rendering without media, for judging frozen entries
        pctx    (when (:graph-dir ctx) (assoc ctx :graph-dir nil :media (atom {})))
        ;; fragments: leaves and promoted children (no attribution of their own)
        items1  (reduce (fn [items u]
                          (let [attr? (not (child? u))]
                            (update items (id u) advance :fragment
                                    (c/fragment-md ctx u :attribution? attr?)
                                    (c/fragment-html ctx u :attribution? attr?) nil nil now
                                    (when pctx (delay [(c/fragment-md pctx u :attribution? attr?)
                                                       (c/fragment-html pctx u :attribution? attr?)])))))
                        items0 (distinct (concat leaves children)))
        items1  (reduce (fn [items u] (assoc-in items [(id u) :thread-child?] (boolean (child? u))))
                        items1 (distinct (concat leaves children)))
        ;; threads, now that every child's current id/version is known
        items2  (reduce (fn [items u]
                          (let [ks   (kids u)
                                rem  (remote u)
                                segs (concat (for [k ks] [:child (items (id k))]) (when rem [[:remote rem]]))
                                tr   (vec (for [[kind e] segs]
                                            (if (= kind :remote)
                                              {:id (:id e) :version (:version e) :origin (:origin e)}
                                              {:id (:blyg-id e) :version (:version e)})))]
                            (-> items
                                (update (id u) advance :thread
                                        (c/thread-md ctx u (for [[kind e] segs] (if (= kind :remote) (:id e) (:blyg-id e))))
                                        (c/thread-html ctx u segs) tr (when rem (stub-of-ref rem)) now
                                        (when pctx (delay [(c/thread-md pctx u (for [[kind e] segs] (if (= kind :remote) (:id e) (:blyg-id e))))
                                                           (c/thread-html pctx u segs)])))
                                (assoc-in [(id u) :thread-child?] false)
                                (cond-> rem (update (id u) assoc :mention-target (:page-url rem)
                                                    :mention-endpoint (:webmention rem)
                                                    :remote-key ((:reply-url cand) u))))))
                        items1 threads)
        items3  (reduce (fn [items u] (if (has-tag? realm ((:fetch ctx) u) :pin) (update items (id u) w/pin-entry) items))
                        items2 (distinct (concat tagged children)))
        ;; titles (presentation only): top-level items' first lines; frozen ones keep goddinpotty's
        items3  (reduce (fn [items u]
                          (let [e (items (id u))]
                            (cond (:tm-frozen e) items
                                  (child? u) (update items (id u) dissoc :title :title-source)
                                  :else (if-let [t (c/title ctx u)]
                                          (update items (id u) assoc :title t :title-source "first-line")
                                          (update items (id u) dissoc :title :title-source)))))
                        items3 (distinct (concat tagged children)))
        gone    (set/difference (set (keys items0)) current)
        live    (remove #(= "withdrawn" (name (:kind (items0 %)))) gone)
        items4  (if withdraw? (reduce #(update %1 %2 w/withdraw-entry now) items3 live) items3)
        new-ids (remove (set (keys items0)) current)
        legacy  (for [k live :let [e (items0 k)]] [k (norm-text (:content-md e))])
        dupes   (for [k new-ids
                      :let [n (norm-text (:content-md (items4 k)))
                            hit (some (fn [[lk lt]] (when (and (not (ident/trivial? n)) (>= (ident/similarity n lt) 0.8)) lk)) legacy)]
                      :when hit]
                  [k hit])]
    {:state  (assoc state :items items4)
     :report {:tagged (count tagged) :threads (count threads) :children (count children)
              :rekeyed (count (remove (set (keys (:items state))) (keys items0)))
              :skipped skipped
              :new (vec new-ids)
              :untagged (vec live)
              :possible-duplicates (vec dupes)}}))

(defn changes
  "How new-state differs from old-state, by blyg id: {:new :bumped :withdrawn :kind :thread-child :title}."
  [old-state new-state]
  (let [ix (fn [s] (into {} (for [[_ e] (:items s)] [(:blyg-id e) e])))
        o (ix old-state) n (ix new-state)
        by (fn [f] (vec (sort (for [[k e] n :let [p (o k)] :when (and p (f p e))] k))))]
    {:new          (vec (sort (remove o (keys n))))
     :bumped       (by #(not= (:version %1) (:version %2)))
     :withdrawn    (by #(and (not= "withdrawn" (name (:kind %1))) (= "withdrawn" (name (:kind %2)))))
     :kind         (by #(not= (name (:kind %1)) (name (:kind %2))))
     :thread-child (by #(not= (boolean (:thread-child? %1)) (boolean (:thread-child? %2))))
     :title        (by #(not= (:title %1) (:title %2)))}))
