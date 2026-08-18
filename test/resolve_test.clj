(ns ^:domain resolve-test
  (:require [clojure.test :refer [deftest is]]
            [input.grammar-parser :as gp]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- walk [text]
  (gp/parse-domain-string text))

(defn- fixture
  "Load a DSL fixture from test/resources/musics -- keeps escape-heavy
   backslash input (\\repeat etc.) out of Clojure string literals."
  [name]
  (walk (slurp (str "test/resources/musics/" name))))

;; ============================================================
;; locate
;; ============================================================

(deftest locate-finds-a-leaf-through-nested-containers
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4 e4}")
        {:keys [part ctx-chain path]} (r/locate tree root-id [0 1])]
    (is (d/leaf? part) "path lands on the 2nd leaf of :verse")
    (is (= [62] (:pitches part)))
    (is (= [0 1] path))
    (is (= 2 (count ctx-chain))
        "chain = verse's own context, ROOT's context -- ROOT's context appears
         exactly once, not duplicated by a separately-supplied root-ctx")))

(deftest locate-descends-into-a-container-by-index
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4}")
        {:keys [part path]} (r/locate tree root-id [0])]
    (is (d/container? part))
    (is (= :verse (:id part)))
    (is (= [0] path))))

(deftest locate-returns-nil-for-out-of-range-index
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4}")]
    (is (nil? (r/locate tree root-id [5])))))

(deftest locate-returns-nil-past-a-leaf
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4}")]
    (is (nil? (r/locate tree root-id [0 0 0]))
        "path continues after already reaching a leaf")))

(deftest locate-lands-on-the-iterator-itself
  (let [{:keys [tree root-id]} (fixture "repeat-unfold.mus")
        {:keys [part]} (r/locate tree root-id [0])]
    (is (d/iterator? part) "path [0] selects the Iterator among ROOT's children")))

(deftest locate-descends-into-iterator-source
  (let [{:keys [tree root-id]} (fixture "repeat-unfold.mus")
        {:keys [part]} (r/locate tree root-id [0 0])]
    (is (d/container? part) "one more path segment steps past the Iterator into its :source")
    (is (= 2 (count (:children part))))))

(deftest locate-selects-a-child-by-id-without-knowing-its-position
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4} {chorus: g4 a4}")
        {:keys [part]} (r/locate tree root-id [:chorus])]
    (is (d/container? part))
    (is (= :chorus (:id part)))))

(deftest locate-mixes-id-and-index-selectors
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4} {chorus: g4 a4}")
        {:keys [part]} (r/locate tree root-id [:chorus 1])]
    (is (d/leaf? part))
    (is (= [69] (:pitches part)) "chorus's 2nd leaf (a4)")))

(deftest locate-returns-nil-for-unmatched-id
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4}")]
    (is (nil? (r/locate tree root-id [:bogus])))))

;; ============================================================
;; Unit (context-less container)
;; ============================================================

