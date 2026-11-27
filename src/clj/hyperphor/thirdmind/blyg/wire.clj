(ns hyperphor.thirdmind.blyg.wire
  "Blygger protocol 0.3 wire pieces, ported from goddinpotty.export.blygger: ids, content hash,
  version bumps, tombstones, pins, item/pin/manifest/index JSON and feed.xml. Pure: `surface`
  returns {path text} and writes nothing.
  Entries keep goddinpotty's state shape ({:blyg-id :kind :created :updated :version :content-md
  :content-html :content-hash :changelog ...}), so imported state passes through unchanged."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

;;; Ids, hashing, timestamps

;; Crockford base32, lowercase, no i/l/o/u (blygger-spec worker/src/util.ts)
(def ^String id-alphabet "0123456789abcdefghjkmnpqrstvwxyz")

(defn- encode-base32 [^bytes bs n-chars]
  (loop [n (java.math.BigInteger. 1 bs), i 0, acc ()]
    (if (= i n-chars)
      (apply str acc)
      (recur (.shiftRight n 5) (inc i)
             (cons (.charAt id-alphabet (.intValue (.and n (java.math.BigInteger/valueOf 31)))) acc)))))

(defn new-id
  "128 random bits as 26 chars of lowercase Crockford base32: an item's permanent identity."
  []
  (let [bs (byte-array 16)]
    (.nextBytes (java.security.SecureRandom.) bs)
    (encode-base32 bs 26)))

(defn content-hash
  "\"sha256:\" + hex SHA-256 of content-md (UTF-8). Covers content_md only."
  [content-md]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str content-md) "UTF-8"))]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and (int %) 0xff)) d)))))

