(ns hyperphor.thirdmind.ingest
  "Turn a parsed Logseq graph into Tunamelt page and block objects."
  (:require [clojure.string :as str]
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
  "Resolve refs across parsed pages; returns {:objects [[uri obj] ...] :report {...}}."
  [realm pages]
  (let [by-key     (group-by (comp page-key :name) pages)
        collisions (into {} (filter #(> (count (val %)) 1)) by-key)
        aliases    (into {} (for [p pages, a (split-list (get-in p [:props "alias"]))]
                              [(page-key a) (page-key (:name p))]))
        resolve-pg (fn [name] (let [k (page-key name)] (get aliases k k)))
        ;; block ids: explicit id:: or page-key~outline-path
        blocks     (for [p pages, b (:blocks p)]
                     (assoc b
                            :page-key (page-key (:name p))
                            :id (or (get-in b [:props "id"])
                                    (str (page-key (:name p)) "~" (str/join "." (:path b))))))
        id-counts  (frequencies (keep #(get-in % [:props "id"]) blocks))
        known-ids  (set (keys id-counts))
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
               :unresolved-block-refs @unresolved}}))

(defn ingest!
  "Read the graph at dir and store it in the configured Tunamelt store under realm. Returns the report."
  [dir realm]
  (let [{:keys [objects report]} (graph->objects realm (read-graph dir))]
    (t/put-many objects)
    report))
