(ns hyperphor.thirdmind.ask-claude
  "#AskClaude (or #AskClaudePage) ending a block: ask Claude about its outline (or whole page), and
  nest the answer under a #AskClaude wrapper block, as the logseq-claude plugin does."
  (:require [clojure.string :as str]
            [hyperphor.thirdmind.graph-write :as gw]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.tunamelt.core :as t]))

(def tag "AskClaude")

(def modes
  "Trigger tag → prompt scope: the top-level subtree holding the block, or the whole page."
  {"AskClaude" :tree "AskClaudePage" :page})

(def ^:private tag-re #"(?i)#(?:\[\[AskClaude(?:Page)?\]\]|AskClaude(?:Page)?)(?![\w-])")

(defn wrapper?
  "Is content an answer wrapper (starts with #AskClaude)?"
  [content]
  (boolean (re-find #"(?i)^#(?:\[\[AskClaude\]\]|AskClaude)(?![\w-])" (str content))))

(defn in-answer?
  "Is the db block at uri inside an answer wrapper?"
  [uri]
  (loop [u uri, n 0]
    (when-let [o (and u (< n 50) (t/get u))]
      (cond (wrapper? (:content o)) true
            (str/includes? (str (:parent o)) "/block/") (recur (:parent o) (inc n))
            :else false))))

(defn triggers
  "[mode block-entry] for blocks in diff newly given a trigger tag, that aren't wrappers and have
  no non-blank children yet."
  [realm {:keys [new] :as diff}]
  (let [by-uri  (into {} (for [[t m] modes] [(ingest/page-uri realm (ingest/page-key t)) m]))
        parents (set (for [{:keys [obj]} new :when (not (str/blank? (:content obj)))] (:parent obj)))]
    (distinct
     (for [[t {:keys [uri obj] :as b}] (ingest/new-tags diff)
           :let [mode (by-uri t)]
           :when (and mode (not (wrapper? (:content obj))) (not (parents uri)))]
       [mode b]))))

(defn strip-tag
  "s without the trigger tag (and the space before it)."
  [s]
  (if (re-find tag-re s) (-> s (str/replace (re-pattern (str "[ \\t]*" tag-re)) "") (str/replace #"[ \t]+$" "")) s))

;;; Prompts follow the plugin's: an outline with earlier answers left out, then the ask.

(defn- under? [p q] (and (< (count q) (count p)) (= q (subvec p 0 (count q)))))

(defn prompt
  "Prompt for target (a parsed block) on page page-name, scoped by mode (:tree or :page)."
  [mode page-name blocks target]
  (let [tp      (:path target)
        scope   (if (= mode :page) blocks (filter #(= (first tp) (first (:path %))) blocks))
        skipped (set (for [b scope
                           :when (and (wrapper? (:content b)) (not (under? tp (:path b))) (not= tp (:path b)))]
                       (:path b)))
        shown   (remove (fn [b] (some #(or (= % (:path b)) (under? (:path b) %)) skipped)) scope)
        ask     (strip-tag (:content target))
        lines   (for [b shown
                      :let [c (if (= tp (:path b)) ask (:content b))
                            pad (apply str (repeat (max 0 (dec (count (:path b)))) "  "))]
                      :when (seq c)
                      [i l] (map-indexed vector (str/split-lines c))]
                  (str pad (if (zero? i) "- " "  ") l))
        outline (str "# " page-name "\n\n" (str/join "\n" lines))]
    (if (or (str/blank? ask) (= 1 (count shown)))
      outline
      (str outline "\n\nRespond to this item from the " (if (= mode :page) "page" "outline") " above:\n\n" ask))))

;;; Answer markdown → blocks, ported from the plugin's markdownToBlocks.

(def ^:private list-re #"^(\s*)(?:[-*+]|\d+\.)\s+(.*)")

(defn- nest [items]
  (loop [[it & more] items, acc []]
    (if-not it
      acc
      (let [[kids rest] (split-with #(> (:indent %) (:indent it)) more)]
        (recur rest (conj acc {:content (:content it) :children (nest kids)}))))))

(defn md->blocks
  "[{:content :children}] for a markdown answer."
  [md]
  (loop [[l & more :as ls] (str/split-lines md), acc []]
    (cond
      (empty? ls) acc
      (str/blank? l) (recur more acc)

      (str/starts-with? (str/triml l) "```")
      (let [[body tail] (split-with #(not (str/starts-with? (str/triml %) "```")) more)]
        (recur (rest tail) (conj acc {:content (str/join "\n" (concat [l] body (take 1 tail))) :children []})))

      (str/starts-with? (str/triml l) "|")
      (let [[rows tail] (split-with #(str/starts-with? (str/triml %) "|") ls)]
        (recur tail (conj acc {:content (str/join "\n" (map str/trim rows)) :children []})))

      (re-matches list-re l)
      (let [[items tail] (split-with #(re-matches list-re %) ls)]
        (recur tail (into acc (nest (for [i items :let [[_ ind c] (re-matches list-re i)]]
                                      {:indent (count ind) :content c})))))

      (str/starts-with? l "#")
      (recur more (conj acc {:content l :children []}))

      :else
      (let [[para tail] (split-with #(not (or (str/blank? %) (re-matches list-re %) (str/starts-with? % "#")
                                              (str/starts-with? (str/triml %) "```")))
                                    more)
            text (reduce (fn [s [prev nxt]]
                           (if (re-find #"(  |\\)$" prev)
                             (str (str/replace s #"[ \t]+$" "") "\n" (str/trim nxt))
                             (str (str/trimr s) " " (str/trim nxt))))
                         l (map vector (cons l para) para))]
        (recur tail (conj acc {:content text :children []}))))))

(defn- block-lines [indent unit {:keys [content children]}]
  (concat (map-indexed (fn [i l] (str indent (if (zero? i) "- " "  ") l)) (str/split-lines content))
          (mapcat #(block-lines (str indent unit) unit %) children)))

(defn answer-lines
  "File lines for the wrapper block and answer, nested under a bullet at indent."
  [indent unit answer]
  (block-lines (str indent unit) unit {:content (str "#" tag) :children (md->blocks answer)}))

(defn- subtree
  "Blocks under b (not b itself)."
  [blocks b]
  (filter #(under? (:path %) (:path b)) blocks))

(defn- subtree-end
  "Line index just past b's subtree (trailing blank lines excluded)."
  [lines blocks b]
  (let [last-b (or (last (subtree blocks b)) b)]
    (gw/block-end lines blocks last-b)))

(def thinking (str "#" tag " ⏳ Thinking…"))

(defn- replace-under!
  "Rewrite rel: under the block with content (tag stripped), if (ok? its subtree), put lines-for's
  lines in place of the subtree. Returns true if written."
  [cfg rel content ok? lines-for]
  (gw/rewrite! cfg rel
               (fn [{:keys [lines blocks]}]
                 (when-let [b (or (gw/find-block blocks content) (gw/find-block blocks (strip-tag content)))]
                   (when (and (:start b) (ok? (subtree blocks b)))
                     (let [end (gw/block-end lines blocks b)
                           ls  (reduce (fn [ls n] (update ls n strip-tag)) lines (:line-nos b))
                           new (lines-for (gw/bullet-indent lines b) (gw/indent-unit lines))]
                       (vec (concat (subvec ls 0 end) new (subvec (vec ls) (subtree-end lines blocks b))))))))))

(defn- error-text
  "e's message, with its root cause's when that differs (\"Request failed: timeout\")."
  [^Throwable e]
  (let [root (last (take-while some? (iterate #(.getCause ^Throwable %) e)))
        msg  #(or (ex-message %) (.getSimpleName (class %)))]
    (if (identical? root e) (msg e) (str (msg e) ": " (msg root)))))

(defn- blank-only? [kids] (every? #(str/blank? (:content %)) kids))
(defn- thinking-only? [kids] (and (= 1 (count kids)) (= thinking (:content (first kids)))))

(defn answer!
  "Ask about the block with content in file rel: show a Thinking placeholder under it, then replace
  it with the answer. Returns true if the answer was written."
  [{:keys [ask log] :as cfg} rel mode content]
  (when-let [{:keys [name blocks]} (gw/snapshot cfg rel)]
    (if-let [b (some-> (gw/find-block blocks content) (#(when (:start %) %)))]
      (let [p (prompt mode name blocks b)]
        ;; Blank children (stray empty bullets) are replaced too.
        (if-not (replace-under! cfg rel content blank-only?
                                (fn [indent unit] [(str indent unit "- " thinking)]))
          (log "AskClaude: block changed, not asked:" rel)
          (let [[answer err] (try [(ask p)] (catch Exception e [nil (error-text e)]))
                written      (replace-under! cfg rel content thinking-only?
                                             (fn [indent unit]
                                               (if err
                                                 [(str indent unit "- #" tag " ⚠️ " (first (str/split-lines (str err))))]
                                                 (answer-lines indent unit answer))))]
            (cond err          (log "AskClaude: ask failed:" rel err)
                  (not written) (log "AskClaude: placeholder changed, answer not written:" rel "\n" answer))
            (and written (not err)))))
      (log "AskClaude: block not found" rel))))
