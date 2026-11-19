(ns hyperphor.thirdmind.ui.server-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.times :as times]
            [hyperphor.tunamelt.core :as t]
            [hyperphor.thirdmind.ui.server :as server]))

(def realm "g")

(use-fixtures :once
  (fn [f]
    (let [db (str (java.io.File/createTempFile "thirdmind-ui" ".db"))]
      (try (core/fresh-store! db realm)
           (ingest/ingest! (.getPath (io/resource "graph")) realm)
           ;; the fixture is in this repo, so it has git history to time from
           (times/update-file-times! (.getPath (io/resource "graph")) realm "pages/ELIZA.md")
           (f)
           (finally (core/delete-store-files! db))))))

(def handler (delay (server/handler {:realm realm :dir (.getPath (io/resource "graph")) :graph "graph"})))
(defn- get* [uri & [qs]] (@handler {:uri uri :query-string qs}))
(defn- body [uri & [qs]] (:body (get* uri qs)))

(deftest pages-test
  (testing "index grid rows: pages, journals, referenced stubs, with ref counts"
    (is (str/includes? (body "/") "id=\"index-grid\""))
    (let [rows (into {} (map (juxt :title identity)) (json/read-str (body "/index.json") :key-fn keyword))]
      (is (= {:title "ELIZA" :html "ELIZA" :url "/page/eliza" :kind "page" :blocks 1 :refs 3}
             (dissoc (rows "ELIZA") :modified)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (:modified (rows "ELIZA"))))
      (is (= "journal" (:kind (rows "Jul 5th, 2013"))))
      (is (= "stub" (:kind (rows "book"))))))

  (testing "page: outline, markers, collapsed, props, backlinks"
    (let [b (body "/page/computer%20power%20and%20human%20reason")]
      (is (str/includes? b "<h1>Computer Power and Human Reason"))
      (is (str/includes? b "href=\"logseq://graph/graph?page=Computer%20Power%20and%20Human%20Reason\""))
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

  (testing "front matter, emphasised title, empty blocks, #AskClaude, macros"
    (let [b (body "/page/__erlebnis__")]
      (is (str/includes? b "<title>Erlebnis</title>"))
      (is (str/includes? b "<h1><em>Erlebnis</em>"))
      (is (not (str/includes? b "title:")) "front matter not rendered")
      (is (not (str/includes? b "id=\"empty-one\"")) "empty block skipped")
      (is (re-find #"(?s)<details class=\"ask-claude\"><summary>.*?#AskClaude.*?</summary><div class=\"claude\"><ul" b))
      (is (str/includes? b "Short answer: <strong>partially</strong>"))
      (is (str/includes? b "youtube.com/embed/J3rNOAhonPw"))
      (is (str/includes? b "platform.twitter.com/widgets.js") "tweet script on pages with tweets"))
    (is (not (str/includes? (body "/page/eliza") "widgets.js")) "and not elsewhere"))

  (testing "times: block margin dates, page range, journal weekday"
    (let [b (body "/page/eliza")]
      (is (re-find #"<span class=\"page-time\" [^>]*>\w{3} \d+, \d{4}</span>" b) "page range from git")
      (is (not (str/includes? b "class=\"block-time\"")) "lone block's date repeats the page's, so hidden"))
    (let [ms  #(.toEpochMilli (java.time.Instant/parse (str % "T12:00:00Z")))
          blk #(ingest/block-uri realm (str (ingest/page-key "Plain") "~" %))
          _   (t/put (blk "-1") (assoc (t/get (blk "-1")) :created (ms "2021-06-01") :updated (ms "2021-06-02")))
          b   (body "/page/plain")]
      (is (str/includes? b ">Jun 1 – Jun 2, 2021</span>") "page range")
      (is (not (str/includes? b "class=\"block-time\"")) "block range is the page's, so hidden"))
    (let [ms  #(.toEpochMilli (java.time.Instant/parse (str % "T12:00:00Z")))
          blk #(ingest/block-uri realm (str (ingest/page-key "Computer Power and Human Reason") "~" %))]
      (doseq [[path from to] [["2" "2021-06-01" "2021-06-02"] ["3" "2023-08-03" "2023-08-03"]]]
        (t/put (blk path) (assoc (t/get (blk path)) :created (ms from) :updated (ms to))))
      (let [b (body "/page/computer%20power%20and%20human%20reason")]
        (is (str/includes? b ">Jun 1, 2021 – Aug 3, 2023</span>"))
        (is (str/includes? b ">Jun &apos;21</time>"))
        (is (str/includes? b ">Aug &apos;23</time>"))
        (is (str/includes? b "title=\"Created Jun 1, 2021 · updated Jun 2, 2021\""))))
    (is (not (str/includes? (body "/page/plain") "block-time")) "untimed pages show none")
    (is (str/includes? (body "/page/jul%205th%2C%202013") "<span class=\"weekday\">Friday</span>")))

  (testing "search and 404"
    (is (str/includes? (body "/search" "q=weizenbaum") "<mark>"))
    (is (= 404 (:status (get* "/page/nope"))))
    (is (= 200 (:status (get* "/static/thirdmind.css"))))
    (is (= 200 (:status (get* "/static/index.js")))))

  (testing "assets served from the graph, and nothing outside it"
    (let [r (get* "/assets/a%20note.txt")]
      (is (= 200 (:status r)))
      (is (= "asset fixture\n" (slurp (:body r)))))
    (is (= 404 (:status (get* "/assets/..%2Fpages%2FELIZA.md"))))
    (is (= 404 (:status (get* "/assets/nope.png"))))))
