(ns hyperphor.thirdmind.identity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyperphor.thirdmind.identity :as id]
            [hyperphor.thirdmind.parse :as parse]))

(defn- blocks [s] (:blocks (parse/parse-lines (str/split-lines s))))

(defn- match [a b] (id/match (blocks a) (blocks b)))

(deftest matching
  (testing "unchanged, inserted, re-indented"
    (is (= [0 nil 1 2] (match "- alpha one\n- beta two\n- gamma three" "- alpha one\n- new block here\n- beta two\n\t- gamma three"))))
  (testing "moved within the page"
    (is (= [2 0 1] (match "- alpha one\n- beta two\n- gamma three" "- gamma three\n- alpha one\n- beta two"))))
  (testing "edited in place"
    (is (= [0 1] (match "- alpha one\n- the quick brown fox" "- alpha one\n- the quick brown fox jumps"))))
  (testing "rewritten is new"
    (is (= [0 nil] (match "- alpha one\n- the quick brown fox" "- alpha one\n- something else entirely"))))
  (testing "moved and edited"
    (is (= [1 2 0] (match "- Twitter: [@mtraven](https://x.com/mtraven)\n- beta two\n- gamma three"
                          "- beta two\n- gamma three\n- Nazi bar: [@mtraven](https://x.com/mtraven)"))))
  (testing "a child merged into its parent keeps the parent's id"
    (is (= [0 nil] (match "- I should grab the standard definition:\n\t- [Right Thing](http://wiki.c2.com/?RightThing)"
                          "- I should grab the standard definition: [Right Thing](http://wiki.c2.com/?RightThing)\n- P")))))
