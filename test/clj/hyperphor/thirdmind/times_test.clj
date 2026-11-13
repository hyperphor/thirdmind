(ns hyperphor.thirdmind.times-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.times :as times]
            [hyperphor.tunamelt.core :as t]))

(def realm "g")

(defn- day-ms [d] (.toEpochMilli (.toInstant (java.time.OffsetDateTime/parse (str d "T12:00:00Z")))))

(defn- git! [dir date & args]
  (let [env (merge (into {} (System/getenv))
                   {"GIT_AUTHOR_DATE" (str date "T12:00:00Z") "GIT_COMMITTER_DATE" (str date "T12:00:00Z")})
        r   (apply sh/sh "git" "-c" "user.name=t" "-c" "user.email=t@example.com" "-c" "commit.gpgsign=false"
                   (concat args [:dir (str dir) :env env]))]
    (assert (zero? (:exit r)) (:err r))
    r))

(defn- write! [dir rel s] (doto (io/file dir rel) io/make-parents (spit s)))

(deftest parse-blame-test
  (let [sha "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"
        out (str sha " 1 1 2\nauthor x\nauthor-time 100\nfilename f\n\tline one\n"
                 sha " 2 2\n\tline two\n"
                 "0000000000000000000000000000000000000000 3 3 1\nauthor-time 999\n\tnew\n")]
    (is (= [100000 100000 42] (times/parse-blame out 42)))))

(deftest blame-times-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "thirdmind-times" (make-array java.nio.file.attribute.FileAttribute 0)))
        db  (java.io.File/createTempFile "thirdmind-times" ".db")]
    (try
      (git! dir "2021-10-30" "init" "-q")
      (write! dir "pages/P.md" (str "- old roam block\n  created-at:: 1600000000000\n  updated-at:: 1600000500000\n"
                                    "- first\n- second\n  continued\n"))
      (git! dir "2021-10-30" "add" ".")
      (git! dir "2021-10-30" "commit" "-q" "-m" "import")
      (write! dir "pages/P.md" (str "- old roam block\n  created-at:: 1600000000000\n  updated-at:: 1600000500000\n"
                                    "- first\n  id:: 650000aa-0000-0000-0000-000000000000\n- second\n  continued, edited\n"))
      (git! dir "2023-03-04" "commit" "-q" "-am" "edit")
      (write! dir "pages/P.md" (str "- old roam block\n  created-at:: 1600000000000\n  updated-at:: 1600000500000\n"
                                    "- first\n  id:: 650000aa-0000-0000-0000-000000000000\n- second\n  continued, edited\n"
                                    "- uncommitted\n"))
      (core/fresh-store! (str db) realm)
      (ingest/ingest! (str dir) realm)
      (times/update-file-times! (str dir) realm "pages/P.md")
      (let [b (fn [id] (select-keys (t/get (ingest/block-uri realm id)) [:created :updated]))]
        (testing "unchanged Roam-era block keeps its created-at/updated-at"
          (is (= {:created 1600000000000 :updated 1600000500000} (b "p~0"))))
        (testing "adding id:: isn't an edit"
          (is (= {:created (day-ms "2021-10-30") :updated (day-ms "2021-10-30")}
                 (b "650000aa-0000-0000-0000-000000000000"))))
        (testing "edited continuation line moves updated, not created"
          (is (= {:created (day-ms "2021-10-30") :updated (day-ms "2023-03-04")} (b "p~2"))))
        (testing "uncommitted lines get the file's mtime"
          (is (= (.lastModified (io/file dir "pages/P.md")) (:updated (b "p~3"))))))
      (testing "file record is marked timed; backfill skips it"
        (is (= 0 (times/backfill! (str dir) realm (fn [& _])))))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))
        (doseq [s ["" "-wal" "-shm"]] (.delete (io/file (str db s))))))))
