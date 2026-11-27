(defproject com.hyperphor/thirdmind "0.1.0-SNAPSHOT"
  :description "Side process and knowledge store for a Logseq (markdown) graph"
  :url "https://github.com/hyperphor/thirdmind"
  :license {:name "Apache 2 License"
            :url "https://opensource.org/licenses/Apache-2.0"}
  :deploy-repositories [["clojars" {:sign-releases false
                                    :username :env/clojars_username
                                    :password :env/clojars_password}]]
  :dependencies [[org.clojure/clojure "1.12.3"]
                 [com.hyperphor/tunamelt "0.2.0"]
                 [org.commonmark/commonmark "0.24.0"]
                 [org.commonmark/commonmark-ext-autolink "0.24.0"]
                 [org.clojure/data.json "2.5.2"]
                 [hiccup "2.0.0"]
                 [http-kit "2.8.0"]
                 [com.anthropic/anthropic-java "3.3.0"]]
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :resource-paths ["resources"]
  :profiles {:test {:resource-paths ["test/resources"]}}
  :main ^:skip-aot hyperphor.thirdmind.core
  :aliases {"search" ["run" "-m" "hyperphor.thirdmind.search"]
            "watch"  ["run" "-m" "hyperphor.thirdmind.watch"]
            "serve"  ["run" "-m" "hyperphor.thirdmind.ui.server"]
            "times"  ["run" "-m" "hyperphor.thirdmind.times"]
            "replay-ids" ["run" "-m" "hyperphor.thirdmind.identity-replay"]
            "blyg"   ["run" "-m" "hyperphor.thirdmind.blyg.cli"]
            "site"   ["run" "-m" "hyperphor.thirdmind.site.cli"]}
  :jvm-opts ["--enable-native-access=ALL-UNNAMED"])
