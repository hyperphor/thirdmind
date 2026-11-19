(ns hyperphor.thirdmind.blyg.cli-test
  (:require [clojure.test :refer [deftest is]]
            [hyperphor.thirdmind.blyg.cli :as cli]))

(deftest goddinpotty-settings
  (let [f (java.io.File/createTempFile "goddinpotty" ".edn")]
    (try
      (spit f (str "#merge* [#include \"default-config.edn\"\n {:exit-tags [\"Private\" \"x\" ; why\n"
                   "  \"y\"\n  #_ \"skipped\" ; note\n  #_ \"also\"\n  ]\n"
                   "  :real-base-url \"https://s.example/\" ; c\n  :output-dir \"/out\"}]"))
      (is (= {:base-url "https://s.example/" :site-dir "/out" :exit-tags ["Private" "x" "y"]}
             (cli/goddinpotty-settings f)))
      (finally (.delete f)))))
