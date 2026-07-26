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

(deftest grace-borrows-duration
  (testing "\\grace borrows a capped duration from the main note (never zero)"
    ;; c8 (1/8) wants to borrow from d4 (1/4); cap = 1/4 * 1/4 = 1/16,
    ;; so the grace note is clamped down to 1/16.
    (let [t (first-token "\\grace c8 d4")]
      (is (= 1/16 (:duration t)))))

  (testing "\\grace adds grace modifier"
    (let [t (first-token "\\grace c8 d4")]
      (is (some #(= "grace" (first %)) (:modifiers t)))))

  (testing "\\grace shrinks the main note by exactly the borrowed amount"
    (let [ts (tokens "\\grace c8 d4")]
      (is (= 3/16 (:duration (second ts)))))))

(deftest acciaccatura-tags-type
  (testing "\\acciaccatura tags with acciaccatura and borrows duration"
    (let [t (first-token "\\acciaccatura c8 d4")]
      (is (= 1/16 (:duration t)))
      (is (some #(and (= "grace" (first %))
                      (= "acciaccatura" (second %)))
                (:modifiers t))))))

(deftest appoggiatura-tags-type
  (testing "\\appoggiatura tags with appoggiatura and borrows duration"
    (let [t (first-token "\\appoggiatura c8 d4")]
      (is (= 1/16 (:duration t)))
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

;; ── Dynamic marks glued onto notes/chords ───────────────────

(deftest note-dynamic-modifier
  (testing "c4\\f adds a dynamic modifier tuple, same shape as tremolo/ornament"
    (let [t (first-token "c4\\f")]
      (is (some #(= ["dynamic" "f"] %) (:modifiers t))))))

(deftest chord-dynamic-modifier
  (testing "<c e g>4\\mf adds a dynamic modifier tuple to the chord"
    (let [t (first-token "<c e g>4\\mf")]
      (is (some #(= ["dynamic" "mf"] %) (:modifiers t))))))

(deftest note-hairpin-modifier
  (testing "c4\\< adds a hairpin modifier tuple"
    (let [t (first-token "c4\\<")]
      (is (some #(= ["hairpin" "<"] %) (:modifiers t)))))
  (testing "c4\\> adds a hairpin modifier tuple"
    (let [t (first-token "c4\\>")]
      (is (some #(= ["hairpin" ">"] %) (:modifiers t))))))

(deftest note-dynamic-hairpin-chain-modifier
  (testing "c4\\mf\\< carries both modifier tuples, dynamic then hairpin"
    (let [t (first-token "c4\\mf\\<")]
      (is (some #(= ["dynamic" "mf"] %) (:modifiers t)))
      (is (some #(= ["hairpin" "<"] %) (:modifiers t))))))

;; note-dynamic-sets-volume-going-forward and the hairpin/chain equivalents
;; live further down, after root-ctx is defined -- see the "Instruction
;; timestamps" section.

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
      (is (d/container? (:source iter)))
      (is (= 3 (count (:children (:source iter))))))))

(deftest repeat-with-alternative
  (testing "\\repeat volta with \\alternative stores alternative composite"
    (let [iter (first-token "\\repeat volta 2 {c4 d4} \\alternative {e4 f4}")]
      (is (d/iterator? iter))
      (is (some? (get-in iter [:params :alternative])))
      (is (d/container? (get-in iter [:params :alternative]))))))

;; ── Measured tremolo (Iterator) ─────────────────────────────

(deftest measured-tremolo-creates-iterator
  (testing "\\repeat tremolo 4 produces an Iterator"
    (let [iter (first-token "\\repeat tremolo 4 {c16 d16}")]
      (is (d/iterator? iter))
      (is (= :TREMOLO (:type iter)))
      (is (= 4 (get-in iter [:params :count])))
      (is (d/container? (:source iter)))
      (is (= 2 (count (:children (:source iter))))))))

;; ── Instruction timestamps ──────────────────────────────────

(def root-ctx (c/context-root {"Tempo" 120 "volume" 0.8 "timbre" 42}))

(deftest note-dynamic-sets-volume-going-forward
  (testing "c4\\f behaves like a bare !f BangConst written just before d4 --
            volume changes at d4's own onset, same as a note-glued dynamic
            in LilyPond"
    (let [seq-c (first-token "{c4 d4\\f e4}")
          ctx   (:context seq-c)]
      (is (= 0.8 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "before d4: inherits root default 0.8, no dynamic fired yet")
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "f = 70, in effect from d4's onset (t=0.25) onward")
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 0.5))
          "still forte at e4"))))

(deftest note-dynamic-hairpin-chain-produces-a-real-crescendo
  (testing "c4\\mf\\< ... f4\\ff\\> chains a dynamic and a hairpin on the
            same note -- the hairpin re-stamps the dynamic's own point with
            its direction instead of the bare open-ended sentinel, so the
            volume actually ramps smoothly between the two dynamics"
    (let [seq-c (first-token "{c4 d4\\mf\\< e4 f4\\ff\\> g4}")
          ctx   (:context seq-c)]
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "mf = 60 at d4's onset")
      (is (= 70.0 (c/ctx-value-chain [ctx root-ctx] :volume 0.5))
          "midway between mf (60) and ff (80): a real interpolated crescendo")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.75))
          "ff = 80 at f4's onset")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 1.0))
          "holds at ff after the decrescendo's own point, same as any :fixed value"))))

(deftest note-bare-hairpin-matches-existing-open-ended-ramp-behavior
  (testing "c4\\< with no preceding dynamic on the same note behaves exactly
            like a bare !vol< Assignment -- same :ramp-start sentinel, not a
            new/different mechanism"
    (let [seq-c (first-token "{c4 d4\\< e4}")
          ctx   (:context seq-c)]
      (is (= :ramp-start (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "no known start value yet, same open question a bare !vol< leaves"))))

(deftest instruction-timestamp-bang-const
  (testing "!pp at start, !ff after two quarter notes → volume changes at 0.5"
    (let [seq-c (first-token "{!pp c4 d4 !ff e4}")
          ctx   (:context seq-c)]
      (is (= 30 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "pp = 30 at time 0")
      (is (= 30 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "still pp between the two dynamics")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.5))
          "ff = 80 at accumulated time 0.5"))))

(deftest instruction-timestamp-assignment
  (testing "Two !vol assignments at different positions prove timestamps"
    (let [seq-c (first-token "{c4 !vol:40 d4 !vol:80 e4}")
          ctx   (:context seq-c)]
      ;; !vol:40 at time 0.25 (after c4), !vol:80 at time 0.5 (after c4+d4)
      ;; :vol is an alias of :volume -- walk-assignment canonicalizes it,
      ;; so it must be queried back under the canonical key.
      (is (= 40 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "vol = 40 at time 0.25")
      (is (= 40 (c/ctx-value-chain [ctx root-ctx] :volume 0.375))
          "still 40 between the two assignments (FIXED)")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.5))
          "vol = 80 at time 0.5"))))

(deftest instruction-timestamp-at-start
  (testing "Instruction at start of sequence lands at time 0.0"
    (let [seq-c (first-token "{!ff c4 d4}")
          ctx   (:context seq-c)]
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "ff = 80 at time 0.0"))))

(deftest instruction-no-early-shadow
  (testing "Mid-sequence !ff does not shadow parent volume before its timestamp"
    (let [seq-c (first-token "{c4 d4 !ff e4}")
          ctx   (:context seq-c)]
      ;; !ff lands at time 0.5 -- before that, volume inherits from root-ctx (0.8)
      (is (= 0.8 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "before !ff: inherits root default 0.8")
      (is (= 0.8 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "still root default at 0.25")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.5))
          "!ff takes effect at 0.5"))))

;; ── Meter ───────────────────────────────────────────────────

(deftest meter-bare-ratio-reaches-context
  (testing "!Meter:7/8 (bare ratio) actually lands in the context, not just
            the printed instruction -- this was silently a no-op before"
    (let [seq-c (first-token "{!Meter:7/8 c4 d4}")
          ctx   (:context seq-c)
          m     (c/ctx-value-chain [ctx root-ctx] :Meter 0.0)]
      (is (= 7 (:num m)))
      (is (= 8 (:den m)))
      (is (nil? (:subdivisions m))))))

(deftest meter-additive-string-reaches-context
  (testing "!Meter:\"7/8(2+2+3)\" (quoted, additive) sets an explicit grouping"
    (let [seq-c (first-token "{!Meter:\"7/8(2+2+3)\" c4 d4}")
          ctx   (:context seq-c)
          m     (c/ctx-value-chain [ctx root-ctx] :Meter 0.0)]
      (is (= 7 (:num m)))
      (is (= 8 (:den m)))
      (is (= [2 2 3] (:subdivisions m))))))

(deftest meter-alias-canonicalizes
  (testing "!M:3/4 (the :M alias) reads back under the canonical :Meter key"
    (let [seq-c (first-token "{!M:3/4 c4}")
          ctx   (:context seq-c)
          m     (c/ctx-value-chain [ctx root-ctx] :Meter 0.0)]
      (is (= 3 (:num m)))
      (is (= 4 (:den m))))))

;; ── Tempo ───────────────────────────────────────────────────

(deftest tempo-bare-int-reaches-context
  (testing "!tempo:120 (bare BPM, no note-value) lands under :Tempo"
    (let [seq-c (first-token "{!tempo:120 c4}")
          ctx   (:context seq-c)]
      (is (= 120 (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))))))

(deftest tempo-mark-quarter-equivalent
  (testing "!tempo:4=120 (quarter=120) is the same as the bare-BPM form"
    (let [seq-c (first-token "{!tempo:4=120 c4}")
          ctx   (:context seq-c)]
      (is (= 120 (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))))))

(deftest tempo-mark-eighth-note-halves
  (testing "!tempo:8=120 (eighth=120) is quarter-equivalent 60 -- an eighth
            note is half a quarter, so eighth=120 is the same speed as
            quarter=60"
    (let [seq-c (first-token "{!tempo:8=120 c4}")
          ctx   (:context seq-c)]
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))))))

(deftest tempo-mark-ratio-note-value
  (testing "!tempo:3/8=120 (dotted-quarter=120) takes the ratio as-is:
            120 * 3/8 * 4 = 180"
    (let [seq-c (first-token "{!tempo:3/8=120 c4}")
          ctx   (:context seq-c)]
      (is (= 180 (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))))))

(deftest tempo-Tempo-and-T-aliases-canonicalize
  (testing "!Tempo:130 and !T:140 both read back under the same :Tempo key
            (previously broken -- resolve.clj queried lowercase :tempo,
            which only the bare !tempo: spelling happened to match)"
    (let [seq-Tempo (first-token "{!Tempo:130 c4}")
          seq-T     (first-token "{!T:140 c4}")]
      (is (= 130 (c/ctx-value-chain [(:context seq-Tempo) root-ctx] :Tempo 0.0)))
      (is (= 140 (c/ctx-value-chain [(:context seq-T) root-ctx] :Tempo 0.0))))))
