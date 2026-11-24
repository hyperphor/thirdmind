(ns hyperphor.thirdmind.site.cli
  "`lein site published [--list]`: pages the site publishes, from the db.
  `lein site parity [<goddinpotty-output-dir>]`: that set vs the page files goddinpotty wrote. Read-only.
  parity --blocks also lists blocks we'd show whose text goddinpotty's page lacks.
  Both take --db path (default the config's, which the watcher builds: rebuild it after parser
  changes) and --rebuild (first ingest the graph afresh into --db)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hyperphor.thirdmind.blyg.cli :as blyg]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.config :as config]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.site.include :as include]
            [hyperphor.tunamelt.core :as t]))

;;; Pages goddinpotty generates rather than renders from the graph
(def generated #{"Index" "Map"})

(defn settings
  "include/compute opts from a config's :site, filling exit tags and main page from goddinpotty's config."
  [{:keys [site]}]
  (let [gp (some-> (:goddinpotty site) blyg/goddinpotty-settings)]
    {:exit-tags   (or (:exit-tags site) (:exit-tags gp))
     :entry-tags  (:entry-tags site ["EntryPoint"])
     :entry-pages (cons (or (:main-page site) (:main-page gp)) (:entry-pages site))
     :output-dir  (or (:output-dir site) (:site-dir gp))}))

(defn slugs
  "{slug page-uri} for published page uris."
  [pages]
  (into {} (for [u pages] [(c/slug (:name (t/get u))) u])))

(defn parity
  "Our published slugs vs dir's page files, matched ignoring case (as macOS's file system does):
  {:ours n :theirs n :only-ours [slug] :only-theirs [slug] :case [[ours theirs]]}."
  [pages dir]
  (let [ours   (set (keys (slugs pages)))
        ;; slugs drop ".", so a name with one (index.html) isn't a page
        theirs (set (remove #(or (generated %) (str/includes? % ".")) (c/published-pages dir)))
        by-lc  (fn [ss] (group-by str/lower-case ss))
        o (by-lc ours), th (by-lc theirs)]
    {:ours        (count ours)
     :theirs      (count theirs)
     :only-ours   (sort (mapcat o (remove th (keys o))))
     :only-theirs (sort (mapcat th (remove o (keys th))))
     :case        (sort (for [[k os] o, :let [ts (th k)] :when (and ts (not= (set os) (set ts)))]
                          [(str/join " " os) (str/join " " ts)]))}))

(defn- words
  "Lower-case letter/digit runs of s, space-joined."
  [s]
  (str/join " " (map str/lower-case (re-seq #"[\p{L}\p{N}]+" s))))

(defn- block-words
  "First 6 words a block shows: properties, macros, images and link targets dropped. nil if under 3
  words, or for code, which renders too differently to match."
  [content]
  (let [ws (-> (if (str/includes? content "```") "" content)
               (str/replace #"(?m)^\s*[A-Za-z0-9_.\-]+::.*$" "")
               (str/replace #"\{\{(?:(?!\}\}).)*\}\}" "")
               (str/replace #"!\[[^\]]*\]\([^)]*\)(\{[^}]*\})?" "")
               (str/replace #"\]\([^)]*\)" "]")
               (str/replace #"https?://\S+" "")
               (str/replace #"\(\([^()\s]+\)\)" "")
               (str/replace #"(?i)#(?:\[\[)?AskClaude(?:\]\])?" "")   ; a hover icon, not text
               (str/replace #"<[^>]*>" " ")
               (words)
               (str/split #" "))]
    (when (>= (count (remove str/blank? ws)) 3) (str/join " " (take 6 ws)))))

(defn- outline-text
  "Words of a goddinpotty page's outline: from the main div to the footer (the sidebar, with its
  incoming links, comes before)."
  [html]
  (let [a (str/index-of html "<div class=\"main\"") b (or (str/index-of html "py-5 footer") (count html))]
    (when a
      (words (-> (subs html a b) (str/replace #"<[^>]*>" " ")
                 ;; entities only ever split words here, so any entity is a word break
                 (str/replace #"&(?:#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);" " "))))))

(defn block-parity
  "Blocks we'd show on pages both sides publish whose text goddinpotty's page lacks (candidate
  leaks, or rendering noise): [[slug block-uri words]]."
  [{:keys [pages hidden]} dir]
  (let [files (into {} (for [f (c/published-pages dir)] [(str/lower-case f) f]))
        blocks (group-by (comp :page :obj) (for [p pages, b (t/find "block" {:attrs {:page p}})] b))]
    (for [[slug p] (sort (slugs pages))
          :let [f (files (str/lower-case slug))
                text (some-> f (->> (io/file dir) slurp outline-text) (str " "))]
          :when text
          {u :uri o :obj} (blocks p)
          :when (not (hidden u))
          :let [w (block-words (str (:content o)))]
          :when (and w (not (str/includes? text (str w " "))) (not (str/includes? text w)))]
      [slug u w])))

(defn -main [& args]
  (let [[cmd & more] args
        [cfg more]   (if (config/config-file? (first more))
                       [(config/load-config (first more)) (rest more)]
                       [(some-> (config/default-file) config/load-config) more])
        _            (when-not cfg (println "No config (config/*.edn)") (System/exit 1))
        opts         (settings cfg)
        db           (or (second (drop-while #(not= "--db" %) more)) (:db cfg))
        more         (remove #{"--db" db} more)]
    (if (some #{"--rebuild"} more)
      (do (when (= db (:db cfg)) (println "--rebuild needs a --db other than the config's") (System/exit 1))
          (core/fresh-store! db (:realm cfg))
          (ingest/ingest! (:graph cfg) (:realm cfg)))
      (t/configure! {:store db :realm (:realm cfg)}))
    (let [{:keys [pages hidden]} (include/published (:realm cfg) opts)]
      (case cmd
        "published"
        (do (println (count pages) "pages published," (count hidden) "blocks hidden on them")
            (when (some #{"--list"} more) (run! println (sort (keys (slugs pages))))))

        "why"
        (let [{:keys [via exit? included]} (include/published (:realm cfg) opts)
              aliases (ingest/db-aliases (:realm cfg))
              label (fn [u] (let [o (t/get u), c (some-> (:content o) (str/replace #"\s+" " "))]
                              (if c (str "  block: " (subs c 0 (min 90 (count c)))) (str "  page: " (:name o)))))]
          (doseq [name (map str/trim (str/split (str/join " " (remove #(str/starts-with? % "--") more)) #","))
                  :let [u (ingest/page-uri (:realm cfg) (let [k (ingest/page-key name)] (get aliases k k)))]]
            (println name "→" (cond (nil? (t/get u)) "no such page" (pages u) "PUBLISHED"
                                    (included u) "included, not shown" :else "not published")
                     (if (exit? u) "(exit point: journal, or exit-tagged here or above)" ""))
            (when (included u) (doseq [x (take-while some? (iterate via u))] (println (label x))))))

        "parity"
        (let [dir (or (first (remove #(str/starts-with? % "--") more)) (:output-dir opts))
              {:keys [ours theirs only-ours only-theirs case]} (parity pages dir)]
          (println ours "published by ThirdMind," theirs "by goddinpotty in" dir)
          (println (count only-ours) "only ThirdMind's:")
          (doseq [s only-ours] (println "  +" s))
          (println (count only-theirs) "only goddinpotty's:")
          (doseq [s only-theirs] (println "  -" s))
          (println (count case) "differ only in case (ThirdMind's, goddinpotty's):")
          (doseq [[a b] case] (println "  " a b))
          (when (some #{"--blocks"} more)
            (let [missing (block-parity {:pages pages :hidden hidden} dir)]
              (println (count missing) "blocks we'd show that goddinpotty's page lacks:")
              (doseq [[slug _ w] missing] (println "  " slug "|" w))))
          (System/exit (if (or (seq only-ours) (seq only-theirs)) 1 0)))

        (do (println "lein site published [--list] | parity [<goddinpotty-output-dir>] | why <page name>[, <page name>...]") (System/exit 1))))))
