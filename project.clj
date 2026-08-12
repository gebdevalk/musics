(defproject musics "0.1.0-SNAPSHOT"
  :description "Interactive/realtime music with REPL"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [instaparse "1.4.12"]
                 [org.clojure/core.async "1.6.681"]
                 ;; musics.clj/parse's :ids -- an ordered-set (insertion
                 ;; order, but still = -compatible with a plain #{...}) so
                 ;; play-file can use it directly, with no separate
                 ;; ordered field and no re-derivation via root-children.
                 [org.flatland/ordered "1.15.12"]]
  :source-paths ["src"]
  :repl-options {:init-ns user}
  ;; :dev's "dev" source-path exists only for lein repl's convenience
  ;; (dev/user.clj, see its own docstring) -- lein test merges :dev and
  ;; :test by default, which otherwise puts dev/user.clj on the classpath
  ;; during test runs too, and Leiningen auto-requires any user.clj it
  ;; finds there, colliding musics' own load/find with clojure.core's and
  ;; printing "already refers to" warnings on every test run. :test's
  ;; ^:replace here drops "dev" back out for that task specifically,
  ;; without touching what lein repl sees.
  :profiles {:dev  {:source-paths ["dev"]}
             :test {:source-paths ^:replace ["src"]}}
  ;; Namespace-level metadata (see each test/*.clj's ns form), grouped
  ;; by architectural layer per CLAUDE.md -- lein test :parsing/:domain/
  ;; :engine/:repl/:forth runs just that group; plain `lein test` (no
  ;; selector) still runs everything, since :default is deliberately
  ;; not set here.
  :test-selectors {:parsing :parsing
                    :domain  :domain
                    :engine  :engine
                    :repl    :repl
                    :forth   :forth})
