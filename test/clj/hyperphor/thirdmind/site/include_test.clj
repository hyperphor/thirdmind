(ns hyperphor.thirdmind.site.include-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.site.include :as include]))

(def realm "s")

(def files
  {"pages/Home.md"         "- Welcome. See [[Linked]] and [[Secret]] and [[Diary note]]\n- [[Hub]]"
   "pages/Linked.md"       "- Linked page\n\t- a child #Private\n- [[Deep]] is behind a private child\n\t- #Private"
   "pages/Deep.md"         "- not reached: only linked from a private block"
   "pages/Secret.md"       "- whole page private\n- #Private"
   "pages/Secret.Sub.md"   "- namespace child of a private page"
   "pages/Hub.md"          "- links [[Orphan stub]] and [[Shell]]\n- and [[Shell]] again"
   "pages/Other.md"        "- also links [[Orphan stub]], unreached itself"
   "pages/Paren.md"        "title:: Paren\n\n- This page (#Private) is private too"
   "pages/Heading.md"      "# Heading text\n\t- nested under the heading #Private\n- fine"
   "pages/Aliased.md"      "alias:: Twin\n\n- reached only through its alias"
   "pages/Twin.md"         "- has its own file, so the alias joins rather than shadows it"
   "journals/2024_01_01.md" "- journal links [[Hidden by journal]]"
   "pages/Hidden by journal.md" "- only a journal links here"})

(defn- graph-fixture [f]
  (let [dir  (.toFile (java.nio.file.Files/createTempDirectory "site-include" (make-array java.nio.file.attribute.FileAttribute 0)))
        db   (java.io.File/createTempFile "site-include" ".db")]
    (doseq [[rel text] files] (io/make-parents (io/file dir rel)) (spit (io/file dir rel) text))
    (spit (io/file dir "pages/Home.md") (str (files "pages/Home.md") "\n- [[Paren]] [[Heading]] [[Twin]]"))
    (try
      (core/fresh-store! (str db) realm)
      (ingest/ingest! (str dir) realm)
      (f)
      (finally (core/delete-store-files! db)))))

(use-fixtures :once graph-fixture)

(defn- names [uris] (set (map #(java.net.URLDecoder/decode (subs % (inc (.lastIndexOf ^String % "/"))) "UTF-8") uris)))

(deftest published-set
  (let [{:keys [pages hidden]} (include/published realm {:exit-tags ["Private"] :entry-tags ["EntryPoint"]
                                                         :entry-pages ["Home"]})
        ps (names pages)]
    (is (every? ps ["home" "linked" "hub" "heading" "twin" "aliased"]))
    (is (not-any? ps ["deep" "secret" "secret/sub" "paren" "hidden by journal" "jan 1st, 2024" "other"])
        "private pages, their namespace children, journal-only and stub-joined pages stay out")
    (is (not (ps "orphan stub")) "stubs aren't walked or published")
    (is (= 4 (count (filter #(.contains ^String % "/block/linked") hidden))) "private blocks and children are hidden")
    (is (some #(.contains ^String % "/block/heading") hidden) "a bullet indented under heading text nests under it")))
