(ns hyperphor.thirdmind.parse
  "Line-based parser for Logseq markdown files: page name, page properties, block outline, refs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URLDecoder]))

;;; Page names

(defn- decode-pct
  "Decode %XX sequences only (legacy Logseq file names); leaves +, _ etc alone."
  [s]
  (str/replace s #"(?:%[0-9A-Fa-f]{2})+" #(URLDecoder/decode ^String % "UTF-8")))

(defn- ordinal [n]
  (str n (if (<= 11 (mod n 100) 13)
           "th"
           (case (mod n 10) 1 "st" 2 "nd" 3 "rd" "th"))))

(def ^:private months ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn journal-info
  "For a journal file name like 2013_07_05.md, {:name \"Jul 5th, 2013\" :day 20130705}, else nil."
  [file-name]
  (when-let [[_ y m d] (re-matches #"(\d{4})_(\d{2})_(\d{2})\.md" file-name)]
    (let [[y m d] (map parse-long [y m d])]
      {:name (str (months (dec m)) " " (ordinal d) ", " y)
       :day  (+ (* 10000 y) (* 100 m) d)})))

(defn file-page-name
  "Page name implied by a file name (before any title:: override)."
  [file-name journal?]
  (if journal?
    (:name (journal-info file-name))
    (decode-pct (str/replace file-name #"\.md$" ""))))

;;; Properties

(def ^:private property-re #"^([A-Za-z0-9_.\-]+):: ?(.*)$")

(defn property-line
  "[key value] if line (already trimmed) is a key:: value property, else nil."
  [line]
  (when-let [[_ k v] (re-matches property-re line)]
    [(str/lower-case k) (str/trim v)]))

;;; Refs (outside code)

;;; Shared with ui.render, so rendered links agree with stored refs.
(def code-re       #"(?s)```.*?(?:```|$)|`[^`\n]*`")
(def bracket-tag-re #"(?<=^|\s)#\[\[([^\[\]]+)\]\]")
(def tag-re        #"(?<=^|\s)#([^\s#\[\](){},;!?\"'+][^\s#\[\](){},;!?\"']*)")
(def wikilink-re   #"\[\[([^\[\]]+)\]\]")
(def block-ref-re  #"\(\(([^()\s]+)\)\)")

(defn trim-tag
  "Tags may end in sentence punctuation, which isn't part of the name."
  [tag]
  (str/replace tag #"[.:]+$" ""))

(defn strip-code
  "Remove fenced and inline code so refs inside them are ignored."
  [s]
  (str/replace s code-re ""))

(defn extract-refs
  "{:pages [names] :tags [names] :block-refs [ids]} referenced in text."
  [text]
  (let [s     (strip-code text)
        tags  (map trim-tag (concat (map second (re-seq bracket-tag-re s))
                                    (map second (re-seq tag-re s))))
        pages (map second (re-seq wikilink-re s))]
    {:pages      (vec (distinct (remove str/blank? (concat pages tags))))
     :tags       (vec (distinct (remove str/blank? tags)))
     :block-refs (vec (distinct (map second (re-seq block-ref-re s))))}))

;;; Outline

(def ^:private bullet-re #"^([ \t]*)- ?(.*)$")

(defn- indent-width [s]
  (reduce + (map #(if (= % \tab) 4 1) s)))

(def ^:private markers #{"TODO" "DOING" "DONE" "LATER" "NOW" "WAITING" "CANCELED" "CANCELLED"})

(defn- finish-block
  "Turn accumulated lines into a block: separate properties from content."
  [{:keys [lines] :as b}]
  (let [{props true content false}
        (group-by (fn [{:keys [line fenced?]}] (boolean (and (not fenced?) (property-line (str/trim line)))))
                  lines)
        content (str/trim (str/join "\n" (map :line content)))]
    (-> b
        (dissoc :lines)
        (assoc :content content
               :props (into {} (map (comp property-line str/trim :line)) props))
        (cond-> (markers (first (str/split content #"\s" 2)))
          (assoc :marker (first (str/split content #"\s" 2)))))))

(defn parse-lines
  "Parse markdown lines into {:props {} :blocks [{:content :props :path [i ...] :children-of path}]}.
  Blocks are flat, in document order; :path is the outline position (e.g. [0 2 1])."
  [lines]
  (loop [[line & more :as ls] lines
         fenced? false
         stack   []      ; [{:width w :path p}] open ancestors
         counts  {[] 0}  ; parent path -> next child index
         current nil     ; block being accumulated
         preamble []     ; lines before the first bullet
         blocks  []]
    (if (empty? ls)
      (let [blocks (cond-> blocks current (conj (finish-block current)))
            pre    (finish-block {:lines preamble})
            ;; Preamble properties are page properties; other preamble text becomes block 0-.
            [page-props blocks]
            (cond
              ;; first block that is nothing but properties = page properties (pre-block)
              (and (empty? (:content pre)) (seq blocks)
                   (= [0] (:path (first blocks)))
                   (empty? (:content (first blocks))) (seq (:props (first blocks))))
              [(merge (:props pre) (:props (first blocks))) (vec (rest blocks))]
              (seq (:content pre))
              [(:props pre) (into [(assoc pre :path [-1])] blocks)]
              :else
              [(:props pre) blocks])]
        {:props page-props :blocks blocks})
      (let [fence-line? (str/starts-with? (str/trim line) "```")
            bullet      (and (not fenced?) (re-matches bullet-re line))]
        (cond
          bullet
          (let [[_ indent text] bullet
                w      (indent-width indent)
                stack  (vec (take-while #(< (:width %) w) stack))
                parent (or (:path (peek stack)) [])
                idx    (get counts parent 0)
                path   (conj parent idx)
                fence? (str/starts-with? (str/trim text) "```")]
            (recur more (if fence? (not fenced?) false)
                   (conj stack {:width w :path path})
                   (assoc counts parent (inc idx))
                   {:path path :lines [{:line text}]}
                   preamble
                   (cond-> blocks current (conj (finish-block current)))))

          current
          (recur more (if fence-line? (not fenced?) fenced?) stack counts
                 (update current :lines conj {:line (str/trim line) :fenced? (or fenced? fence-line?)})
                 preamble blocks)

          :else
          (recur more (if fence-line? (not fenced?) fenced?) stack counts nil
                 (conj preamble {:line line :fenced? (or fenced? fence-line?)}) blocks))))))

(defn parse-file
  "Parse a Logseq .md file. Returns {:name :file :journal? :journal-day :props :blocks}."
  [^java.io.File f journal? rel-path]
  (let [{:keys [props blocks]} (parse-lines (str/split-lines (slurp f)))
        fname    (.getName f)
        ;; non-date files in journals/ (eg backups) are treated as ordinary pages
        journal? (boolean (and journal? (journal-info fname)))]
    (cond-> {:name    (or (get props "title") (file-page-name fname journal?))
             :file    rel-path
             :journal? journal?
             :props   props
             :blocks  blocks}
      journal? (assoc :journal-day (:day (journal-info fname))))))

(defn graph-files
  "Seq of [File journal? rel-path] for pages/ and journals/ .md files of a graph directory."
  [dir]
  (for [[sub journal?] [["pages" false] ["journals" true]]
        :let [d (io/file dir sub)]
        :when (.isDirectory d)
        ^java.io.File f (sort (.listFiles d))
        :when (and (.isFile f) (str/ends-with? (.getName f) ".md"))]
    [f journal? (str sub "/" (.getName f))]))
