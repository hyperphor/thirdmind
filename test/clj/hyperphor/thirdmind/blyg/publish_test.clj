(ns hyperphor.thirdmind.blyg.publish-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.blyg.cli :as cli]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.blyg.migrate :as m]
            [hyperphor.thirdmind.blyg.pages :as pages]
            [hyperphor.thirdmind.blyg.publish :as p]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]))

(def realm "g")
(def ^:dynamic *dir* nil)
(def ^:dynamic *db* nil)

(def page (str "- A thread post #blyg\n\t- the first child point\n\t- the second child point\n\t- \n"
               "- A solo fragment post #blyg\n"))

(use-fixtures :each
  (fn [f]
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "thirdmind-pub" (make-array java.nio.file.attribute.FileAttribute 0)))
          db  (str (java.io.File/createTempFile "thirdmind-pub" ".db"))]
      (try
        (io/make-parents (io/file dir "pages/B.md"))
        (spit (io/file dir "pages/B.md") page)
        (binding [*dir* (str dir) *db* db] (f))
        (finally
          (doseq [x (reverse (file-seq dir))] (.delete ^java.io.File x))
          (core/delete-store-files! db))))))

(defn- ingest! [] (core/fresh-store! *db* realm) (ingest/ingest! *dir* realm))
(defn- ctx [] (c/ctx realm {:base-url "https://s.example" :published #{"B"} :exit-tags []}))
(defn- counts [ch] (into {} (for [[k v] ch] [k (count v)])))
(def zero {:new 0 :bumped 0 :withdrawn 0 :kind 0 :thread-child 0 :title 0})

(defn- legacy
  "A goddinpotty-like state for the current graph: keyed by fake uuids, rendered a little differently."
  []
  (let [{:keys [state]} (p/plan (ctx) {:items {}} "2026-01-01T00:00:00Z")]
    {:items (into {} (map-indexed (fn [i [_ e]]
                                    [(format "6a000000-0000-0000-0000-%012d" i)
                                     (-> e (dissoc :tm-base) (update :content-md str/replace "child" "*child*"))])
                                  (:items state)))}))

(deftest publishing
  (ingest!)
  (let [{s1 :state r1 :report} (p/plan (ctx) {:items {}} "2026-01-01T00:00:00Z")]
    (is (= [1 2] [(count (filter #(= :thread (:kind %)) (vals (:items s1)))) (count (filter :thread-child? (vals (:items s1))))])
        "one thread, its two non-empty children promoted; the empty child left out")
    (is (= 4 (count (:new r1))))
    (is (= zero (counts (p/changes s1 (:state (p/plan (ctx) s1 "later"))))) "a second run changes nothing")
    (testing "an edit bumps the child and, through its transclusion, the thread"
      (spit (io/file *dir* "pages/B.md") (str/replace page "first child point" "first child point, edited"))
      (ingest!)
      (is (= {:bumped 2} (select-keys (counts (p/changes s1 (:state (p/plan (ctx) s1 "later")))) [:bumped]))))))

(deftest migrating
  (ingest!)
  (let [old (legacy)
        {:keys [state report]} (m/import-state (ctx) old)]
    (is (= 0 (:unmatched report)))
    (is (every? #(re-matches #"[0-9a-z]{12}" %) (keys (:items state))) "keyed by tmid")
    (is (every? :legacy-uuid (vals (:items state))))
    (let [s2 (:state (p/plan (ctx) state "later"))]
      (is (= zero (counts (p/changes state s2))) "rendering differences alone don't bump")
      (is (= zero (counts (p/changes s2 (:state (p/plan (ctx) s2 "later2"))))))
      (testing "untagging leaves an item alone unless withdrawing"
        (spit (io/file *dir* "pages/B.md") (str/replace page "A solo fragment post #blyg" "A solo fragment post"))
        (ingest!)
        (let [{s3 :state r3 :report} (p/plan (ctx) s2 "later3")]
          (is (= 1 (count (:untagged r3))))
          (is (= zero (counts (p/changes s2 s3))))
          (is (= 1 (:withdrawn (counts (p/changes s2 (:state (p/plan (ctx) s2 "later3" :withdraw? true))))))))))))

(deftest moved-block-keeps-its-item
  (ingest!)
  (let [s1 (:state (p/plan (ctx) {:items {}} "t1"))
        id (fn [s] (some #(when (str/includes? (:content-md %) "A solo") (:blyg-id %)) (vals (:items s))))]
    ;; to another page: a new tmid, so only re-keying keeps the item
    (spit (io/file *dir* "pages/B.md") (str/replace page "- A solo fragment post #blyg\n" ""))
    (spit (io/file *dir* "pages/Other.md") "- A solo fragment post #blyg, now elsewhere\n")
    (ingest!)
    (let [{s2 :state r2 :report} (p/plan (ctx) s1 "t2")]
      (is (= 1 (:rekeyed r2)))
      (is (some? (id s1)))
      (is (= (id s1) (id s2)))
      (is (= {:new 0 :bumped 1} (select-keys (counts (p/changes s1 s2)) [:new :bumped]))))))

(deftest duplicate-guard
  (ingest!)
  (let [old (update (legacy) :items #(into {} (map (fn [[k e]] [k (assoc e :content-md (str "Totally different " k))])) %))
        {:keys [state]} (m/import-state (ctx) old)
        _  (spit (io/file *dir* "pages/B.md") (str page "- Totally different 6a000000-0000-0000-0000-000000000000 #blyg\n"))
        _  (ingest!)
        {:keys [report]} (p/plan (ctx) state "later")]
    (is (seq (:possible-duplicates report)) "a new item resembling an unmatched old one is flagged")))

(deftest page-templates
  (let [tpl (str "<html><head><title>Site: old</title><meta content=\"old\" name=\"og:url\" /><meta content=\"old\" name=\"og:title\" />"
                 "<link href=\"https://s/blyg/items/00000000000000000000000000.json\" rel=\"alternate\" /></head><body>"
                 "<div class=\"col-lg-7\"><div class=\"main\"><h1>old</h1>old body</div></div></div></div><footer>f</footer></body></html>")
        out (pages/fill tpl {:title "New & title" :h1 "H" :body [:p "body"] :item-id "abcdefghjkmnpqrstvwxyz0123" :url "https://s/f/x/"})]
    (is (str/includes? out "<meta content=\"https://s/f/x/\" name=\"og:url\""))
    (is (str/includes? out "<title>Site: New &amp; title</title>"))
    (is (str/includes? out "<meta content=\"New &amp; title\" name=\"og:title\""))
    (is (str/includes? out "<div class=\"main\"><h1>H</h1><p>body</p></div></div></div></div><footer>"))
    (is (str/includes? out "items/abcdefghjkmnpqrstvwxyz0123.json\" rel=\"alternate\""))
    (is (not (str/includes? out "old body")))))

(deftest diffing
  (let [mk (fn [m] (let [d (.toFile (java.nio.file.Files/createTempDirectory "blyg-d" (make-array java.nio.file.attribute.FileAttribute 0)))]
                     (cli/write-files! d m) d))
        a (mk {"items/x.json" "{\"a\":1}" "f/x/index.html" "<p>1</p>" "items/n.json" "{}"})
        b (mk {"items/x.json" "{ \"a\" : 1 }" "f/x/index.html" "<p>2</p>" "items/gone.json" "{}"})]
    (try
      (is (= {:missing ["items/gone.json"] :extra ["items/n.json"] :differ []} (cli/diff-dirs a b)))
      (finally (doseq [d [a b], x (reverse (file-seq d))] (.delete ^java.io.File x))))))
