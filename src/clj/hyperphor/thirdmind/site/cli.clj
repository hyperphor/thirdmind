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
            [hyperphor.thirdmind.site.render :as render]
            [hyperphor.tunamelt.core :as t]))

;;; Pages goddinpotty generates rather than renders from the graph
(def generated #{"Index" "Map"})

(defn settings
  "Site settings from a config's :site (include/compute opts among them), filling exit tags, main
  page, base URL and output dir from goddinpotty's config."
  [{:keys [site graph realm]}]
  (let [gp   (some-> (:goddinpotty site) blyg/goddinpotty-settings)
        main (or (:main-page site) (:main-page gp))]
    (merge (dissoc site :goddinpotty)
           {:exit-tags   (or (:exit-tags site) (:exit-tags gp))
            :entry-tags  (:entry-tags site ["EntryPoint"])
            :entry-pages (cons main (:entry-pages site))
            :main-page   main
            :base-url    (or (:base-url site) (:base-url gp))
            :graph-dir   graph
            :graph-name  (or (:graph-name site) realm)
            :output-dir  (or (:output-dir site) (:site-dir gp))})))

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

(declare block-words*)

(defn- block-words
  "First 6 words a block shows: properties, macros, images and link targets dropped. nil if under 3
  words, or for code, which renders too differently to match."
  [content]
  (block-words* content 6))

(defn- block-words*
  [content n]
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
    (when (>= (count (remove str/blank? ws)) 3) (str/join " " (take n ws)))))

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

