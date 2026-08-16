(ns ^:parsing token-id-test
  "Tests for leaf token ID extraction via insta/span.
   Verifies that leaf.id is the original input text, not a computed value.
   Run: lein test token-id-test"
  (:require [clojure.test :refer [deftest is testing]]
            [input.grammar-parser :as gp]
            [core.domain.flat-domain :as d]))

(defn- parse [text] (gp/parse-domain-string text))

(defn- resolve-child [tree child]
  (if (keyword? child) (get tree child) child))

(defn- collect
  "Walk a {:tree :root-id} result from root, collecting nodes matching pred."
  [pred {:keys [tree root-id]}]
  (letfn [(walk [node]
            (cond
              (pred node)         [node]
              (d/container? node) (mapcat walk (map (partial resolve-child tree) (:children node)))
              :else               []))]
    (walk (get tree root-id))))

(defn- all-leaves [result] (collect d/leaf? result))
(defn- all-rests  [result] (collect d/rest? result))

;; ============================================================
;; Note token IDs
;; ============================================================

(deftest note-token-ids
  (testing "simple notes preserve original text as id"
    (let [ls (all-leaves (parse "{c4 d e}"))]
      (is (= "c4" (:id (nth ls 0))))
      (is (= "d"  (:id (nth ls 1))))
      (is (= "e"  (:id (nth ls 2))))))

  (testing "notes with accidentals"
    (let [ls (all-leaves (parse "{c#4 eb}"))]
      (is (= "c#4" (:id (first ls))))
      (is (= "eb"  (:id (second ls))))))

  (testing "notes with octave ticks"
    (let [ls (all-leaves (parse "{c'' d,}"))]
      (is (= "c''" (:id (first ls))))
      (is (= "d,"  (:id (second ls))))))

  (testing "note with articulation includes full token"
    (let [ls (all-leaves (parse "{c4-.}"))]
      (is (= "c4-." (:id (first ls))))))

  (testing "note with tie"
    (let [ls (all-leaves (parse "{c4~ c}"))]
      (is (= "c4~" (:id (first ls)))))))

;; ============================================================
;; Rest token IDs
;; ============================================================

(deftest rest-token-ids
  (testing "rests preserve original text as id"
    (let [rs (all-rests (parse "{r4 r2}"))]
      (is (= "r4" (:id (first rs))))
      (is (= "r2" (:id (second rs))))))

  (testing "bare rest without duration"
    (let [rs (all-rests (parse "{c4 r}"))]
      (is (= "r" (:id (first rs)))))))

;; ============================================================
;; Chord token IDs
;; ============================================================

(deftest chord-token-ids
  (testing "chord preserves original text as id"
    (let [ls (all-leaves (parse "{<c e g>4}"))]
      (is (= "<c e g>4" (:id (first ls))))))

  (testing "chord without duration"
    (let [ls (all-leaves (parse "{c4 <c e g>}"))]
      (is (= "<c e g>" (:id (second ls)))))))

;; ============================================================
;; Inside composites
;; ============================================================

(deftest token-ids-in-sequence
  (testing "notes inside a sequence have correct token ids"
    (let [ls (all-leaves (parse "{c4 d e f}"))]
      (is (= 4 (count ls)))
      (is (= "c4" (:id (nth ls 0))))
      (is (= "d"  (:id (nth ls 1))))
      (is (= "e"  (:id (nth ls 2))))
      (is (= "f"  (:id (nth ls 3)))))))

(deftest token-ids-in-parallel
  (testing "notes inside parallel sequences have correct token ids"
    (let [ls (all-leaves (parse "<<{c4 d} {e f}>>"))]
      (is (= 4 (count ls)))
      (is (= "c4" (:id (nth ls 0))))
      (is (= "d"  (:id (nth ls 1))))
      (is (= "e"  (:id (nth ls 2))))
      (is (= "f"  (:id (nth ls 3)))))))

(deftest token-ids-with-instructions
  (testing "token ids correct when interleaved with instructions"
    (let [ls (all-leaves (parse "{verse: !mf c4 d !ff e}"))]
      (is (= 3 (count ls)))
      (is (= "c4" (:id (nth ls 0))))
      (is (= "d"  (:id (nth ls 1))))
      (is (= "e"  (:id (nth ls 2)))))))

;; ============================================================
;; \transpose respells token ids
;; ============================================================
;; Unlike every other case above, a transposed leaf's :id is NOT
;; necessarily the original source text -- its pitch actually changed,
;; so LilyPond itself respells the note name too (\transpose c d { c4 }
;; prints as d4, not c4). See flat-tree-walker/respell-fn and
;; flat-core-builder/transpose-pitches!.
;;
;; Every note goes through the same key-aware lookup regardless of
;; interval -- there's no separate "it's just an octave, don't bother"
;; special case. A whole-octave interval just happens to leave the
;; pitch class unchanged, so the lookup naturally returns the same
;; letter it started with.
;;
;; The token's own absolute-vs-relative format is preserved either way,
;; keyed off letter case (musics.ebnf splits Pitch into PitchLetterAbs/
;; PitchLetterRel for exactly this reason, same rule leaf-parser/
;; resolve-pitch already used) -- a relative note (lowercase, e.g. "d")
;; is respelled as another relative note, an absolute note (uppercase,
;; e.g. "C5/2" or even a bare "C4") stays absolute, always with a fresh
;; explicit octave digit (never reusing stale digits from the original
;; token, which for an absolute note might not even have been an octave
;; at all -- see the "no explicit octave digit" case below) -- and its
;; duration/articulation/tie suffix is never touched, only the
;; pitch-naming prefix.

(deftest transpose-respells-token-ids
  (testing "relative notes: letter/accidental respelled, no key in scope -- sharps"
    (let [ls (all-leaves (parse "{\\transpose c d (c4 d e)}"))]
      (is (= [62 64 66] (map (comp first :pitches) ls)))
      (is (= ["d4" "e" "f#"] (map :id ls)))))

  (testing "relative notes: a key in scope picks flats when its signature does"
    (let [ls (all-leaves (parse "{ !key:F.major \\transpose c d (c4 d e) }"))]
      (is (= ["d4" "e" "gb"] (map :id ls)))))

  (testing "absolute notes, whole-octave transpose: only the octave digit moves"
    (let [ls (all-leaves (parse "{\\transpose c c' (C5/2 D5/)}"))]
      (is (= ["C6/2" "D6/"] (map :id ls)))))

  (testing "absolute note with no explicit octave digit -- resolves at the implicit default octave (4), and the regenerated id always gets a fresh octave digit, never confused with a duration"
    (let [ls (all-leaves (parse "{\\transpose c c' (C4)}"))]
      (is (= ["C5/"] (map :id ls)))))

  (testing "absolute notes, non-octave transpose: letter respelled, octave recomputed"
    (let [ls (all-leaves (parse "{\\transpose c d (C5/2)}"))]
      (is (= ["D5/2"] (map :id ls))))))
