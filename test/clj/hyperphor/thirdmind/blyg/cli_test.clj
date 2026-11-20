(ns hyperphor.thirdmind.blyg.cli-test
  (:require [clojure.test :refer [deftest is]]
            [hyperphor.thirdmind.blyg.cli :as cli]))

(deftest goddinpotty-settings
  (let [f (java.io.File/createTempFile "goddinpotty" ".edn")]
    (try
      (spit f (str "#merge* [#include \"default-config.edn\"\n {:exit-tags [\"Private\" \"x\" ; why\n"
                   "  \"y\"\n  #_ \"skipped\" ; note\n  #_ \"also\"\n  ]\n"
                   "  :real-base-url \"https://s.example/\" ; c\n  :output-dir \"/out\"}]"))
      (is (= {:base-url "https://s.example/" :site-dir "/out" :state-file nil :exit-tags ["Private" "x" "y"]}
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

(deftest push-is-scoped
  (let [tmp    #(.toFile (java.nio.file.Files/createTempDirectory % (make-array java.nio.file.attribute.FileAttribute 0)))
        remote (tmp "blyg-remote") repo (tmp "blyg-repo")
        sh     (fn [d & args] (apply clojure.java.shell/sh (concat args [:dir (str d)])))
        f      #(clojure.java.io/file repo %)]
    (try
      (sh remote "git" "init" "-q" "--bare")
      (sh repo "git" "init" "-q")
      (sh repo "git" "config" "user.email" "t@example.com") (sh repo "git" "config" "user.name" "t")
      (clojure.java.io/make-parents (f "site/blyg/x.json")) (spit (f "site/blyg/x.json") "1") (spit (f "other.txt") "a")
      (sh repo "git" "add" ".") (sh repo "git" "commit" "-qm" "init")
      (sh repo "git" "remote" "add" "website" (str remote))
      (sh repo "git" "push" "-q" "website" "HEAD:refs/heads/main") (sh repo "git" "branch" "-u" "website/main")
      (spit (f "site/blyg/x.json") "2") (spit (f "site/blyg/y.json") "new") (spit (f "other.txt") "b")
      (sh repo "git" "add" "other.txt")
      (is (= :pushed (cli/push! repo "site/blyg" "website")))
      (is (= ["site/blyg/x.json" "site/blyg/y.json"]
             (clojure.string/split-lines (:out (sh repo "git" "show" "--name-only" "--format=" "HEAD")))))
      (is (= "M  other.txt\n" (:out (sh repo "git" "status" "--short"))) "other staged work left staged, uncommitted")
      (is (= (:out (sh repo "git" "rev-parse" "HEAD")) (:out (sh remote "git" "rev-parse" "main"))))
      (is (= :unchanged (cli/push! repo "site/blyg" "website")))
      (finally (doseq [d [remote repo], x (reverse (file-seq d))] (.delete ^java.io.File x))))))
