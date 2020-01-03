(defproject sweet-tooth/describe "0.3.0"
  :description "Describe data structures"
  :url "https://github.com/sweet-tooth-clojure/describe"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[aysylu/loom "1.0.2"]]
  :plugins [[lein-cloverage "1.0.13"]
            [lein-shell "0.5.0"]
            [lein-ancient "0.6.15"]
            [lein-changelog "0.3.2"]
            [lein-doo "0.1.10"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.0"]
                                  [org.clojure/clojurescript "1.10.516"]]}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"update-readme-version" ["shell" "sed" "-i" "s/\\\\[sweet-tooth\\/describe \"[0-9.]*\"\\\\]/[sweet-tooth\\/describe \"${:version}\"]/" "README.md"]}
  :release-tasks [["shell" "git" "diff" "--exit-code"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["changelog" "release"]
                  ["update-readme-version"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["vcs" "push"]]

  :cljsbuild {:builds [{:id           "test"
                        :source-paths ["src" "test"]
                        :compiler     {:output-to     "target/testable.js"
                                       :main          sweet-tooth.cljs-test-runner
                                       :optimizations :none
                                       :target        :nodejs}}]}

  :doo {:build "test"
        :alias {:default [:node]}})
