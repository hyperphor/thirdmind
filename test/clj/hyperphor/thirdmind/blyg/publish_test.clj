(ns hyperphor.thirdmind.blyg.publish-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hyperphor.thirdmind.blyg.cli :as cli]
            [hyperphor.thirdmind.blyg.content :as c]
            [hyperphor.thirdmind.blyg.migrate :as m]
            [hyperphor.thirdmind.blyg.pages :as pages]
            [hyperphor.thirdmind.blyg.publish :as p]
            [hyperphor.thirdmind.blyg.remote :as r]
            [hyperphor.thirdmind.blyg.wire :as w]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ingest :as ingest]))

(def realm "g")
(def ^:dynamic *dir* nil)
(def ^:dynamic *db* nil)

(def page (str "- A thread post #blyg\n\t- the first child point\n\t- the second child point\n\t- \n"
               "- A solo fragment post #blyg\n"))

(use-fixtures :each
  (fn [f]
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "thirdmind-pub" (make-array java.nio.file.attribute.FileAttribute 0)))
          db  (str (java.io.File/createTempFile "thirdmind-pub" ".db"))]
      (try
        (io/make-parents (io/file dir "pages/B.md"))
        (spit (io/file dir "pages/B.md") page)
        (binding [*dir* (str dir) *db* db] (f))
        (finally
          (doseq [x (reverse (file-seq dir))] (.delete ^java.io.File x))
          (core/delete-store-files! db))))))

