(ns hyperphor.thirdmind.site.demo-test
  "The demo graph publishes what its pages say it does."
  (:require [clojure.test :refer [deftest is]]
            [hyperphor.thirdmind.config :as config]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.site.cli :as cli]
            [hyperphor.thirdmind.site.include :as include]))

(deftest demo-published-set
  (let [cfg (config/load-config "demo/config.edn")
        db  (java.io.File/createTempFile "demo" ".db")]
    (try
      (core/fresh-store! (str db) (:realm cfg))
      (ingest/ingest! (:graph cfg) (:realm cfg))
      (let [{:keys [pages hidden]} (include/published (:realm cfg) (cli/settings cfg))
            names (set (map #(java.net.URLDecoder/decode (subs % (inc (.lastIndexOf ^String % "/"))) "UTF-8") pages))]
        (is (every? names ["welcome" "privacy" "formatting" "graphs" "recipes/bread" "open page"
                           "reached from open page" "featured page" "publishing rules"]))
        (is (not-any? names ["secret plans" "hidden behind private" "journal only" "unlinked page"]))
        (is (= 2 (count (filter #(.contains ^String % "/block/privacy") hidden)))
            "the #Private block and its parent"))
      (finally (core/delete-store-files! db)))))
