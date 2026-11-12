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

(defn url-encode
  "Percent-encode s for a URL path or query (spaces as %20)."
  [s]
  (str/replace (java.net.URLEncoder/encode (str s) "UTF-8") "+" "%20"))

(defn page-href [key] (str "/page/" key))
(defn block-href [id] (str "/block/" (url-encode id)))

(defn- page-link [ctx name text class]
  (let [key (:key ((:page ctx) name))]
    (format "<a class=\"%s%s\" href=\"%s\">%s</a>"
            class (if (:stub ((:page ctx) name)) " stub" "") (page-href key) (label text))))

(defn- plain-text
  "First line of block content with refs reduced to their names, for block-ref labels."
  [content]
  (-> (first (str/split-lines (str content)))
      (str/replace #"^(?:>\s*|#+\s+)" "")
      (str/replace parse/wikilink-re "$1")
      (str/replace parse/block-ref-re "…")))

(defn- block-link [ctx id]
  (if-let [content ((:block ctx) id)]
    (format "<a class=\"bref\" href=\"%s\">%s</a>" (block-href id) (label (plain-text content)))
    (format "<span class=\"bref missing\">((%s))</span>" (escape id))))

;;; Images and asset links. Logseq paths (../assets/...) often hold spaces and ?, which
;;; commonmark won't take as a destination, so these go straight to HTML too.

(def ^:private image-re #"!\[([^\]\n]*)\]\(([^)\n]+)\)(\{[^}\n]*\})?")
(def ^:private asset-link-re #"\[([^\]\n]*)\]\((\.\./assets/[^)\n]+)\)")
;; [label]([[Page]]): a page link with its own text
(def ^:private labeled-link-re #"\[([^\]\n]+)\]\(\[\[([^\[\]]+)\]\]\)")

(defn asset-href
  "Served URL for a link destination: ../assets/x → /assets/x (encoded); others unchanged."
  [dest]
  (if-let [[_ path] (re-matches #"\.\./assets/(.*)" (str/trim dest))]
    (str "/assets/" (str/join "/" (map url-encode (str/split path #"/"))))
    (str/trim dest)))

(defn- image [alt dest attrs]
  (let [width (some->> attrs (re-find #":width\s+(\d+)") second)]
    (format "<img src=\"%s\" alt=\"%s\"%s>" (escape (asset-href dest)) (escape alt)
            (if width (format " width=\"%s\"" width) ""))))

(def ^:private ref-re
  (re-pattern (str/join "|" (map #(str "(?:" % ")")
                                 [image-re asset-link-re labeled-link-re
                                  parse/bracket-tag-re parse/wikilink-re parse/block-ref-re parse/tag-re]))))

(defn- link-refs
  "Replace refs, images and asset links in a non-code segment with HTML."
  [ctx s]
  (str/replace s ref-re
               (fn [[m img-alt img-dest img-attrs asset-label asset-dest link-label link-page btag wiki bref tag]]
                 (cond img-dest   (image img-alt img-dest img-attrs)
                       asset-dest (format "<a class=\"asset\" href=\"%s\">%s</a>"
                                          (escape (asset-href asset-dest)) (label asset-label))
                       link-page (page-link ctx link-page link-label "ref")
                       btag (page-link ctx btag (str "#" btag) "tag")
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

(def ^:private max-embed-depth 3)

(defn block-html
  "HTML for a block's content (marker excluded). A block that is only ((id)) shows the target
  block's content in place, as Logseq does. ctx:
  :page  (fn [name] {:key canonical-page-key :stub bool})
  :block (fn [id] content or nil)"
  [ctx content]
  (let [id     (second (re-matches parse/block-ref-re (str/trim content)))
        target (when id ((:block ctx) id))
        depth  (get ctx ::depth 0)]
    (if (and target (< depth max-embed-depth))
      (str "<div class=\"transclusion\">"
           (block-html (assoc ctx ::depth (inc depth)) target)
           (format "<a class=\"bref-src\" href=\"%s\" title=\"referenced block\">↗</a>" (block-href id))
           "</div>\n")
      (markdown->html (link-logseq ctx content)))))

(defn split-marker
  "[marker rest] if content starts with a task marker like TODO, else [nil content]."
  [marker content]
  (if (and marker (str/starts-with? content marker))
    [marker (str/triml (subs content (count marker)))]
    [nil content]))
