(ns hyperphor.thirdmind.claude
  "Minimal Claude client: (ask prompt) → answer text."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.anthropic.client.okhttp AnthropicOkHttpClient]
           [com.anthropic.models.messages Message MessageCreateParams MessageParam StopReason
            WebFetchTool20260209 WebSearchTool20260209]))

;;; Settings fall back to the logseq-claude plugin's (apiKey, systemPrompt, webAccess), so both agree.

(def ^:private plugin-settings (io/file (System/getProperty "user.home") ".logseq/settings/logseq-claude.json"))

(defn- plugin [k]
  (when (.isFile plugin-settings)
    (get (json/read-str (slurp plugin-settings)) k)))

(def default-model "claude-opus-5-5")

(def ^:private client
  (delay (if (System/getenv "ANTHROPIC_API_KEY")
           (AnthropicOkHttpClient/fromEnv)
           (-> (AnthropicOkHttpClient/builder) (.apiKey ^String (plugin "apiKey")) (.build)))))

(defn- web-access?
  "Server-side web_search / web_fetch on, unless the plugin's webAccess setting is false."
  []
  (not (false? (plugin "webAccess"))))

(defn- create [model system messages]
  (let [b (-> (MessageCreateParams/builder) (.model ^String model) (.maxTokens 16000) (.messages ^java.util.List messages))]
    (when (seq system) (.system b ^String system))
    (when (web-access?)
      (.addTool b (.build (WebSearchTool20260209/builder)))
      (.addTool b (.build (WebFetchTool20260209/builder))))
    (.create (.messages ^com.anthropic.client.AnthropicClient @client) ^MessageCreateParams (.build b))))

(defn ask
  "Send prompt to Claude (with web search/fetch); returns the answer text. Throws on refusal or an empty answer."
  [^String prompt]
  (let [model  (or (System/getenv "THIRDMIND_CLAUDE_MODEL") default-model)
        system (plugin "systemPrompt")
        user   (MessageParam/ofUser prompt)
        ;; A long server-tool round stops with pause_turn: send its content back to resume.
        ^Message msg (loop [msgs [user], n 0]
                       (let [^Message m (create model system msgs)]
                         (if (and (= StopReason/PAUSE_TURN (.orElse (.stopReason m) nil)) (< n 5))
                           (recur (conj msgs (.toParam m)) (inc n))
                           m)))
        ;; The answer is the text after the last tool round; citations split it into adjacent blocks.
        blocks (reverse (take-while #(.isPresent (.text %)) (reverse (.content msg))))
        text   (str/join (for [b blocks] (.text (.get (.text b)))))]
    (when (= StopReason/REFUSAL (.orElse (.stopReason msg) nil))
      (throw (ex-info "Claude declined to answer" {:stop "refusal"})))
    (when (str/blank? text) (throw (ex-info "Empty answer from Claude" {})))
    text))
