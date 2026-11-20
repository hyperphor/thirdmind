(ns hyperphor.thirdmind.watch
  "Side process: keep the db in sync with a graph directory's .md files and run tag handlers."
  (:require [clojure.string :as str]
            [hyperphor.thirdmind.ask-claude :as ask-claude]
            [hyperphor.thirdmind.blyg.cli :as blyg]
            [hyperphor.thirdmind.claude :as claude]
            [hyperphor.thirdmind.config :as config]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ids :as ids]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.parse :as parse]
            [hyperphor.thirdmind.smart-url :as smart-url]
            [hyperphor.thirdmind.times :as times]
            [hyperphor.tunamelt.core :as t])
  (:import [java.util.concurrent Executors ExecutorService]
           [java.time LocalTime]
           [java.time.format DateTimeFormatter]))

;;; Polls mtimes rather than using WatchService, which on macOS is itself a slow poller.

(def ^:private time-fmt (DateTimeFormatter/ofPattern "HH:mm:ss"))

(def log-file "Also append log lines here, when set (lein watch: target/watch.log)." (atom nil))

(defn log [& xs]
  (let [line (str (.format (LocalTime/now) time-fmt) " " (str/join " " xs))]
    (println line)
    (flush)
    (when-let [f @log-file] (spit f (str line "\n") :append true))))

(defn- first-line [s] (first (str/split-lines (str s))))

(def default-handlers
  "Tag name → (fn [block-entry]) run when a block newly carries that tag. Entry has :uri :obj :tag :file."
  {"blyg" (fn [{:keys [file obj]}] (log "#blyg" file "—" (first-line (:content obj))))})

(def default-actions
  "Name → {:detect (fn [cfg diff] → [{:key k ...}]) :act (fn [cfg rel event])}. Actions write to the
  graph, so they only run with :write?; :act runs off the sync thread, one event at a time."
  {"smart-url"
   {:detect (fn [_ diff]
              (for [[url b] (smart-url/new-urls diff)
                    :when (not (ask-claude/in-answer? (:uri b)))]
                {:key [(get-in b [:obj :content]) url] :url url :content (get-in b [:obj :content])}))
    :act    (fn [{:keys [fetch-title] :as cfg} rel {:keys [url content]}]
              (when-let [title (try (fetch-title url) (catch Exception e (log "smart-url:" (ex-message e)) nil))]
                (if (smart-url/link! cfg rel content url title)
                  (log "smart-url" rel url "→" title)
                  (log "smart-url: not linked (block not found, or file kept changing)" rel url))))}
   "ask-claude"
   {:detect (fn [{:keys [realm]} diff]
              (for [[mode b] (ask-claude/triggers realm diff)]
                {:key (get-in b [:obj :content]) :mode mode :content (get-in b [:obj :content])}))
    :act    (fn [cfg rel {:keys [mode content]}]
              (log "#AskClaude" (name mode) rel "—" (first-line content))
              (when (ask-claude/answer! cfg rel mode content) (log "#AskClaude answered" rel)))}})

(defn- act! [{:keys [actions run in-flight] :as cfg} rel diff]
  (doseq [[name {:keys [detect act]}] actions
          ev (detect cfg diff)
          :let [k [name rel (:key ev)]]
          :when (not (contains? @in-flight k))]
    (swap! in-flight conj k)
    (run (fn []
           (try (act cfg rel ev)
                (catch Throwable e (log name "error" rel (ex-message e)))
                (finally (swap! in-flight disj k)))))))

;;; Auto-publish: a sync that changes a #blyg block (or one under it) marks the blyg dirty; after
;;; :quiet-ms with no further such change, publish and push. Runs on the sync thread, so the db holds still.

(defn blyg-touched?
  "Does diff add, change or remove a block that carries a blyg control tag or sits under one?"
  [realm {:keys [old new]}]
  (let [tags     (set (for [tg blyg/control-tags] (ingest/page-uri realm (ingest/page-key tg))))
        tagged?  #(some tags (:tags %))
        under?   (fn [o] (loop [u (:parent o) n 0]
                           (when-let [p (and u (< n 100) (t/get u))]
                             (or (tagged? p) (recur (:parent p) (inc n))))))
        entry    (juxt :uri :obj)
        [os ns]  [(set (map entry old)) (set (map entry new))]
        changed  (concat (remove (comp os entry) new) (remove (comp ns entry) old))]
    (boolean (some (fn [{o :obj}] (or (tagged? o) (under? o))) changed))))

(defn blyg-publish!
  "Publish the blyg per config :blygger settings, and push it if anything changed; logs the outcome."
  [realm {:keys [goddinpotty state ids-backup site-repo mount remote]}]
  (let [site (str site-repo "/" mount)
        {:keys [status changes report]}
        (blyg/publish! {:realm realm :goddinpotty goddinpotty :state state :live site :out site
                        :ids-backup ids-backup :skip-unchanged? true})]
    (log "blyg publish:" (name status) (if (= status :unchanged) "" (blyg/change-counts changes)))
    (case status
      :refused (log "blyg: not publishing, new items look like old ones:" (:possible-duplicates report)
                    "- check, then `lein blyg publish --allow-new`")
      :written (log "blyg push:" (name (blyg/push! site-repo mount remote)))
      nil)))

(defn- auto-publish! [{:keys [realm blygger blyg-dirty blyg-run] :or {blyg-run blyg-publish!}}]
  (when-let [t0 (some-> blyg-dirty deref)]
    (when (>= (- (System/currentTimeMillis) t0) (:quiet-ms blygger 120000))
      (reset! blyg-dirty nil)
      (try (blyg-run realm blygger)
           (catch Exception e (log "blyg publish error:" (ex-message e)))))))

