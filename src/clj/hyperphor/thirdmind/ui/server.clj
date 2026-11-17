(ns hyperphor.thirdmind.ui.server
  "`lein serve`: browse an ingested graph over HTTP. Reads the db; run `lein watch` alongside to keep it fresh."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ui.pages :as pages]
            [hyperphor.tunamelt.core :as t]
            [org.httpkit.server :as http]
            [hyperphor.multitool.cljcore :as ju]
            )
  (:import [java.net URLConnection URLDecoder]))

(defn- decode [s] (URLDecoder/decode (str/replace s "+" "%2B") "UTF-8"))

(defn- query-param [query-string k]
  (some (fn [kv] (let [[a b] (str/split kv #"=" 2)]
                   (when (= a k) (URLDecoder/decode (or b "") "UTF-8"))))
        (some-> query-string (str/split #"&"))))

(def ^:private tweet-script "<script async src=\"https://platform.twitter.com/widgets.js\"></script>")

(defn- layout [{:keys [title head body wide]} q]
  (let [html (str (h/html
                   [:html
                    [:head [:meta {:charset "utf-8"}]
                     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                     [:title title]
                     [:link {:rel "stylesheet" :href "/static/thirdmind.css"}]
                     head]
                    [:body {:class (when wide "wide")}
                     [:nav [:a.home {:href "/"} "Index"]
                      [:form {:action "/search"} [:input {:type "search" :name "q" :value q :placeholder "Search"}]]]
                     [:main body]]]))]
    ;; tweets need Twitter's script; only load it where there are some
    (str "<!DOCTYPE html>\n"
         (cond-> html
           (str/includes? html "class=\"twitter-tweet\"") (str/replace "</body>" (str tweet-script "</body>"))))))

(defn- html-response [status view q]
  {:status status :headers {"Content-Type" "text/html; charset=utf-8"} :body (layout view q)})

(defn- not-found [what]
  (html-response 404 {:title "Not found" :body [:p "Not found: " what]} nil))

(defn- static [name content-type]
  {:status 200 :headers {"Content-Type" content-type} :body (slurp (io/resource (str "thirdmind/" name)))})

(defn- asset
  "Serve a file from the graph's assets/ directory; nil if absent or outside it."
  [dir rel]
  (let [root (.getCanonicalFile (io/file dir "assets"))
        f    (.getCanonicalFile (io/file root rel))]
    (when (and (str/starts-with? (.getPath f) (str (.getPath root) java.io.File/separator)) (.isFile f))
      {:status  200
       :headers {"Content-Type" (or (URLConnection/guessContentTypeFromName (.getName f)) "application/octet-stream")}
       :body    f})))

(defn handler
  "Ring handler. opts: :realm, :dir (graph directory, for assets), :graph (Logseq graph name)."
  [{:keys [realm dir graph]}]
  (fn [{:keys [uri query-string]}]
    (let [ctx (pages/ctx realm graph)
          q   (query-param query-string "q")]
      (try
        (cond
          (= uri "/")
          (html-response 200 (pages/index-view ctx) nil)

          (= uri "/index.json")
          {:status 200 :headers {"Content-Type" "application/json"} :body (json/write-str (pages/index-rows realm))}

          (str/starts-with? uri "/page/")
          (let [key (subs uri (count "/page/"))]
            (if-let [canonical (some #(when (not= key %) %) [(get (:aliases ctx) key)])]
              {:status 302 :headers {"Location" (str "/page/" canonical)}}
              (if-let [v (pages/page-view ctx key)] (html-response 200 v nil) (not-found key))))

          (str/starts-with? uri "/block/")
          (let [id (decode (subs uri (count "/block/")))]
            (if-let [v (pages/block-view ctx id)] (html-response 200 v nil) (not-found id)))

          (str/starts-with? uri "/assets/")
          (let [rel (decode (subs uri (count "/assets/")))]
            (or (and dir (asset dir rel)) (not-found rel)))

          (= uri "/search")
          (html-response 200 (pages/search-view ctx q) q)

          (= uri "/static/thirdmind.css") (static "thirdmind.css" "text/css")
          (= uri "/static/index.js")      (static "index.js" "text/javascript")

          :else (not-found uri))
        (catch Exception e
          {:status 500 :headers {"Content-Type" "text/plain"} :body (str "Error: " (ex-message e))})))))

(defn -main
  "Usage: lein serve <graph-dir> [db-path] [port]"
  [& [dir db port]]
  (when-not dir
    (println "Usage: lein serve <graph-dir> [db-path] [port]")
    (System/exit 1))
  (let [realm (core/realm-for dir)
        db    (or db (str realm ".db"))
        port  (if port (parse-long port) 8080)]
    (when-not (.exists (io/file db))
      (println "No database at" db "- run `lein watch" dir "` or `lein run" dir "` first")
      (System/exit 1))
    (t/configure! {:store db :realm realm})
    (http/run-server (handler {:realm realm :dir dir :graph (.getName (io/file dir))}) {:port port})
    (println (str "Serving " db " at http://localhost:" port "/"))
    ;; Smart enough to be a no-op on server
    (ju/open-url (format "http://localhost:%s" port))
    @(promise)))