(deftest unit-contributes-no-context-of-its-own-to-the-chain
  ;; A leaf directly in :verse and a leaf inside a Unit nested in :verse
  ;; should see the exact same ctx-chain -- the Unit is structurally
  ;; present (it's a real container, addressable by index) but contributes
  ;; nothing to the chain, unlike every other composite type.
  ;; Path [0] is :verse itself (a top-level named Sequence); [0 0] is the
  ;; Unit, [0 0 0] a leaf inside it; [0 1] is e4, verse's other child.
  (let [{:keys [tree root-id]} (walk "{verse: '{grp: c4 d4} e4}")
        {chain-in-unit :ctx-chain}   (r/locate tree root-id [0 0 0])
        {chain-sibling :ctx-chain}   (r/locate tree root-id [0 1])]
    (is (= 2 (count chain-in-unit)) "ROOT's context + verse's context, no third for the Unit")
    (is (= chain-in-unit chain-sibling)
        "same chain whether the leaf is inside the Unit or a direct sibling of it")))

(deftest unit-is-a-real-addressable-container
  (let [{:keys [tree root-id]} (walk "{verse: '{grp: c4 d4} e4}")
        {:keys [part]} (r/locate tree root-id [0 0])]
    (is (d/container? part))
    (is (= :grp (:id part)))
    (is (= :UNIT (:type part)))))

;; ============================================================
;; resolve-event: numeric sampling must never crash on a sentinel
;; ============================================================

(deftest resolve-event-falls-back-when-a-ramp-start-sentinel-is-still-active
  ;; Regression coverage: a bare open-ended Ramp/Hairpin with no
  ;; preceding value (c4\<, !vol<) stores a :ramp-start sentinel
  ;; (core.domain.context/env-get's non-:fixed branch) precisely so a
  ;; LATER real value can interpolate from it. Sampled before that later
  ;; value ever arrives (any note between the hairpin and whatever
  ;; eventually resolves it), the sentinel used to reach resolve-common
  ;; as a literal, non-numeric value and crash -- ClassCastException,
  ;; clojure.lang.Keyword can't cast to java.lang.Number -- every numeric
  ;; consumer downstream (clamp-velocity, musical->seconds, an (int ...)
  ;; coercion). Confirmed directly with exactly this ordinary a piece,
  ;; not a contrived one -- no dynamic anywhere on the hairpin to give it
  ;; a real starting value, and the same shape as a bare !tempo< with no
  ;; local value set yet.
  ;;
  ;; Fixed at the root cause, not just papered over downstream: the
  ;; :ramp-start point is appended with ip :invalid (flat-tree-walker's
  ;; walk-assignment/apply-note-dynamics!), so ctx-value-chain treats "no
  ;; numeric value yet" exactly like "nothing said here at all" and keeps
  ;; searching the chain -- reaching ROOT's own real default (50, from
  ;; common.defaults/root-defaults, the session this walk actually runs
  ;; against), not some hardcoded literal that ignores the rest of the
  ;; chain. resolve.clj's sample still has its own defensive fallback
  ;; underneath this (for any non-numeric value, whatever the source),
  ;; but this specific scenario no longer even reaches it.
  (let [{:keys [tree root-id]} (walk "{a: c4 d4\\< e4 f4}")
        {:keys [part ctx-chain]} (r/locate tree root-id [0 2])]
    (is (= [64] (:pitches part)) "e4, the note right after the bare hairpin")
    (is (= 64 (:velocity (r/resolve-event {:part part :ctx-chain ctx-chain} nil 0.0 0.5)))
        "falls through past the sentinel to root's own real default (50
         on :volume's own 0-100 authoring scale, 64 once rescaled to
         MIDI via common.defaults/volume->midi), same as if the hairpin
         had never been written at all")))

(deftest resolve-event-falls-back-to-an-enclosing-ancestors-real-value
  ;; A bare ramp-start's ip :invalid means ctx-value-chain keeps
  ;; searching the WHOLE chain, not just gives up at root -- an enclosing
  ;; container's own real value, if one exists, wins over root's generic
  ;; default, same as it would for any other ordinary lookup.
  (let [{:keys [tree root-id]} (walk "{outer: !tempo:90 {inner: !tempo< c4 d4}}")
        {:keys [part ctx-chain]} (r/locate tree root-id [0 1 1])
        dur-secs (:dur-secs (r/resolve-event {:part part :ctx-chain ctx-chain} nil 0.0 0.0))]
    (is (= [60] (:pitches part)) "c4, inner's first note, right after the bare tempo ramp")
    (is (< (Math/abs (- dur-secs (double (/ 1/4 90 1/240)))) 1e-9)
        "1/4 (c4's duration) at outer's own tempo (90) -- not root's
         generic default (92) -- proves the search actually continued
         past inner's own still-unresolved point to outer's real one.
         1/240, not 1/60: duration is a whole-note fraction and tempo is
         quarter-note BPM, so converting to seconds needs *4 (a quarter
         note's own duration in beats is duration*4) before applying
         beats-per-minute -- see musical->seconds' own docstring.
         An epsilon compare, not = or == -- dur-secs is a double built
         from a different sequence of floating-point operations
         (musical->seconds) than this assertion's own (/ ...), so the
         last bit can legitimately differ even though both mean 2/3")))
