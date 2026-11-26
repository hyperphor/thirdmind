(ns hyperphor.thirdmind.smart-url
  "Smart URLs: a bare URL newly typed into a block becomes [page title](url)."
  (:require [clojure.string :as str]
            [hyperphor.thirdmind.graph-write :as gw]
            [org.httpkit.client :as http]))

;;; Bare URLs. One span finder serves both detection and replacement.

(def ^:private masked-re
  "Regions where a URL isn't bare: inline code, links and images, macros, <autolinks>, HTML tags
  (eg an <iframe src=...>), [[pages]]."
  #"`[^`\n]*`|!?\[[^\]\n]*\]\([^)\n]*\)|\{\{.*?\}\}|<[^>\s]*>|</?[A-Za-z][^>]*>|\[\[[^\]\n]*\]\]")

(def ^:private url-re #"https?://[^\s<>\[\]{}\"`]+")

(defn- trim-url
  "Drop trailing sentence punctuation, and a ) that doesn't close a ( inside the URL."
  [u]
  (let [u (str/replace u #"[.,;:!?'*_~]+$" "")]
    (if (and (str/ends-with? u ")") (> (count (re-seq #"\)" u)) (count (re-seq #"\(" u))))
      (recur (subs u 0 (dec (count u))))
      u)))

(defn bare-url-spans
  "[[start end url] ...] for bare URLs in s, left to right."
  [s]
  (let [masks (let [m (re-matcher masked-re s)]
                (loop [acc []] (if (.find m) (recur (conj acc [(.start m) (.end m)])) acc)))
        masked? (fn [i] (some (fn [[a b]] (and (<= a i) (< i b))) masks))
        m (re-matcher url-re s)]
    (loop [acc []]
      (if (.find m)
        (let [start (.start m)
              u     (trim-url (.group m))]
          (recur (if (or (masked? start) (< (count u) 10)
                         (and (pos? start) (re-matches #"[\w/=]" (str (.charAt s (dec start))))))
                   acc
                   (conj acc [start (+ start (count u)) u]))))
        acc))))

(defn bare-urls [s] (map #(nth % 2) (bare-url-spans s)))

(defn eligible?
  "Blocks we leave alone: fenced code, AskClaude answers (in-answer? is supplied by the caller)."
  [content]
  (not (str/includes? content "```")))

(defn new-urls
  "[url block-entry] for each bare URL on a :new block that no :old block with the same uri or
  content had (so existing bare URLs in an edited block are left alone)."
  [{:keys [old new]}]
  (let [had (into #{} (for [{:keys [uri obj]} old, u (bare-urls (:content obj)), k [uri (:content obj)]] [u k]))]
    (distinct
     (for [{:keys [uri obj] :as b} new
           :when (eligible? (:content obj))
           u (bare-urls (:content obj))
           :when (not (or (had [u uri]) (had [u (:content obj)])))]
       [u b]))))

;;; Titles

(def ^:private entities {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " " "#39" "'"
                         "mdash" "—" "ndash" "–" "hellip" "…" "rsquo" "’" "lsquo" "‘" "ldquo" "“" "rdquo" "”"})

(defn- decode-entities [s]
  (str/replace s #"&(#x[0-9a-fA-F]+|#\d+|\w+);"
               (fn [[m e]]
                 (cond (str/starts-with? e "#x") (String. (Character/toChars (Integer/parseInt (subs e 2) 16)))
                       (and (str/starts-with? e "#") (not= e "#39")) (String. (Character/toChars (int (parse-long (subs e 1)))))
                       :else (get entities e m)))))

(def ^:private junk-titles
  #"(?i)^(just a moment.*|attention required.*|access denied|403 forbidden|404 not found|not found|log ?in|sign ?in|error.*|untitled|home)$")

(defn clean-title
  "Usable link text from a raw title, or nil."
  [s]
  (when s
    (let [t (-> s decode-entities (str/replace #"\s+" " ") str/trim
                (str/replace #"[\[\]]" "") (str/replace #"`" "'"))]
      (when (and (seq t) (not (re-matches junk-titles t)))
        (if (> (count t) 100) (str (str/replace (subs t 0 100) #"\s+\S*$" "") "…") t)))))

(defn html-title
  "og:title if present, else <title>, from an HTML string."
  [html]
  (or (some->> (re-find #"(?is)<meta[^>]+(?:property|name)=[\"']og:title[\"'][^>]*>" html)
               (re-find #"(?is)content=[\"']([^\"']*)[\"']") second clean-title)
      (some->> (re-find #"(?is)<title[^>]*>(.*?)</title>" html) second clean-title)))

;;; Some sites (eg dodcio.defense.gov) refuse any non-browser client, so for a refusal the
;;; title is made from the URL instead.

(defn url-title
  "Link text from url itself: its last path segment, made readable, and the host.
  https://dodcio.defense.gov/BrilliantBasics/ → \"Brilliant Basics (dodcio.defense.gov)\"."
  [url]
  (let [u    (java.net.URI. url)
        host (str/replace (str (.getHost u)) #"^www\." "")
        seg  (some-> (last (remove str/blank? (str/split (str (.getRawPath u)) #"/")))
                     (java.net.URLDecoder/decode "UTF-8")
                     (str/replace #"(?i)\.(html?|php|aspx?|jsp)$" "")
                     (str/replace #"[_+-]+" " ")
                     (str/replace #"([a-z])([A-Z])" "$1 $2")
                     str/trim clean-title)]
    (if (seq seg) (str seg " (" host ")") host)))

(defn fetch-title
  "Page title for url, or url-title if the site refuses the fetch. Throws, saying why (HTTP status,
  error, not HTML, no title), if there isn't one."
  [url]
  (let [{:keys [status headers body error]}
        @(http/get url {:timeout 15000 :as :text :max-redirects 5
                        :headers {"User-Agent" "Mozilla/5.0 (compatible; ThirdMind/0.1)"
                                  "Accept"     "text/html,application/xhtml+xml"}})
        fail #(throw (ex-info (str "no title for " url ": " %) {:url url :status status}))]
    (cond error                                         (fail (str (or (ex-message error) error)))
          (#{401 403 429} status)                       (url-title url)
          (not (<= 200 (or status 0) 299))              (fail (str "HTTP " status))
          (not (str/includes? (str (:content-type headers)) "html")) (fail (str "not HTML (" (:content-type headers) ")"))
          :else (or (and (string? body) (html-title body)) (fail "no usable <title> or og:title")))))

;;; Rewrite

(defn- link-line
  "line with each bare occurrence of url replaced by [title](url), right to left."
  [line url title]
  (reduce (fn [s [a b u]] (if (= u url) (str (subs s 0 a) "[" title "](" url ")" (subs s b)) s))
          line (reverse (bare-url-spans line))))

(defn link!
  "Replace bare url in the block whose content is content, in file rel; failing that (it was edited
  since), in the one block that still has url bare. Returns true if written."
  [cfg rel content url title]
  (gw/rewrite! cfg rel
               (fn [{:keys [lines blocks]}]
                 (when-let [b (or (gw/find-block blocks content)
                                  (let [hits (filter #(and (eligible? (:content %)) (some #{url} (bare-urls (:content %)))) blocks)]
                                    (when (= 1 (count hits)) (first hits))))]
                   (reduce (fn [ls n] (update ls n link-line url title)) lines (:line-nos b))))))
