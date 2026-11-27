(ns hyperphor.thirdmind.graph-write
  "The one path by which ThirdMind writes to the source graph: wait for quiet, re-parse,
  locate the block by content, edit lines, write atomically."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hyperphor.thirdmind.parse :as parse])
  (:import [java.nio.file Files StandardCopyOption CopyOption]))

;;; Logseq saves as you type, so a write waits until the file has been still for quiet-ms.

(defn- wait-quiet!
  "Block until f is quiet-ms old (true), or give up after max-wait-ms (false)."
  [^java.io.File f quiet-ms max-wait-ms]
  (let [deadline (+ (System/currentTimeMillis) max-wait-ms)]
    (loop []
      (let [age (- (System/currentTimeMillis) (.lastModified f))]
        (cond (>= age quiet-ms) true
              (> (System/currentTimeMillis) deadline) false
              :else (do (Thread/sleep (long (min 1000 (- quiet-ms age)))) (recur)))))))

(defn- write-atomically! [^java.io.File f ^String s]
  (let [tmp (io/file (.getParentFile f) (str "." (.getName f) ".thirdmind-tmp"))]
    (spit tmp s)
    (Files/move (.toPath tmp) (.toPath f)
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn find-block
  "The parsed block whose content is exactly content, if exactly one."
  [blocks content]
  (let [hits (filter #(= content (:content %)) blocks)]
    (when (= 1 (count hits)) (first hits))))

(defn block-end
  "Line index just past block b (before the next bullet, trailing blank lines excluded)."
  [lines blocks b]
  (let [next-start (or (some #(when (and (:start %) (> (:start %) (:start b))) (:start %)) blocks)
                       (count lines))]
    (loop [end next-start]
      (if (and (> end (inc (:start b))) (str/blank? (nth lines (dec end)))) (recur (dec end)) end))))

(defn children?
  "Does block b have child blocks?"
  [blocks b]
  (let [p (:path b)]
    (boolean (some #(and (= (inc (count p)) (count (:path %))) (= p (pop (:path %)))) blocks))))

(defn indent-unit
  "The file's indent unit: tab unless its nested bullets use spaces."
  [lines]
  (or (some #(when-let [[_ ws] (re-matches #"^([ \t]+)- ?.*" %)] (if (str/includes? ws "\t") "\t" ws)) lines)
      "\t"))

(defn bullet-indent [lines b] (second (re-matches #"^([ \t]*).*" (nth lines (:start b)))))

(defn snapshot
  "{:lines :blocks :name :stamp} of graph file rel once it has been still for quiet-ms, else nil."
  [{:keys [dir quiet-ms max-wait-ms] :or {quiet-ms 8000 max-wait-ms 120000}} rel]
  (let [f (io/file dir rel)]
    (when (and (.isFile f) (wait-quiet! f quiet-ms max-wait-ms))
      (let [stamp  (.lastModified f)
            text   (slurp f)
            parsed (parse/parse-file f (str/starts-with? rel "journals/") rel)]
        {:stamp  stamp
         :text   text
         :name   (:name parsed)
         :lines  (vec (str/split text #"\n" -1))   ; keeps a trailing "" for a final newline
         :blocks (:blocks parsed)}))))

(defn rewrite!
  "Rewrite graph file rel: (edit {:lines :blocks}) returns new lines, or nil to leave it.
  Waits for quiet-ms of stillness and refuses if the file changes meanwhile. Returns true if written."
  [cfg rel edit]
  (when-let [{:keys [stamp text] :as snap} (snapshot cfg rel)]
    (when-let [new-lines (edit snap)]
      (let [f   (io/file (:dir cfg) rel)
            out (str/join "\n" new-lines)]
        (when (and (not= out text) (= stamp (.lastModified f)))
          (write-atomically! f out)
          true)))))
