(ns hyperphor.thirdmind.blyg.wire-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyperphor.thirdmind.blyg.wire :as w]))

(def origin "https://s.example/blyg/")
(def opts {:title "T Blyg" :author {:name "T" :url origin} :generator "g" :generator-url "u"})

(deftest ids-and-hashes
  (is (re-matches #"[0-9a-hjkmnp-tv-z]{26}" (w/new-id)))
  (is (= "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" (w/content-hash ""))))

(deftest versions
  (let [e1 (w/next-entry nil :fragment "hi" "<div>hi</div>" nil nil "2026-01-01T00:00:00Z")]
    (is (= [1 :fragment] [(:version e1) (:kind e1)]))
    (testing "presentation-only html change: no bump"
      (is (identical? e1 (w/next-entry e1 :fragment "hi" "<div class=\"x\"><span>hi</span></div>" nil nil "later"))))
    (testing "a link target appearing in the html bumps, though the md is the same"
      (is (= 2 (:version (w/next-entry e1 :fragment "hi" "<div><a href=\"https://p\">hi</a></div>" nil nil "later")))))
    (let [e2 (w/next-entry e1 :fragment "hi there" "<div>hi there</div>" nil nil "2026-01-02T00:00:00Z")]
      (is (= [2 "2026-01-01T00:00:00Z" "2026-01-02T00:00:00Z"] [(:version e2) (:created e2) (:updated e2)]))
      (is (= [1 2] (map :version (:changelog e2))))
      (testing "pins: current version, idempotent, marked in the changelog"
        (let [p (w/pin-entry e2)]
          (is (= #{2} (set (keys (:pins p)))))
          (is (identical? p (w/pin-entry p)))
          (is (= [nil true] (map :pinned (:changelog p))))
          (is (= "hi there" (get (json/read-str ((w/surface origin opts {"k" p}) (str "items/" (:blyg-id p) "/v2.json")))
                                 "content_md")))))
      (testing "withdrawal: a tombstone version"
        (let [t (w/withdraw-entry (assoc e2 :stub-of {:id "x"}) "2026-01-03T00:00:00Z")
              j (w/item-json origin t)]
          (is (= [3 "withdrawn" "" ""] [(j :version) (j :kind) (j :content_md) (j :content_html)]))
          (is (= (w/content-hash "") (j :content_hash)))
          (is (not (contains? j :stub_of)))
          (is (not (contains? j :transclusions)) "a fragment's tombstone has no transclusions")
          (is (= [] (:transclusions (w/withdraw-entry (assoc e2 :kind :thread) "t"))) "a thread's has []"))))))

(deftest feed
  (let [mk   (fn [id md at & kvs] (merge (w/next-entry nil :fragment md (str "<p>" md "</p>") nil nil at)
                                         {:blyg-id id} (apply hash-map kvs)))
        a    (mk "a0000000000000000000000000" "first post" "2026-01-01T00:00:00Z")
        b    (mk "b0000000000000000000000000" "a child" "2026-01-02T00:00:00Z" :thread-child? true)
        c    (w/withdraw-entry (mk "c0000000000000000000000000" "gone" "2026-01-03T00:00:00Z") "2026-01-04T00:00:00Z")
        xml  (w/feed-xml origin opts {"a" a "b" b "c" c})]
    (is (= ["blyg:c0000000000000000000000000:v2" "blyg:a0000000000000000000000000:v1"]
           (map second (re-seq #"<guid[^>]*>([^<]+)</guid>" xml)))
        "newest first, thread children left out, a withdrawn item only its withdrawal")
    (is (str/includes? xml "<title>first post</title>"))
    (is (str/includes? xml "<pubDate>Thu, 01 Jan 2026 00:00:00 GMT</pubDate>"))
    (is (= #{"blyg.json" "items/index.json" "feed.xml" "items/a0000000000000000000000000.json"}
           (set (keys (w/surface origin opts {"a" a})))))))
