(ns hyperphor.thirdmind.ui.server
  "`lein serve`: browse an ingested graph over HTTP. Reads the db; run `lein watch` alongside to keep it fresh."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [hyperphor.thirdmind.core :as core]
            [hyperphor.thirdmind.ui.pages :as pages]
            [hyperphor.tunamelt.core :as t]
            [org.httpkit.server :as http])
  (:import [java.net URLDecoder]))

(defn- decode [s] (URLDecoder/decode ^String s "UTF-8"))

(defn- query-param [query-string k]
  (some (fn [kv] (let [[a b] (str/split kv #"=" 2)] (when (= a k) (decode (or b "")))))
        (some-> query-string (str/split #"&"))))

(defn- layout [{:keys [title body]} q]
  (str "<!DOCTYPE html>\n"
       (h/html
        [:html
         [:head [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:link {:rel "stylesheet" :href "/static/thirdmind.css"}]]
         [:body
          [:nav [:a.home {:href "/"} "Index"]
           [:form {:action "/search"} [:input {:type "search" :name "q" :value q :placeholder "Search"}]]]
          [:main body]]])))

(defn- html-response [status view q]
  {:status status :headers {"Content-Type" "text/html; charset=utf-8"} :body (layout view q)})

(defn- not-found [what]
  (html-response 404 {:title "Not found" :body [:p "Not found: " what]} nil))

(defn handler
  "Ring handler for realm."
  [realm]
  (fn [{:keys [uri query-string]}]
    (let [ctx (pages/ctx realm)
          q   (query-param query-string "q")]
      (try
        (cond
          (= uri "/")
          (html-response 200 (pages/index-view ctx) nil)

          (str/starts-with? uri "/page/")
          (let [key (subs uri (count "/page/"))]
            (if-let [canonical (some #(when (not= key %) %) [(get (:aliases ctx) key)])]
              {:status 302 :headers {"Location" (str "/page/" canonical)}}
              (if-let [v (pages/page-view ctx key)] (html-response 200 v nil) (not-found key))))

          (str/starts-with? uri "/block/")
          (let [id (decode (subs uri (count "/block/")))]
            (if-let [v (pages/block-view ctx id)] (html-response 200 v nil) (not-found id)))

          (= uri "/search")
          (html-response 200 (pages/search-view ctx q) q)

          (= uri "/static/thirdmind.css")
          {:status 200 :headers {"Content-Type" "text/css"} :body (slurp (io/resource "thirdmind/thirdmind.css"))}

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
    (http/run-server (handler realm) {:port port})
    (println (str "Serving " db " at http://localhost:" port "/"))
    @(promise)))
