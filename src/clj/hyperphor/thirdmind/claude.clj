(ns hyperphor.thirdmind.claude
  "Minimal Claude client: (ask prompt) → answer text."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.anthropic.client.okhttp AnthropicOkHttpClient]
           [com.anthropic.models.messages MessageCreateParams StopReason]))

;;; Settings fall back to the logseq-claude plugin's (apiKey, systemPrompt), so both agree.

(def ^:private plugin-settings (io/file (System/getProperty "user.home") ".logseq/settings/logseq-claude.json"))

(defn- plugin [k]
  (when (.isFile plugin-settings)
    (get (json/read-str (slurp plugin-settings)) k)))

(def default-model "claude-opus-5-5")

(def ^:private client
  (delay (if (System/getenv "ANTHROPIC_API_KEY")
           (AnthropicOkHttpClient/fromEnv)
           (-> (AnthropicOkHttpClient/builder) (.apiKey ^String (plugin "apiKey")) (.build)))))

(defn ask
  "Send prompt to Claude; returns the answer text. Throws on refusal or an empty answer."
  [^String prompt]
  (let [model  (or (System/getenv "THIRDMIND_CLAUDE_MODEL") default-model)
        system (plugin "systemPrompt")
        params (cond-> (-> (MessageCreateParams/builder)
                           (.model ^String model)
                           (.maxTokens 16000)
                           (.addUserMessage prompt))
                 (seq system) (.system ^String system)
                 true         (.build))
        msg    (.create (.messages ^com.anthropic.client.AnthropicClient @client) ^MessageCreateParams params)
        text   (str/join "\n\n" (for [b (.content msg) :let [t (.text b)] :when (.isPresent t)] (.text (.get t))))]
    (when (= StopReason/REFUSAL (.orElse (.stopReason msg) nil))
      (throw (ex-info "Claude declined to answer" {:stop "refusal"})))
    (when (str/blank? text) (throw (ex-info "Empty answer from Claude" {})))
    text))
