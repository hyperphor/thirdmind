(ns hyperphor.thirdmind.site.cli-test
  (:require [clojure.test :refer [deftest is]]
            [hyperphor.thirdmind.site.cli :as cli]))

(deftest settings-without-goddinpotty
  (let [s (cli/settings {:graph "/g" :realm "g" :site {:main-page "Home"}})]
    (is (= ["Private"] (:exit-tags s)) "#Private is hidden unless exit tags are configured")
    (is (= "Home" (:main-page s)))
    (is (false? (:blyg? s)) "no Blyg link without :blygger"))
  (is (= [] (:exit-tags (cli/settings {:site {:main-page "Home" :exit-tags []}}))) "an explicit [] is kept")
  (is (true? (:blyg? (cli/settings {:site {:main-page "Home"} :blygger {:mount "blyg"}})))))
