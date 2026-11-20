(ns hyperphor.thirdmind.blyg.cli
  "`lein blyg preview <graph-dir> --goddinpotty <config.edn> [--db path] [--out file]`: render every
  #blyg block to one local HTML page. Reads the db; writes only the preview file.
  `lein blyg parity <state.edn> <live-blyg-dir>`: does wire/surface reproduce a live surface? Read-only.
  import / publish / diff: see usage. publish writes only to --out and --state."
  (:require [clojure.data.json :as json]
            [clojure.pprint]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [hiccup.util :refer [raw-string]]
            [hiccup2.core :as h]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.blyg.migrate :as migrate]
            [hyperphor.thirdmind.blyg.pages :as pages]
            [hyperphor.thirdmind.blyg.publish :as publish]
            [hyperphor.thirdmind.blyg.wire :as w]
            [hyperphor.thirdmind.config :as config]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ids :as ids]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.tunamelt.core :as t]))

(defn goddinpotty-settings
  "{:base-url :site-dir :state-file :exit-tags} read from a goddinpotty config (by regex: it uses #include)."
  [file]
  (let [s (-> (slurp file) (str/replace #";[^\n]*" "") (str/replace #"#_\s*\"[^\"]*\"" ""))
        v (fn [k] (second (re-find (re-pattern (str ":" k "\\s+\"([^\"]+)\"")) s)))]
    {:base-url  (v "real-base-url")
     :site-dir  (v "output-dir")
     :state-file (v "state-file")
     :exit-tags (some->> (re-find #"(?s):exit-tags\s*\[(.*?)\]" s) second
                         (re-seq #"\"([^\"]+)\"") (map second))}))

(def control-tags ["blyg" "blyg-pin" "blyg-reply"])

(defn candidates
  "Block entries carrying a control tag, newest first when block times are known."
  [realm]
  (->> (for [tg control-tags
             e (t/referrers (ingest/page-uri realm (ingest/page-key tg)) {:attr :tags :type "block"})]
         (select-keys e [:uri :obj]))
       distinct
       (sort-by (fn [{o :obj}] [(- (or (:updated o) 0)) (str (:page o)) (:order o)]))))

(defn- flags [realm o]
  (for [tg (rest control-tags) :when (some #{(ingest/page-uri realm (ingest/page-key tg))} (:tags o))] tg))

(defn- item-view [ctx realm {:keys [uri obj]}]
  (let [kids   (c/body-children ctx uri)
        page   (t/get (:page obj))
        thread? (seq kids)
        md     (if thread?
                 (str (c/thread-md ctx uri (map #(str "child-" (inc %)) (range (count kids))))
                      (str/join (map-indexed (fn [i k] (str "\n\n── child-" (inc i) " ──\n\n" (c/item-md ctx k))) kids)))
                 (c/fragment-md ctx uri))]
    [:article
     [:header [:span.kind (if thread? (str "thread · " (count kids) " children") "fragment")]
      (for [f (flags realm obj)] [:span.flag f])
      [:span.page (:name page)]
      (when-let [u (:updated obj)] [:span.date (str (java.time.LocalDate/ofInstant (java.time.Instant/ofEpochMilli u) (java.time.ZoneId/systemDefault)))])]
     [:div.html (raw-string (c/fragment-html ctx uri))]
     [:details [:summary "content_md"] [:pre md]]]))

(def ^:private css "
body{font:16px/1.5 Georgia,serif;max-width:46em;margin:2em auto;padding:0 16px;background:#fdfcf8;color:#222}
article{border-top:1px solid #ccc;padding:1em 0}
header{font:13px system-ui,sans-serif;color:#666;display:flex;gap:.8em;flex-wrap:wrap}
.kind{font-weight:600;color:#333}.flag{background:#fde68a;padding:0 .4em;border-radius:3px}
pre{white-space:pre-wrap;font-size:13px;background:#f3f1ea;padding:.6em}
img{max-width:100%} iframe.video{width:100%;aspect-ratio:16/9;border:0}
details.ask-claude>summary{cursor:pointer} ul.claude{border-left:3px solid #c9b8f0;padding-left:1.2em}
.source-page{font-size:13px;color:#666}
@media (prefers-color-scheme: dark){body{background:#1b1b1b;color:#ddd}pre{background:#2a2a2a}header{color:#aaa}.kind{color:#eee}.flag{color:#222}}")

(defn preview!
  "Write the preview page for realm to out; returns the number of items."
  [realm settings out]
  (let [ctx   (c/ctx realm {:base-url (:base-url settings) :exit-tags (:exit-tags settings)
                            :published (c/published-pages (:site-dir settings))})
        items (candidates realm)]
    (io/make-parents (io/file out))
    (spit out (str "<!doctype html>"
                   (h/html [:html [:head [:meta {:charset "utf-8"}]
                                   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                                   [:title "Blyg preview"] [:style (raw-string css)]]
                            [:body [:h1 "Blyg preview"]
                             [:p (count items) " items, rendered from " realm
                              ". Links go only to pages in " (:site-dir settings) "."]
                             (for [it items] (item-view ctx realm it))]])))
    (count items)))

(defn parity
  "Paths where (wire/surface state) differs from the .json/.xml files under live-dir (JSON
  compared parsed); manifest fields come from the live blyg.json. {:ours n :live n :differ [...]}"
  [state-file live-dir]
  (let [dir   (str (str/replace live-dir #"/+$" "") "/")
        live  (into {} (for [^java.io.File f (file-seq (io/file dir)) :when (.isFile f)
                             :let [p (subs (.getPath f) (count dir))]
                             :when (re-find #"\.(json|xml)$" p)]
                         [p (slurp f)]))
        m     (json/read-str (live "blyg.json") :key-fn keyword)
        ours  (w/surface (:site m) {:title (:title m) :author (:author m) :generator (:generator m)
                                    :generator-url (:generator_url m)}
                         (:items (edn/read-string (slurp state-file))))
        same? (fn [p] (let [a (ours p) b (live p)]
                        (and a b (if (str/ends-with? p ".json") (= (json/read-str a) (json/read-str b)) (= a b)))))]
    {:ours (count ours) :live (count live)
     :differ (vec (sort (remove same? (distinct (concat (keys ours) (keys live))))))}))

(defn read-state [file] (edn/read-string (slurp file)))

(defn write-state!
  "Write state as sorted, pretty EDN (as goddinpotty's save-state! does), via a temp file."
  [file state]
  (let [sorted (-> state
                   (update :items #(into (sorted-map) (for [[k e] %] [k (into (sorted-map) e)])))
                   (->> (into (sorted-map))))
        tmp    (io/file (str file ".tmp"))]
    (io/make-parents (io/file file))
    (spit tmp (with-out-str (clojure.pprint/pprint sorted)))
    (.renameTo tmp (io/file file))))

(defn- live-manifest [live-dir] (json/read-str (slurp (io/file live-dir "blyg.json")) :key-fn keyword))

(defn site-files
  "{path text}: the protocol surface plus HTML pages for state, chrome and manifest fields taken
  from the live blyg at live-dir."
  [state live-dir]
  (let [m      (live-manifest live-dir)
        origin (:site m)
        opts   {:title (:title m) :author (:author m)
                :generator "thirdmind-blyg/0.1" :generator-url "https://github.com/hyperphor/thirdmind"}
        perm   (some #(when (.isFile (io/file % "index.html")) (slurp (io/file % "index.html")))
                     (.listFiles (io/file live-dir "f")))]
    (merge (w/surface origin opts (:items state))
           (pages/site-pages {:archive (slurp (io/file live-dir "index.html")) :permalink perm}
                             origin opts (:items state)))))

(defn write-files!
  "Write {path text} under dir. Adds and overwrites; never deletes (items stay 200 forever)."
  [dir files]
  (doseq [[p text] files :let [f (io/file dir p)]]
    (io/make-parents f)
    (spit f text)))

(defn diff-dirs
  "staging vs live: {:missing [paths live has, staging lacks] :extra [...] :differ [json/xml paths]}.
  JSON compares parsed; HTML only by presence (its markup differs by design)."
  [staging live]
  (let [files (fn [d] (let [d (str (str/replace (str d) #"/+$" "") "/")]
                        (into {} (for [^java.io.File f (file-seq (io/file d)) :when (.isFile f)]
                                   [(subs (.getPath f) (count d)) f]))))
        a (files staging) b (files live)
        same? (fn [p] (let [x (slurp (a p)) y (slurp (b p))]
                        (if (str/ends-with? p ".json") (= (json/read-str x) (json/read-str y)) (= x y))))]
    {:missing (vec (sort (remove a (keys b))))
     :extra   (vec (sort (remove b (keys a))))
     :differ  (vec (sort (for [p (keys a) :when (and (b p) (re-find #"\.(json|xml)$" p) (not (same? p)))] p)))}))

(defn- git [repo & args]
  (let [{:keys [exit out err]} (apply sh/sh "git" "-C" (str repo) args)]
    (when-not (zero? exit) (throw (ex-info (str "git " (str/join " " args) " failed: " err) {:exit exit})))
    out))

(defn push!
  "Commit and push only path (eg ammdi/blyg) in repo, to remote. Anything else in the repo,
  staged or not, is left alone. Returns :pushed or :unchanged."
  [repo path remote]
  (git repo "add" "--" path)
  (if (str/blank? (git repo "diff" "--cached" "--name-only" "--" path))
    :unchanged
    (do (git repo "commit" "-q" "-m" (str "Blyg publish " (java.time.LocalDate/now)) "--" path)
        (git repo "push" "-q" remote)
        :pushed)))

(defn- content-ctx [realm gp]
  (let [s (goddinpotty-settings gp)]
    (c/ctx realm {:base-url (:base-url s) :exit-tags (:exit-tags s) :published (c/published-pages (:site-dir s))})))

(defn change-counts [ch] (into (sorted-map) (for [[k v] ch] [k (count v)])))

(defn publish!
  "Plan a publish of realm's #blyg items against the :state file, and write site files to :out and
  the new state (and :ids-backup, ids store already open), unless refused (possible duplicates,
  without :allow-new?), :dry-run?, or (:skip-unchanged?) nothing changed.
  Returns {:report :changes :status}, status :refused, :dry-run, :unchanged or :written."
  [{:keys [realm goddinpotty state live out ids-backup withdraw? allow-new? dry-run? skip-unchanged?]}]
  (let [old (read-state state)
        {new :state report :report} (publish/plan (content-ctx realm goddinpotty) old (w/now-iso) :withdraw? withdraw?)
        ch  (publish/changes old new)
        res {:report report :changes ch}]
    (assoc res :status
           (cond
             (and (seq (:possible-duplicates report)) (not allow-new?)) :refused
             dry-run? :dry-run
             (and skip-unchanged? (not-any? seq (vals ch))) :unchanged
             :else (do (write-files! out (site-files new live))
                       (write-state! state new)
                       (when ids-backup (ids/export! realm ids-backup))
                       :written)))))

(defn- open-db! [dir opts]
  (let [realm (core/realm-for dir)
        db    (or (opts "--db") (str realm ".db"))]
    (when-not (.isFile (io/file db))
      (println "No db at" db "- run `lein watch" dir "` first")
      (System/exit 1))
    (t/configure! {:store db :realm realm})
    realm))

(def ^:private usage
  ["Usage: lein blyg <command> [config.edn] [options]"
   "  config.edn: a ThirdMind config (default: $THIRDMIND_CONFIG, or the one file in config/);"
   "  its :graph, :db and :blygger settings supply every option below, which override them."
   ""
   "  publish [--dry-run] [--allow-new] [--withdraw]   publish #blyg items into the site repo"
   "  push                                             commit and push the blyg dir of the site repo"
   "  preview [--out file]                             every #blyg item on one local page"
   "  import                                           goddinpotty's state → ThirdMind's (once, at cut-over)"
   "  diff <staging-dir> <live-blyg-dir>              compare two blyg dirs"
   "  parity <state.edn> <live-blyg-dir>              does a state reproduce a live surface?"
   ""
   "  overrides: --goddinpotty file --db path --state file --live dir --out dir --ids-backup file"
   "             --from file (import) --path p --remote r (push); a graph dir or site repo may"
   "             be given as the first argument"])

(defn- config-opts
  "Option defaults for cmd from config cfg."
  [cmd cfg]
  (let [b    (:blygger cfg)
        site (when (and (:site-repo b) (:mount b)) (str (:site-repo b) "/" (:mount b)))
        gp   (:goddinpotty b)]
    (into {} (remove (comp nil? val)
                     (case cmd
                       "publish" {"--goddinpotty" gp "--state" (:state b) "--live" site "--out" site
                                  "--ids-backup" (:ids-backup b) "--db" (:db cfg)}
                       "preview" {"--goddinpotty" gp "--db" (:db cfg)}
                       "import"  {"--goddinpotty" gp "--out" (:state b) "--db" (:db cfg)
                                  "--from" (some-> gp goddinpotty-settings :state-file)}
                       "push"    {"--path" (:mount b) "--remote" (:remote b)}
                       {})))))

(defn -main [& args]
  (let [[cmd & more] args
        [cfg more] (if (#{"parity" "diff"} cmd) [nil more] (config/from-args more))
        flags #{"--dry-run" "--allow-new" "--withdraw"}
        pos   (take-while #(not (str/starts-with? % "--")) more)
        kv    (drop (count pos) more)
        opts  (merge (config-opts cmd cfg)
                     (loop [[a & r] kv, acc {}]
                       (cond (nil? a) acc
                             (flags a) (recur r (assoc acc a true))
                             :else (recur (rest r) (assoc acc a (first r))))))
        graph (or (first pos) (:graph cfg))
        pos   (if (and graph (#{"preview" "import" "publish"} cmd)) (cons graph (rest pos)) pos)
        pos   (if (= cmd "push") (cons (or (first pos) (get-in cfg [:blygger :site-repo])) (rest pos)) pos)
        need  (fn [& ks] (when-not (and (first pos) (every? opts ks)) (run! println usage) (System/exit 1)))]
    (case cmd
      "parity"
      (let [{:keys [ours live differ]} (parity (first pos) (second pos))]
        (println ours "files generated," live "live," (count differ) "differ")
        (doseq [p differ] (println "  " p))
        (System/exit (if (seq differ) 1 0)))

      "diff"
      (let [{:keys [missing extra differ]} (diff-dirs (first pos) (second pos))]
        (println (count missing) "missing from staging," (count extra) "new in staging," (count differ) "json/xml differ")
        (doseq [[k ps] [["missing" missing] ["differ" differ] ["new" extra]], p (take 40 ps)] (println " " k p))
        (System/exit (if (seq missing) 1 0)))

      "push"
      (do (need "--path" "--remote")
          (println (name (push! (first pos) (opts "--path") (opts "--remote"))) (opts "--path")))

      "preview"
      (do (need "--goddinpotty")
          (let [realm (open-db! (first pos) opts)
                out   (or (opts "--out") "target/blyg-preview.html")]
            (println "Wrote" (preview! realm (goddinpotty-settings (opts "--goddinpotty")) out) "items to" out)))

      "import"
      (do (need "--goddinpotty" "--from" "--out")
          (let [realm (open-db! (first pos) opts)
                {:keys [state report]} (migrate/import-state (content-ctx realm (opts "--goddinpotty")) (read-state (opts "--from")))]
            (clojure.pprint/pprint report)
            (write-state! (opts "--out") state)
            (println "Wrote" (opts "--out"))))

      "publish"
      (do (need "--goddinpotty" "--state" "--live" "--out")
          (let [realm (open-db! (first pos) opts)
                _     (when (opts "--ids-backup") (ids/open! (ids/path-for (or (opts "--db") (str realm ".db")))))
                {:keys [report changes status]}
                (publish! {:realm realm :goddinpotty (opts "--goddinpotty") :state (opts "--state")
                           :live (opts "--live") :out (opts "--out") :ids-backup (opts "--ids-backup")
                           :withdraw? (opts "--withdraw") :allow-new? (opts "--allow-new")
                           :dry-run? (opts "--dry-run")})]
            (clojure.pprint/pprint (update report :untagged count))
            (println "Changes:" (change-counts changes))
            (case status
              :refused (do (println "Refusing: new items look like unmatched existing ones (possible duplicates). --allow-new to publish anyway.")
                           (System/exit 1))
              :dry-run (println "Dry run: nothing written.")
              (do (println "Wrote site files to" (opts "--out") "and state to" (opts "--state"))
                  (when (opts "--ids-backup") (println "Backed up id records to" (opts "--ids-backup")))))))

      (do (run! println usage) (System/exit 1)))
    (shutdown-agents)))