(defn now-iso
  "Now as ISO 8601 UTC, second precision."
  []
  (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- rfc822 [iso]
  (.format (java.time.format.DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss 'GMT'" java.util.Locale/US)
           (.atZone (java.time.Instant/parse iso) java.time.ZoneOffset/UTC)))

;;; Versions. A version bumps when what readers see changes: content_md (the hash), the resolved
;;; transclusions (a thread re-baking an edited child), or the visible HTML (text and link
;;; targets, eg a page published since). Presentation-only markup doesn't count.

(def ^:private structural-tags #{"ul" "ol" "li" "blockquote" "table" "tr" "td" "th" "pre" "code"})

(defn html-signature
  "What a reader sees of html: text, link/image targets, structural tags."
  [html]
  (-> (str html)
      (str/replace #"<(/?)([a-zA-Z0-9]+)[^>]*>"
                   (fn [[tag close name]]
                     (let [name (str/lower-case name)]
                       (if (structural-tags name)
                         (str "<" close name ">")
                         (if-let [[_ u] (re-find #"(?:href|src)=\"([^\"]*)\"" tag)] (str " " u " ") " ")))))
      (str/replace #"\s+" " ")
      str/trim))

(defn next-entry
  "existing (or nil) moved to the current content: unchanged if nothing a reader sees changed,
  else a new version. stub-of is frozen once set (§5.9); transclusions is nil for fragments."
  [existing kind content-md content-html transclusions stub-of now]
  (let [hash (content-hash content-md)]
    (cond
      (nil? existing)
      {:blyg-id (new-id) :kind kind :created now :updated now :version 1
       :content-md content-md :content-html content-html :content-hash hash
       :transclusions transclusions :stub-of stub-of
       :changelog [{:version 1 :at now :note nil}]}

      (and (= kind (:kind existing))
           (= hash (:content-hash existing))
           (= transclusions (:transclusions existing))
           (= (html-signature content-html) (html-signature (:content-html existing))))
      existing

      :else
      (let [v (inc (:version existing))]
        (assoc existing
               :kind kind :updated now :version v
               :content-md content-md :content-html content-html :content-hash hash
               :transclusions transclusions
               :stub-of (or stub-of (:stub-of existing))
               :changelog (conj (:changelog existing) {:version v :at now :note nil}))))))

(defn withdraw-entry
  "The tombstone version: empty content, [] transclusions for a thread, no stub_of (§9, §10.6)."
  [existing now]
  (let [v (inc (:version existing))]
    (assoc existing
           :kind :withdrawn :updated now :version v
           :content-md "" :content-html "" :content-hash (content-hash "")
           :transclusions (when (= :thread (:kind existing)) [])
           :stub-of nil
           :changelog (conj (:changelog existing) {:version v :at now :note nil}))))

(defn pin-entry
  "entry with its current version pinned (idempotent; never un-pins: a pin is 200 forever, §8).
  A withdrawn entry has nothing to pin and is returned as is."
  [entry]
  (if (or (= :withdrawn (:kind entry)) (contains? (:pins entry) (:version entry)))
    entry
    (-> entry
        (assoc-in [:pins (:version entry)]
                  (cond-> {:content-md (:content-md entry) :content-html (:content-html entry)
                           :content-hash (:content-hash entry) :at (:updated entry)}
                    (:transclusions entry) (assoc :transclusions (:transclusions entry))
                    (:stub-of entry)       (assoc :stub-of (:stub-of entry))))
        (update :changelog (fn [cl] (mapv #(cond-> % (= (:version %) (:version entry)) (assoc :pinned true)) cl))))))

;;; JSON documents. opts: {:title :author {:name :url} :generator :generator-url :feed-window}

(def ^:private protocol "0.3")

(defn overall-updated [items] (or (last (sort (map :updated (vals items)))) (now-iso)))

(defn claimed-level
  "2 once the surface holds a stub or a cross-origin transclusion, else 1 (§3)."
  [items]
  (if (some (fn [e] (or (:stub-of e) (some :origin (:transclusions e)))) (vals items)) 2 1))

(defn- unescape-attr [s]
  (-> s (str/replace "&quot;" "\"") (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&amp;" "&")))

(def ^:private mime-types
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif" "webp" "image/webp"
   "svg" "image/svg+xml" "pdf" "application/pdf" "mp4" "video/mp4" "mp3" "audio/mpeg"})

(defn media-refs
  "media[] for html (§5.4): each distinct img of this origin's media/, {:url :mime :alt}."
  [origin html]
  (let [pre (str origin "media/")]
    (->> (re-seq #"<img\b[^>]*>" (str html))
         (keep (fn [tag]
                 (let [src (second (re-find #"src=\"([^\"]*)\"" tag))]
                   (when (and src (str/starts-with? src pre))
                     (let [n (subs src (count origin))]
                       {:url n
                        :mime (get mime-types (str/lower-case (or (second (re-find #"\.(\w+)$" n)) "")) "application/octet-stream")
                        :alt (unescape-attr (or (second (re-find #"alt=\"([^\"]*)\"" tag)) ""))})))))
         (reduce (fn [acc m] (if (some #(= (:url %) (:url m)) acc) acc (conj acc m))) []))))

(defn item-json [origin entry]
  (let [withdrawn? (= :withdrawn (keyword (:kind entry)))]
    (cond-> {:blyg protocol
             :id (:blyg-id entry)
             :kind (name (:kind entry))
             :origin origin
             :page (str "f/" (:blyg-id entry) "/")
             :created (:created entry)
             :updated (:updated entry)
             :version (:version entry)
             :content_md (if withdrawn? "" (:content-md entry))
             :content_html (if withdrawn? "" (:content-html entry))
             :content_hash (:content-hash entry)
             :media (if withdrawn? [] (media-refs origin (:content-html entry)))
             :changelog (mapv (fn [c] (cond-> {:version (:version c) :at (:at c) :note (:note c)}
                                        (:generated c) (assoc :generated true)
                                        (:pinned c) (assoc :pinned true)))
                              (:changelog entry))}
      (:transclusions entry)                  (assoc :transclusions (:transclusions entry))
      (and (not withdrawn?) (:stub-of entry)) (assoc :stub_of (:stub-of entry)))))

(defn pin-json [origin {:keys [author]} entry version]
  (let [pin (get-in entry [:pins version])]
    (cond-> {:blyg protocol
             :id (:blyg-id entry)
             :kind (name (:kind entry))
             :version version
             :at (:at pin)
             :note (:note (first (filter #(= version (:version %)) (:changelog entry))))
             :pinned true
             :origin origin
             :author author
             :content_md (:content-md pin)
             :content_html (:content-html pin)
             :content_hash (:content-hash pin)}
      (:transclusions pin) (assoc :transclusions (:transclusions pin))
      (:stub-of pin)       (assoc :stub_of (:stub-of pin)))))

(defn manifest [origin {:keys [title author generator generator-url]} items]
  {:blyg protocol
   :level (claimed-level items)
   :generator generator
   :generator_url generator-url
   :site origin
   :title title
   :author author
   :feed "feed.xml"
   :items "items/index.json"
   :updated (overall-updated items)})

(defn archive-index [items]
  {:updated (overall-updated items)
   :items (->> (vals items)
               (map (fn [e] {:id (:blyg-id e) :kind (name (:kind e))
                             :created (:created e) :updated (:updated e) :version (:version e)}))
               (sort-by :updated) reverse vec)})

;;; Titles (feed <title>): a :title, else an excerpt of the item's own text.

(defn- md->plain [md]
  (-> (str md) (str/replace #"\[([^\]]*)\]\([^\)]*\)" "$1") (str/replace #"\s+" " ") str/trim))

(defn- safe-subs
  "(subs s 0 n), pulled back by one if it would split a surrogate pair."
  [^String s n]
  (let [t (subs s 0 n)]
    (if (and (pos? (count t)) (Character/isHighSurrogate (.charAt t (dec (count t))))) (subs t 0 (dec (count t))) t)))

(defn- excerpt [md n]
  (let [p (md->plain md)] (if (<= (count p) n) p (str (safe-subs p n) "…"))))

(defn title-excerpt
  "Excerpt of content-md without its attribution line and ![[id]] directives."
  [content-md]
  (excerpt (-> (str content-md)
               (str/replace #"(?s)^From \[[^\]]*\]\([^)]*\)\n\n" "")
               (str/replace #"(?m)^!\[\[[0-9a-z]{26}\]\]\s*$" ""))
           60))

(defn entry-title
  "Title for entry: its own, else its first own transcluded point's, else \"Re: <cited source>\"."
  [items entry]
  (let [own (or (:title entry) (title-excerpt (:content-md entry)))]
    (if-not (str/blank? own)
      own
      (let [by-id (into {} (map (juxt :blyg-id identity) (vals items)))]
        (or (->> (:transclusions entry) (remove :origin) (keep (comp by-id :id))
                 (map (comp title-excerpt :content-md)) (remove str/blank?) first)
            (some->> (get-in entry [:stub-of :cited :source]) (str "Re: "))
            "")))))

;;; feed.xml: one <item> per publish event, newest first; a withdrawn item only its withdrawal;
;;; thread children left out (their thread carries them).

(defn feed-events [items feed-window]
  (->> (vals items)
       (remove :thread-child?)
       (mapcat (fn [e] (if (= :withdrawn (keyword (:kind e)))
                         [{:entry e :event (last (:changelog e))}]
                         (map (fn [c] {:entry e :event c}) (:changelog e)))))
       (sort-by (comp :at :event)) reverse
       (take feed-window)))

(defn- escape-xml [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- cdata [s] (str "<![CDATA[" (str/replace s "]]>" "]]]]><![CDATA[>") "]]>"))

(defn- stub-citation-html [entry]
  (when-let [cited (get-in entry [:stub-of :cited])]
    (let [url (or (:url cited) (get-in entry [:stub-of :origin]))]
      (str "<p>In reply to <a href=\"" (escape-xml url) "\">" (escape-xml (or (:source cited) url)) "</a>"
           (when (:author cited) (str " — " (escape-xml (:author cited)))) "</p>"))))

(defn- feed-item-xml [origin items {:keys [entry event]}]
  (let [withdrawn? (= :withdrawn (keyword (:kind entry)))
        id (:blyg-id entry)]
    (str "    <item>\n"
         "      <guid isPermaLink=\"false\">blyg:" id ":v" (:version event) "</guid>\n"
         "      <link>" origin "f/" id "/</link>\n"
         "      <title>" (escape-xml (cond withdrawn? "withdrawn"
                                        (:note event) (str (:note event) " — " (entry-title items entry))
                                        :else (entry-title items entry))) "</title>\n"
         "      <description>" (if withdrawn? "" (cdata (str (stub-citation-html entry) (:content-html entry)))) "</description>\n"
         "      <pubDate>" (rfc822 (:at event)) "</pubDate>\n"
         "      <blyg:id>" id "</blyg:id>\n"
         "      <blyg:kind>" (name (:kind entry)) "</blyg:kind>\n"
         "      <blyg:version>" (:version event) "</blyg:version>\n"
         "      <blyg:created>" (:created entry) "</blyg:created>\n"
         "      <blyg:item>" origin "items/" id ".json</blyg:item>\n"
         "    </item>")))

(defn feed-xml [origin {:keys [title feed-window] :or {feed-window 50}} items]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<rss version=\"2.0\" xmlns:blyg=\"https://blygger.org/ns/0.1\">\n"
       "  <channel>\n"
       "    <title>" (escape-xml title) "</title>\n"
       "    <link>" origin "</link>\n"
       "    <description>" (escape-xml title) "</description>\n"
       "    <lastBuildDate>" (rfc822 (overall-updated items)) "</lastBuildDate>\n"
       "    <blyg:level>" (claimed-level items) "</blyg:level>\n"
       "    <blyg:manifest>" origin "blyg.json</blyg:manifest>\n"
       (str/join "\n" (map (partial feed-item-xml origin items) (feed-events items feed-window)))
       "\n  </channel>\n</rss>\n"))

;;; The whole protocol surface (HTML archive and permalink pages aside)

(defn surface
  "{path text} of the protocol surface under origin for items ({key entry}), paths relative to
  the mount: blyg.json, items/index.json, items/{id}.json, items/{id}/v{n}.json, feed.xml."
  [origin opts items]
  (into {"blyg.json"        (json/write-str (manifest origin opts items))
         "items/index.json" (json/write-str (archive-index items))
         "feed.xml"         (feed-xml origin opts items)}
        (mapcat (fn [e]
                  (cons [(str "items/" (:blyg-id e) ".json") (json/write-str (item-json origin e))]
                        (for [v (keys (:pins e))]
                          [(str "items/" (:blyg-id e) "/v" v ".json") (json/write-str (pin-json origin opts e v))])))
                (vals items))))
