(ns hyperphor.thirdmind.config
  "Per-graph ThirdMind config: an EDN file such as demo/config.edn.
  {:graph dir, :db path, :realm, :port, :write?, :blygger {...}}; :realm and :db default from :graph."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hyperphor.thirdmind.core :as core]))

(defn config-file? [s] (boolean (and s (str/ends-with? s ".edn"))))

(defn load-config
  "The config in file, with defaults filled in."
  [file]
  (let [c     (edn/read-string (slurp file))
        realm (or (:realm c) (core/realm-for (:graph c)))]
    (merge {:realm realm :db (str realm ".db")} c {:file (str file)})))

(defn default-file
  "$THIRDMIND_CONFIG, else the one .edn file in config/, else nil."
  []
  (or (System/getenv "THIRDMIND_CONFIG")
      (let [fs (filter #(str/ends-with? (.getName ^java.io.File %) ".edn") (.listFiles (io/file "config")))]
        (when (= 1 (count fs)) (str (first fs))))))

(defn from-args
  "[config rest-args]: a leading .edn arg is the config file; with no positional args at all, the
  default one (so naming another graph never picks up this one's settings, eg :write?)."
  [args]
  (cond (config-file? (first args))                       [(load-config (first args)) (rest args)]
        (every? #(str/starts-with? % "--") args)           [(some-> (default-file) load-config) args]
        :else                                              [nil args]))