(defn- ingest! [] (core/fresh-store! *db* realm) (ingest/ingest! *dir* realm))
(defn- ctx [] (c/ctx realm {:base-url "https://s.example" :published #{"B"} :exit-tags []}))
(defn- counts [ch] (into {} (for [[k v] ch] [k (count v)])))
(def zero {:new 0 :bumped 0 :withdrawn 0 :kind 0 :thread-child 0 :title 0})

(defn- legacy
  "A goddinpotty-like state for the current graph: keyed by fake uuids, rendered a little differently."
  []
  (let [{:keys [state]} (p/plan (ctx) {:items {}} "2026-01-01T00:00:00Z")]
    {:items (into {} (map-indexed (fn [i [_ e]]
                                    [(format "6a000000-0000-0000-0000-%012d" i)
                                     (-> e (dissoc :tm-base) (update :content-md str/replace "child" "*child*"))])
                                  (:items state)))}))

(deftest publishing
  (ingest!)
  (let [{s1 :state r1 :report} (p/plan (ctx) {:items {}} "2026-01-01T00:00:00Z")]
    (is (= [1 2] [(count (filter #(= :thread (:kind %)) (vals (:items s1)))) (count (filter :thread-child? (vals (:items s1))))])
        "one thread, its two non-empty children promoted; the empty child left out")
    (is (= 4 (count (:new r1))))
    (is (= zero (counts (p/changes s1 (:state (p/plan (ctx) s1 "later"))))) "a second run changes nothing")
    (testing "an edit bumps the child and, through its transclusion, the thread"
      (spit (io/file *dir* "pages/B.md") (str/replace page "first child point" "first child point, edited"))
      (ingest!)
      (is (= {:bumped 2} (select-keys (counts (p/changes s1 (:state (p/plan (ctx) s1 "later")))) [:bumped]))))))

(deftest relinking-doesnt-bump
  (spit (io/file *dir* "pages/B.md") (str page "- see [[C]] #blyg\n"))
  (ingest!)
  (let [pctx #(c/ctx realm {:base-url "https://s.example" :published % :exit-tags []})
        s1   (:state (p/plan (pctx #{"B"}) {:items {}} "t1"))
        s2   (:state (p/plan (pctx #{"B" "C"}) s1 "t2"))
        see  #(some (fn [e] (when (str/includes? (:content-md e) "see") e)) (vals (:items %)))]
    (is (= zero (counts (p/changes s1 s2))) "a linked page newly published doesn't bump")
    (is (str/includes? (:content-md (see s2)) "](https://s.example/C)") "the current version is re-linked in place")
    (is (= (w/content-hash (:content-md (see s2))) (:content-hash (see s2))))))

(deftest migrating
  (ingest!)
  (let [old (legacy)
        {:keys [state report]} (m/import-state (ctx) old)]
    (is (= 0 (:unmatched report)))
    (is (every? #(re-matches #"[0-9a-z]{12}" %) (keys (:items state))) "keyed by tmid")
    (is (every? :legacy-uuid (vals (:items state))))
    (let [s2 (:state (p/plan (ctx) state "later"))]
      (is (= zero (counts (p/changes state s2))) "rendering differences alone don't bump")
      (is (= zero (counts (p/changes s2 (:state (p/plan (ctx) s2 "later2"))))))
      (testing "untagging leaves an item alone unless withdrawing"
        (spit (io/file *dir* "pages/B.md") (str/replace page "A solo fragment post #blyg" "A solo fragment post"))
        (ingest!)
        (let [{s3 :state r3 :report} (p/plan (ctx) s2 "later3")]
          (is (= 1 (count (:untagged r3))))
          (is (= zero (counts (p/changes s2 s3))))
          (is (= 1 (:withdrawn (counts (p/changes s2 (:state (p/plan (ctx) s2 "later3" :withdraw? true))))))))))))

(deftest moved-block-keeps-its-item
  (ingest!)
  (let [s1 (:state (p/plan (ctx) {:items {}} "t1"))
        id (fn [s] (some #(when (str/includes? (:content-md %) "A solo") (:blyg-id %)) (vals (:items s))))]
    ;; to another page: a new tmid, so only re-keying keeps the item
    (spit (io/file *dir* "pages/B.md") (str/replace page "- A solo fragment post #blyg\n" ""))
    (spit (io/file *dir* "pages/Other.md") "- A solo fragment post #blyg, now elsewhere\n")
    (ingest!)
    (let [{s2 :state r2 :report} (p/plan (ctx) s1 "t2")]
      (is (= 1 (:rekeyed r2)))
      (is (some? (id s1)))
      (is (= (id s1) (id s2)))
      (is (= {:new 0 :bumped 1} (select-keys (counts (p/changes s1 s2)) [:new :bumped]))))))

(deftest titles-and-edits-since-goddinpotty
  (ingest!)
  (let [old (update (legacy) :items
                    #(into {} (for [[k e] %] [k (cond-> e (str/includes? (:content-md e) "solo") (assoc :content-md "From [B](https://s.example/B)\n\nA solo fragment"))])))
        {:keys [state]} (m/import-state (ctx) old)
        s2  (:state (p/plan (ctx) state "later"))
        by  (fn [s txt] (some #(when (str/includes? (str (:content-md %)) txt) %) (vals (:items s))))]
    (is (= 2 (:version (by s2 "A solo fragment post"))) "edited since goddinpotty's run: a new version")
    (is (:tm-frozen (by s2 "A thread post")) "unchanged but for rendering: frozen")
    (is (= "A solo fragment post" (:title (by s2 "A solo fragment post"))))
    (is (str/includes? (:content-html (by s2 "A solo fragment post")) "<div class=\"blyg-title\">A solo fragment post</div>"))
    (is (nil? (:title (by s2 "the first child point"))) "thread children get no title")))

(deftest duplicate-guard
  (ingest!)
  (let [old (update (legacy) :items #(into {} (map (fn [[k e]] [k (assoc e :content-md (str "Totally different " k))])) %))
        {:keys [state]} (m/import-state (ctx) old)
        _  (spit (io/file *dir* "pages/B.md") (str page "- Totally different 6a000000-0000-0000-0000-000000000000 #blyg\n"))
        _  (ingest!)
        {:keys [report]} (p/plan (ctx) state "later")]
    (is (seq (:possible-duplicates report)) "a new item resembling an unmatched old one is flagged")))

(deftest page-templates
  (let [tpl (str "<html><head><title>Site: old</title><meta content=\"old\" name=\"og:url\" /><meta content=\"old\" name=\"og:title\" />"
                 "<link href=\"https://s/blyg/items/00000000000000000000000000.json\" rel=\"alternate\" /></head><body>"
                 "<div class=\"col-lg-7\"><div class=\"main\"><h1>old</h1>old body</div></div></div></div><footer>f</footer></body></html>")
        out (pages/fill tpl {:title "New & title" :h1 "H" :body [:p "body"] :item-id "abcdefghjkmnpqrstvwxyz0123" :url "https://s/f/x/"})]
    (is (str/includes? out "<meta content=\"https://s/f/x/\" name=\"og:url\""))
    (is (str/includes? out "<title>Site: New &amp; title</title>"))
    (is (str/includes? out "<meta content=\"New &amp; title\" name=\"og:title\""))
    (is (str/includes? out "<div class=\"main\"><h1>H</h1><p>body</p></div></div></div></div><footer>"))
    (is (str/includes? out "items/abcdefghjkmnpqrstvwxyz0123.json\" rel=\"alternate\""))
    (is (not (str/includes? out "old body")))))

(deftest diffing
  (let [mk (fn [m] (let [d (.toFile (java.nio.file.Files/createTempDirectory "blyg-d" (make-array java.nio.file.attribute.FileAttribute 0)))]
                     (cli/write-files! d m) d))
        a (mk {"items/x.json" "{\"a\":1}" "f/x/index.html" "<p>1</p>" "items/n.json" "{}"})
        b (mk {"items/x.json" "{ \"a\" : 1 }" "f/x/index.html" "<p>2</p>" "items/gone.json" "{}"})]
    (try
      (is (= {:missing ["items/gone.json"] :extra ["items/n.json"] :differ []} (cli/diff-dirs a b)))
      (finally (doseq [d [a b], x (reverse (file-seq d))] (.delete ^java.io.File x))))))

(deftest images-and-update-notes
  (ingest!)
  (let [origin "https://s.example/blyg/"
        mctx   #(c/ctx realm {:base-url "https://s.example" :published #{"B"} :exit-tags []
                              :graph-dir *dir* :origin origin})
        s1     (:state (p/plan (mctx) {:items {}} "2026-01-01T00:00:00Z"))
        thread #(some (fn [e] (when (= :thread (:kind e)) e)) (vals (:items %)))]
    (io/make-parents (io/file *dir* "assets/pic one.png"))
    (spit (io/file *dir* "assets/pic one.png") "PNGBYTES")
    (spit (io/file *dir* "pages/B.md") (str/replace page "\t- \n" "\t- ![a pic](../assets/pic one.png)\n"))
    (ingest!)
    (let [ctx (mctx)
          s2  (:state (p/plan ctx s1 "2026-01-02T00:00:00Z"))
          img (some #(when (str/includes? (str (:content-html %)) "<img") %) (vals (:items s2)))
          n   (c/media-name (io/file *dir* "assets/pic one.png"))]
      (is (re-matches #"[0-9a-f]{12}-pic-one\.png" n))
      (is (= {n (io/file *dir* "assets/pic one.png")} @(:media ctx)) "the publisher learns what to copy")
      (is (str/includes? (:content-html img) (str "src=\"" origin "media/" n "\"")) "absolute media URL")
      (is (= [{:url (str "media/" n) :mime "image/png" :alt "a pic"}] (:media (w/item-json origin img))))
      (testing "the thread's new version says what changed, as a generated note"
        (is (= 2 (:version (thread s2))))
        (is (= {:version 2 :at "2026-01-02T00:00:00Z" :note "Added a part." :generated true} (last (:changelog (thread s2)))))
        (is (str/includes? (w/feed-xml origin {:title "T"} (:items s2)) "<title>Added a part. — A thread post</title>")))
      (is (= #{n} (cli/referenced-media origin s2)))
      (testing "media are copied once, never overwritten"
        (let [d (.toFile (java.nio.file.Files/createTempDirectory "blyg-m" (make-array java.nio.file.attribute.FileAttribute 0)))]
          (try
            (is (= [n] (cli/copy-media! d @(:media ctx))))
            (is (= [] (cli/copy-media! d @(:media ctx))))
            (is (= "PNGBYTES" (slurp (io/file d "media" n))))
            (finally (doseq [x (reverse (file-seq d))] (.delete ^java.io.File x)))))))))

(deftest frozen-items-keep-site-images
  (io/make-parents (io/file *dir* "assets/p.png"))
  (spit (io/file *dir* "assets/p.png") "PNG")
  (spit (io/file *dir* "pages/B.md") (str page "- An old picture post ![](../assets/p.png) #blyg\n"))
  (ingest!)
  (let [{:keys [state]} (m/import-state (ctx) (legacy))
        s2   (:state (p/plan (ctx) state "2026-01-01T00:00:00Z"))
        mctx (c/ctx realm {:base-url "https://s.example" :published #{"B"} :exit-tags []
                           :graph-dir *dir* :origin "https://s.example/blyg/"})
        s3   (:state (p/plan mctx s2 "2026-01-02T00:00:00Z"))]
    (is (= zero (counts (p/changes s2 s3))) "moving images to media alone doesn't bump frozen items")
    (is (every? :tm-frozen (vals (:items s3))))))

(deftest change-notes
  (let [ex {:kind :thread :content-html "<p>x</p>" :transclusions [{:id "a" :version 1} {:id "b" :version 1}]}]
    (is (= "Added 2 parts." (p/change-note ex :thread "" [{:id "a" :version 1} {:id "b" :version 1} {:id "c" :version 1} {:id "d" :version 1}])))
    (is (= "Removed a part." (p/change-note ex :thread "" [{:id "a" :version 1}])))
    (is (= "Revised a part." (p/change-note ex :thread "<p>x</p>" [{:id "a" :version 2} {:id "b" :version 1}])))
    (is (= "Added an image." (p/change-note (assoc ex :kind :fragment :transclusions nil) :fragment "<p>x</p><img src=\"u\">" nil)))
    (is (= "Updated an image." (p/change-note (assoc ex :kind :fragment :transclusions nil :content-html "<img src=\"a\">") :fragment "<img src=\"b\">" nil)))
    (is (= "Edited." (p/change-note (assoc ex :kind :fragment :transclusions nil) :fragment "<p>y</p>" nil)))
    (is (= "Now a fragment." (p/change-note ex :fragment "" nil)))))

(deftest replying
  (spit (io/file *dir* "pages/B.md")
        (str "- My answer [Their post](https://other.example/f/x/) #blyg-response\n"
             "- Another answer https://other.example/f/new/ #blyg-reply\n"))
  (ingest!)
  (let [remote {:origin "https://other.example/" :id "x" :version 3 :retrieved "2026-01-01T00:00:00Z"
                :source-title "Their post" :content-md "their text" :content-html "<p>their text</p>"
                :page-url "https://other.example/f/x/" :webmention "https://other.example/wm"}
        {:keys [state report]} (p/plan (ctx) {:items {} :remotes {"https://other.example/f/x/" remote}} "2026-01-01T00:00:00Z")
        [e & more] (vals (:items state))]
    (is (nil? more) "the reply to an unfetched target is skipped")
    (is (str/includes? (second (first (:skipped report))) "couldn't be fetched"))
    (is (= :thread (keyword (:kind e))) "#blyg-response makes a stub, like #blyg-reply")
    (is (= {:origin "https://other.example/" :id "x" :version 3} (select-keys (:stub-of e) [:origin :id :version])))
    (is (= [{:id "x" :version 3 :origin "https://other.example/"}] (:transclusions e)))
    (is (= "https://other.example/wm" (:mention-endpoint e)))
    (is (not (str/includes? (:content-md e) "other.example/f/x")) "the target URL is cited, not quoted")
    (is (not (str/includes? (:content-md e) "blyg-response")) "the tag is stripped")))

(deftest fetching-reply-targets
  (let [item  {:blyg "0.3" :id "y" :version 2 :kind "fragment" :content_md "theirs" :content_html "<p>theirs</p>"}
        pages {"https://o.example/f/y/"         {:status 200 :final-url "https://o.example/f/y/"
                                                  :body "<html><link rel=\"alternate\" type=\"application/json\" href=\"/items/y.json\"></html>"}
               "https://o.example/items/y.json" {:status 200 :final-url "https://o.example/items/y.json" :body (json/write-str item)}
               "https://o.example/blyg.json"    {:status 200 :final-url "https://o.example/blyg.json"
                                                  :body (json/write-str {:title "O's Blyg" :webmention "/wm"})}}
        get   (fn [url _] (pages url))
        snap  (r/resolve-target get "https://o.example/f/y/")]
    (is (= {:origin "https://o.example/" :id "y" :version 2 :source-title "O's Blyg"
            :webmention "https://o.example/wm" :page-url "https://o.example/f/y/"}
           (select-keys snap [:origin :id :version :source-title :webmention :page-url]))
        "a permalink page leads to its item document; the origin is where that was fetched from")
    (is (nil? (r/resolve-target get "https://elsewhere.example/post")) "not a blyg item")
    (spit (io/file *dir* "pages/B.md") "- Reply https://o.example/f/y/ #blyg-reply\n- Other https://nope.example/ #blyg-reply\n")
    (ingest!)
    (let [st (p/fetch-remotes (ctx) {:items {}} #(r/resolve-target get %))
          {:keys [state report]} (p/plan (ctx) st "2026-01-01T00:00:00Z")]
      (is (= ["https://o.example/f/y/"] (keys (:remotes st))))
      (is (= 1 (count (:skipped report))))
      (is (= "y" (:id (:stub-of (first (vals (:items state))))))))))

(deftest sending-mentions
  (let [origin "https://me.example/"
        stub   {:blyg-id "s" :version 1 :kind "thread" :stub-of {:id "y" :version 2}
                :mention-target "https://o.example/f/y/"}
        items  {"a" stub
                "b" (assoc stub :blyg-id "done" :mention-sent {:target-version 2 :ok? true})
                "c" (dissoc stub :stub-of)}
        posts  (atom [])
        http   (fn [url & {:keys [method body]}]
                 (case [method url]
                   [nil "https://me.example/items/s.json"] {:status 200 :body (json/write-str {:version 1 :stub_of {:id "y"}})}
                   [:head "https://o.example/f/y/"] {:status 200 :headers {"link" ["</wm>; rel=\"webmention\""]}}
                   [:post "https://o.example/wm"] (do (swap! posts conj body) {:status 202})
                   nil))
        {out :items lines :log} (r/send-mentions http origin items)]
    (is (= [(str "source=" (java.net.URLEncoder/encode "https://me.example/f/s/" "UTF-8")
                 "&target=" (java.net.URLEncoder/encode "https://o.example/f/y/" "UTF-8"))] @posts)
        "one mention: already-sent and non-stub items are left alone; endpoint found by the Link header")
    (is (= {:endpoint "https://o.example/wm" :target-version 2 :ok? true :status 202}
           (select-keys (get-in out ["a" :mention-sent]) [:endpoint :target-version :ok? :status])))
    (is (= 1 (count lines)))
    (testing "a stub not live yet waits, and records nothing"
      (let [{o :items l :log} (r/send-mentions (constantly nil) origin {"a" stub})]
        (is (nil? (get-in o ["a" :mention-sent])))
        (is (str/includes? (first l) "deferred"))))))
