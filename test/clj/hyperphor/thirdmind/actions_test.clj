(ns hyperphor.thirdmind.actions-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.ask-claude :as ac]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.parse :as parse]
            [hyperphor.thirdmind.smart-url :as su]
            [hyperphor.thirdmind.watch :as watch]
            [hyperphor.tunamelt.core :as t]))

;;; Units

(deftest bare-url-spans
  (is (= ["https://a.org/x"] (su/bare-urls "see https://a.org/x.")))
  (is (= ["https://en.wikipedia.org/wiki/Foo_(bar)"] (su/bare-urls "(https://en.wikipedia.org/wiki/Foo_(bar))")))
  (is (empty? (su/bare-urls "[label](https://a.org) ![i](https://a.org/i.png) `https://a.org/c` <https://a.org/z>")))
  (is (empty? (su/bare-urls "{{video https://youtube.com/watch?v=1}} [[https://a.org/page]]")))
  (is (empty? (su/bare-urls "x?u=https://a.org/y")) "not embedded in another URL")
  (is (= [[4 19 "https://a.org/x"]] (su/bare-url-spans "see https://a.org/x and [y](https://a.org/x)"))))

(deftest url-titles
  (is (= "Brilliant Basics (dodcio.defense.gov)" (su/url-title "https://dodcio.defense.gov/BrilliantBasics/")))
  (is (= "dodaf20 ontology1 (dodcio.defense.gov)"
         (su/url-title "https://dodcio.defense.gov/Library/DoD-Architecture-Framework/dodaf20_ontology1/")))
  (is (= "my page (example.com)" (su/url-title "https://www.example.com/a/my-page.html?x=1")))
  (is (= "example.com" (su/url-title "https://example.com/"))))

(deftest titles
  (is (= "Hello & World" (su/html-title "<html><title>\n Hello &amp;\n World </title></html>")))
  (is (= "OG one" (su/html-title "<meta property=\"og:title\" content=\"OG one\"><title>Other</title>")))
  (is (= "a b" (su/html-title "<title>[a] b</title>")) "brackets stripped")
  (is (nil? (su/html-title "<title>Just a moment...</title>"))))

(deftest answer-markdown
  (is (= [{:content "## Head" :children []}
          {:content "Para joined" :children []}
          {:content "one" :children [{:content "sub" :children []}]}
          {:content "two" :children []}
          {:content "```clj\n(f)\n```" :children []}]
         (ac/md->blocks "## Head\nPara\njoined\n\n- one\n  - sub\n- two\n\n```clj\n(f)\n```"))))

(deftest prompts
  (let [blocks (:blocks (parse/parse-lines (str/split-lines
                                            (str "- Brand\n\t- stoic? #AskClaude\n\t- earlier\n\t\t- #AskClaude\n\t\t\t- old answer\n"
                                                 "- other topic\n- #AskClaudePage\n"))))]
    (is (= (str "# Brand (book)\n\n- Brand\n  - stoic?\n  - earlier\n\nRespond to this item from the outline above:\n\nstoic?")
           (ac/prompt :tree "Brand (book)" blocks (second blocks)))
        "earlier answers and other top-level trees left out")
    (is (= "# Brand (book)\n\n- Brand\n  - stoic? #AskClaude\n  - earlier\n- other topic"
           (ac/prompt :page "Brand (book)" blocks (last blocks)))
        "the whole page; a bare tag adds no ask")))

;;; Through the watcher, on a temp copy of the fixture graph

(def realm "g")
(def ^:dynamic *cfg* nil)
(def calls (atom []))

(defn- copy-dir [from to]
  (doseq [^java.io.File f (file-seq (io/file from)) :when (.isFile f)]
    (let [dest (io/file to (str (.relativize (.toPath (io/file from)) (.toPath f))))]
      (io/make-parents dest)
      (io/copy f dest))))

