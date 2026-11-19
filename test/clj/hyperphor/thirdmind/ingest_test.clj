(ns hyperphor.thirdmind.ingest-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.parse :as parse]
            [hyperphor.tunamelt.core :as t]))

(def graph-dir (.getPath (io/resource "graph")))
(def realm "g")

(def ^:dynamic *report* nil)

(defn- graph-fixture [f]
  (let [file (java.io.File/createTempFile "thirdmind-test" ".db")]
    (try
      (core/fresh-store! (str file) realm)
      (binding [*report* (ingest/ingest! graph-dir realm)] (f))
      (finally
        (core/delete-store-files! file)))))

(use-fixtures :once graph-fixture)

(defn- page [name] (ingest/page-uri realm (ingest/page-key name)))
(defn- block [id] (t/get (ingest/block-uri realm id)))
(defn- cphr-block [path] (block (str (ingest/page-key "Computer Power and Human Reason") "~" path)))

(deftest parse-units-test
  (is (= [{"title" "__Erlebnis__" "alias" "Lived"} ["" "- body"]]
         (parse/split-front-matter ["---" "title: __Erlebnis__" "Alias: Lived" "---" "" "- body"])))
  (is (= [{} ["--- not front matter" "- x"]] (parse/split-front-matter ["--- not front matter" "- x"])))
  (is (= [{} ["---" "- never closed"]] (parse/split-front-matter ["---" "- never closed"])))
  (is (= {:name "Jul 5th, 2013" :day 20130705} (parse/journal-info "2013_07_05.md")))
  (is (= "Nov 22nd, 2021" (:name (parse/journal-info "2021_11_22.md"))))
  (is (= "Jun 11th, 2020" (:name (parse/journal-info "2020_06_11.md"))))
  (is (= "a/b: c" (parse/file-page-name "a%2Fb%3A c.md" false)))
  (is (= "__Erlebnis__" (parse/file-page-name "__Erlebnis__.md" false)))
  (is (= {:pages ["x y" "tag" "t2"] :tags ["x y" "tag" "t2"] :block-refs []}
         (parse/extract-refs "#[[x y]] #tag, and #t2. not#this http://a.b/#frag #+BEGIN_QUOTE"))))

(deftest report-test
  (is (= 6 (:files *report*) (:file-pages *report*)))
  (is (empty? (:name-collisions *report*)))
  (is (= [["missing-id"]] (map rest (:unresolved-block-refs *report*))))
  (is (empty? (:duplicate-ids *report*)))
  (is (= #{"book" "ai history" "to read" "real-tag" "askclaude"}
         (set (map (comp :name :obj) (t/find "page" {:attrs {:stub true}}))))))

(deftest pages-test
  (let [p (t/get (page "Computer Power and Human Reason"))]
    (is (= ["Weizenbaum" "JW"] (:alias p)))
    (is (= [(page "book") (page "AI history")] (:tags p)))
    (is (= "pages/Computer Power and Human Reason.md" (:file p))))
  (is (= "Acid Horizon/Transcript" (:name (t/get (page "Acid Horizon/Transcript")))))
  (let [j (t/get (page "Jul 5th, 2013"))]
    (is (true? (:journal j)))
    (is (= 20130705 (:journal-day j)))))

(deftest blocks-test
  (testing "explicit id, properties, refs"
    (let [b (block "abc-123")]
      (is (= "#book by Joseph Weizenbaum, father of [[ELIZA]]." (:content b)))
      (is (= 1625245843522 (:prop/created-at b)))
      (is (= #{(page "book") (page "ELIZA")} (set (:refs b))))
      (is (= [(page "book")] (:tags b)))
      (is (= (page "Computer Power and Human Reason") (:parent b) (:page b)))))
  (testing "nesting with tabs, marker, block refs"
    (let [todo  (cphr-block "1")
          child (cphr-block "1.0")
          grand (cphr-block "1.0.0")]
      (is (= "TODO" (:marker todo)))
      (is (= [(page "to read")] (:tags todo)))
      (is (= (ingest/block-uri realm (str (ingest/page-key "Computer Power and Human Reason") "~1"))
             (:parent child)))
      (is (true? (:prop/collapsed child)))
      (is (= [(ingest/block-uri realm "abc-123")] (:block-refs child)))
      (is (= [(page "ELIZA")] (:refs grand)) "case-insensitive page refs")
      (is (= 1 (:order (cphr-block "1.1"))))))
  (testing "code is content, not blocks or refs"
    (let [code (cphr-block "2")]
      (is (re-find #"not a block \[\[NotAPage\]\]" (:content code)))
      (is (nil? (:refs code)))
      (is (nil? (cphr-block "2.0")))))
  (is (= [(page "real-tag")] (:tags (cphr-block "3"))))
  (testing "alias and journal refs resolve"
    (let [[b] (t/find "block" {:attrs {:page (page "ELIZA")}})]
      (is (= #{(page "Computer Power and Human Reason") (page "Jul 5th, 2013")} (set (:refs (:obj b)))))))
  (testing "page with no bullets is one block"
    (is (= 1 (count (t/find "block" {:attrs {:page (page "Plain")}}))))))

(deftest backlinks-and-outline-test
  (is (= 3 (count (t/referrers (page "ELIZA") {:attr :refs}))))
  (is (= ["- journal entry about [[Computer Power and Human Reason]]"
          "  - nested with two spaces"]
         (core/outline (page "Jul 5th, 2013")))))
