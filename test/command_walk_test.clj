(ns command-walk-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.grammar-parser :as gp]
            [core.domain.music-domain :as d]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- tokens
  "Parse + walk text via the grammar parser, return top-level tokens."
  [text]
  (:tokens (gp/parse-domain-string text)))

(defn- first-token [text] (first (tokens text)))

;; ── Times ───────────────────────────────────────────────────

(deftest times-scales-durations
  (testing "\\times 2/3 scales each duration by 2/3"
    (let [ts (tokens "\\times 2/3 {c4 d4 e4}")]
      (is (= 3 (count ts)))
      (is (every? #(= 1/6 (:duration %)) ts)
          "1/4 * 2/3 = 1/6")))

  (testing "\\times 3/4 scales each duration by 3/4"
    (let [ts (tokens "\\times 3/4 {c2 d2}")]
      (is (= 2 (count ts)))
      (is (every? #(= 3/8 (:duration %)) ts)
          "1/2 * 3/4 = 3/8"))))

;; ── Tuplet ──────────────────────────────────────────────────

(deftest tuplet-scales-durations
  (testing "\\tuplet 3/2 — play 3 in time of 2 → factor 2/3"
    (let [ts (tokens "\\tuplet 3/2 {c4 d4 e4}")]
      (is (= 3 (count ts)))
      (is (every? #(= 1/6 (:duration %)) ts)
          "1/4 * 2/3 = 1/6")))

  (testing "\\tuplet 5/4 — play 5 in time of 4 → factor 4/5"
    (let [ts (tokens "\\tuplet 5/4 {c8 d8 e8 f8 g8}")]
      (is (= 5 (count ts)))
      (is (every? #(= 1/10 (:duration %)) ts)
          "1/8 * 4/5 = 4/40 = 1/10"))))

;; ── Transpose ───────────────────────────────────────────────

(deftest transpose-shifts-pitches
  (testing "\\transpose c d shifts pitches up by 2 semitones"
    (let [base  (tokens "c4 d4")
          trans (tokens "\\transpose c d {c4 d4}")]
      (is (= 2 (count trans)))
      (is (= (mapv (partial + 2) (:pitches (first base)))
             (:pitches (first trans))))
      (is (= (mapv (partial + 2) (:pitches (second base)))
             (:pitches (second trans))))))

  (testing "\\transpose c g shifts pitches up by 7 semitones"
    (let [base  (tokens "c4")
          trans (tokens "\\transpose c g {c4}")]
      (is (= (mapv (partial + 7) (:pitches (first base)))
             (:pitches (first trans)))))))

;; ── Grace ───────────────────────────────────────────────────

(deftest grace-zeroes-duration
  (testing "\\grace sets duration to 0"
    (let [t (first-token "\\grace c8")]
      (is (= 0 (:duration t)))))

  (testing "\\grace adds grace modifier"
    (let [t (first-token "\\grace c8")]
      (is (some #(= "grace" (first %)) (:modifiers t))))))

(deftest acciaccatura-tags-type
  (testing "\\acciaccatura tags with acciaccatura"
    (let [t (first-token "\\acciaccatura c8")]
      (is (= 0 (:duration t)))
      (is (some #(and (= "grace" (first %))
                      (= "acciaccatura" (second %)))
                (:modifiers t))))))

(deftest appoggiatura-tags-type
  (testing "\\appoggiatura tags with appoggiatura"
    (let [t (first-token "\\appoggiatura c8")]
      (is (= 0 (:duration t)))
      (is (some #(and (= "grace" (first %))
                      (= "appoggiatura" (second %)))
                (:modifiers t))))))

;; ── Tremolo (note/chord) ────────────────────────────────────

(deftest tremolo-note-modifier
  (testing "c4:32 preserves duration and adds tremolo modifier"
    (let [t (first-token "c4:32")]
      (is (= 1/4 (:duration t)))
      (is (some #(and (= "tremolo" (first %))
                      (= 32 (second %)))
                (:modifiers t))))))

(deftest tremolo-chord-modifier
  (testing "<c e>4:32 adds tremolo modifier to chord"
    (let [t (first-token "<c e>4:32")]
      (is (= 1/4 (:duration t)))
      (is (some #(= "tremolo" (first %)) (:modifiers t)))
      (is (< 1 (count (:pitches t)))
          "chord should have multiple pitches"))))

;; ── Repeat (Iterator) ──────────────────────────────────────

(deftest repeat-volta-creates-iterator
  (testing "\\repeat volta 2 produces an Iterator"
    (let [ts   (tokens "\\repeat volta 2 {c4 d4}")
          iter (first ts)]
      (is (= 1 (count ts)))
      (is (d/iterator? iter))
      (is (= :REPEAT (:type iter)))
      (is (= 2 (get-in iter [:params :count])))
      (is (= :volta (get-in iter [:params :repeat-type]))))))

(deftest repeat-unfold-creates-iterator
  (testing "\\repeat unfold 4 produces an Iterator with unfold type"
    (let [iter (first-token "\\repeat unfold 4 {c4}")]
      (is (d/iterator? iter))
      (is (= :REPEAT (:type iter)))
      (is (= 4 (get-in iter [:params :count])))
      (is (= :unfold (get-in iter [:params :repeat-type]))))))

(deftest repeat-source-has-children
  (testing "Iterator source contains the walked notes"
    (let [iter (first-token "\\repeat volta 2 {c4 d4 e4}")]
      (is (d/iterator? iter))
      (is (d/composite? (:source iter)))
      (is (= 3 (d/composite-count (:source iter)))))))

(deftest repeat-with-alternative
  (testing "\\repeat volta with \\alternative stores alternative composite"
    (let [iter (first-token "\\repeat volta 2 {c4 d4} \\alternative {e4 f4}")]
      (is (d/iterator? iter))
      (is (some? (get-in iter [:params :alternative])))
      (is (d/composite? (get-in iter [:params :alternative]))))))

;; ── Measured tremolo (Iterator) ─────────────────────────────

(deftest measured-tremolo-creates-iterator
  (testing "\\repeat tremolo 4 produces an Iterator"
    (let [iter (first-token "\\repeat tremolo 4 {c16 d16}")]
      (is (d/iterator? iter))
      (is (= :TREMOLO (:type iter)))
      (is (= 4 (get-in iter [:params :count])))
      (is (d/composite? (:source iter)))
      (is (= 2 (d/composite-count (:source iter)))))))
