(ns hyperphor.thirdmind.ids-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ids :as ids]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.watch :as watch]
            [hyperphor.tunamelt.core :as t]))

(def realm "g")
(def ^:dynamic *dir* nil)
(def ^:dynamic *db* nil)

(use-fixtures :each
  (fn [f]
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "thirdmind-ids" (make-array java.nio.file.attribute.FileAttribute 0)))
          db  (str (java.io.File/createTempFile "thirdmind-ids" ".db"))]
      (try
        (doseq [^java.io.File src (file-seq (io/file (io/resource "graph"))) :when (.isFile src)]
          (let [dest (io/file dir (str (.relativize (.toPath (io/file (io/resource "graph"))) (.toPath src))))]
            (io/make-parents dest)
            (io/copy src dest)))
        (binding [*dir* (str dir) *db* db] (f))
        (finally
          (doseq [c (reverse (file-seq dir))] (.delete ^java.io.File c))
          (core/delete-store-files! db))))))

(defn- write! [rel content]
  (let [f (io/file *dir* rel)]
    (spit f content)
    (.setLastModified f (- (System/currentTimeMillis) 1000 (rand-int 100000)))))

(defn- tmids
  "{content tmid} for blocks of the page from file rel."
  [rel]
  (let [puri (:page (t/get (ingest/file-uri realm rel)))]
    (into {} (for [{o :obj} (t/find "block" {:attrs {:page puri}})] [(:content o) (:tmid o)]))))

(defn- all-tmids [] (into {} (for [{:keys [uri obj]} (t/find "block" {:realm realm})] [uri (:tmid obj)])))

(defn- rebuild! [] (core/fresh-store! *db* realm) (ingest/ingest! *dir* realm))

(deftest survive-rebuild
  (rebuild!)
  (let [before (all-tmids)]
    (is (every? some? (vals before)))
    (is (apply distinct? (vals before)))
    (rebuild!)
    (is (= before (all-tmids)))))

(deftest edits
  (write! "pages/E.md" "- alpha block one\n- the quick brown fox\n")
  (rebuild!)
  (let [before (tmids "pages/E.md")]
    (write! "pages/E.md" "- a new first block\n- alpha block one\n- the quick brown fox jumps\n")
    (ingest/update-file! *dir* realm "pages/E.md")
    (let [after (tmids "pages/E.md")]
      (is (= (before "alpha block one") (after "alpha block one")))
      (is (= (before "the quick brown fox") (after "the quick brown fox jumps")))
      (is (not (contains? (set (vals before)) (after "a new first block")))))
    (testing "a title:: change moves the page but keeps the ids"
      (write! "pages/E.md" "title:: Renamed E\n\n- a new first block\n- alpha block one\n- the quick brown fox jumps\n")
      (let [mid (tmids "pages/E.md")]
        (ingest/update-file! *dir* realm "pages/E.md")
        (is (= "Renamed E" (:name (t/get (:page (t/get (ingest/file-uri realm "pages/E.md")))))))
        (is (= (vals (sort mid)) (vals (sort (tmids "pages/E.md")))))))))

(deftest colliding-page-names
  (write! "pages/X.md" "- block of x file\n")
  (write! "pages/Other.md" "title:: x\n\n- block of other file\n")
  (rebuild!)
  (let [x (:blocks (ids/record realm "pages/X.md"))
        o (:blocks (ids/record realm "pages/Other.md"))]
    (is (= ["block of x file"] (map second x)))
    (is (= ["block of other file"] (map second o)))
    (rebuild!)
    (is (= x (:blocks (ids/record realm "pages/X.md"))))))

(deftest renamed-file
  (write! "pages/Old Name.md" "- a block worth keeping\n- \n")
  (let [cfg   {:dir *dir* :realm realm :db *db* :handlers {}}
        known (do (t/configure! {:store *db* :realm realm}) (watch/init! cfg))
        id    ((tmids "pages/Old Name.md") "a block worth keeping")]
    (io/delete-file (io/file *dir* "pages/Old Name.md"))
    (write! "pages/New Name.md" "- a block worth keeping\n- \n")
    (watch/sync! cfg known)
    (is (= id ((tmids "pages/New Name.md") "a block worth keeping")))
    (testing "and across a rebuild while not watching"
      (io/delete-file (io/file *dir* "pages/New Name.md"))
      (write! "pages/Third.md" "- a block worth keeping\n")
      (rebuild!)
      (is (= id ((tmids "pages/Third.md") "a block worth keeping"))))))
