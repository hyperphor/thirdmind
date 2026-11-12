(ns hyperphor.thirdmind.ui.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.ui.render :as r]))

(def aliases {"weizenbaum" "computer%20power%20and%20human%20reason"})
(def stubs #{"book" "to%20read"})

(def ctx
  {:page  (fn [name] (let [k (ingest/page-key name), k (get aliases k k)]
                       {:key k :stub (contains? stubs k)}))
   :block {"abc-123" "#book by Joseph Weizenbaum, father of [[ELIZA]]."}})

(defn- html [s] (r/block-html ctx s))

(deftest refs-test
  (testing "wikilinks resolve case-insensitively and through aliases"
    (is (str/includes? (html "in [[eliza]]") "<a class=\"ref\" href=\"/page/eliza\">eliza</a>"))
    (is (str/includes? (html "[[Weizenbaum]]")
                       "href=\"/page/computer%20power%20and%20human%20reason\">Weizenbaum</a>")))
  (testing "tags, including multi-word and trailing punctuation"
    (is (str/includes? (html "#book by") "<a class=\"tag stub\" href=\"/page/book\">#book</a> by"))
    (is (str/includes? (html "read it #[[to read]]") "href=\"/page/to%20read\">#to read</a>"))
    (is (str/includes? (html "x #real-tag.") "href=\"/page/real-tag\">#real-tag</a>.")))
  (testing "headings and url anchors aren't tags"
    (let [h (html "# Heading #real-tag.")]
      (is (str/starts-with? h "<h1>"))
      (is (str/includes? h "#real-tag</a>")))
    (is (not (str/includes? (html "a url http://x.com/#anchor") "class=\"tag"))))
  (testing "block refs"
    (is (str/includes? (html "see ((abc-123))")
                       "<a class=\"bref\" href=\"/block/abc-123\">#book by Joseph Weizenbaum, father of ELIZA.</a>"))
    (is (str/includes? (html "and ((missing-id))") "<span class=\"bref missing\">((missing-id))</span>")))
  (testing "nothing inside code"
    (let [h (html "Code block:\n```clojure\n- not a block [[NotAPage]] #nottag\n```\nafter code, `inline [[AlsoNot]]`")]
      (is (not (str/includes? h "<a ")))
      (is (str/includes? h "[[NotAPage]] #nottag"))
      (is (str/includes? h "<code>inline [[AlsoNot]]</code>"))))
  (testing "labels are escaped"
    (is (str/includes? (html "[[a<b>_c_]]") ">a&lt;b&gt;_c_</a>"))))

(deftest markdown-test
  (is (= "<p><strong>bold</strong> and <a href=\"http://x.com\">http://x.com</a></p>\n"
         (html "**bold** and http://x.com")))
  (is (= ["TODO" "read it"] (r/split-marker "TODO" "TODO read it")))
  (is (= [nil "plain"] (r/split-marker nil "plain"))))