(def ^:private usage
  "lein site published [--list] | parity [<goddinpotty-output-dir>] [--blocks] | why <page name>[, <page name>...]
  | build [--out dir]   (default the config's :site :build-dir; never the live site before cutover)
  all take --db path and --rebuild; diff, verify and push are still to come (design/static-site.md)")

(defn- overlap
  "Shared words of two word strings, as a fraction of the longer (1.0 when both are empty)."
  [a b]
  (let [fa (frequencies (str/split a #" ")) fb (frequencies (str/split b #" "))
        n  (max (reduce + (vals fa)) (reduce + (vals fb)))]
    (if (zero? n) 1.0 (/ (double (reduce + (for [[w k] fa] (min k (get fb w 0))))) n))))

(defn html-parity
  "Our built pages (in built) vs goddinpotty's (in dir), by outline words: [[slug overlap] ...],
  worst first, for pages both have."
  [pages built dir]
  (let [theirs (into {} (for [f (c/published-pages dir)] [(str/lower-case f) f]))]
    (sort-by second
             (for [slug (keys (slugs pages))
                   :let [g (theirs (str/lower-case slug)) ours (io/file built slug)]
                   :when (and g (.isFile ours))]
               [slug (overlap (str (outline-text (slurp ours))) (str (outline-text (slurp (io/file dir g)))))]))))

(defn- h64
  "A 64-bit hash of s, from two independent 32-bit ones (too few collisions to matter here)."
  [^String s]
  (bit-or (bit-shift-left (long (.hashCode s)) 32) (bit-and (long (hash s)) 0xffffffff)))

(defn- grams
  "Hashes of the k-word runs of word string s, for k in ks."
  [s ks]
  (let [ws (vec (remove str/blank? (str/split s #" ")))]
    (set (for [k ks, i (range (inc (- (count ws) k)))] (h64 (str/join " " (subvec ws i (+ i k))))))))

(defn- body-text [html]
  (let [a (or (str/index-of html "<body") 0)]
    (words (-> (subs html a) (str/replace #"<[^>]*>" " ") (str/replace #"&(?:#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);" " ")))))

(defn leaks
  "Blocks the walk left out (on any page, journals included) whose first 15 words (all, if fewer)
  appear anywhere in a built page's body, sidebar included, and whose first 6 are in no published
  block or page title: [[words [slug]]]. Should be empty. Blocks under 3 words aren't checked."
  [{:keys [pages included]} realm built]
  (let [ks     [3 4 5 6]
        files  (for [slug (keys (slugs pages)) :let [f (io/file built slug)] :when (.isFile f)] [slug (body-text (slurp f))])
        seen   (into #{} (mapcat #(grams (second %) ks)) files)
        blocks (t/find "block" {:realm realm})
        shown  (into #{} (mapcat #(let [c (str (:content (:obj %)))]
                                    (concat (grams (words c) ks) (grams (str (block-words* c 100000)) ks))))
                     (concat (filter #(included (:uri %)) blocks)
                             (for [p pages] {:obj {:content (:name (t/get p))}})))]
    (for [{u :uri o :obj} blocks
          :when (not (included u))
          :let [w (block-words (str (:content o)))]
          :when w
          :let [h (h64 w)]
          :when (and (seen h) (not (shown h)))
          ;; confirm with the first 15 words, so a short shared phrase isn't reported
          :let [lw    (block-words* (str (:content o)) 15)
                where (vec (for [[slug text] files :when (str/includes? (str " " text " ") (str " " lw " "))] slug))]
          :when (seq where)]
      [w where])))

(defn -main [& args]
  (when-not (#{"published" "parity" "why" "build"} (first args))
    (println usage)
    (System/exit 1))
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
    (let [{:keys [pages hidden] :as inc} (include/published (:realm cfg) opts)]
      (case cmd
        "published"
        (do (println (count pages) "pages published," (count hidden) "blocks hidden on them")
            (when (some #{"--list"} more) (run! println (sort (keys (slugs pages))))))

        "build"
        (let [out (or (second (drop-while #(not= "--out" %) more)) (:build-dir opts) "target/site")]
          (when (= (.getCanonicalPath (io/file out)) (some-> (:output-dir opts) io/file .getCanonicalPath))
            (println "Not building into the live site dir" out "before cutover") (System/exit 1))
          (println (render/build! (render/ctx (:realm cfg) opts inc) out) "→" out))

        "why"
        (let [{:keys [via exit? included]} inc
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
        (let [dir (or (first (remove #(str/starts-with? % "--") (remove #{(second (drop-while #(not= "--out" %) more))} more))) (:output-dir opts))
              {:keys [ours theirs only-ours only-theirs case]} (parity pages dir)]
          (println ours "published by ThirdMind," theirs "by goddinpotty in" dir)
          (println (count only-ours) "only ThirdMind's:")
          (doseq [s only-ours] (println "  +" s))
          (println (count only-theirs) "only goddinpotty's:")
          (doseq [s only-theirs] (println "  -" s))
          (println (count case) "differ only in case (ThirdMind's, goddinpotty's):")
          (doseq [[a b] case] (println "  " a b))
          (when (some #{"--html"} more)
            (let [built (or (second (drop-while #(not= "--out" %) more)) (:build-dir opts) "target/site")
                  ps    (html-parity pages built dir)
                  ls    (leaks inc (:realm cfg) built)]
              (println "Outline word overlap with goddinpotty, over" (count ps) "pages: median"
                       (format "%.3f" (second (nth ps (quot (count ps) 2) [nil 1.0]))) "; below 0.9:")
              (doseq [[slug x] (take-while #(< (second %) 0.9) ps)] (println (format "   %.3f %s" x slug)))
              (println (count ls) "unpublished blocks whose words appear in a built page but in no published block (should be 0):")
              (doseq [[w slugs] ls] (println "  " w "|" (str/join " " slugs)))))
          (when (some #{"--blocks"} more)
            (let [missing (block-parity {:pages pages :hidden hidden} dir)]
              (println (count missing) "blocks we'd show that goddinpotty's page lacks:")
              (doseq [[slug _ w] missing] (println "  " slug "|" w))))
          (System/exit (if (or (seq only-ours) (seq only-theirs)) 1 0)))

        nil))))
