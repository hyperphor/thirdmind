(ns hyperphor.thirdmind.blyg.cli
  "`lein blyg preview <graph-dir> --goddinpotty <config.edn> [--db path] [--out file]`: render every
  #blyg block to one local HTML page. Reads the db; writes only the preview file.
  `lein blyg parity <state.edn> <live-blyg-dir>`: does wire/surface reproduce a live surface? Read-only."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup.util :refer [raw-string]]
            [hiccup2.core :as h]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.blyg.wire :as w]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.tunamelt.core :as t]))

(defn goddinpotty-settings
  "{:base-url :site-dir :exit-tags} read from a goddinpotty config (by regex: it uses #include)."
  [file]
  (let [s (-> (slurp file) (str/replace #";[^\n]*" "") (str/replace #"#_\s*\"[^\"]*\"" ""))
        v (fn [k] (second (re-find (re-pattern (str ":" k "\\s+\"([^\"]+)\"")) s)))]
    {:base-url  (v "real-base-url")
     :site-dir  (v "output-dir")
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

(defn -main [& args]
  (when (= "parity" (first args))
    (let [{:keys [ours live differ]} (parity (nth args 1) (nth args 2))]
      (println ours "files generated," live "live," (count differ) "differ")
      (doseq [p differ] (println "  " p))
      (System/exit (if (seq differ) 1 0))))
  (let [[cmd dir & more] args
        opts (into {} (map vec (partition 2 more)))
        gp   (opts "--goddinpotty")]
    (when-not (and (= cmd "preview") dir gp)
      (println "Usage: lein blyg preview <graph-dir> --goddinpotty <config.edn> [--db path] [--out file]")
      (println "       lein blyg parity <state.edn> <live-blyg-dir>")
      (System/exit 1))
    (let [realm (core/realm-for dir)
          db    (or (opts "--db") (str realm ".db"))
          out   (or (opts "--out") "target/blyg-preview.html")]
      (when-not (.isFile (io/file db))
        (println "No db at" db "- run `lein watch" dir "` first")
        (System/exit 1))
      (t/configure! {:store db :realm realm})
      (println "Wrote" (preview! realm (goddinpotty-settings gp) out) "items to" out)
      (shutdown-agents))))
