(ns hyperphor.thirdmind.site.include-test
  (:require [clojure.data.json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]
            [hyperphor.thirdmind.site.include :as include]
            [hyperphor.thirdmind.site.render :as render]))

(def realm "s")

(def files
  {"pages/Home.md"         "- Welcome. See [[Linked]] and [[Secret]] and [[Diary note]]\n- [[Hub]]"
   "pages/Linked.md"       "- Linked page\n\t- a child #Private\n- [[Deep]] is behind a private child\n\t- #Private\n- Secret ref: ((secret-target))\n- quoted\n  id:: ref-target\n- see ((ref-target)), [[Secret]] and [[Home]]"
   "pages/Deep.md"         "- not reached: only linked from a private block"
   "pages/Secret.md"       "- whole page private\n  id:: secret-target\n- #Private"
   "pages/Secret.Sub.md"   "- namespace child of a private page"
   "pages/Hub.md"          "- links [[Orphan stub]] and [[Shell]]\n- and [[Shell]] again"
   "pages/Other.md"        "- also links [[Orphan stub]], unreached itself"
   "pages/Paren.md"        "title:: Paren\n\n- This page (#Private) is private too"
   "pages/Heading.md"      "# Heading text\n\t- nested under the heading #Private\n- fine"
   "pages/Aliased.md"      "alias:: Twin\n\n- reached only through its alias"
   "pages/Asked.md"        "- what is x? #AskClaude\n\t- the **answer**\n\t- more of it"
   "pages/Plural.md"       "aliases:: Other name\n\n- declared with aliases::"
   "pages/Twin.md"         "- has its own file, so the alias joins rather than shadows it"
   "journals/2024_01_01.md" "- journal links [[Hidden by journal]]\n- a diary line about [[Home]]"
   "pages/Hidden by journal.md" "- only a journal links here"
   "journals/2024_01_02.md" "- a post about [[Blygged]] #blyg\n\t- its child links [[Blyg child]]\n\t- and [[Blyg secret]]\n\t\t- #Private"
   "pages/Blygged.md"      "- linked from a journal #blyg item"
   "pages/Blyg child.md"   "- linked from that item's child"
   "pages/Blyg secret.md"  "- linked only from a private child of the item"
   "pages/Open.md"         "public:: true\n\n- unlinked, but public:: true, and it links [[Via open]]"
   "pages/Via open.md"     "- reached from a public:: page"
   "pages/Tagged.md"       "tags:: Hub, [[Linked]]\n\n- a page tagged bare and bracketed"})

(defn- graph-fixture [f]
  (let [dir  (.toFile (java.nio.file.Files/createTempDirectory "site-include" (make-array java.nio.file.attribute.FileAttribute 0)))
        db   (java.io.File/createTempFile "site-include" ".db")]
    (doseq [[rel text] files] (io/make-parents (io/file dir rel)) (spit (io/file dir rel) text))
    (spit (io/file dir "pages/Home.md") (str (files "pages/Home.md") "\n- [[Paren]] [[Heading]] [[Twin]] [[Asked]] [[Other name]]"))
    (try
      (core/fresh-store! (str db) realm)
      (ingest/ingest! (str dir) realm)
      (f)
      (finally (core/delete-store-files! db)))))

(use-fixtures :once graph-fixture)

