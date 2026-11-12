(ns hyperphor.thirdmind.ui.pages
  "Views of an ingested graph as Hiccup: page, block, index, search. Read-only on the db."
  (:require [clojure.string :as str]
            [hiccup.util :refer [raw-string]]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.search :as search]
            [hyperphor.thirdmind.ui.render :as r]
            [hyperphor.tunamelt.core :as t]))

(defn- uri-key [uri] (subs uri (inc (str/last-index-of uri "/"))))

(defn ctx
  "Render context for realm (see render/block-html), plus :realm, :aliases and :fetch (memoized t/get).
  Build one per request."
  [realm]
  (let [aliases (ingest/db-aliases realm)
        fetch   (memoize t/get)
        page    (memoize (fn [name]
                           (let [k (ingest/page-key name), k (get aliases k k)]
                             {:key k :stub (let [p (fetch (ingest/page-uri realm k))] (or (nil? p) (:stub p)))})))]
    {:realm   realm
     :aliases aliases
     :fetch   fetch
     :page    page
     :block   (fn [id] (:content (fetch (ingest/block-uri realm id))))}))

;;; Pieces

(defn- page-link [ctx uri]
  (let [p ((:fetch ctx) uri)]
    [:a {:href (r/page-href (uri-key uri)) :class (when (or (nil? p) (:stub p)) "stub")}
     (or (:name p) (uri-key uri))]))

;;; Logseq hides these too
(def ^:private hidden-props #{"collapsed" "created-at" "updated-at"})

(defn- props
  "Hiccup for an object's prop/* attributes, or nil."
  [obj]
  (when-let [ps (seq (for [[k v] (sort-by key obj)
                           :when (and (= "prop" (namespace k)) (not (hidden-props (name k))))]
                       [k v]))]
    [:dl.props (for [[k v] ps] (list [:dt (name k)] [:dd (str v)]))]))

(defn- block-body [ctx {:keys [obj]}]
  (let [[marker content] (r/split-marker (:marker obj) (str (:content obj)))]
    [:div.block
     (when marker [:span {:class (str "marker " (str/lower-case marker))} marker])
     (raw-string (r/block-html ctx content))
     (props obj)]))

(defn- outline
  "Nested list of blocks under parent-uri, given {parent-uri [block-entry]}."
  [ctx children parent-uri]
  (when-let [bs (seq (sort-by (comp :order :obj) (children parent-uri)))]
    [:ul.outline
     (for [{:keys [uri obj] :as b} bs
           :let [sub (outline ctx children uri)]]
       [:li {:id (uri-key uri)}
        (if (and sub (:prop/collapsed obj))
          [:details [:summary (block-body ctx b)] sub]
          (list (block-body ctx b) sub))])]))

(defn- page-blocks [uri] (t/find "block" {:attrs {:page uri}}))

(defn- ancestors-of
  "Block entries {:uri :obj} from the page's top-level block down to (excluding) the block at uri."
  [{:keys [fetch]} uri]
  (loop [u (:parent (fetch uri)), acc ()]
    (if-let [o (and u (str/includes? u "/block/") (fetch u))]
      (recur (:parent o) (cons {:uri u :obj o} acc))
      acc)))

(defn- breadcrumb [ctx uri]
  (let [{:keys [page]} ((:fetch ctx) uri)]
    [:div.breadcrumb
     (interpose " › "
                (cons (page-link ctx page)
                      (for [{u :uri o :obj} (ancestors-of ctx uri)]
                        [:a {:href (r/block-href (java.net.URLDecoder/decode ^String (uri-key u) "UTF-8"))}
                         (first (str/split-lines (str (:content o))))])))]))

(defn- backlinks [ctx uri]
  (let [refs (->> (t/referrers uri {:attr :refs :type "block"})
                  (remove #(= uri (:page (:obj %))))
                  (group-by (comp :page :obj)))]
    (when (seq refs)
      [:section.backlinks
       [:h2 (count (apply concat (vals refs))) " linked references"]
       (for [[puri bs] (sort-by #(str/lower-case (str (:name ((:fetch ctx) (key %))))) refs)]
         [:div.backlink-page
          [:h3 (page-link ctx puri)]
          (for [b bs]
            [:div.backlink
             (when (seq (ancestors-of ctx (:uri b))) (breadcrumb ctx (:uri b)))
             (block-body ctx b)])])])))

;;; Views: each returns {:title :body} or nil if not found

(defn page-view [{:keys [realm] :as ctx} key]
  (let [uri (ingest/page-uri realm key)]
    (when-let [p (t/get uri)]
      {:title (:name p)
       :body  (list
               [:h1 (:name p) (when (:stub p) [:span.stub-note " (no page)"])]
               (when (seq (:alias p)) [:p.aliases "Also: " (str/join ", " (:alias p))])
               (when (seq (:tags p)) [:p.tags (interpose " " (map #(page-link ctx %) (:tags p)))])
               (props p)
               (outline ctx (group-by (comp :parent :obj) (page-blocks uri)) uri)
               (backlinks ctx uri))})))

(defn block-view [{:keys [realm] :as ctx} id]
  (let [uri (ingest/block-uri realm id)]
    (when-let [b (t/get-entry uri)]
      (let [children (group-by (comp :parent :obj) (page-blocks (:page (:obj b))))]
        {:title (first (str/split-lines (str (:content (:obj b)))))
         :body  (list (breadcrumb ctx uri)
                      [:ul.outline [:li (block-body ctx b) (outline ctx children uri)]]
                      (backlinks ctx uri))}))))

(defn index-view [ctx]
  (let [pages    (map :obj (t/find "page" {:realm (:realm ctx)}))
        {journals true pages false} (group-by (comp boolean :journal) (remove :stub pages))
        link     (fn [p] [:a {:href (r/page-href (ingest/page-key (:name p)))} (:name p)])]
    {:title "Index"
     :body  (list [:h1 "Index"]
                  [:div.index
                   [:section [:h2 (count pages) " pages"]
                    [:ul (for [p (sort-by (comp str/lower-case :name) pages)] [:li (link p)])]]
                   [:section [:h2 (count journals) " journals"]
                    [:ul (for [p (sort-by :journal-day > journals)] [:li (link p)])]]])}))

(defn- snippet [s]
  (raw-string (-> (r/escape s) (str/replace "«" "<mark>") (str/replace "»" "</mark>"))))

(defn search-view [ctx q]
  (let [results (when-not (str/blank? q)
                  (try (search/search q {:realm (:realm ctx) :limit 500})
                       (catch Exception e (ex-message e))))]
    {:title (str "Search: " q)
     :body  (list [:h1 "Search"]
                  (cond (string? results) [:p.error results]
                        (empty? results)  (when q [:p "No matches."])
                        :else
                        (for [{:keys [uri page-match? hits]} results]
                          [:div.result
                           [:h3 (page-link ctx uri) (when page-match? [:span.page-match " (page)"])]
                           (for [h (take 5 hits)]
                             [:div.snippet
                              [:a {:href (r/block-href (java.net.URLDecoder/decode ^String (uri-key (:uri h)) "UTF-8"))}
                               (snippet (:snippet h))]])])))}))
