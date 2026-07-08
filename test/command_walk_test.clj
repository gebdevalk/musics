(ns command-walk-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.grammar-parser :as gp]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- resolve-child [tree child]
  (if (keyword? child) (get tree child) child))

(defn- tokens
  "Parse + walk text via the grammar parser, return top-level tokens
   (the root container's direct children, keyword refs resolved)."
  [text]
  (let [{:keys [tree root-id]} (gp/parse-domain-string text)
        root (get tree root-id)]
    (mapv (partial resolve-child tree) (:children root))))

(defn- first-token [text] (first (tokens text)))

;; ── Times ───────────────────────────────────────────────────

(deftest times-scales-durations
  (testing "\\times 2/3 scales each duration by 2/3"
    (let [ts (tokens "\\times 2/3 [c4 d4 e4]")]
      (is (= 3 (count ts)))
      (is (every? #(= 1/6 (:duration %)) ts)
          "1/4 * 2/3 = 1/6")))

  (testing "\\times 3/4 scales each duration by 3/4"
    (let [ts (tokens "\\times 3/4 [c2 d2]")]
      (is (= 2 (count ts)))
      (is (every? #(= 3/8 (:duration %)) ts)
          "1/2 * 3/4 = 3/8"))))

;; ── Tuplet ──────────────────────────────────────────────────

(deftest tuplet-scales-durations
  (testing "\\tuplet 3/2 — play 3 in time of 2 → factor 2/3"
    (let [ts (tokens "\\tuplet 3/2 [c4 d4 e4]")]
      (is (= 3 (count ts)))
      (is (every? #(= 1/6 (:duration %)) ts)
          "1/4 * 2/3 = 1/6")))

  (testing "\\tuplet 5/4 — play 5 in time of 4 → factor 4/5"
    (let [ts (tokens "\\tuplet 5/4 [c8 d8 e8 f8 g8]")]
      (is (= 5 (count ts)))
      (is (every? #(= 1/10 (:duration %)) ts)
          "1/8 * 4/5 = 4/40 = 1/10"))))

;; ── Transpose ───────────────────────────────────────────────

(deftest transpose-shifts-pitches
  (testing "\\transpose c d shifts pitches up by 2 semitones"
    (let [base  (tokens "c4 d4")
          trans (tokens "\\transpose c d [c4 d4]")]
      (is (= 2 (count trans)))
      (is (= (mapv (partial + 2) (:pitches (first base)))
             (:pitches (first trans))))
      (is (= (mapv (partial + 2) (:pitches (second base)))
             (:pitches (second trans))))))

  (testing "\\transpose c g shifts pitches up by 7 semitones"
    (let [base  (tokens "c4")
          trans (tokens "\\transpose c g [c4]")]
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
    (let [ts   (tokens "\\repeat volta 2 [c4 d4]")
          iter (first ts)]
      (is (= 1 (count ts)))
      (is (d/iterator? iter))
      (is (= :REPEAT (:type iter)))
      (is (= 2 (get-in iter [:params :count])))
      (is (= :volta (get-in iter [:params :repeat-type]))))))

(deftest repeat-unfold-creates-iterator
  (testing "\\repeat unfold 4 produces an Iterator with unfold type"
    (let [iter (first-token "\\repeat unfold 4 [c4]")]
      (is (d/iterator? iter))
      (is (= :REPEAT (:type iter)))
      (is (= 4 (get-in iter [:params :count])))
      (is (= :unfold (get-in iter [:params :repeat-type]))))))

(deftest repeat-source-has-children
  (testing "Iterator source contains the walked notes"
    (let [iter (first-token "\\repeat volta 2 [c4 d4 e4]")]
      (is (d/iterator? iter))
      (is (d/container? (:source iter)))
      (is (= 3 (count (:children (:source iter))))))))

(deftest repeat-with-alternative
  (testing "\\repeat volta with \\alternative stores alternative composite"
    (let [iter (first-token "\\repeat volta 2 [c4 d4] \\alternative [e4 f4]")]
      (is (d/iterator? iter))
      (is (some? (get-in iter [:params :alternative])))
      (is (d/container? (get-in iter [:params :alternative]))))))

;; ── Measured tremolo (Iterator) ─────────────────────────────

(deftest measured-tremolo-creates-iterator
  (testing "\\repeat tremolo 4 produces an Iterator"
    (let [iter (first-token "\\repeat tremolo 4 [c16 d16]")]
      (is (d/iterator? iter))
      (is (= :TREMOLO (:type iter)))
      (is (= 4 (get-in iter [:params :count])))
      (is (d/container? (:source iter)))
      (is (= 2 (count (:children (:source iter))))))))

;; ── Instruction timestamps ──────────────────────────────────

(def root-ctx (c/context-root {"tempo" 120 "volume" 0.8 "timbre" 42}))

(deftest instruction-timestamp-bang-const
  (testing "!pp at start, !ff after two quarter notes → volume changes at 0.5"
    (let [seq-c (first-token "[!pp c4 d4 !ff e4]")
          ctx   (:context seq-c)]
      (is (= 30 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "pp = 30 at time 0")
      (is (= 30 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "still pp between the two dynamics")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.5))
          "ff = 80 at accumulated time 0.5"))))

(deftest instruction-timestamp-assignment
  (testing "Two !vol assignments at different positions prove timestamps"
    (let [seq-c (first-token "[c4 !vol:40 d4 !vol:80 e4]")
          ctx   (:context seq-c)]
      ;; !vol:40 at time 0.25 (after c4), !vol:80 at time 0.5 (after c4+d4)
      (is (= 40 (c/ctx-value-chain [ctx root-ctx] :vol 0.25))
          "vol = 40 at time 0.25")
      (is (= 40 (c/ctx-value-chain [ctx root-ctx] :vol 0.375))
          "still 40 between the two assignments (FIXED)")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :vol 0.5))
          "vol = 80 at time 0.5"))))

(deftest instruction-timestamp-at-start
  (testing "Instruction at start of sequence lands at time 0.0"
    (let [seq-c (first-token "[!ff c4 d4]")
          ctx   (:context seq-c)]
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "ff = 80 at time 0.0"))))

(deftest instruction-no-early-shadow
  (testing "Mid-sequence !ff does not shadow parent volume before its timestamp"
    (let [seq-c (first-token "[c4 d4 !ff e4]")
          ctx   (:context seq-c)]
      ;; !ff lands at time 0.5 -- before that, volume inherits from root-ctx (0.8)
      (is (= 0.8 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "before !ff: inherits root default 0.8")
      (is (= 0.8 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "still root default at 0.25")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.5))
          "!ff takes effect at 0.5"))))
