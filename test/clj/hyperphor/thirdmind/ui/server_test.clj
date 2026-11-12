(ns hyperphor.thirdmind.ui.server-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.ui.server :as server]))

(def realm "g")

(use-fixtures :once
  (fn [f]
    (let [db (str (java.io.File/createTempFile "thirdmind-ui" ".db"))]
      (try (core/fresh-store! db realm)
           (ingest/ingest! (.getPath (io/resource "graph")) realm)
           (f)
           (finally (doseq [s ["" "-wal" "-shm"]] (.delete (io/file (str db s)))))))))

(defn- get* [uri & [qs]] ((server/handler realm) {:uri uri :query-string qs}))
(defn- body [uri & [qs]] (:body (get* uri qs)))

(deftest pages-test
  (testing "index lists pages and journals"
    (let [b (body "/")]
      (is (str/includes? b "href=\"/page/eliza\">ELIZA</a>"))
      (is (str/includes? b "Jul 5th, 2013"))
      (is (not (str/includes? b ">book</a>")) "stubs not listed")))

  (testing "page: outline, markers, collapsed, props, backlinks"
    (let [b (body "/page/computer%20power%20and%20human%20reason")]
      (is (str/includes? b "<h1>Computer Power and Human Reason"))
      (is (str/includes? b "<span class=\"marker todo\">TODO</span>"))
      (is (str/includes? b "<details><summary>"))
      (is (not (str/includes? b "<dt>created-at</dt>")) "timestamps hidden")
      (is (str/includes? b "linked references"))
      (is (str/includes? b "<h3><a href=\"/page/eliza\">ELIZA</a></h3>") "backlink via alias")))

  (testing "alias redirects; stub page renders with backlinks"
    (is (= {"Location" "/page/computer%20power%20and%20human%20reason"} (:headers (get* "/page/weizenbaum"))))
    (let [b (body "/page/book")]
      (is (str/includes? b "(no page)"))
      (is (str/includes? b "linked references"))))

  (testing "block page and block refs"
    (is (str/includes? (body "/block/abc-123") "father of"))
    (is (str/includes? (body "/page/computer%20power%20and%20human%20reason") "href=\"/block/abc-123\"")))

  (testing "search and 404"
    (is (str/includes? (body "/search" "q=weizenbaum") "<mark>"))
    (is (= 404 (:status (get* "/page/nope"))))
    (is (= 200 (:status (get* "/static/thirdmind.css"))))))
