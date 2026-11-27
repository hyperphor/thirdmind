(ns hyperphor.thirdmind.blyg.remote
  "The network side of replies, after goddinpotty.export.blyg-remote: fetching another blyg's item
  (§10.2, §12.2) and sending webmentions (§15). Best-effort: failures return nil, never throw."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private timeout (java.time.Duration/ofSeconds 10))

(def ^:private client
  (delay (-> (java.net.http.HttpClient/newBuilder)
             (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
             (.connectTimeout timeout)
             (.build))))

(defn http
  "One request: method :get, :head or :post (body form-encoded). {:status :body :final-url :headers},
  or nil on a network failure."
  [url & {:keys [method body accept] :or {method :get}}]
  (try
    (let [b    (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                   (.timeout timeout)
                   (.header "User-Agent" "thirdmind-blyg/0.1"))
          _    (when accept (.header b "Accept" accept))
          _    (case method
                 :get  (.GET b)
                 :head (.method b "HEAD" (java.net.http.HttpRequest$BodyPublishers/noBody))
                 :post (-> b (.header "Content-Type" "application/x-www-form-urlencoded")
                           (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))))
          resp (.send ^java.net.http.HttpClient @client (.build b) (java.net.http.HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp) :final-url (str (.uri resp))
       :headers (into {} (for [[k v] (.map (.headers resp))] [(str/lower-case k) (vec v)]))})
    (catch Exception _ nil)))

(defn http-get [url accept] (http url :accept accept))

(defn- parse-json [s] (try (json/read-str s :key-fn keyword) (catch Exception _ nil)))

(defn- ok-body [r] (when (= 200 (:status r)) r))

(defn- resolve-url [base href]
  (try (str (.resolve (java.net.URI/create base) (str/trim href))) (catch Exception _ nil)))

(defn- html-links
  "Attribute maps (lower-cased keys) of html's <link> and <a> elements."
  [html]
  (for [el (re-seq #"(?is)<(?:link|a)\b[^>]*>" (str html))]
    (into {} (for [[_ k v] (re-seq #"(?is)([\w-]+)\s*=\s*(\"[^\"]*\"|'[^']*'|[^\s>]+)" el)]
               [(str/lower-case k) (str/replace v #"^[\"']|[\"']$" "")]))))

(defn- rel? [a rel] (boolean (some #{rel} (str/split (str/lower-case (a "rel" "")) #"\s+"))))

(defn- json-alternate
  "The href of html's <link rel=alternate type=application/json> (§5.8), resolved against base."
  [base html]
  (some #(when (and (rel? % "alternate") (= "application/json" (str/lower-case (% "type" ""))))
           (resolve-url base (% "href")))
        (html-links html)))

(defn- item-doc
  "{:doc :url} for the blyg item document at url, itself or via its permalink page's alternate link."
  [get url]
  (when-let [r (ok-body (get url "application/json, text/html"))]
    (let [doc (parse-json (:body r))]
      (if (:blyg doc)
        {:doc doc :url (:final-url r)}
        (when-let [alt (json-alternate (:final-url r) (:body r))]
          (when-let [r2 (ok-body (get alt "application/json"))]
            (let [doc (parse-json (:body r2))]
              (when (:blyg doc) {:doc doc :url (:final-url r2)}))))))))

(defn resolve-target
  "The snapshot a reply to url quotes and cites (the shape of state :remotes values), or nil if url
  doesn't lead to a blyg item. get is (fn [url accept] response), default http-get."
  ([url] (resolve-target http-get url))
  ([get url]
   (when-let [{:keys [doc] item-url :url} (item-doc get url)]
     (let [id       (:id doc)
           suffix   (str "items/" id ".json")
           ;; §12.2: the origin is where the item was fetched from, not what it says
           origin   (if (str/ends-with? item-url suffix)
                      (subs item-url 0 (- (count item-url) (count suffix)))
                      (some-> (:origin doc) (str/replace #"/*$" "/")))
           manifest (some-> (get (str origin "blyg.json") "application/json") ok-body :body parse-json)]
       (when (and id (:version doc) (seq origin))
         {:origin       origin
          :id           id
          :version      (:version doc)
          :kind         (:kind doc)
          :page-url     (str origin (or (:page doc) (str "f/" id "/")))
          :content-html (:content_html doc)
          :content-md   (:content_md doc)
          :source-title (:title manifest)
          :author       (or (get-in doc [:author :name]) (get-in manifest [:author :name]))
          :webmention   (some->> (:webmention manifest) (resolve-url (str origin "blyg.json")))
          :retrieved    (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS))})))))

;;; Webmentions (§15)

(defn discover-endpoint
  "page-url's webmention endpoint: its Link header, else a rel=webmention <link>/<a> in the page (W3C
  discovery). nil if none."
  [http page-url]
  (or (some->> (get-in (http page-url :method :head) [:headers "link"])
               (some #(when (re-find #"(?i)rel\s*=\s*\"?\s*webmention" %) (second (re-find #"<([^>]+)>" %))))
               (resolve-url page-url))
      (when-let [r (ok-body (http page-url))]
        (some #(when (rel? % "webmention") (resolve-url (:final-url r) (% "href"))) (html-links (:body r))))))

(defn- live-stub?
  "Is our item document at item-url serving version, as a stub? (The receiver will fetch it, §15.4.)"
  [http item-url version]
  (let [doc (some-> (http item-url :accept "application/json") ok-body :body parse-json)]
    (boolean (and doc (= version (:version doc)) (:stub_of doc)))))

(defn- form-encode [m]
  (str/join "&" (for [[k v] m] (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8")))))

(defn send-mentions
  "items (state :items) with a webmention sent for each stub that needs one, recorded as
  :mention-sent. A stub needs one unless already accepted or terminally refused for its target's
  version (§15.2); it waits while origin isn't serving it yet. Returns {:items :log [line]}."
  ([origin items] (send-mentions http origin items))
  ([http origin items]
   (reduce-kv
    (fn [{:keys [items] :as acc} k {:keys [stub-of mention-target mention-sent blyg-id version] :as entry}]
      (let [now  #(str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS))
            note (fn [acc line] (update acc :log conj (str blyg-id ": " line)))]
        (cond
          (or (nil? stub-of) (nil? mention-target) (= "withdrawn" (name (:kind entry)))
              (and (= (:target-version mention-sent) (:version stub-of))
                   (or (:ok? mention-sent) (:terminal? mention-sent))))
          acc

          (not (live-stub? http (str origin "items/" blyg-id ".json") version))
          (note acc (str "not live at v" version " yet; mention deferred"))

          :else
          (if-let [endpoint (or (:mention-endpoint entry) (discover-endpoint http mention-target))]
            (let [source    (str origin "f/" blyg-id "/")
                  {:keys [status]} (http endpoint :method :post :body (form-encode {:source source :target mention-target}))
                  ok?       (boolean (and status (<= 200 status 299)))
                  terminal? (boolean (and status (<= 400 status 499) (not= 429 status)))]
              (-> acc
                  (assoc-in [:items k :mention-sent]
                            (cond-> {:at (now) :endpoint endpoint :target-version (:version stub-of) :ok? ok? :terminal? terminal?}
                              status (assoc :status status)
                              (nil? status) (assoc :error "unreachable")))
                  (note (str "webmention to " endpoint " for " mention-target ": " (or status "unreachable")))))
            (-> acc
                (assoc-in [:items k :mention-sent] {:at (now) :target-version (:version stub-of)
                                                    :no-endpoint? true :ok? false :terminal? true})
                (note (str mention-target " has no webmention endpoint")))))))
    {:items items :log []}
    items)))
