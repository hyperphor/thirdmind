(ns hyperphor.thirdmind.identity-replay
  "`lein replay-ids <graph-repo> [--gap k] [--since date] [--max-pairs n]`: replay the graph's git
  history through identity/match and score it against id:: uuids, which the matcher never sees.
  Read-only: git log and cat-file, no checkouts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hyperphor.thirdmind.identity :as id]
            [hyperphor.thirdmind.parse :as parse])
  (:import [java.io BufferedInputStream ByteArrayOutputStream]))

(defn file-versions
  "{path [blob-sha ...]} oldest first, for pages/ and journals/ files; a deletion ends a run."
  [repo since]
  (let [p (.start (ProcessBuilder. ^java.util.List
                                   (cond-> ["git" "-C" (str repo) "log" "--raw" "--no-abbrev" "--no-renames"
                                            "--reverse" "--format=tformat:C" "--diff-filter=AMD"]
                                     since (conj (str "--since=" since))
                                     true  (into ["--" "pages" "journals"]))))]
    (with-open [r (io/reader (.getInputStream p))]
      (reduce (fn [acc l]
                (if-let [[_ sha status path] (re-matches #":\S+ \S+ \S+ (\S+) ([AMD])\t(.+\.md)" l)]
                  (if (= status "D")
                    (update acc path #(when (seq %) (conj % :gap)))
                    (update acc path (fnil conj []) sha))
                  acc))
              {} (line-seq r)))))

(defn- blob-reader
  "fn sha → text, over one `git cat-file --batch` process."
  [repo]
  (let [p   (.start (ProcessBuilder. ^java.util.List ["git" "-C" (str repo) "cat-file" "--batch"]))
        out (.getOutputStream p)
        in  (BufferedInputStream. (.getInputStream p) 65536)
        header (fn [] (let [b (ByteArrayOutputStream.)]
                        (loop [] (let [c (.read in)] (when-not (= c 10) (.write b c) (recur))))
                        (.toString b "UTF-8")))]
    (fn [sha]
      (.write out (.getBytes (str sha "\n"))) (.flush out)
      (let [[_ _ size] (str/split (header) #" ")
            n (parse-long size)
            buf (.readNBytes in (int n))]
        (.read in)                      ; trailing newline
        (String. buf "UTF-8")))))

(defn- blocks [text] (:blocks (parse/parse-lines (str/split-lines text))))

(defn- uuid-index [bs]
  (let [f (frequencies (keep #(get-in % [:props "id"]) bs))]
    (into {} (keep-indexed (fn [i b] (when-let [u (get-in b [:props "id"])] (when (= 1 (f u)) [u i])))) bs)))

(defn score-pair
  "Outcomes for non-trivial blocks whose uuid is present once on both sides: [[kind outcome old-block new-block] ...], kind
  :same / :moved / :edited / :rewritten (similarity < 0.5), outcome :kept / :lost / :wrong."
  [ov nv]
  (let [strip #(mapv (fn [b] (select-keys b [:content :path])) %)
        m     (id/match (strip ov) (strip nv))
        ou    (uuid-index ov)]
    (for [[u j] (uuid-index nv) :let [i (ou u)] :when (and i (not (id/trivial? (:content (nv j)))))
          :let [o (ov i) n (nv j)
                kind (cond (not= (:content o) (:content n))
                           (if (>= (id/similarity (:content o) (:content n)) 0.5) :edited :rewritten)
                           (not= (:path o) (:path n)) :moved
                           :else :same)]]
      [kind (cond (= i (m j)) :kept (nil? (m j)) :lost :else :wrong) o n])))

(defn replay
  "Score consecutive (or gap-apart) versions of each file that has id:: blocks."
  [repo {:keys [gap since max-pairs] :or {gap 1 max-pairs Long/MAX_VALUE}}]
  (let [read-blob (blob-reader repo)
        runs      (for [[path vs] (file-versions repo since)
                        run (remove #{[:gap]} (partition-by #{:gap} vs))
                        :when (not= [:gap] run)]
                    [path (vec run)])
        pairs     (for [[path run] runs, i (range 0 (- (count run) gap) gap)]
                    [path (run i) (run (+ i gap))])
        cache     (atom {})
        parsed    (fn [sha] (or (@cache sha)
                                (let [t (read-blob sha)
                                      v (when (str/includes? t "id:: ") (vec (blocks t)))]
                                  (when (> (count @cache) 2000) (reset! cache {}))
                                  (swap! cache assoc sha v)
                                  v)))]
    (loop [[[path a b] & more] pairs, n 0, counts {}, examples []]
      (if (or (nil? path) (>= n max-pairs))
        {:pairs n :counts counts :examples examples}
        (let [ov (parsed a) nv (when ov (parsed b))]
          (if (and ov nv (not= a b))
            (let [rs (score-pair ov nv)]
              (recur more (inc n)
                     (reduce (fn [c [k o]] (update c [k o] (fnil inc 0))) counts rs)
                     (into examples (for [[k o ob nb] rs :when (and (= o :wrong) (#{:same :moved :edited} k) (< (count examples) 40))]
                                      {:path path :kind k :outcome o :old (:content ob) :new (:content nb)}))))
            (recur more n counts examples)))))))

(defn- report [{:keys [pairs counts examples]}]
  (println "version pairs scored:" pairs)
  (doseq [k [:same :moved :edited :rewritten]
          :let [c (fn [o] (get counts [k o] 0))
                total (+ (c :kept) (c :lost) (c :wrong))]
          :when (pos? total)]
    (printf "  %-7s %7d  kept %6.2f%%  lost %d  wrong %d%n" (name k) total
            (* 100.0 (/ (c :kept) total)) (c :lost) (c :wrong)))
  (doseq [{:keys [path kind outcome old new]} examples]
    (println "\n" (name outcome) (name kind) path)
    (println "   old:" (subs old 0 (min 160 (count old))))
    (println "   new:" (subs new 0 (min 160 (count new)))))
  (flush))

(defn -main [repo & args]
  (let [opts (into {} (for [[k v] (partition 2 args)]
                        (case k
                          "--gap" [:gap (parse-long v)]
                          "--since" [:since v]
                          "--max-pairs" [:max-pairs (parse-long v)])))]
    (report (replay repo opts))
    (shutdown-agents)
    (System/exit 0)))
