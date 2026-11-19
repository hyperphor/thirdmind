(ns hyperphor.thirdmind.watch-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.watch :as watch]
            [hyperphor.tunamelt.core :as t]))

(def realm "g")
(def ^:dynamic *cfg* nil)
(def events (atom []))

(defn- copy-dir [from to]
  (doseq [^java.io.File f (file-seq (io/file from)) :when (.isFile f)]
    (let [dest (io/file to (str (.relativize (.toPath (io/file from)) (.toPath f))))]
      (io/make-parents dest)
      (io/copy f dest))))

(defn- delete-tree [^java.io.File f]
  (doseq [c (reverse (file-seq f))] (.delete ^java.io.File c)))

(defn- watch-fixture [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "thirdmind-graph" (make-array java.nio.file.attribute.FileAttribute 0)))
        db  (java.io.File/createTempFile "thirdmind-watch" ".db")]
    (try
      (copy-dir (io/resource "graph") dir)
      (reset! events [])
      (t/configure! {:store (str db) :realm realm})
      (binding [*cfg* {:dir (str dir) :realm realm :db (str db)
                       :handlers {"blyg" #(swap! events conj [(:file %) (get-in % [:obj :content])])}}]
        (f))
      (finally
        (delete-tree dir)
        (core/delete-store-files! db)))))

(use-fixtures :each watch-fixture)

(defn- write! [rel content]
  (let [f (io/file (:dir *cfg*) rel)]
    (spit f content)
    ;; distinct, already-settled stamp
    (.setLastModified f (- (System/currentTimeMillis) 1000 (rand-int 100000)))))

(defn- page [name] (ingest/page-uri realm (ingest/page-key name)))
(defn- contents [name] (set (map (comp :content :obj) (t/find "block" {:attrs {:page (page name)}}))))
(defn- sync-and-events! [known]
  (reset! events [])
  [(watch/sync! *cfg* known) @events])

(deftest watch-test
  (let [known (watch/init! *cfg*)]
    (is (empty? @events) "initial build fires nothing")
    (is (contains? known "pages/Plain.md"))
    (is (= known (watch/sync! *cfg* known)) "no changes, no work")

    (testing "new page with a tagged block"
      (write! "pages/New.md" "- hello #blyg\n- plain")
      (let [[k evs] (sync-and-events! known)]
        (is (= [["pages/New.md" "hello #blyg"]] evs))
        (is (= #{"hello #blyg" "plain"} (contents "New")))
        (is (= #{(ingest/block-uri realm "new~0")} (set (map :uri (t/search "hello")))))

        (testing "inserting a block above doesn't re-fire"
          (write! "pages/New.md" "- intro\n- hello #blyg\n- plain")
          (let [[k evs] (sync-and-events! k)]
            (is (empty? evs))
            (is (= #{"intro" "hello #blyg" "plain"} (contents "New")))

            (testing "tagging an existing block fires"
              (write! "pages/Plain.md" "Just some text, about [[ELIZA]].\n\n- title-less page #blyg")
              (let [[k evs] (sync-and-events! k)]
                (is (= [["pages/Plain.md" "title-less page #blyg"]] evs))

                (testing "[[link]] counts as a tag"
                  (write! "pages/Linked.md" "- see [[blyg]]")
                  (is (= [["pages/Linked.md" "see [[blyg]]"]] (second (sync-and-events! k)))))

                (testing "deleting files"
                  (.delete (io/file (:dir *cfg*) "pages/New.md"))
                  (.delete (io/file (:dir *cfg*) "pages/ELIZA.md"))
                  (let [[k _] (sync-and-events! k)]
                    (is (not (contains? k "pages/New.md")))
                    (is (nil? (t/get (page "New"))) "unreferenced page removed")
                    (is (empty? (contents "New")))
                    (is (= {:name "ELIZA" :stub true} (t/get (page "ELIZA"))) "referenced page reverts to stub")
                    (is (nil? (t/get (ingest/file-uri realm "pages/ELIZA.md"))))))))))))))

(deftest restart-test
  (watch/init! *cfg*)
  (write! "pages/Offline.md" "- written while stopped #blyg")
  (reset! events [])
  (let [known (watch/init! *cfg*)]
    (is (= [["pages/Offline.md" "written while stopped #blyg"]] @events) "restart reconciles and fires")
    (is (contains? known "pages/Offline.md"))))

(deftest settle-test
  (let [known (watch/init! *cfg*)
        f     (io/file (:dir *cfg*) "pages/Fresh.md")]
    (spit f "- just saved #blyg")
    (let [k (watch/sync! (assoc *cfg* :settle-ms 60000) known)]
      (is (not (contains? k "pages/Fresh.md")) "recently modified file is deferred")
      (.setLastModified f (- (System/currentTimeMillis) 120000))
      (is (contains? (watch/sync! (assoc *cfg* :settle-ms 60000) k) "pages/Fresh.md")))))

(deftest loop-test
  (let [stop (watch/start! (assoc *cfg* :interval-ms 50 :settle-ms 0))]
    (try
      (write! "pages/Looped.md" "- via the loop #blyg")
      (loop [n 0] (when (and (empty? @events) (< n 100)) (Thread/sleep 50) (recur (inc n))))
      (is (= [["pages/Looped.md" "via the loop #blyg"]] @events))
      (finally (stop)))))
