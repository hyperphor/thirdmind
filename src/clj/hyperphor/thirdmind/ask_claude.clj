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
  no children yet."
  [realm {:keys [new] :as diff}]
  (let [by-uri  (into {} (for [[t m] modes] [(ingest/page-uri realm (ingest/page-key t)) m]))
        parents (set (map (comp :parent :obj) new))]
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

(defn answer!
  "Ask about the block with content in file rel, and write the answer under it. Returns true if written."
  [{:keys [ask log] :as cfg} rel mode content]
  (when-let [{:keys [name blocks]} (gw/snapshot cfg rel)]
    (if-let [b (some-> (gw/find-block blocks content) (#(when (:start %) %)))]
      (let [answer  (ask (prompt mode name blocks b))
            written (gw/rewrite! cfg rel
                                 (fn [{:keys [lines blocks]}]
                                   (when-let [b (gw/find-block blocks content)]
                                     (when (and (:start b) (not (gw/children? blocks b)))
                                       (let [end  (gw/block-end lines blocks b)
                                             ls   (reduce (fn [ls n] (update ls n strip-tag)) lines (:line-nos b))
                                             new  (answer-lines (gw/bullet-indent lines b) (gw/indent-unit lines) answer)]
                                         (vec (concat (subvec ls 0 end) new (subvec ls end))))))))]
        (when-not written (log "AskClaude: block changed, answer not written:" rel "\n" answer))
        written)
      (log "AskClaude: block not found" rel))))
