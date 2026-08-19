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
;; resolve-event: a bare open-ended ramp/hairpin resolves to a real
;; ambient value, never a non-numeric placeholder
;; ============================================================

(deftest resolve-event-resolves-a-bare-hairpin-to-an-ambient-value
  ;; Regression coverage: a bare open-ended Ramp/Hairpin with no
  ;; preceding value (c4\<, !vol<) used to store a non-numeric
  ;; :ramp-start sentinel, resolved lazily at query time by recursing
  ;; into the rest of the chain -- sampled before a later real value
  ;; ever arrived, that sentinel could reach resolve-common as a
  ;; literal, non-numeric value and crash (ClassCastException,
  ;; clojure.lang.Keyword can't cast to java.lang.Number). Fixed at the
  ;; root cause, not papered over downstream: flat-tree-walker's
  ;; walk-assignment/apply-note-dynamics! now resolve a bare ramp's own
  ;; starting value immediately, at WALK time (see context.clj's own
  ;; ambient-value), from whatever's already ambient in the chain --
  ;; reaching ROOT's own real default (50, from common.defaults/
  ;; root-defaults, the session this walk actually runs against) --
  ;; and store that as a real, numeric point directly, so there is no
  ;; sentinel left for a query to ever see in the first place.
  ;; resolve.clj's sample still has its own defensive fallback
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
  ;; ambient-value searches the WHOLE ancestor chain (excluding whichever
  ;; context the bare ramp itself is being written into), not just root
  ;; -- an enclosing container's own real value, if one exists, wins over
  ;; root's generic default, same as it would for any other ordinary
  ;; lookup. inner's own bare !tempo< has to skip inner itself (nothing
  ;; local yet) and land on outer's real 90, not root's 92, resolved
  ;; once at walk time and stored as a real point on inner directly.
  (let [{:keys [tree root-id]} (walk "{outer: !tempo:90 {inner: !tempo< c4 d4}}")
        {:keys [part ctx-chain]} (r/locate tree root-id [0 1 1])
        dur-secs (:dur-secs (r/resolve-event {:part part :ctx-chain ctx-chain} nil 0.0 0.0))]
    (is (= [60] (:pitches part)) "c4, inner's first note, right after the bare tempo ramp")
    (is (< (Math/abs (- dur-secs (double (/ 1/4 90 1/240)))) 1e-9)
        "1/4 (c4's duration) at outer's own tempo (90) -- not root's
         generic default (92) -- proves ambient-value actually searched
         past inner's own context to outer's real one.
         1/240, not 1/60: duration is a whole-note fraction and tempo is
         quarter-note BPM, so converting to seconds needs *4 (a quarter
         note's own duration in beats is duration*4) before applying
         beats-per-minute -- see musical->seconds' own docstring.
         An epsilon compare, not = or == -- dur-secs is a double built
         from a different sequence of floating-point operations
         (musical->seconds) than this assertion's own (/ ...), so the
         last bit can legitimately differ even though both mean 2/3")))
