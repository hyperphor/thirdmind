(ns hyperphor.thirdmind.blyg.content-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.tunamelt.core :as t]))

(def realm "g")
(def ^:dynamic *ctx* nil)

(def pages
  {"pages/Pub.md"    (str "- Intro #blyg\n"
                          "\t- see [[Target]], [[T2]] and #topic, [label]([[Target]])\n"
                          "\t- secret #Private\n"
                          "\t- also private\n\t\t- #Private\n"
                          "\t- TODO do it\n"
                          "\t- ![pic](../assets/a b.png)\n"
                          "\t- {{video https://youtu.be/xyz}} {{renderer foo}}\n"
                          "\t- `[[code]] #blyg`\n"
                          "\t- question #AskClaude\n\t\t- the answer\n"
                          "- other\n")
   "pages/Target.md" "alias:: T2\n\n- t\n"
   "pages/topic.md"  "- unpublished page\n"
   "journals/2026_01_02.md" "- a journal post #[[blyg]]\n"})

(use-fixtures :once
  (fn [f]
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "thirdmind-blyg" (make-array java.nio.file.attribute.FileAttribute 0)))
          db  (str (java.io.File/createTempFile "thirdmind-blyg" ".db"))]
      (try
        (doseq [[rel s] pages] (io/make-parents (io/file dir rel)) (spit (io/file dir rel) s))
        (core/fresh-store! db realm)
        (ingest/ingest! (str dir) realm)
        (binding [*ctx* (c/ctx realm {:base-url "https://site.example" :published #{"Pub" "Target"}
                                      :exit-tags ["Private"]})]
          (f))
        (finally
          (doseq [x (reverse (file-seq dir))] (.delete ^java.io.File x))
          (core/delete-store-files! db))))))

(defn- uri [page path] (ingest/block-uri realm (str (ingest/page-key page) "~" path)))

(deftest slugs
  (is (= "Foo-bar-baz∕x" (c/slug "Foo: bar? (baz)/x")))
  (is (= "__Erlebnis__" (c/slug "__Erlebnis__"))))

(deftest markdown
  (is (= (str "From [Pub](https://site.example/Pub)\n\n"
              "Intro\n\n"
              "see [Target](https://site.example/Target), [T2](https://site.example/Target) and #topic, [label](https://site.example/Target)\n\n"
              "◘ do it\n\n"
              "![pic](https://site.example/assets/a%20b.png)\n\n"
              "https://youtu.be/xyz\n\n"
              "`[[code]] #blyg`\n\n"
              "question #AskClaude")
         (c/fragment-md *ctx* (uri "Pub" "0")))
      "tag stripped, links to published pages only, private children dropped, answer left out")
  (testing "journals get no attribution"
    (is (= "a journal post" (c/fragment-md *ctx* (uri "Jan 2nd, 2026" "0")))))
  (is (= "From [Pub](https://site.example/Pub)\n\nIntro\n\n![[aaa]]\n\n![[bbb]]"
         (c/thread-md *ctx* (uri "Pub" "0") ["aaa" "bbb"]))))

(deftest html
  (let [h (c/fragment-html *ctx* (uri "Pub" "0"))]
    (is (str/starts-with? h "<div><p class=\"source-page\">From <a href=\"https://site.example/Pub\">Pub</a></p>"))
    (is (str/includes? h "href=\"https://site.example/Target\""))
    (is (str/includes? h "src=\"https://site.example/assets/a%20b.png\""))
    (is (not (str/includes? h "/page/")) "no ThirdMind UI links")
    (is (not (re-find #"secret|also private" h)))
    (is (= ["<code>[[code]] #blyg"] (re-seq #"<code>\[\[code\]\] #blyg" h)) "control tag kept only in code")
    (is (= 1 (count (re-seq #"blyg<|#blyg" h))))
    (is (re-find #"(?s)<details class=\"ask-claude\"><summary>.*question.*</summary><ul class=\"claude\"><li>.*the answer" h))))