(use-fixtures :each
  (fn [f]
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "thirdmind-act" (make-array java.nio.file.attribute.FileAttribute 0)))
          db  (java.io.File/createTempFile "thirdmind-act" ".db")]
      (try
        (copy-dir (io/resource "graph") dir)
        (reset! calls [])
        (t/configure! {:store (str db) :realm realm})
        (binding [*cfg* (watch/action-defaults
                         {:dir (str dir) :realm realm :db (str db) :write? true :handlers {}
                          :quiet-ms 0 :run #(%) :log (fn [& _])
                          :fetch-title (fn [u] (swap! calls conj [:fetch u]) (str "Title of " (last (str/split u #"/"))))
                          :ask (fn [p] (swap! calls conj [:ask p]) "Short answer.\n\n- point https://c.org/p\n  - detail")})]
          (f))
        (finally
          (doseq [c (reverse (file-seq dir))] (.delete ^java.io.File c))
          (core/delete-store-files! db))))))

(defn- write! [rel content]
  (let [f (io/file (:dir *cfg*) rel)]
    (spit f content)
    (.setLastModified f (- (System/currentTimeMillis) 1000 (rand-int 100000)))))

(defn- read-page [rel] (slurp (io/file (:dir *cfg*) rel)))

(deftest smart-url-action
  (write! "pages/Links.md" "- plain https://b.org/kept\n")
  (let [known (watch/init! *cfg*)]
    (write! "pages/Links.md" "- plain https://b.org/kept\n- new https://a.org/x.\n")
    (let [known (watch/sync! *cfg* known)]
      (is (= [[:fetch "https://a.org/x"]] @calls) "a URL already in the graph is left bare")
      (is (= "- plain https://b.org/kept\n- new [Title of x](https://a.org/x).\n" (read-page "pages/Links.md")))
      (reset! calls [])
      (let [known (watch/sync! *cfg* known)]
        (is (empty? @calls) "the rewritten link isn't detected again")
        (testing "a new URL added to a block with an old one"
          (write! "pages/Links.md" "- plain https://b.org/kept and https://b.org/new\n- new [Title of x](https://a.org/x).\n")
          (watch/sync! *cfg* known)
          (is (= [[:fetch "https://b.org/new"]] @calls))
          (is (str/includes? (read-page "pages/Links.md") "- plain https://b.org/kept and [Title of new](https://b.org/new)\n")))))))

(deftest smart-url-after-more-typing
  (let [known  (watch/init! *cfg*)
        queued (atom [])
        cfg    (assoc *cfg* :run #(swap! queued conj %))]
    (write! "pages/Typed.md" "- NATO uses https://a.org/bfo\n")
    (watch/sync! cfg known)
    (write! "pages/Typed.md" "- NATO uses https://a.org/bfo as a standard. It isn't going well.\n")
    (run! #(%) @queued)
    (is (= "- NATO uses [Title of bfo](https://a.org/bfo) as a standard. It isn't going well.\n"
           (read-page "pages/Typed.md")))))

(deftest ask-claude-action
  (let [known (watch/init! *cfg*)]
    (write! "pages/Q.md" "- topic\n\t- what is it? #AskClaude\n\t  id:: 650000aa-0000-0000-0000-000000000000\n- after\n")
    (let [known (watch/sync! *cfg* known)]
      (is (= [:ask "# Q\n\n- topic\n  - what is it?\n\nRespond to this item from the outline above:\n\nwhat is it?"] (first @calls)))
      (is (= (str "- topic\n\t- what is it?\n\t  id:: 650000aa-0000-0000-0000-000000000000\n"
                  "\t\t- #AskClaude\n\t\t\t- Short answer.\n\t\t\t- point https://c.org/p\n\t\t\t\t- detail\n- after\n")
             (read-page "pages/Q.md")))
      (reset! calls [])
      (watch/sync! *cfg* known)
      (is (empty? @calls) "the wrapper doesn't trigger, and URLs in the answer are left bare"))))

(deftest ask-claude-page-action
  (let [known (watch/init! *cfg*)]
    (write! "pages/P.md" "title:: The Page\n\n- one\n- two\n\t- summarize this page #AskClaudePage\n")
    (watch/sync! *cfg* known)
    (is (= [:ask "# The Page\n\n- one\n- two\n  - summarize this page\n\nRespond to this item from the page above:\n\nsummarize this page"]
           (first @calls)))
    (is (str/ends-with? (read-page "pages/P.md") "\t- summarize this page\n\t\t- #AskClaude\n\t\t\t- Short answer.\n\t\t\t- point https://c.org/p\n\t\t\t\t- detail\n"))))
