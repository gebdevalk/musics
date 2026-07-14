(defproject musics "0.1.0-SNAPSHOT"
  :description "Interactive/realtime music with REPL"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [instaparse "1.4.12"]
                 [org.clojure/core.async "1.6.681"]]
  :source-paths ["src"]
  :repl-options {:init-ns user}
  :profiles {:dev {:source-paths ["dev"]}})
