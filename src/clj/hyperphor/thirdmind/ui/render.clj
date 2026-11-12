(ns hyperphor.thirdmind.ui.render
  "Logseq block content → HTML. Wikilinks, tags and block refs become links; the rest is commonmark."
  (:require [clojure.string :as str]
            [hyperphor.thirdmind.parse :as parse])
  (:import [org.commonmark.parser Parser]
           [org.commonmark.renderer.html HtmlRenderer]
           [org.commonmark.ext.autolink AutolinkExtension]))

;;; Logseq refs are rewritten to inline HTML (outside code) before commonmark sees the text,
;;; using parse's regexes so rendered links match the refs ingest stored.

(def ^:private extensions [(AutolinkExtension/create)])
(def ^:private parser   (-> (Parser/builder) (.extensions extensions) .build))
(def ^:private renderer (-> (HtmlRenderer/builder) (.extensions extensions) .build))

(defn escape
  "HTML-escape s."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- label
  "Link text: HTML-escaped, with markdown emphasis/code chars backslash-escaped."
  [s]
  (str/replace (escape s) #"([\\`*_])" "\\\\$1"))

(defn page-href [key] (str "/page/" key))
(defn block-href [id] (str "/block/" (java.net.URLEncoder/encode (str id) "UTF-8")))

(defn- page-link [ctx name text class]
  (let [key (:key ((:page ctx) name))]
    (format "<a class=\"%s%s\" href=\"%s\">%s</a>"
            class (if (:stub ((:page ctx) name)) " stub" "") (page-href key) (label text))))

(defn- plain-text
  "First line of block content with refs reduced to their names, for block-ref labels."
  [content]
  (-> (first (str/split-lines (str content)))
      (str/replace parse/wikilink-re "$1")
      (str/replace parse/block-ref-re "…")))

(defn- block-link [ctx id]
  (if-let [content ((:block ctx) id)]
    (format "<a class=\"bref\" href=\"%s\">%s</a>" (block-href id) (label (plain-text content)))
    (format "<span class=\"bref missing\">((%s))</span>" (escape id))))

(def ^:private ref-re
  (re-pattern (str/join "|" (map #(str "(?:" % ")")
                                 [parse/bracket-tag-re parse/wikilink-re parse/block-ref-re parse/tag-re]))))

(defn- link-refs
  "Replace refs in a non-code segment with HTML links."
  [ctx s]
  (str/replace s ref-re
               (fn [[m btag wiki bref tag]]
                 (cond btag (page-link ctx btag (str "#" btag) "tag")
                       wiki (page-link ctx wiki wiki "ref")
                       bref (block-link ctx bref)
                       :else (let [t (parse/trim-tag tag)]
                               (str (page-link ctx t (str "#" t) "tag") (subs tag (count t))))))))

(defn link-logseq
  "Content with Logseq refs outside code turned into HTML links."
  [ctx content]
  (let [m (re-matcher parse/code-re content)]
    (loop [pos 0, out (StringBuilder.)]
      (if (.find m)
        (recur (.end m) (doto out
                          (.append (link-refs ctx (subs content pos (.start m))))
                          (.append (.group m))))
        (str (.append out (link-refs ctx (subs content pos))))))))

(defn markdown->html
  "Commonmark rendering of s."
  [s]
  (.render renderer (.parse parser s)))

(defn block-html
  "HTML for a block's content (marker excluded). ctx:
  :page  (fn [name] {:key canonical-page-key :stub bool})
  :block (fn [id] content or nil)"
  [ctx content]
  (markdown->html (link-logseq ctx content)))

(defn split-marker
  "[marker rest] if content starts with a task marker like TODO, else [nil content]."
  [marker content]
  (if (and marker (str/starts-with? content marker))
    [marker (str/triml (subs content (count marker)))]
    [nil content]))