(defn scan
  "{rel-path [mtime size]} for the graph's page and journal files."
  [dir]
  (into {} (for [[^java.io.File f _ rel] (parse/graph-files dir)]
             [rel [(.lastModified f) (.length f)]])))

(defn- recorded
  "{rel-path [mtime size]} as of the db's file records."
  [realm]
  (into {} (for [{o :obj} (t/find "file" {:realm realm})] [(:path o) [(:mtime o) (:size o)]])))

(defn- fire! [{:keys [realm handlers]} rel diff]
  (doseq [[tag-uri block] (ingest/new-tags diff)
          :let [_ (log "new tag:" (:name (t/get tag-uri)) (:name (t/get (get-in block [:obj :page]))))]
          [tag handler]   handlers
          :when (= tag-uri (ingest/page-uri realm (ingest/page-key tag)))]
    (try (handler (assoc block :tag tag :file rel))
         (catch Exception e (log "handler error" tag rel (ex-message e))))))

(defn sync!
  "One pass: re-ingest files changed since known ({rel [mtime size]}), firing handlers.
  Files modified within :settle-ms are left for a later pass. Returns the updated known map."
  [{:keys [dir realm settle-ms times?] :or {settle-ms 0} :as cfg} known]
  (let [disk    (scan dir)
        cutoff  (- (System/currentTimeMillis) settle-ms)
        settled (into {} (filter (fn [[_ [mtime]]] (< mtime cutoff))) disk)
        ;; removals first, so a renamed file's ids are orphaned before its new name looks for them
        changed (concat (remove disk (keys known))
                        (for [[rel stamp] settled :when (not= stamp (known rel))] rel))]
    (reduce (fn [known rel]
              (try
                (let [diff (ingest/update-file! dir realm rel)]
                  (log (if (disk rel) "synced" "removed") rel)
                  (when (and times? (disk rel)) (times/update-file-times! dir realm rel))
                  (fire! cfg rel diff)
                  (when (and (:blyg-dirty cfg) (blyg-touched? realm diff))
                    (reset! (:blyg-dirty cfg) (System/currentTimeMillis)))
                  (when (:write? cfg) (act! cfg rel diff)))
                (catch Exception e (log "error syncing" rel (ex-message e))))
              (if-let [stamp (disk rel)] (assoc known rel stamp) (dissoc known rel)))
            known
            changed)))

(defn init!
  "Bring the configured store up to date with dir; returns the known map for sync!.
  A db without file records (eg from `lein run`, or new) is rebuilt from scratch, without firing handlers."
  [{:keys [dir realm db] :as cfg}]
  (ids/open! (ids/path-for db))
  (if (seq (t/find "file" {:realm realm :limit 1}))
    (sync! cfg (recorded realm))
    (do (log "No file records in" db "- rebuilding from" dir)
        (core/fresh-store! db realm)
        (ingest/ingest! dir realm)
        (log "Rebuilt:" (t/types {:counts? true}))
        (when (:times? cfg) (log "Block times not computed; run `lein times" dir "` to backfill"))
        (recorded realm))))

(defn action-defaults
  "cfg with what write actions need, unless supplied: a serial worker, Claude, title fetching."
  [cfg]
  (merge {:actions default-actions :in-flight (atom #{}) :quiet-ms 8000 :log log
          :ask claude/ask :fetch-title smart-url/fetch-title}
         (when-not (:run cfg)
           (let [^ExecutorService ex (Executors/newSingleThreadExecutor)]
             {:run #(.submit ex ^Runnable %) :executor ex}))
         cfg))

(defn start!
  "Init, then sync! every :interval-ms on a background thread. Returns a fn that stops it."
  [{:keys [interval-ms] :or {interval-ms 2000} :as cfg}]
  (let [cfg     (cond-> (merge {:handlers default-handlers :settle-ms interval-ms
                                :times? (times/git-repo? (:dir cfg))}
                               cfg)
                  (:write? cfg) action-defaults
                  (get-in cfg [:blygger :auto-publish?]) (update :blyg-dirty #(or % (atom (System/currentTimeMillis)))))  ; publish once at start
        known   (init! cfg)
        running (atom true)
        worker  (future
                  (loop [known known]
                    (when @running
                      (Thread/sleep (long interval-ms))
                      (let [known (try (sync! cfg known)
                                       (catch Exception e (log "sync error" (ex-message e)) known))]
                        (auto-publish! cfg)
                        (recur known)))))]
    (fn [] (reset! running false) @worker (some-> ^ExecutorService (:executor cfg) .shutdownNow) nil)))

(defn -main
  "Usage: lein watch [config.edn | [--write] <graph-dir> [db-path]]"
  [& args]
  (let [[cfg args] (config/from-args args)
        write? (or (some #{"--write"} args) (:write? cfg))
        [dir db] (remove #{"--write"} args)
        dir    (or dir (:graph cfg))
        db     (or db (:db cfg))]
    (when-not dir
      (println "Usage: lein watch [config.edn | [--write] <graph-dir> [db-path]]")
      (println "  --write  enable actions that edit the graph (smart URLs, #AskClaude)")
      (println "  with no arguments, the config in config/ (see config.clj)")
      (System/exit 1))
    (let [realm (or (:realm cfg) (core/realm-for dir))
          db    (or db (str realm ".db"))]
      (t/configure! {:store db :realm realm})
      (reset! log-file "target/watch.log")
      (start! {:dir dir :realm realm :db db :write? (boolean write?) :blygger (:blygger cfg)})
      (log "Watching" dir "→" db (if write? "(writing: smart URLs, #AskClaude)" "")
           (if (get-in cfg [:blygger :auto-publish?]) "(auto-publishing #blyg)" ""))
      @(promise))))
