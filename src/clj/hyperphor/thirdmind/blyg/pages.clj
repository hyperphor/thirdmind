(ns hyperphor.thirdmind.blyg.pages
  "The blyg's HTML pages: archive (index.html) and a permalink page per item (f/{id}/index.html).
  The site chrome comes from template pages (the live ones), with title and main content replaced,
  so the blyg keeps the site's look without ThirdMind knowing its nav, css or analytics."
  (:require [clojure.string :as str]
            [hiccup.util :refer [raw-string]]
            [hiccup2.core :as h]
            [hyperphor.thirdmind.blyg.wire :as w]))

(defn- human-date [iso]
  (.format (java.time.format.DateTimeFormatter/ofPattern "MMM d, yyyy" java.util.Locale/US)
           (.atZone (java.time.Instant/parse iso) java.time.ZoneOffset/UTC)))

(defn- withdrawn? [e] (= "withdrawn" (name (:kind e))))

(defn card
  "An item as a card (goddinpotty's fragment-card-hiccup)."
  [origin entry & {:keys [permalink?]}]
  (let [titled? (and (not (withdrawn? entry)) (= "property" (some-> (:title-source entry) name)))
        id      (:blyg-id entry)]
    [:article.card.my-3.fragment {:class (cond (withdrawn? entry) "withdrawn" titled? "titled")}
     [:div.card-body.py-2
      (when (seq (:pins entry))
        [:span.pin-badge.float-end.text-muted.small
         {:title "Pinned -- a version of this item is permanently citable/forkable (§8)"}
         [:i.bi-pin-angle-fill] " Pinned"])
      (when-let [cited (and (not (withdrawn? entry)) (get-in entry [:stub-of :cited]))]
        [:p.stub-of.text-muted.small.mb-1 "In reply to "
         [:a {:href (or (:url cited) (get-in entry [:stub-of :origin]))} (or (:source cited) (get-in entry [:stub-of :origin]))]
         (when (:author cited) (str " — " (:author cited)))])
      (when titled? [:div.blyg-title (:title entry)])
      [:div.item-content (if (withdrawn? entry) [:p.text-muted "[withdrawn]"] (raw-string (:content-html entry)))]
      [:p.meta-line.text-muted.small.mb-0
       "Created " (human-date (:created entry))
       (when (> (:version entry) 1) (list " · updated " (human-date (:updated entry)) " · v" (:version entry)))
       (when-not permalink? (list " · " [:a.permalink {:href (str origin "f/" id "/")} "Permalink"]))]]]))

;;; Templates

(def ^:private main-open "<div class=\"col-lg-7\"><div class=\"main\">")
(def ^:private main-close "</div></div></div></div><footer")

(defn fill
  "template (a live blyg page) with its title, h1 and main content replaced; url, if given, becomes
  og:url, and item-id the item in its <link rel=alternate>."
  [template {:keys [title h1 body item-id url]}]
  (let [i (str/index-of template main-open)
        j (str/index-of template main-close)]
    (when-not (and i j (< i j)) (throw (ex-info "template has no recognizable main region" {})))
    (-> (str (subs template 0 (+ i (count main-open))) (h/html [:h1 h1] body) (subs template j))
        (str/replace-first #"<title>([^<:]*: )?[^<]*</title>" (fn [[_ prefix]] (str "<title>" prefix (h/html title) "</title>")))
        (str/replace-first #"(<meta content=\")[^\"]*(\" name=\"og:title\")" (fn [[_ a b]] (str a (h/html title) b)))
        (cond-> url (str/replace-first #"(<meta content=\")[^\"]*(\" name=\"og:url\")" (fn [[_ a b]] (str a (h/html url) b))))
        (cond-> item-id (str/replace-first #"items/[0-9a-z]{26}\.json(\" rel=\"alternate\")" (str "items/" item-id ".json$1"))))))

(defn archive-html [template origin {:keys [title]} items]
  (let [entries (->> (vals items) (remove #(or (withdrawn? %) (:thread-child? %))) (sort-by :created) reverse)]
    (fill template
          {:title title :h1 title :url origin
           :body [:div.blyg
                  [:p.blyg-tagline [:a {:href "https://blygger.org/"} "Blygger"]
                   " is a new medium for public writing. This blyg is my personal feed. Links to existing AMMDI functionality are exploratory."]
                  [:p.blyg-links [:a {:href (str origin "feed.xml")} "RSS"] " · " [:a {:href (str origin "blyg.json")} "JSON manifest"]]
                  (if (seq entries) (map #(card origin %) entries) [:p "Nothing published yet."])]})))

(defn permalink-html [template origin {blyg-title :title} items entry]
  (let [title (if (withdrawn? entry) "withdrawn" (w/entry-title items entry))
        json  (str origin "items/" (:blyg-id entry) ".json")]
    (fill template
          {:title title
           :h1 (if (and (:title entry) (not (withdrawn? entry))) [:a {:href origin} blyg-title] title)
           :item-id (:blyg-id entry)
           :url (str origin "f/" (:blyg-id entry) "/")
           :body [:div.blyg (card origin entry :permalink? true)
                  [:p [:a {:href json} "JSON"] " · " [:a {:href origin} "Blyg"]]]})))

(defn site-pages
  "{path html}: index.html and f/{id}/index.html for every item (withdrawn ones too: 200 forever).
  templates {:archive html :permalink html}."
  [{:keys [archive permalink]} origin opts items]
  (into {"index.html" (archive-html archive origin opts items)}
        (for [e (vals items)] [(str "f/" (:blyg-id e) "/index.html") (permalink-html permalink origin opts items e)])))
