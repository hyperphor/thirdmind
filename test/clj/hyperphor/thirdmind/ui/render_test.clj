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
  (testing "[label]([[Page]]) links the page with its own text"
    (is (str/includes? (html "[the novel]([[ELIZA]]).") "<a class=\"ref\" href=\"/page/eliza\">the novel</a>.")))
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
  (testing "a block that is only a block ref embeds the target; cycles stop"
    (let [c (assoc ctx :block {"q" "> quoted *text*" "loop" "((loop))"})]
      (is (str/includes? (r/block-html c "((q))") "<div class=\"transclusion\"><blockquote>\n<p>quoted <em>text</em></p>"))
      (is (str/includes? (r/block-html c "see ((q))") ">quoted <em>text</em></a>") "inline ref: label, no >")
      (is (string? (r/block-html c "((loop))")))))
  (testing "nothing inside code"
    (let [h (html "Code block:\n```clojure\n- not a block [[NotAPage]] #nottag\n```\nafter code, `inline [[AlsoNot]]`")]
      (is (not (str/includes? h "<a ")))
      (is (str/includes? h "[[NotAPage]] #nottag"))
      (is (str/includes? h "<code>inline [[AlsoNot]]</code>"))))
  (testing "labels are escaped, but emphasis in names renders"
    (is (str/includes? (html "[[a<b> c]]") ">a&lt;b&gt; c</a>"))
    (is (str/includes? (html "[[__Erlebnis__]]") "\"/page/__erlebnis__\"><em>Erlebnis</em></a>"))))

(deftest macros-test
  (testing "videos"
    (is (str/includes? (html "{{video https://youtu.be/J3rNOAhonPw}}") "src=\"https://www.youtube.com/embed/J3rNOAhonPw\""))
    (is (str/includes? (html "{{youtube https://www.youtube.com/watch?v=FiIP0K4LXbU&t=3}}") "embed/FiIP0K4LXbU\""))
    (is (str/includes? (html "{{video https://www.youtube.com/shorts/abc}}") "class=\"video vertical\""))
    (is (str/includes? (html "{{video https://vimeo.com/123}}") "player.vimeo.com/video/123"))
    (is (str/includes? (html "{{video https://example.com/v.mp4}}") "<a href=\"https://example.com/v.mp4\">")))
  (testing "iframes"
    (is (str/includes? (html "{{iframe https://open.spotify.com/playlist/3NKD?si=x}}")
                       "src=\"https://open.spotify.com/embed/playlist/3NKD\""))
    (is (str/includes? (html "{{iframe https://open.spotify.com/track/abc}}") "height:152px"))
    (is (str/includes? (html "{{iframe https://example.com/widget}}") "src=\"https://example.com/widget\"")))
  (testing "tweets, including x.com and [title](url) args"
    (is (str/includes? (html "{{tweet  https://twitter.com/i/status/148 }}")
                       "<blockquote class=\"twitter-tweet\"><a href=\"https://twitter.com/i/status/148\">"))
    (is (str/includes? (html "{{twitter [A title](https://x.com/u/status/9)}}") "href=\"https://twitter.com/u/status/9\"")))
  (testing "embed, sidenote, unknown"
    (is (str/includes? (r/block-html ctx "{{embed ((abc-123))}}") "<div class=\"transclusion\">"))
    (is (str/includes? (html "{{embed [[ELIZA]]}}") "href=\"/page/eliza\""))
    (is (str/includes? (html "x {{sidenote see [[ELIZA]]}}") "<span class=\"sidenote\">see <a class=\"ref\" href=\"/page/eliza\">"))
    (is (str/includes? (html "{{timer 5}}") "<span class=\"macro\">{{timer 5}}</span>")))
  (testing "not inside code"
    (is (not (str/includes? (html "`{{video https://youtu.be/x}}`") "iframe")))))

(deftest italics-test
  (is (str/includes? (html "unlike __Erfahrung__, a") "unlike <em>Erfahrung</em>, a"))
  (is (str/includes? (html "__see [[ELIZA]]__") "<em>see <a class=\"ref\""))
  (is (not (str/includes? (html "https://x.com/a__b__c and snake__case__x") "<em>")))
  (is (str/includes? (html "**bold**") "<strong>bold</strong>")))

(deftest titles-test
  (is (= "<em>Erlebnis</em>" (r/title-html "__Erlebnis__")))
  (is (= "<em>Materialism</em>, Terry Eagleton" (r/title-html "__Materialism__, Terry Eagleton")))
  (is (= "a &lt;b&gt;" (r/title-html "a <b>")))
  (is (= "Erlebnis" (r/title-text "__Erlebnis__")))
  (is (= "1. not a list" (r/title-html "1. not a list"))))

(deftest images-test
  (testing "local images, with spaces, ? and a size suffix"
    (is (= "<img src=\"/assets/computational%20agency%20%3F%20x.png\" alt=\"\">\n"
           (html "![](../assets/computational agency ? x.png)")))
    (is (str/includes? (html "![pic](../assets/p.png){:height 1016, :width 807}")
                       "<img src=\"/assets/p.png\" alt=\"pic\" width=\"807\">")))
  (testing "remote images keep their url; alt text isn't scanned for tags"
    (let [h (html "![a #b](https://x.com/i.jpg?w=1&h=2)")]
      (is (str/includes? h "src=\"https://x.com/i.jpg?w=1&amp;h=2\""))
      (is (not (str/includes? h "class=\"tag")))))
  (testing "asset links"
    (is (str/includes? (html "[the pdf](../assets/a b.pdf)") "<a class=\"asset\" href=\"/assets/a%20b.pdf\">the pdf</a>")))
  (testing "not inside code"
    (is (not (str/includes? (html "`![](../assets/x.png)`") "<img")))))

(deftest markdown-test
  (is (= "<p><strong>bold</strong> and <a href=\"http://x.com\">http://x.com</a></p>\n"
         (html "**bold** and http://x.com")))
  (is (= ["TODO" "read it"] (r/split-marker "TODO" "TODO read it")))
  (is (= [nil "plain"] (r/split-marker nil "plain"))))
