(ns hyperphor.thirdmind.ingest
  "Turn a parsed Logseq graph into Tunamelt page and block objects."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hyperphor.thirdmind.parse :as parse]
            [hyperphor.tunamelt.core :as t])
  (:import [java.net URLEncoder]))

;;; Ids. Every page URI goes through page-key, so refs and objects always agree.

(defn- encode [s]
  (str/replace (URLEncoder/encode (str s) "UTF-8") "+" "%20"))

(defn page-key
  "Canonical id for a page name (Logseq page names are case-insensitive)."
  [name]
  (encode (str/lower-case (str/trim name))))

(defn page-uri [realm key] (str "hyperphor://" realm "/page/" key))
(defn block-uri [realm id] (str "hyperphor://" realm "/block/" (encode id)))
(defn file-uri [realm rel] (str "hyperphor://" realm "/file/" (encode rel)))

(defn- uri-key [uri] (subs uri (inc (str/last-index-of uri "/"))))

(defn- coerce-prop [v]
  (cond (re-matches #"-?\d{1,18}" v) (parse-long v)
        (= v "true")  true
        (= v "false") false
        :else v))

(defn- prop-attrs [props]
  (into {} (map (fn [[k v]] [(keyword "prop" k) (coerce-prop v)])) (dissoc props "id" "title")))

(defn- split-list
  "Values of a comma-separated property like alias:: or tags::, [[ ]] stripped."
  [v]
  (->> (str/split (or v "") #",")
       (map #(-> % str/trim (str/replace #"^#?\[\[|\]\]$" "") (str/replace #"^#" "")))
       (remove str/blank?)))

;;; Graph

(defn read-graph
  "Parse all pages and journals of a graph directory."
  [dir]
  (mapv (fn [[f journal? rel]] (parse/parse-file f journal? rel)) (parse/graph-files dir)))

(defn graph->objects
  "Resolve refs across parsed pages; returns {:objects [[uri obj] ...] :report {...}}.
  ctx supplies what lies outside pages: :aliases {alias-key page-key}, :known-id? (block ids)."
  ([realm pages] (graph->objects realm pages {}))
  ([realm pages ctx]
  (let [by-key     (group-by (comp page-key :name) pages)
        collisions (into {} (filter #(> (count (val %)) 1)) by-key)
        aliases    (into (or (:aliases ctx) {})
                         (for [p pages, a (split-list (get-in p [:props "alias"]))]
                           [(page-key a) (page-key (:name p))]))
        resolve-pg (fn [name] (let [k (page-key name)] (get aliases k k)))
        ;; block ids: explicit id:: or page-key~outline-path
        blocks     (for [p pages, b (:blocks p)]
                     (assoc b
                            :page-key (page-key (:name p))
                            :id (or (get-in b [:props "id"])
                                    (str (page-key (:name p)) "~" (str/join "." (:path b))))))
        id-counts  (frequencies (keep #(get-in % [:props "id"]) blocks))
        local-ids  (set (keys id-counts))
        known-ids  (fn [id] (or (local-ids id) (when-let [known? (:known-id? ctx)] (known? id))))
        by-path    (into {} (map (juxt (juxt :page-key :path) :id)) blocks)
        page-refs  (atom #{})
        unresolved (atom [])
        block-objs
        (for [{:keys [page-key path id content props marker] :as b} blocks]
          (let [{:keys [pages tags block-refs]} (parse/extract-refs
                                                 (str content " " (str/join " " (vals props))))
                ref-keys (mapv resolve-pg pages)
                tag-keys (mapv resolve-pg tags)
                parent   (when (> (count path) 1) (by-path [page-key (pop path)]))]
            (swap! page-refs into ref-keys)
            (doseq [r block-refs :when (not (known-ids r))] (swap! unresolved conj [id r]))
            [(block-uri realm id)
             (cond-> (merge (prop-attrs props)
                            {:content content
                             :page    (page-uri realm page-key)
                             :parent  (if parent (block-uri realm parent) (page-uri realm page-key))
                             :order   (peek path)
                             :depth   (count path)})
               (seq ref-keys)   (assoc :refs (mapv #(page-uri realm %) ref-keys))
               (seq tag-keys)   (assoc :tags (mapv #(page-uri realm %) tag-keys))
               (seq block-refs) (assoc :block-refs (mapv #(block-uri realm %) (filter known-ids block-refs)))
               marker           (assoc :marker marker))]))
        block-objs (doall block-objs)
        page-objs  (for [{:keys [name file journal? journal-day props blocks]} pages
                         :let [tags (map resolve-pg (split-list (get props "tags")))]]
                     [(page-uri realm (page-key name))
                      (cond-> (merge (prop-attrs props)
                                     {:name name :file file :journal journal? :blocks (count blocks)})
                        journal-day         (assoc :journal-day journal-day)
                        (seq tags)          (assoc :tags (mapv #(page-uri realm %) tags))
                        (get props "alias") (assoc :alias (vec (split-list (get props "alias")))))])
        file-keys  (set (keys by-key))
        page-tags  (mapcat #(map resolve-pg (split-list (get-in % [:props "tags"]))) pages)
        stub-keys  (sort (remove file-keys (into @page-refs page-tags)))
        stubs      (for [k stub-keys]
                     [(page-uri realm k) {:name (java.net.URLDecoder/decode ^String k "UTF-8") :stub true}])]
    {:objects (concat page-objs stubs block-objs)
     :report  {:files            (count pages)
               :file-pages       (count by-key)
               :name-collisions  (into {} (map (fn [[k ps]] [k (mapv :file ps)])) collisions)
               :stub-pages       (count stub-keys)
               :blocks           (count block-objs)
               :duplicate-ids    (into {} (filter #(> (val %) 1)) id-counts)
               :unresolved-block-refs @unresolved}})))

(defn- file-record
  "[uri obj] recording a graph file's stamp and the page it produced."
  [dir realm rel page-name]
  (let [f (io/file dir rel)]
    [(file-uri realm rel)
     {:path rel :mtime (.lastModified f) :size (.length f) :page (page-uri realm (page-key page-name))}]))

(defn ingest!
  "Read the graph at dir and store it in the configured Tunamelt store under realm. Returns the report."
  [dir realm]
  (let [pages (read-graph dir)
        {:keys [objects report]} (graph->objects realm pages)]
    (t/put-many (concat objects (map #(file-record dir realm (:file %) (:name %)) pages)))
    report))

;;; Incremental: one file at a time, with aliases and block ids resolved against the db.

(defn- db-aliases [realm]
  (into {} (for [{:keys [uri obj]} (t/find "page" {:realm realm})
                 a (:alias obj)]
             [(page-key a) (uri-key uri)])))

(defn- page-blocks [page-uri]
  (mapv #(select-keys % [:uri :obj]) (t/find "block" {:attrs {:page page-uri}})))

(defn update-file!
  "Re-ingest graph file rel (path under dir), or remove its page if the file is gone.
  Returns {:page uri :old [block entries before] :new [block entries after]}."
  [dir realm rel]
  (let [furi     (file-uri realm rel)
        old-puri (:page (t/get furi))
        old-page (some-> old-puri t/get)
        old      (if old-puri (page-blocks old-puri) [])
        f        (io/file dir rel)
        parsed   (when (.isFile f) (parse/parse-file f (str/starts-with? rel "journals/") rel))
        new-puri (some->> parsed :name page-key (page-uri realm))
        objects  (when parsed
                   (->> (:objects (graph->objects realm [parsed]
                                                  {:aliases   (db-aliases realm)
                                                   :known-id? #(some? (t/get (block-uri realm %)))}))
                        ;; stubs only for pages not already in the db
                        (remove (fn [[u o]] (and (:stub o) (t/get u))))
                        vec))]
    (t/delete furi)
    ;; blocks re-put under the same uri are overwritten; only the vanished ones need deleting
    (let [kept (set (map first objects))]
      (doseq [b old :when (not (kept (:uri b)))] (t/delete (:uri b))))
    (when (and old-puri (not= old-puri new-puri))
      (t/delete old-puri)
      ;; a page still referenced elsewhere reverts to a stub
      (when (seq (t/referrers old-puri))
        (t/put old-puri {:name (:name old-page) :stub true})))
    (when parsed
      (t/put-many (conj objects (file-record dir realm rel (:name parsed)))))
    {:page (or new-puri old-puri)
     :old  old
     :new  (vec (for [[u o] objects :when (str/includes? u "/block/")] {:uri u :obj o}))}))

(defn new-tags
  "[tag-uri block-entry] for each tag on a :new block that no :old block with the same uri or
  content carried (so renumbered or moved blocks don't count as new)."
  [{:keys [old new]}]
  (let [had (into #{} (for [{:keys [uri obj]} old, t (:tags obj), k [uri (:content obj)]] [t k]))]
    (for [{:keys [uri obj] :as b} new
          t (:tags obj)
          :when (not (or (had [t uri]) (had [t (:content obj)])))]
      [t b])))
