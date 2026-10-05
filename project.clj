(defproject musics "0.1.0-SNAPSHOT"
  :description "Interactive/realtime music with REPL"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [instaparse "1.4.12"]
                 [org.clojure/core.async "1.6.681"]
                 [cljfx "1.7.19"]
                 [overtone/midi-clj "0.5.0"]
                 [org.clojure/core.logic "1.1.0"]
                 ;; JavaFX for cljfx, which brings 17.0.2: 25 matches the
                 ;; JDK and no longer uses sun.misc.Unsafe (a warning on 25)
                 [org.openjfx/javafx-base "25.0.4"]
                 [org.openjfx/javafx-controls "25.0.4"]
                 [org.openjfx/javafx-graphics "25.0.4"]
                 [org.openjfx/javafx-media "25.0.4"]
                 [org.openjfx/javafx-web "25.0.4"]]
  :source-paths ["src"]
  ;; ZGC (generational, the only kind from JDK 24): sub-millisecond GC
  ;; pauses, so a collection doesn't hold up the engine's sender thread and
  ;; make notes late. Compact object headers (JDK 25): 8-byte headers, less
  ;; heap for Clojure's many small objects. Both need JDK 25 as written;
  ;; on JDK 21 drop the second and add -XX:+ZGenerational. JavaFX loads
  ;; native libraries: allowed, rather than warned about on every (gui).
  :jvm-opts ["-XX:+UseZGC" "-XX:+UseCompactObjectHeaders"
             "--enable-native-access=ALL-UNNAMED"]
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
             :test {:source-paths ^:replace ["src"]}
             ;; clj-kondo as a library, for `lein lint` -- the editor's
             ;; on-save linting is off (.lsp/config.edn)
             :lint {:dependencies [[clj-kondo/clj-kondo "2026.08.04"]]}}
  ;; lint: errors and warnings in src/ and test/, no .clj-kondo/.cache
  ;; written; verify: lint, then the full test suite
  :aliases {"lint"   ["with-profile" "+lint" "run" "-m" "clj-kondo.main"
                      "--lint" "src" "test" "--cache" "false" "--fail-level" "error"]
            "verify" ["do" ["lint"] ["test"]]}
  ;; Namespace-level metadata (see each test/*.clj's ns form), grouped
  ;; by architectural layer per CLAUDE.md -- lein test :parsing/:domain/
  ;; :engine/:repl/:algo runs just that group; plain `lein test`
  ;; (no selector) still runs everything, since :default is deliberately
  ;; not set here.
  ;;
  ;; :algo added separately from :domain -- the 25 test namespaces under
  ;; it (musics.algo.rhythmic/melodic/common/random/metric/indisp's own direct
  ;; tests: rhythm, scaling, melody, counterpoint, chance, farey, trig,
  ;; reshape, split, the ten advanced_rhythm ports, etc.) were all tagged
  ;; ^:domain despite testing the musics/algo/ tree, not musics.domain.*/common.*
  ;; (the real domain-model layer -- context/domain/ornaments/
  ;; resolve/music-elements/music-tools, which stayed ^:domain) --
  ;; musics/algo/ grew substantially after :domain's original 5-category split
  ;; and nothing ever gave it its own selector, so "just run the domain
  ;; model's own tests" and "just run the generative algorithm tree's
  ;; tests" were impossible to separate.
  :test-selectors {:parsing :parsing
                    :domain  :domain
                    :engine  :engine
                    :repl    :repl
                    :algo    :algo})
