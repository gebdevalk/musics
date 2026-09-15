(ns ^:engine wall-preset-test
  "Presets and plain cooked algos merged into one concept in the
   2026-09-09 redesign (see core.wall's own ns docstring): what used to
   be configure-preset!'s own *preset-registry* is now just
   *algo-registry* itself -- build!/build-algo! is the one way to get a
   named, ready-to-play, independently-hot-swappable entry there, off
   ANY registered factory, any number of times. This file used to be
   configure-preset!-specific; it now covers the same ground under
   build!'s own name."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [musics :as m]
            [core.repo :as repo]
            [core.wall :as wall]
            [core.async-engine :as engine]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]))

;; A tiny, deterministic factory: (fn [name {:keys [a b]}] -> name),
;; building a wall fn that just stamps [a b] onto every node it sees, so
;; a built algo's own config is trivially observable without needing
;; real MIDI/playback. params ALWAYS a plain map, same as every other
;; factory now (2026-09-11 redesign).
(defn- stamp-factory [name {:keys [a b]}]
  (wall/build-algo! name (fn [nodes _ctx _voice] (map #(assoc % :stamp [a b]) nodes))))

;; ============================================================
;; build! -- independent built algos off one factory
;; ============================================================

(deftest two-built-algos-off-one-factory-stay-independent
  (with-fresh-registries
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::bright ::stamp {:a 1 :b 2})
    (wall/build! ::dark   ::stamp {:a 9 :b 9})
    (is (= [{:stamp [1 2]}] ((wall/algo ::bright) [{}] [] nil)))
    (is (= [{:stamp [9 9]}] ((wall/algo ::dark) [{}] [] nil)))
    (is (some? (wall/factory ::stamp))
        "::stamp's own factory entry is only ever READ, never overwritten --
         unlike the old configure-algo!, which would have turned ::stamp
         itself into a resolved fn after the first configure")))

(deftest build!-preserves-the-built-fns-own-doc
  (with-fresh-registries
    (wall/register-factory! ::stamp
      (fn [name {:keys [a b]}] (wall/build-algo! name (fn [nodes _ctx _voice] (map #(assoc % :stamp [a b]) nodes))
                                        "stamps [a b] onto every node")))
    (wall/build! ::bright ::stamp {:a 1 :b 2})
    (is (= "stamps [a b] onto every node" (wall/algos ::bright)))))

(deftest build!-preserves-factory-name-and-params-onto-the-built-entry
  (with-fresh-registries
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::bright ::stamp {:a 1 :b 2})
    (is (= {:factory-name ::stamp :params {:a 1 :b 2}}
           (select-keys (get (wall/registered) ::bright) [:factory-name :params]))
        "build! stamps the recipe onto the entry, not just the resolved fn")))

(deftest unregistered-factory-name-warns-and-leaves-prior-build-untouched
  (with-fresh-registries
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::bright ::stamp {:a 1 :b 2})
    (with-out-str (wall/build! ::bright ::nonexistent {:a 5 :b 5}))
    (is (= wall/identity-algo (wall/algo ::bright))
        "an unrecognized factory-name still builds SOMETHING under ::bright
         (identity-algo, with a console warning) rather than throwing --
         same degrade-and-warn policy build! has everywhere else; unlike
         the old configure-preset!, this DOES overwrite whatever was there,
         since build! is the one, hot-swappable store now, not a second
         cache layered in front of it")))

;; ============================================================
;; resolve-config-form -- params can be literals, repo Data, or groups
;; ============================================================

(deftest build!-args-are-plain-literals-by-default
  (with-fresh-registries
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::p ::stamp {:a [60 62 64] :b 1/4})
    (is (= [{:stamp [[60 62 64] 1/4]}] ((wall/algo ::p) [{}] [] nil))
        "a literal vector with nothing keyword-shaped in it passes through unchanged")))

(deftest build!-args-resolve-real-repo-data
  (with-fresh-registries
    (let [talea {:type :DATA :id :myTalea :context (c/context)
                 :children [1/4 1/8 1/8 1/4]}
          root  {:type :ROOT :id :ROOT :context (c/context-root {})
                 :children [:myTalea]}]
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :myTalea talea))
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::p ::stamp {:a :myTalea :b 0})
    (is (= [{:stamp [[1/4 1/8 1/8 1/4] 0]}] ((wall/algo ::p) [{}] [] nil))
        "a bare keyword resolving to a :DATA container pulls its raw
         committed values, not anything Leaf/voice-shaped")))

(deftest build!-unresolvable-keyword-falls-back-to-literal
  (with-fresh-registries
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children []})
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::p ::stamp {:a :major :b 0})
    (is (= [{:stamp [:major 0]}] ((wall/algo ::p) [{}] [] nil))
        "an id that names nothing in the repo is treated as an ordinary
         literal keyword flag, not an error -- factory args are routinely
         plain flags, not repo references")))

(deftest build!-resolves-groups-recursively-preserving-collection-type
  (with-fresh-registries
    (let [color {:type :DATA :id :myColor :context (c/context) :children [60 62 64]}
          root  {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:myColor]}]
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :myColor color))
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::p ::stamp {:a [:myColor :flag] :b #{1 :myColor}})
    (is (= [{:stamp [[[60 62 64] :flag] #{1 [60 62 64]}]}]
           ((wall/algo ::p) [{}] [] nil))
        "a vector stays a vector, a set stays a set -- each item resolved
         independently, :flag unresolvable so passed through as-is")))

;; ============================================================
;; a built algo is reachable through the exact same assign-algo!/play
;; :algo mechanism every other Name shape already uses
;; ============================================================

(deftest a-built-algo-resolves-through-assign-algo!-and-doesnt-fail-validation
  (with-fresh-registries
    (let [n1    (c/context)
          verse {:type :SEQ :id :verse :context (c/context)
                 :children [(d/leaf :n1 n1 1/4 [60])]}
          root  {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]}]
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :verse verse))
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::bright ::stamp {:a 1 :b 2})
    (repo/play-latest!)
    (let [eng (engine/engine nil repo/play-tx :ROOT)]
      (binding [engine/*engine* eng]
        ;; play-top-level!'s own validate-algo-name! runs BEFORE the flush --
        ;; if a built algo's name were still rejected as "unregistered", this
        ;; whole call would throw instead of returning normally.
        (let [id (engine/play :verse :algo ::bright)]
          (is (= (wall/algo ::bright) (wall/algo (:algo (engine/voice-at eng [id])))))
          (engine/stop! eng))))))

(deftest unregister-algo!-forgets-a-built-algo-without-touching-the-underlying-factory
  (with-fresh-registries
    (wall/register-factory! ::stamp stamp-factory)
    (wall/build! ::bright ::stamp {:a 1 :b 2})
    (wall/unregister-algo! ::bright)
    (is (nil? (wall/algo ::bright)))
    (is (some? (wall/factory ::stamp)) "the factory itself is untouched")))

;; ============================================================
;; Against REAL .mus text, not hand-built repo maps -- every DataElement
;; the walker puts inside a :DATA container's :children is wrapped
;; {:type kw :val v} (flat_tree_walker.clj's :Pitch/:DurationNum/
;; walk-primitive cases), confirmed live: a hand-built fixture using
;; bare values (like the tests above) would NOT have caught
;; resolve-config-form failing to unwrap this.
;; ============================================================

(deftest build!-unwraps-a-real-parsed-duration-only-data-container
  (with-fresh-registries
    (m/reset)
    (let [{:keys [sid ids]} (m/parse "'[ /4 /8 /8 /4 ]")]
      (m/commit! sid)
      (wall/register-factory! ::stamp stamp-factory)
      (wall/build! ::p ::stamp {:a (first ids) :b 0})
      (is (= [{:stamp [[1/4 1/8 1/8 1/4] 0]}] ((wall/algo ::p) [{}] [] nil))
          "a real, walker-produced :DATA container of durations resolves to
           plain Ratios, not {:type :duration :val v} wrapper maps"))))

(deftest build!-unwraps-a-real-parsed-pitch-only-data-container
  (with-fresh-registries
    (m/reset)
    (let [{:keys [sid ids]} (m/parse "'[ C E G ]")]
      (m/commit! sid)
      (wall/register-factory! ::stamp stamp-factory)
      (wall/build! ::p ::stamp {:a (first ids) :b 0})
      (is (= [{:stamp [[60 64 67] 0]}] ((wall/algo ::p) [{}] [] nil))
          "a real, walker-produced :DATA container of pitches resolves to
           plain MIDI ints, not {:type :pitch :val v} wrapper maps"))))

(deftest a-bare-Data-reference-passed-to-play-plays-silently-not-crash
  ;; Not a build!-specific test, but a genuine, easy-to-assume-wrong
  ;; corner of the same '[ ] mechanism: a :DATA container has no
  ;; Leaf/Rest/Drum/Bar children play-node recognizes, so (play id) on
  ;; one must neither throw nor hang -- confirmed live, not assumed.
  (with-fresh-registries
    (m/reset)
    (let [{:keys [sid ids]} (m/parse "'[ /4 /8 /8 /4 ]")]
      (m/commit! sid)
      (repo/play-latest!)
      (let [eng (engine/engine nil repo/play-tx :ROOT)]
        (binding [engine/*engine* eng]
          (let [track (engine/play (first ids))]
            (Thread/sleep 100)
            (is (nil? (get @(:voices eng) [track]))
                "the voice already finished -- zero recognizable content, zero
                 duration, nothing left running")))))))
