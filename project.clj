(defproject musics "0.1.0-SNAPSHOT"
  :description "Interactive/realtime music with REPL"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [instaparse "1.4.12"]
                 [org.clojure/core.async "1.6.681"]]
  :source-paths ["src"]
  :repl-options {:init-ns user}
  :profiles {:dev {:source-paths ["dev"]}}
  ;; Namespace-level metadata (see each test/*.clj's ns form), grouped
  ;; by architectural layer per CLAUDE.md -- lein test :parsing/:domain/
  ;; :engine/:repl runs just that group; plain `lein test` (no
  ;; selector) still runs everything, since :default is deliberately
  ;; not set here.
  :test-selectors {:parsing :parsing
                    :domain  :domain
                    :engine  :engine
                    :repl    :repl})
