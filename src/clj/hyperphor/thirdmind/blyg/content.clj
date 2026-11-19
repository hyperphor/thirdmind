(ns hyperphor.thirdmind.blyg.content
  "A #blyg block (+ children) → content_md / content_html, from the db: control tags stripped,
  links pointed at the published site (only at pages it has), private children left out."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup.util :refer [raw-string]]
            [hiccup2.core :as h]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.parse :as parse]
            [hyperphor.thirdmind.ui.render :as r]
            [hyperphor.tunamelt.core :as t]))

;;; Site URLs, named as goddinpotty names its pages (utils/clean-page-title)

(def ^:private slug-drop (set "()[]?!.#$%^&*+=;:\"'\\,<>~`{}"))

(defn slug
  "goddinpotty's file name / URL path for a page title."
  [title]
  (-> (apply str (remove slug-drop title)) (str/replace #"\s" "-") (str/replace "/" "∕")))

(defn published-pages
  "Names of the page files in a goddinpotty output dir (what the site publishes)."
  [dir]
  (set (for [^java.io.File f (.listFiles (io/file dir))
             :when (and (.isFile f) (not (str/starts-with? (.getName f) ".")))]
         (.getName f))))

(defn ctx
  "Context for realm. opts :base-url, :published (set of slugs), :exit-tags (tag names marking a
  block private), :tags (control tags to strip, default blyg, blyg-pin, blyg-reply)."
  [realm {:keys [base-url published exit-tags tags] :or {tags ["blyg" "blyg-pin" "blyg-reply"]}}]
  (let [aliases (ingest/db-aliases realm)
        fetch   (memoize t/get)
        canon   (fn [name] (ingest/page-uri realm (let [k (ingest/page-key name)] (get aliases k k))))]
    {:realm     realm
     :base-url  (str (str/replace base-url #"/+$" "") "/")
     :published (set published)
     :fetch     fetch
     :page-of   (fn [name] (fetch (canon name)))
     :control   (set (map str/lower-case tags))
     :exit      (set (map canon exit-tags))
     :askclaude (canon "AskClaude")}))

(defn page-url
  "Site URL for page name as written, else its canonical page (via aliases); nil if unpublished."
  [{:keys [base-url published page-of]} name & [anchor]]
  (when-let [title (some #(when (and % (published (slug %))) %) [name (:name (page-of name))])]
    (str base-url (slug title) (when anchor (str "#" anchor)))))

(defn- asset-url [{:keys [base-url]} dest]
  (let [h (r/asset-href dest)] (if (str/starts-with? h "/") (str base-url (subs h 1)) h)))

;;; Text, outside code

(defn- outside-code [s f]
  (let [m (re-matcher parse/code-re s)]
    (loop [pos 0, out (StringBuilder.)]
      (if (.find m)
        (recur (.end m) (doto out (.append (f (subs s pos (.start m)))) (.append (.group m))))
        (str (.append out (f (subs s pos))))))))

(defn strip-tags
  "content without control tags (and the space before them)."
  [{:keys [control]} content]
  (-> (outside-code content
                    (fn [s] (str/replace s #"(?m)(?:[ \t]+|^)#(?:\[\[([^\[\]\n]+)\]\]|([\w-]+))(?![\w-])"
                                         (fn [[m a b]] (if (control (str/lower-case (or a b))) "" m)))))
      (str/replace #"(?m)[ \t]+$" "")
      str/trim))

(def ^:private markers {"TODO" "◘" "DONE" "⌧"})

(defn- marker [s]
  (if-let [[_ m more] (re-matches #"(?s)(TODO|DONE)\b\s*(.*)" s)] (str (markers m) " " more) s))

(def ^:private md-re
  (re-pattern (str/join "|" (map #(str "(?:" % ")")
                                 [#"\{\{([A-Za-z][\w-]*)\s*((?:(?!\}\}).)*)\}\}"
                                  #"!\[([^\]\n]*)\]\(([^)\n]+)\)(?:\{[^}\n]*\})?"
                                  #"\[([^\]\n]*)\]\((\.\./assets/[^)\n]+)\)"
                                  #"\[([^\]\n]+)\]\(\[\[([^\[\]]+)\]\]\)"
                                  parse/bracket-tag-re parse/wikilink-re parse/tag-re]))))

(defn- md-link [ctx name text]
  (if-let [u (page-url ctx name)] (format "[%s](%s)" text u) text))

(defn- md-refs [ctx s]
  (str/replace s md-re
               (fn [[_ mac arg alt img alabel adest llabel lpage btag wiki tag]]
                 (cond mac    (if (#{"tweet" "twitter" "video" "youtube"} (str/lower-case mac))
                                (or (re-find #"https?://[^\s)\]}]+" arg) "")
                                "")
                       img    (format "![%s](%s)" alt (asset-url ctx img))
                       adest  (format "[%s](%s)" alabel (asset-url ctx adest))
                       lpage  (md-link ctx lpage llabel)
                       btag   (md-link ctx btag (str "#" btag))
                       wiki   (md-link ctx wiki wiki)
                       :else  (let [t (parse/trim-tag tag)]
                                (str (md-link ctx t (str "#" t)) (subs tag (count t))))))))

;;; Blocks

(defn- obj [ctx uri] ((:fetch ctx) uri))

(defn- kids [uri] (sort-by (comp :order :obj) (t/find "block" {:attrs {:parent uri}})))

(defn private?
  "Is the block marked private itself: an exit tag on it, or a child that is only an exit tag?"
  [{:keys [exit] :as ctx} uri]
  (let [o (obj ctx uri)]
    (boolean (or (some exit (concat (:tags o) (:refs o)))
                 (some (fn [{c :obj}] (and (some exit (:tags c)) (re-matches #"#\S+" (str/trim (str (:content c))))))
                       (kids uri))))))

(defn- ask-claude? [ctx o] (some #{(:askclaude ctx)} (:tags o)))

(defn body-children
  "Child block uris that belong in the item: not private, not empty, none under an #AskClaude
  block (its children are the answer)."
  [ctx uri]
  (if (ask-claude? ctx (obj ctx uri))
    []
    (for [{u :uri o :obj} (kids uri)
          :when (and (not (private? ctx u))
                     (not (and (str/blank? (:content o)) (empty? (kids u))))
                     (not (and (re-matches #"#\S+" (str/trim (str (:content o)))) (some (:exit ctx) (:tags o)))))]
      u)))

(defn own-md
  "Markdown of the block's own text."
  [ctx uri]
  (->> (strip-tags ctx (str (:content (obj ctx uri)))) (#(outside-code % (partial md-refs ctx))) marker str/trim))

(defn item-md
  "Own text, then each body child's (recursively), as paragraphs."
  [ctx uri]
  (str/join "\n\n" (remove str/blank? (cons (own-md ctx uri) (map (partial item-md ctx) (body-children ctx uri))))))

(defn- uuid-anchor
  "The block's Logseq id:: (what goddinpotty's pages use as anchors), if it has one."
  [uri]
  (let [id (java.net.URLDecoder/decode ^String (subs uri (inc (str/last-index-of uri "/"))) "UTF-8")]
    (when (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" id) id)))

(defn source-url
  "[page-name url] to attribute the block to: its page, unless a journal or unpublished."
  [ctx uri]
  (let [p (obj ctx (:page (obj ctx uri)))]
    (when (and p (not (:journal p)) (not (str/blank? (:name p))))
      (when-let [u (page-url ctx (:name p) (uuid-anchor uri))] [(:name p) u]))))

(defn- with-attribution-md [ctx uri body]
  (if-let [[name u] (source-url ctx uri)] (str "From [" name "](" u ")\n\n" body) body))

(defn fragment-md
  "content_md of a fragment: attribution line (unless :attribution? false), then item-md."
  [ctx uri & {:keys [attribution?] :or {attribution? true}}]
  (cond->> (item-md ctx uri) attribution? (with-attribution-md ctx uri)))

(defn thread-md
  "content_md of a thread: attribution, own text, then a ![[id]] directive per child item id."
  [ctx uri child-ids]
  (with-attribution-md ctx uri
    (str/join "\n\n" (remove str/blank? (cons (own-md ctx uri) (map #(str "![[" % "]]") child-ids))))))

;;; HTML: ThirdMind's renderer, with links to the site and URLs made absolute.

(defn absolutize-html
  "Relative src/href attributes in html made absolute against base."
  [html base]
  (str/replace html #"(src|href)=\"([^\"]*)\""
               (fn [[whole attr url]]
                 (cond (or (str/blank? url) (re-matches #"(?i)[a-z][a-z0-9+.-]*:.*" url)
                           (str/starts-with? url "//") (str/starts-with? url "#")) whole
                       (str/starts-with? url "/") (str attr "=\"" (str/replace base #"/+$" "") url "\"")
                       :else (str attr "=\"" base url "\"")))))

(defn- render-ctx [{:keys [realm fetch] :as ctx}]
  {:page       (constantly {:key "" :stub false})
   :page-href  #(page-url ctx %)
   :block      (fn [id] (:content (fetch (ingest/block-uri realm id))))
   :block-href (constantly nil)})

(defn own-hiccup [ctx uri]
  (raw-string (r/block-html (render-ctx ctx) (marker (strip-tags ctx (str (:content (obj ctx uri))))))))

(defn- answer-hiccup [ctx uri]
  (for [{u :uri o :obj} (kids uri) :when (not (private? ctx u))]
    [:li (own-hiccup ctx u) (when-let [s (seq (answer-hiccup ctx u))] [:ul s])]))

(defn item-hiccup
  "Own text, with body children as a nested list; an #AskClaude block's answer in a closed <details>."
  [ctx uri]
  (let [own (own-hiccup ctx uri)]
    (if (ask-claude? ctx (obj ctx uri))
      (if-let [ans (seq (answer-hiccup ctx uri))]
        [:details.ask-claude [:summary own] [:ul.claude ans]]
        [:div own])
      (if-let [ks (seq (body-children ctx uri))]
        [:div own (into [:ul.blyg-children] (for [k ks] [:li (item-hiccup ctx k)]))]
        [:div own]))))

(defn fragment-html
  "content_html of a fragment: attribution (unless :attribution? false), then item-hiccup."
  [{:keys [base-url] :as ctx} uri & {:keys [attribution?] :or {attribution? true}}]
  (let [body (item-hiccup ctx uri)
        src  (when attribution? (source-url ctx uri))]
    (-> (str (h/html (if-let [[name u] src] [:div [:p.source-page "From " [:a {:href u} name]] body] body)))
        (absolutize-html base-url))))

(defn thread-html
  "content_html of a thread: attribution, own text, then one blockquote per segment, in order:
  [:child entry] (an own item) or [:remote snapshot] (another blyg's, with its origin)."
  [{:keys [base-url] :as ctx} uri segments]
  (let [quote (fn [[kind e]]
                (if (= kind :remote)
                  [:blockquote.blyg-transclusion {:data-blyg-id (:id e) :data-blyg-version (:version e)
                                                  :data-blyg-origin (:origin e)}
                   (raw-string (:content-html e))]
                  [:blockquote.blyg-transclusion {:data-blyg-id (:blyg-id e) :data-blyg-version (:version e)}
                   (raw-string (:content-html e))]))
        body  (into [:div [:div (own-hiccup ctx uri)]] (map quote segments))
        src   (source-url ctx uri)]
    (-> (str (h/html (if-let [[name u] src] [:div [:p.source-page "From " [:a {:href u} name]] body] body)))
        (absolutize-html base-url))))
