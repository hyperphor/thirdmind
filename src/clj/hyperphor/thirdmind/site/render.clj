(ns hyperphor.thirdmind.site.render
  "Site pages as HTML in goddinpotty's markup (so its CSS and scripts keep working), from the db and
  site/include's result. Rendering reads only those, so the same inputs give the same bytes."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup.util :refer [raw-string]]
            [hiccup2.core :as h]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.parse :as parse]
            [hyperphor.thirdmind.ui.render :as r]
            [hyperphor.tunamelt.core :as t]))

(defn- uri-key [uri] (java.net.URLDecoder/decode ^String (subs uri (inc (str/last-index-of uri "/"))) "UTF-8"))

;;; Twin Pages (http://webseitz.fluxent.com/wiki/TwinPages): goddinpotty's fetch cache, read only

(defn read-enduro-cache
  "{arg value} from one of goddinpotty's one-argument .enduro.d caches (twin-pages-content,
  embed-twitter), or {} if absent."
  [file]
  (if (and file (.isFile (io/file file)))
    (into {} (for [[k v] (edn/read-string (slurp file))] [(first k) v]))
    {}))

;;; Context

(defn ctx
  "Render context. inc is site/include's result; settings as site.cli/settings."
  [realm settings {:keys [pages included] :as inc}]
  (let [aliases (ingest/db-aliases realm)
        fetch   (memoize t/get)
        canon   (fn [name] (ingest/page-uri realm (let [k (ingest/page-key name)] (get aliases k k))))
        slugs   (into {} (for [p pages] [p (c/slug (:name (fetch p)))]))
        shown?  (fn [u] (boolean (and (included u) (slugs (:page (fetch u))))))]
    (merge settings
           {:realm realm :fetch fetch :canon canon :slugs slugs :shown? shown? :inc inc
            :blocks (memoize (fn [page] (t/find "block" {:attrs {:page page}})))
            :twins  (read-enduro-cache (:twin-cache settings))
            ;; tweet URL → oEmbed HTML, so tweets show without Twitter's script
            :tweets (into {} (for [[u v] (read-enduro-cache (:tweet-cache settings)) :when (string? v)]
                               [u (str/trim (str/replace v #"<script[^>]*></script>" ""))]))})))

(defn- page-href [{:keys [canon slugs]} name] (slugs (canon name)))

(defn anchor
  "A block's anchor: its id:: if it has one (as goddinpotty's pages use), else its ThirdMind id."
  [uri obj]
  (let [k (uri-key uri)] (if (str/includes? k "~") (or (:tmid obj) k) k)))

(defn- block-href [{:keys [realm fetch slugs shown?]} id]
  (let [u (ingest/block-uri realm id)]
    (when (shown? u) (str (slugs (:page (fetch u))) "#" (anchor u (fetch u))))))

;;; Blocks

(def ^:private hover-tags #{"askclaude"})

(defn- sidenote [inner]
  (str "<span class=\"sidenote-container\"><span class=\"superscript\"></span><div class=\"sidenote\">"
       "<span class=\"superscript side\"></span>" inner "</div></span>"))

(defn- hover [tag popup]
  (str "<span class=\"hover-tag-container\"><span class=\"hover-tag-lozenge\" onclick=\"toggleHoverTag(this)\">"
       (str/replace tag #"(?<=[a-z0-9])(?=[A-Z])" " ") "</span><div class=\"hover-tag-popup\">" popup "</div></span>"))

(defn- render-ctx [{:keys [realm fetch shown?] :as ctx} popup]
  {:page       (constantly {:key "" :stub false})
   :page-href  #(page-href ctx %)
   :link-class "pagelink"
   :unlinked   #(str "<span class=\"empty\">" % "</span>")
   :tag-prefix ""
   :tag-html   #(when (hover-tags (str/lower-case %)) (hover % (or popup "")))
   :sidenote   sidenote
   ;; a block ref shows its target only if the target is published
   :block      (fn [id] (let [u (ingest/block-uri realm id)] (when (shown? u) (:content (fetch u)))))
   :block-href #(block-href ctx %)
   ;; a ref to an unpublished block shows nothing, not even its id
   :missing-block (constantly "")})

(def ^:private tweet-link-re
  #"<blockquote class=\"twitter-tweet\"><a href=\"([^\"]+)\">[^<]*</a></blockquote>|<a href=\"(https://(?:twitter|x)\.com/[^\"]+/status/\d+[^\"]*)\">\2</a>")

(defn- embed-tweets
  "Tweets ({{tweet}} or a bare status URL) as their cached oEmbed HTML, where the cache has them."
  [tweets html]
  (str/replace html tweet-link-re
               (fn [[m a b]]
                 (let [u (str/replace (or a b) #"^https://x\.com/" "https://twitter.com/")]
                   (or (tweets u) (tweets (str/replace u #"\?.*$" "")) m)))))

(defn- site-html
  "Renderer output adjusted to goddinpotty's conventions: relative asset URLs, images opening
  full size, external links in a new tab, cached tweets inline, one paragraph as a span."
  [ctx html]
  (let [html (str/trim html)
        html (if-let [[_ inner] (re-matches #"(?s)<p>((?:(?!</?p>).)*)</p>" html)] (str "<span>" inner "</span>") html)]
    (-> (embed-tweets (:tweets ctx {}) html)
        (str/replace #"(src|href)=\"/assets/" "$1=\"assets/")
        (str/replace #"<img src=\"(assets/[^\"]*)\"([^>]*)>" "<a class=\"imga\" href=\"$1\" target=\"_image\"><img src=\"$1\"$2></a>")
        (str/replace #"<a href=\"(https?:[^\"]*)\"" "<a class=\"external\" href=\"$1\" target=\"_blank\""))))

(defn- children-of [{:keys [blocks shown?]} page]
  (let [by-parent (group-by (comp :parent :obj) (filter #(shown? (:uri %)) (blocks page)))]
    (fn [u] (sort-by (comp :order :obj) (by-parent u)))))

(defn- hover-block? [o] (some #(hover-tags (str/lower-case %)) (parse/loose-tags (str (:content o)))))

(declare block-ul)

(def ^:private hidden-props
  #{"title" "original-title" "alias" "id" "created-at" "updated-at" "public" "collapsed" "heading"
    "access-date" "library-catalog"})

(defn- props-html
  "goddinpotty's rendering of a block's visible properties: <li><b>key</b>: value</li> each, by key."
  [ctx rctx obj]
  (apply str (for [[k v] (sort-by (comp str/lower-case name key) obj)
                   :when (and (= "prop" (namespace k)) (not (hidden-props (str/lower-case (name k)))))]
               (str "<li><b>" (r/escape (name k)) "</b>: "
                    (str/replace (site-html ctx (r/block-html rctx (str v))) #"^<span>(.*)</span>$" "$1") "</li>"))))

(defn- block-li [ctx kids {:keys [uri obj]} depth]
  (let [subs  (for [k (kids uri)] (block-ul ctx kids k (inc depth)))
        popup (when (hover-block? obj) (str (h/html (seq subs))))
        rctx  (render-ctx ctx popup)
        html  (str (when-not (str/blank? (:content obj)) (site-html ctx (r/block-html rctx (str (:content obj)))))
                   (props-html ctx rctx obj))
        level (:prop/heading obj)]
    [[:li.block (raw-string (if (integer? level) (format "<h%d>%s</h%d>" level html level) html))]
     (if popup nil subs)]))

(defn- block-ul [ctx kids {:keys [uri obj] :as b} depth]
  (let [[li subs] (block-li ctx kids b depth)]
    [:ul {:id (anchor uri obj) :class (if (< depth 2) "nondent" "")} "\n" li subs]))

(defn outline
  "goddinpotty's outline for page uri: its shown blocks, nested."
  [ctx page]
  (let [kids (children-of ctx page)
        o    ((:fetch ctx) page)]
    ;; the first item holds the page's properties, as goddinpotty shows its pre-block
    [:ul.nondent {:id (str "page-" (c/slug (:name o)))} "\n"
     [:li.block (raw-string (props-html ctx (render-ctx ctx nil) o))]
     (for [b (kids page)] (block-ul ctx kids b 1))]))

;;; Widgets

(defn- card [header & body] [:div.card.my-3 [:h5.card-header header] (into [:div.card-body.gp-card-body] body)])

(defn- page-link [ctx uri & [text]]
  (let [o ((:fetch ctx) uri)]
    (if-let [s ((:slugs ctx) uri)]
      [:a.pagelink {:href s} (raw-string (r/title-html (or text (:name o))))]
      [:span.empty (or text (:name o))])))

(defn- about-widget [ctx]
  (let [p ((:canon ctx) (:about-page ctx "AboutBlock"))]
    (when ((:slugs ctx) p)
      [:div.card.my-3 [:h5.card-header "About"] [:div.card-body.minicard-body (outline ctx p)]])))

(def ^:private search-widget
  [:div.card.my-3
   [:h5.card-header [:span#searchh "Search"] [:input#searcht.form-control {:type "text" :onkeyup "keypress(event)"}]]
   [:div#searchb.card-body [:div#searcho]]])

(defn- headings
  "[[level anchor text] ...] for a page's shown heading blocks, in outline order."
  [ctx page]
  (let [kids (children-of ctx page)]
    (letfn [(walk [u] (mapcat (fn [{bu :uri o :obj}]
                                (let [c (str (:content o))
                                      lvl (or (:prop/heading o) (some-> (re-find #"^(#{1,6})\s" c) second count))]
                                  (cons (when (integer? lvl)
                                          [lvl (anchor bu o) (-> c (str/replace #"^#{1,6}\s+" "") str/split-lines first
                                                                 (str/replace parse/wikilink-re "$1"))])
                                        (walk bu))))
                              (kids u)))]
      (remove nil? (walk page)))))

(defn- contents-widget [ctx page]
  (let [hs (headings ctx page)]
    (when (> (count hs) 2)
      (card "Page Contents"
            [:ul.ulz (for [[lvl a text] hs]
                       [:li [:span {:style (str "width: " (* 15 (dec lvl)) "px;")}] [:a {:href (str "#" a)} text]])]))))

(defn- tree
  "{segment subtree} of the published page names under root (a name with no /)."
  [names root]
  (reduce (fn [m n] (if (str/starts-with? n (str root "/"))
                      (assoc-in m (rest (str/split n #"/")) (get-in m (rest (str/split n #"/")) {}))
                      m))
          {} names))

(defn- tree-hiccup [ctx path subtree]
  (let [u ((:canon ctx) (str/join "/" path))]
    (when ((:slugs ctx) u)
      [:div [:li (page-link ctx u (last path))]
       (when (seq subtree)
         [:ul (for [k (sort-by str/lower-case (keys subtree))] (tree-hiccup ctx (conj path k) (subtree k)))])])))

(defn- page-tree-widget [ctx page]
  (let [name  (:name ((:fetch ctx) page))
        root  (first (str/split name #"/"))
        names (map #(:name ((:fetch ctx) %)) (keys (:slugs ctx)))
        sub   (tree names root)]
    (when (or (str/includes? name "/") (seq sub))
      (when-let [t (tree-hiccup ctx [root] sub)] (card "Page Tree" t)))))

(defn- incoming-widget [{:keys [fetch shown?] :as ctx} page]
  (let [refs (->> (t/referrers page {:attr :refs :type "block"})
                  (map #(select-keys % [:uri :obj])) distinct
                  (filter #(shown? (:uri %)))
                  (sort-by (fn [{o :obj}] [(str/lower-case (str (:name (fetch (:page o))))) (:order o)])))]
    (when (seq refs)
      (card "Incoming links"
            [:div.incoming
             (for [{:keys [uri obj] :as b} refs]
               [:div "from " (page-link ctx (:page obj))
                [:div (block-ul ctx (children-of ctx (:page obj)) b 0)]])]))))

(defn- twin-widget [ctx name]
  (when-let [html (get (:twins ctx) name)]
    (when-not (re-find #"No twinpages|No Space passed" html)
      [:div.card.my-3 [:h5.card-header [:a {:href "http://hyperphor.com/ammdi/Twin-Pages"} "Twin Pages"]]
       [:div.card-body (raw-string html)]])))

;;; Dates: block times (times.clj) over the page's shown blocks

(defn- day [ms]
  (.format (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli ms) (java.time.ZoneId/of "America/Los_Angeles")))
           (java.time.format.DateTimeFormatter/ofPattern "dd MMM yyyy" java.util.Locale/US)))

(defn- date-range [{:keys [blocks shown?]} page]
  (let [os (filter :created (map :obj (filter #(shown? (:uri %)) (blocks page))))]
    (when (seq os)
      [:div.date (day (apply min (map :created os))) " - " (day (apply max (map :updated os)))])))

;;; The page

(defn- first-image [html] (second (re-find #"<img src=\"([^\"]+)\"" html)))

(defn- layout
  "goddinpotty's page frame (templating/page-hiccup) around contents."
  [ctx {:keys [title title-html slug contents widgets head-extra]}]
  (let [body  (str (h/html contents))
        image (first-image body)]
    [:html
     [:head
      (when-let [ga (:google-analytics ctx)]
        (list [:script {:async true :src (str "https://www.googletagmanager.com/gtag/js?id=" ga)}]
              [:script (raw-string (format "\n  window.dataLayer = window.dataLayer || [];\n  function gtag(){dataLayer.push(arguments);}\n  gtag('js', new Date());\n\n  gtag('config', '%s');\n" ga))]))
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:meta {:name "og:type" :content "article"}]
      [:meta {:name "og:url" :content (str (:base-url ctx) slug)}]
      [:meta {:name "og:title" :content title}]
      [:meta {:name "og:description" :content (or (:description ctx) title)}]
      (when image [:meta {:name "og:image" :content image}])
      [:title (str (:short-title ctx) ": " title)]
      [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/bootstrap@5.1.1/dist/css/bootstrap.min.css"
              :integrity "sha384-F3w7mX95PdgyTmZZMECAngseQB83DfGTowi0iMjiWaeVhAn4FJkqJByhZMI3AhiU" :crossorigin "anonymous"}]
      [:link {:rel "stylesheet" :href "assets/default.css"}]
      (for [css (:site-css ctx)] [:link {:rel "stylesheet" :href css}])
      [:link {:rel "preconnect" :href "https://fonts.gstatic.com"}]
      [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=EB+Garamond:wght@400;550&display=swap"}]
      [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Varela+Round:wght@400;600&display=swap"}]
      [:script {:src "https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml-full.js" :type "text/javascript"}]
      [:script {:src "assets/elasticlunr.min.js"}]
      [:script {:src "assets/search.js"}]
      (when (str/includes? body "twitter-tweet") [:script {:async true :src "https://platform.twitter.com/widgets.js"}])
      head-extra]
     [:body
      [:nav.navbar.navbar-expand-lg.navbar-dark.bg-dork.fixed-top
       [:div.container.navbar-x
        [:a.pagelink.navbar-brand {:href (c/slug (:main-page ctx))} (:main-page ctx)]
        [:ul.navbar-nav.ml-auto
         [:li.nav-item [:a.pagelink.nav-link {:href "blyg/"} "Blyg"]]
         (for [[label target] (:navbar ctx)]
           [:li.nav-item [:a.pagelink.nav-link {:href (c/slug target)} label]])]]]
      [:div.container.main
       [:div.row
        (into [:div.col-md-3.sidebar] (remove nil? widgets))
        [:div.col-lg-7 [:div.main [:h1 (raw-string title-html)] (raw-string body)]]]]
      [:footer.py-5.footer [:div.container (when-let [c (:colophon ctx)] (into [:p.m-0.text-center.text-white] c))]]
      [:script {:src "https://cdn.jsdelivr.net/npm/bootstrap@5.1.1/dist/js/bootstrap.bundle.min.js"
                :integrity "sha384-/bQdsTh/da6pkI1MST/rWKFNjaCP5gBSY4sEBT38Q/9RBh9AH40zEOg7Hlq2THRZ" :crossorigin "anonymous"}]]]))

(defn- logseq-url [ctx name]
  (str "logseq://graph/" (:graph-name ctx) "?page=" (java.net.URLEncoder/encode (str name) "UTF-8")))

(defn page-html
  "The HTML file for published page uri."
  [ctx page]
  (let [o    ((:fetch ctx) page)
        name (:name o)
        slug ((:slugs ctx) page)]
    (str (h/html
          (layout ctx {:title      (r/title-text name)
                       :title-html (r/title-html name)
                       :slug       slug
                       :contents   [:div [:div (date-range ctx page)]
                                    [:span.local " " [:a {:href (logseq-url ctx name)} "Open in Logseq"]]
                                    [:hr] (outline ctx page) [:hr]]
                       :widgets    [(about-widget ctx) search-widget (contents-widget ctx page)
                                    (page-tree-widget ctx page) (incoming-widget ctx page) (twin-widget ctx name)]})))))

;;; Site-wide: the search index (assets/index.js, read by search.js and the Index page's grid.js)
;;; and the Index page

(defn- depths
  "{page uri, link steps from an entry page} for published pages, from include's :via."
  [{:keys [via]} pages]
  (let [d (memoize (fn d [u] (if-let [p (via u)] (inc (d p)) 0)))]
    (into {} (for [p pages] [p (d p)]))))

(defn- plain [html]
  (-> html (str/replace #"<[^>]*>" " ")
      (str/replace #"&(?:#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);" #(case % "&amp;" "&" "&lt;" "<" "&gt;" ">" "&quot;" "\"" "&apos;" "'" "&#39;" "'" " "))
      (str/replace #"\s+" " ") str/trim))

(defn- iso-day [ms]
  (str (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli ms) (java.time.ZoneId/of "America/Los_Angeles")))))

(defn search-index
  "assets/index.js: one row per published page, as goddinpotty's search/index wrote (date as yyyy-mm-dd)."
  [{:keys [fetch slugs blocks shown? inc] :as ctx}]
  (let [ds (depths inc (keys slugs))]
    (json/write-str
     (vec (map-indexed
           (fn [i [p slug]]
             (let [o    (fetch p)
                   body (plain (str (h/html (outline ctx p))))
                   ts   (keep (comp :updated :obj) (filter #(shown? (:uri %)) (blocks p)))]
               (cond-> {:id i :url slug :title (r/title-text (:name o)) :body body
                        :depth (ds p) :size (count body)}
                 (seq (:alias o)) (assoc :alias (str/join " " (:alias o)))
                 (seq ts)         (assoc :date (iso-day (apply max ts))))))
           (sort-by val slugs)))
     :escape-slash false)))

(defn- index-page [ctx]
  (str (h/html
        (layout ctx {:title "Index" :title-html "Index" :slug "Index"
                     :contents [:div#myGrid {:style "height:800px;"}]
                     :head-extra (list [:script {:src "https://cdn.jsdelivr.net/npm/ag-grid-community/dist/ag-grid-community.min.js"}]
                                       [:script {:src "assets/grid.js"}])
                     :widgets [(about-widget ctx) search-widget]}))))

;;; Files

(defn- index-html [ctx]
  (str (h/html [:meta {:http-equiv "refresh" :content (str "0; url=" (c/slug (:main-page ctx)))}])))

(def ^:private asset-ref-re #"\]\(\.\./assets/([^)\n]+)\)")

(defn image-files
  "Graph asset paths (under assets/) that published blocks reference."
  [{:keys [inc fetch shown?]}]
  (set (for [u (:included inc) :when (shown? u)
             [_ p] (re-seq asset-ref-re (str (:content (fetch u))))]
         (str/trim p))))

(defn outputs
  "{path html} for the whole site, less copied files."
  [ctx]
  (into {"index.html" (index-html ctx) "assets/index.js" (search-index ctx)}
        (concat (for [[p slug] (:slugs ctx)] [slug (page-html ctx p)])
                ;; a graph page named Index would be overwritten: goddinpotty's generated one wins
                [["Index" (index-page ctx)]])))

(def manifest-name ".thirdmind-site.edn")

(defn- write-if-changed! [^java.io.File f ^String s]
  (when-not (and (.isFile f) (= s (slurp f)))
    (io/make-parents f)
    (spit f s)
    true))

(defn- copy-if-changed! [^java.io.File src ^java.io.File dst]
  (when-not (and (.isFile dst) (= (.length src) (.length dst)) (= (.lastModified src) (.lastModified dst)))
    (io/make-parents dst)
    (io/copy src dst)
    (.setLastModified dst (.lastModified src))
    true))

(defn build!
  "Write the site into out: pages and index.html if changed, statics from :static-dir and referenced
  graph images into assets/; delete what the previous build (out's manifest) wrote that's gone.
  Returns counts."
  [ctx out]
  (let [outd    (io/file out)
        files   (outputs ctx)
        statics (for [^java.io.File f (some-> (:static-dir ctx) io/file .listFiles)
                      :when (and (.isFile f) (not (re-find #"^[#.]|^README" (.getName f))))]
                  [(str "assets/" (.getName f)) f])
        images  (for [p (image-files ctx)
                      :let [f (io/file (:graph-dir ctx) "assets" p)] :when (.isFile f)]
                  [(str "assets/" p) f])
        mf      (io/file outd manifest-name)
        before  (if (.isFile mf) (set (edn/read-string (slurp mf))) #{})
        now     (set (concat (keys files) (map first statics) (map first images)))
        written (count (filter identity (for [[p s] files] (write-if-changed! (io/file outd p) s))))
        copied  (count (filter identity (for [[p f] (concat statics images)] (copy-if-changed! f (io/file outd p)))))
        gone    (remove now before)]
    (doseq [p gone] (io/delete-file (io/file outd p) true))
    (spit mf (pr-str (vec (sort now))))
    {:pages (count (:slugs ctx)) :written written :copied copied :deleted (count gone)}))