(defn- names [uris] (set (map #(java.net.URLDecoder/decode (subs % (inc (.lastIndexOf ^String % "/"))) "UTF-8") uris)))

(deftest blyg-items-start-the-walk
  (let [ps (names (:pages (include/published realm {:exit-tags ["Private"] :entry-pages ["Home"]
                                                    :entry-block-tags ["blyg"]})))]
    (is (every? ps ["blygged" "blyg child"]))
    (is (not (ps "blyg secret")) "a private child of a #blyg item doesn't count")
    (is (not-any? #(re-find #"2024" %) ps) "the journal itself stays out")))

(deftest published-set
  (let [{:keys [pages hidden]} (include/published realm {:exit-tags ["Private"] :entry-tags ["EntryPoint"]
                                                         :entry-pages ["Home"]})
        ps (names pages)]
    (is (every? ps ["home" "linked" "hub" "heading" "twin" "aliased"]))
    (is (not-any? ps ["deep" "secret" "secret/sub" "paren" "hidden by journal" "jan 1st, 2024" "other"])
        "private pages, their namespace children, journal-only and stub-joined pages stay out")
    (is (not (ps "orphan stub")) "stubs aren't walked or published")
    (is (ps "plural") "aliases:: works like alias::")
    (is (every? ps ["open" "via open"]) "public:: true pages start the walk")
    (is (= 4 (count (filter #(.contains ^String % "/block/linked") hidden))) "private blocks and children are hidden")
    (is (some #(.contains ^String % "/block/heading") hidden) "a bullet indented under heading text nests under it")))

(deftest site-pages
  (let [opts {:exit-tags ["Private"] :entry-tags ["EntryPoint"] :entry-pages ["Home"] :main-page "Home"}
        ctx  (render/ctx realm opts (include/published realm opts))
        out  (.toFile (java.nio.file.Files/createTempDirectory "site-out" (make-array java.nio.file.attribute.FileAttribute 0)))
        page #(slurp (io/file out %))]
    (is (= 0 (:deleted (render/build! ctx out))))
    (is (.isFile (io/file out ".nojekyll")) "so GitHub Pages keeps _-prefixed pages")
    (is (every? #(.isFile (io/file out "assets" %)) ["default.css" "search.js" "elasticlunr.min.js" "grid.js"])
        "goddinpotty's css and scripts are bundled")
    (is (.isFile (io/file out "Home")))
    (is (not (.exists (io/file out "Secret"))))
    (let [linked (page "Linked")]
      (is (not (re-find #"a child|whole page private|behind a private" linked)) "hidden blocks and private transclusions stay out")
      (is (re-find #"<span class=\"empty\">Secret</span>" linked) "links to unpublished pages are plain text")
      (is (re-find #"<a class=\"pagelink\" href=\"Home\">Home</a>" linked))
      (is (re-find #"quoted" linked) "a published block ref is shown")
      (is (not (re-find #"secret-target" linked)) "nor is an unpublished block's id"))
    (is (re-find #"<b>tags</b>: <a class=\"pagelink\" href=\"Hub\">Hub</a>, <a class=\"pagelink\" href=\"Linked\">Linked</a>" (page "Tagged"))
        "bare tags:: values link like bracketed ones")
    (is (re-find #"Incoming links" (page "Home")))
    (is (not (re-find #"diary line" (page "Home"))) "a journal block linking a page isn't among its incoming links")
    (is (re-find #"url=Home" (page "index.html")))
    (let [asked (page "Asked")]
      (is (re-find #"hover-tag-lozenge[^>]*>Ask Claude</span><div class=\"hover-tag-popup\"><ul" asked)
          "an #AskClaude answer is a popup, its markup intact")
      (is (not (re-find #"(?s)hover-tag-popup.*</p>.*</div></span>" asked)))
      (is (= 1 (count (re-seq #"the <strong>answer</strong>" asked))) "shown once, in the popup"))
    (is (re-find #"myGrid" (page "Index")))
    (is (re-find #"thirdmindFullMap" (page "Map")))
    (let [o (render/outputs (assoc ctx :page-suffix ".html" :navbar [["All Pages" "Index"]]))]
      (is (contains? o "Home.html") ":page-suffix names page files")
      (is (contains? o "All-Pages.html") "the Index page, not Index.html (index.html on a case-insensitive disk)")
      (is (re-find #"href=\"All-Pages\"" (o "Home.html")) "a navbar link to Index follows it"))
    (is (re-find #"thirdmindPageMap\('#pagemap', \"Linked\"\)" (page "Linked")))
    (let [{:keys [nodes nbrs]} (clojure.data.json/read-str (page "graphs/map.json") :key-fn keyword)
          links (set (map :link nodes))]
      (is (links "Home"))
      (is (not-any? links ["Secret" "Deep"]) "the map has only published pages")
      (is (= (count nodes) (count nbrs))))
    (let [rows (clojure.data.json/read-str (page "assets/index.js") :key-fn keyword)]
      (is (some #(= "Linked" (:url %)) rows))
      (is (not-any? #(re-find #"a child|whole page private" (:body %)) rows) "the search index has only shown text"))
    (is (= {:written 0 :copied 0 :deleted 0} (select-keys (render/build! ctx out) [:written :copied :deleted]))
        "a second build changes nothing")
    (let [static (.toFile (java.nio.file.Files/createTempDirectory "site-static" (make-array java.nio.file.attribute.FileAttribute 0)))]
      (spit (io/file static "default.css") "/* mine */")
      (is (= 1 (:copied (render/build! (assoc ctx :static-dir (str static)) out))))
      (is (= "/* mine */" (page "assets/default.css")) "a :static-dir file replaces the bundled one"))))
