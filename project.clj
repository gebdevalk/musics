(defproject musics "0.1.0-SNAPSHOT"
  :description "Interactive/realtime music with REPL"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [instaparse "1.4.12"]]
  :source-paths ["src"]
  :repl-options {:init-ns user})
