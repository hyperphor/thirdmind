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

(deftest parity
  (let [dir    (.toFile (java.nio.file.Files/createTempDirectory "blyg-live" (make-array java.nio.file.attribute.FileAttribute 0)))
        state  (java.io.File/createTempFile "blyg-state" ".edn")
        origin "https://s.example/blyg/"
        opts   {:title "T" :author {:name "T" :url origin} :generator "g" :generator-url "u"}
        items  {"k" (hyperphor.thirdmind.blyg.wire/next-entry nil :fragment "hi" "<p>hi</p>" nil nil "2026-01-01T00:00:00Z")}]
    (try
      (spit state (pr-str {:items items}))
      (doseq [[p t] (hyperphor.thirdmind.blyg.wire/surface origin opts items)]
        (clojure.java.io/make-parents (clojure.java.io/file dir p))
        (spit (clojure.java.io/file dir p) t))
      (is (= [] (:differ (cli/parity state dir))))
      (spit (clojure.java.io/file dir "feed.xml") "changed")
      (is (= ["feed.xml"] (:differ (cli/parity state dir))))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))
        (.delete state)))))
