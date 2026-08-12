(ns ^:domain flat-domain-test
  (:require [clojure.test :refer [deftest is]]
            [input.grammar-parser :as gp]
            [core.domain.flat-domain :as d]
            [common.music-elements :as el]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- walk [text]
  (gp/parse-domain-string text))

(defn- fixture
  "Load a DSL fixture from test/resources/musics -- keeps escape-heavy
   backslash/quote-laden input (\\repeat, \\alternative, embedded strings)
   out of Clojure string literals."
  [name]
  (walk (slurp (str "test/resources/musics/" name))))

;; ============================================================
;; Transform / mutate
;; ============================================================

(deftest invert-mirrors-pitches-around-an-axis
  (let [n ((d/invert 60) (d/leaf :n nil 1/4 [60 64 67]))]
    (is (= [60 56 53] (:pitches n)))))

(deftest invert-is-a-no-op-on-a-pitchless-part
  (let [r ((d/invert 60) (d/rest* :r nil 1/4))]
    (is (nil? (:pitches r)))))

(deftest invert-with-no-axis-uses-its-own-rounded-pitch-mean
  (let [n ((d/invert) (d/leaf :n nil 1/4 [60 64 67]))]
    (is (= [68 64 61] (:pitches n))
        "mean (60+64+67)/3 = 191/3 rounds to 64, mirrored around that")))

(deftest invert-with-no-axis-is-unchanged-on-a-single-pitch
  (let [n ((d/invert) (d/leaf :n nil 1/4 [60]))]
    (is (= [60] (:pitches n)) "a single pitch is its own mean")))

(deftest invert-with-no-axis-is-a-no-op-on-a-pitchless-part
  (let [r ((d/invert) (d/rest* :r nil 1/4))]
    (is (nil? (:pitches r)))))

(deftest tonal-invert-reflects-an-ascending-scale-run-into-its-descent
  ;; An ascending scale run, tonally inverted around its own tonic, is
  ;; exactly the same scale descending -- the cleanest possible check
  ;; that scale-step (not semitone) distance is what's being reflected.
  (let [ck (el/parse-key "C.major")
        n  ((d/tonal-invert ck 60) (d/leaf :n nil 1/4 [60 62 64 65 67 69 71]))]
    (is (= [60 59 57 55 53 52 50] (:pitches n)))))

(deftest tonal-invert-differs-from-semitone-invert-off-symmetric-axis
  ;; Major third above C4 tonally inverts to a MINOR third below it (the
  ;; textbook asymmetry a plain semitone invert can't produce) --
  ;; E4(64, +4 semitones) -> A3(57, -3 semitones), not G#3(56, -4).
  (let [ck (el/parse-key "C.major")]
    (is (= [57] (:pitches ((d/tonal-invert ck 60) (d/leaf :n nil 1/4 [64])))))
    (is (= [56] (:pitches ((d/invert 60) (d/leaf :n nil 1/4 [64])))))))

(deftest tonal-invert-respects-a-non-c-tonic
  ;; Regression coverage: common.music-elements/key-pitches walks scale
  ;; steps cumulatively from the tonic WITHOUT wrapping at 12 (G major
  ;; -> [7 9 11 12 14 16 18], not [7 9 11 0 2 4 6]) -- comparing an
  ;; arbitrary pitch's own (mod 12) pitch-class against that raw form
  ;; directly would silently misclassify C/D/E/F# (G major's own 4th
  ;; through 7th degrees, only reachable there as 12/14/16/18) as
  ;; out-of-scale. An ascending G major run, inverted around its own
  ;; tonic, must come back as the same scale descending -- same shape
  ;; as the C-major check above, proving the wrap is handled.
  (let [gk (el/parse-key "G.major")
        n  ((d/tonal-invert gk 67) (d/leaf :n nil 1/4 [67 69 71 72 74 76 78]))]
    (is (= [67 66 64 62 60 59 57] (:pitches n)))))

(deftest tonal-invert-snaps-an-out-of-scale-pitch-up-first
  (let [ck (el/parse-key "C.major")]
    ;; C#4 (61) isn't in C major -- snaps up to D4 (62, degree 1) before
    ;; reflecting around C4 (degree 0), landing on B3 (59, degree -1).
    (is (= [59] (:pitches ((d/tonal-invert ck 60) (d/leaf :n nil 1/4 [61])))))))

(deftest tonal-invert-is-a-no-op-on-a-pitchless-part
  (let [ck (el/parse-key "C.major")
        r  ((d/tonal-invert ck 60) (d/rest* :r nil 1/4))]
    (is (nil? (:pitches r)))))

(deftest pitch-degree-index-round-trips
  (let [ck        (el/parse-key "C.major")
        scale-pcs (d/scale-pitch-classes ck)]
    (is (= 64 (d/degree-index->pitch scale-pcs (d/pitch->degree-index scale-pcs 64))))))

(deftest degree-leaf-builds-a-single-note-from-a-degree-index
  (let [ck (el/parse-key "C.major")]
    ;; degree-index 35 = C major's degree 0, octave 5 -- C4 (60), same
    ;; reference point tonal-invert's own tests use
    (is (= [60] (:pitches (d/degree-leaf :n nil ck 1/4 35))))))

(deftest degree-leaf-builds-a-chord-from-a-vector-of-degree-indices
  (let [ck (el/parse-key "C.major")]
    (is (= [60 64 67] (:pitches (d/degree-leaf :c nil ck 1/2 [35 37 39]))))))

(deftest degree-leaf-result-is-an-ordinary-leaf-no-degree-or-key-retained
  (let [ck (el/parse-key "C.major")
        n  (d/degree-leaf :n nil ck 1/4 35)]
    (is (d/leaf? n))
    (is (not (contains? n :degrees)))
    (is (not (contains? n :key)))))

(deftest degree-leaf-full-arity-sets-articulation-dynamic-modifiers-tied
  (let [ck (el/parse-key "C.major")
        n  (d/degree-leaf :n nil ck 1/4 35 0.9 10 [[:dynamic "f"]] true)]
    (is (= 0.9 (:articulation n)))
    (is (= 10 (:dynamic n)))
    (is (= [[:dynamic "f"]] (:modifiers n)))
    (is (:tied n))))

(deftest tonal-transpose-differs-from-semitone-transpose-by-scale-position
  ;; Up a third (2 scale degrees) in C major: C->E is 4 semitones,
  ;; D->F is only 3 -- the diatonic asymmetry a plain semitone transpose
  ;; can't produce.
  (let [ck (el/parse-key "C.major")]
    (is (= [64] (:pitches ((d/tonal-transpose ck 2) (d/leaf :n nil 1/4 [60])))))
    (is (= [65] (:pitches ((d/tonal-transpose ck 2) (d/leaf :n nil 1/4 [62])))))))

(deftest tonal-transpose-shifts-a-whole-scale-run-by-one-degree
  (let [ck (el/parse-key "C.major")
        n  ((d/tonal-transpose ck 1) (d/leaf :n nil 1/4 [60 62 64 65 67 69 71]))]
    (is (= [62 64 65 67 69 71 72] (:pitches n)))))

(deftest tonal-transpose-respects-a-non-c-tonic
  (let [gk (el/parse-key "G.major")]
    (is (= [71] (:pitches ((d/tonal-transpose gk 2) (d/leaf :n nil 1/4 [67])))))))

(deftest tonal-transpose-is-a-no-op-on-a-pitchless-part
  (let [ck (el/parse-key "C.major")
        r  ((d/tonal-transpose ck 2) (d/rest* :r nil 1/4))]
    (is (nil? (:pitches r)))))

(deftest snap-to-scale-leaves-an-in-scale-pitch-unchanged
  (let [ck (el/parse-key "C.major")]
    (is (= [60] (:pitches ((d/snap-to-scale ck) (d/leaf :n nil 1/4 [60])))))))

(deftest snap-to-scale-snaps-a-chromatic-pitch-up
  (let [ck (el/parse-key "C.major")]
    (is (= [62] (:pitches ((d/snap-to-scale ck) (d/leaf :n nil 1/4 [61])))))))

(deftest snap-to-scale-respects-a-non-c-tonic
  ;; C5 (72) is G major's own 4th degree, but only reachable via
  ;; key-pitches' raw (unwrapped) form as 12 -- same regression shape
  ;; as tonal-invert/tonal-transpose's non-C-tonic coverage.
  (let [gk (el/parse-key "G.major")]
    (is (= [72] (:pitches ((d/snap-to-scale gk) (d/leaf :n nil 1/4 [72])))))))

(deftest snap-to-scale-is-a-no-op-on-a-pitchless-part
  (let [ck (el/parse-key "C.major")
        r  ((d/snap-to-scale ck) (d/rest* :r nil 1/4))]
    (is (nil? (:pitches r)))))

(deftest tonal-harmonize-adds-a-scale-third-above-keeping-the-original
  (let [ck (el/parse-key "C.major")]
    (is (= [60 64] (:pitches ((d/tonal-harmonize ck 2) (d/leaf :n nil 1/4 [60])))))
    (is (= [62 65] (:pitches ((d/tonal-harmonize ck 2) (d/leaf :n nil 1/4 [62]))))
        "D->F is a minor third, not a major one -- same diatonic asymmetry as tonal-transpose")))

(deftest tonal-harmonize-supports-a-negative-step-for-harmony-below
  (let [ck (el/parse-key "C.major")]
    (is (= [57 60] (:pitches ((d/tonal-harmonize ck -2) (d/leaf :n nil 1/4 [60])))))))

(deftest tonal-harmonize-thickens-every-pitch-of-an-existing-chord
  (let [ck (el/parse-key "C.major")]
    (is (= [60 64 64 67] (:pitches ((d/tonal-harmonize ck 2) (d/leaf :n nil 1/4 [60 64])))))))

(deftest tonal-harmonize-is-a-no-op-on-a-pitchless-part
  (let [ck (el/parse-key "C.major")
        r  ((d/tonal-harmonize ck 2) (d/rest* :r nil 1/4))]
    (is (nil? (:pitches r)))))

(deftest dynamic-shifts-the-offset-and-defaults-a-nil-one-to-zero
  (let [n ((d/dynamic 10) (d/leaf :n nil 1/4 [60]))]
    (is (= 10 (:dynamic n))))
  (let [n ((d/dynamic 10) (d/leaf :n nil 1/4 [60] nil -5 [] false))]
    (is (= 5 (:dynamic n)))))

(deftest dynamic-is-a-no-op-on-a-part-with-no-dynamic-field
  (let [dr ((d/dynamic 10) (d/drum :dr nil 1/4 35))]
    (is (not (contains? dr :dynamic)))))

;; ============================================================
;; fold-node -- generic scaffold, exercised independently of describe/
;; freeze with a small toy handler-map
;; ============================================================

(deftest fold-node-applies-a-toy-algebra-through-real-repo-resolution
  (let [n1     (d/leaf :n1 nil 1/4 [60])
        r1     (d/rest* :r1 nil 1/4)
        inner  {:type :SEQ :id :inner :context nil :children [n1]}
        repo   {:inner inner}
        root   {:type :SEQ :id :s :context nil :children [:inner r1]}
        count-handlers {:container (fn [_ folded] (reduce + (map :result folded)))
                        :leaf      (fn [_] 1)
                        :rest      (fn [_] 1)}]
    (is (= 2 (d/fold-node root count-handlers
                          :resolve-ref (fn [id] (get repo id))))
        "1 leaf inside :inner + 1 rest at the top level")))

(deftest fold-node-default-resolve-ref-leaves-a-keyword-child-as-ref
  (let [root     {:type :SEQ :id :s :context nil :children [:elsewhere]}
        kinds    (atom [])
        handlers {:container (fn [_ folded] (swap! kinds into (map :kind folded)))}]
    (d/fold-node root handlers)
    (is (= [:ref] @kinds) "identity resolve-ref never resolves -- distinct from a failed lookup")))

(deftest fold-node-reports-a-real-failed-lookup-as-missing
  (let [root     {:type :SEQ :id :s :context nil :children [:elsewhere]}
        kinds    (atom [])
        handlers {:container (fn [_ folded] (swap! kinds into (map :kind folded)))
                  :missing   (fn [raw] raw)}]
    (d/fold-node root handlers :resolve-ref (constantly nil))
    (is (= [:missing] @kinds))))

(deftest fold-node-returns-nil-for-a-nil-node
  (is (nil? (d/fold-node nil {:container (fn [_ _] :should-not-run)}))))

;; ============================================================
;; print-structure
;; ============================================================

(deftest print-structure-shows-seq-brackets-and-leaf-count
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4 e4}")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\{ :ROOT" out))
    (is (re-find #"\{ :verse .*\(3 leaves\)" out))))

(deftest print-structure-shows-par-brackets
  (let [{:keys [tree root-id]} (walk "<<verse: {c4 d4} {e4 f4}>>")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"<< :verse" out))))

(deftest print-structure-shows-iterator-and-nested-source
  (let [{:keys [tree root-id]} (fixture "repeat-unfold.mus")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat unfold 2" out))
    (is (re-find #"\{ :s\d+ .*\(2 leaves\)" out)
        "nested source renders with its own SEQ brackets, indented under \\repeat")))

(deftest print-structure-shows-volta-with-alternative
  (let [{:keys [tree root-id]} (fixture "repeat-volta-alternative.mus")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat volta 2" out))
    (is (re-find #"\\alternative" out))
    (is (re-find #"(?s)\\repeat volta 2.*\\alternative.*\{ :s\d+ .*\(2 leaves\)" out)
        "alternative appears after the main source, same order as the input text")))

(deftest print-structure-shows-measured-tremolo
  (let [{:keys [tree root-id]} (fixture "repeat-tremolo.mus")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat tremolo 32" out))))

(deftest print-structure-reports-a-dangling-reference-instead-of-crashing
  ;; A reference to an id not parsed yet (or ever) resolves to nil via
  ;; repo -- describe-node used to let that nil ride into :children,
  ;; which then NPE'd in print-structure's (pos? (:leaf-count node)).
  (let [{:keys [tree root-id]} (walk "{song: :verse}")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\?\? :verse  \(unresolved\)" out))))

(deftest print-structure-reports-an-unresolvable-root-id-instead-of-crashing
  ;; root-id itself not existing in repo hit the exact same (pos? nil)
  ;; NPE at the top level, before ever reaching a container/child.
  (let [{:keys [tree]} (walk "{verse: c4 d4}")
        out (with-out-str (d/print-structure tree :nope))]
    (is (re-find #"\?\? :nope  \(unresolved\)" out))))

(deftest print-structure-shows-data-brackets-and-counts-plain-values-as-leaves
  ;; Data holds plain Int/Float/etc values, not Leaf/Rest/Drum records --
  ;; describe-node used to call itself on these (since they're neither
  ;; leaf?/rest?/drum? nor container?/iterator?), get nil back, and let
  ;; that nil ride into :children the same way a dangling reference did.
  ;; Also covers Data's own closing bracket, which the grammar closes
  ;; with a bare ']', not "]'".
  (let [{:keys [tree root-id]} (walk "'[1 2 3]")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"'\[ :d\d+ .*\(3 leaves\)" out))
    (is (not (re-find #"]'" out)) "Data closes with a bare ], not ]'")))

(deftest data-holds-bare-duration-atoms-as-plain-values
  ;; BareDuration ('/4, '/8., a talea authored as pure data) walks to
  ;; the exact same {:type :duration :val <rational>} shape as a bare
  ;; Pitch atom's own {:type :pitch :val <midi>} -- distinct from a
  ;; regular Note's Duration digit, which never reaches generic dispatch
  ;; at all (Note/Chord/Rest/Drum pull their own Duration via find-child).
  (let [{:keys [tree root-id]} (walk "'[/4 /8. /16]")
        data-id (first (:children (get tree root-id)))
        data    (get tree data-id)]
    (is (= [{:type :duration :val 1/4}
            {:type :duration :val 3/16}
            {:type :duration :val 1/16}]
           (:children data)))))

(deftest print-structure-shows-unit-brackets-and-preserves-a-given-id
  (let [{:keys [tree root-id]} (walk "{verse: [grp: c4 d4] e4}")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\[ :grp" out) "Unit renders with a [ bracket and keeps its explicit id")
    (is (re-find #"\(2 leaves\)" out))))
