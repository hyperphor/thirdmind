(ns hyperphor.thirdmind.config-test
  (:require [clojure.test :refer [deftest is]]
            [hyperphor.thirdmind.config :as config]))

(deftest config-args
  (let [f (java.io.File/createTempFile "thirdmind-cfg" ".edn")]
    (try
      (spit f "{:graph \"/x/My Graph\" :blygger {:mount \"m\"}}")
      (let [[c rest-args] (config/from-args [(str f) "--dry-run"])]
        (is (= ["my-graph" "my-graph.db" "m"] [(:realm c) (:db c) (get-in c [:blygger :mount])]))
        (is (= ["--dry-run"] rest-args)))
      (is (nil? (first (config/from-args ["/some/other/graph"]))) "a named graph never takes the default config")
      (finally (.delete f)))))
