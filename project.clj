(defproject musics "0.1.0-SNAPSHOT"
  :description "Interactive/realtime music with REPL"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [instaparse "1.4.12"]
                 [org.clojure/core.async "1.6.681"]
                 [cljfx "1.7.19"]
                 [overtone/midi-clj "0.5.0"]]
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
  ;; :engine/:repl/:forth/:algo runs just that group; plain `lein test`
  ;; (no selector) still runs everything, since :default is deliberately
  ;; not set here.
  ;;
  ;; :algo added separately from :domain -- the 25 test namespaces under
  ;; it (algo.rithmic/melodic/common/random/metric/indisp's own direct
  ;; tests: rhythm, scaling, melody, counterpoint, chance, farey, trig,
  ;; reshape, split, the ten advanced_rhythm ports, etc.) were all tagged
  ;; ^:domain despite testing the algo/ tree, not core.domain.*/common.*
  ;; (the real domain-model layer -- context/flat-domain/ornaments/
  ;; resolve/music-elements/music-tools, which stayed ^:domain) --
  ;; algo/ grew substantially after :domain's original 5-category split
  ;; and nothing ever gave it its own selector, so "just run the domain
  ;; model's own tests" and "just run the generative algorithm tree's
  ;; tests" were impossible to separate.
  :test-selectors {:parsing :parsing
                    :domain  :domain
                    :engine  :engine
                    :repl    :repl
                    :forth   :forth
                    :algo    :algo})
