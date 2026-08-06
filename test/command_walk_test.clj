(ns ^:parsing command-walk-test
  (:require [clojure.test :refer [deftest is testing]]
            [input.grammar-parser :as gp]
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
    (let [ts (tokens "\\times 2/3 (c4 d4 e4)")]
      (is (= 3 (count ts)))
      (is (every? #(= 1/6 (:duration %)) ts)
          "1/4 * 2/3 = 1/6")))

  (testing "\\times 3/4 scales each duration by 3/4"
    (let [ts (tokens "\\times 3/4 (c2 d2)")]
      (is (= 2 (count ts)))
      (is (every? #(= 3/8 (:duration %)) ts)
          "1/2 * 3/4 = 3/8"))))

;; ── Tuplet ──────────────────────────────────────────────────

(deftest tuplet-scales-durations
  (testing "\\tuplet 3/2 — play 3 in time of 2 → factor 2/3"
    (let [ts (tokens "\\tuplet 3/2 (c4 d4 e4)")]
      (is (= 3 (count ts)))
      (is (every? #(= 1/6 (:duration %)) ts)
          "1/4 * 2/3 = 1/6")))

  (testing "\\tuplet 5/4 — play 5 in time of 4 → factor 4/5"
    (let [ts (tokens "\\tuplet 5/4 (c8 d8 e8 f8 g8)")]
      (is (= 5 (count ts)))
      (is (every? #(= 1/10 (:duration %)) ts)
          "1/8 * 4/5 = 4/40 = 1/10"))))

;; ── Transpose ───────────────────────────────────────────────

(deftest transpose-shifts-pitches
  (testing "\\transpose c d shifts pitches up by 2 semitones"
    (let [base  (tokens "c4 d4")
          trans (tokens "\\transpose c d (c4 d4)")]
      (is (= 2 (count trans)))
      (is (= (mapv (partial + 2) (:pitches (first base)))
             (:pitches (first trans))))
      (is (= (mapv (partial + 2) (:pitches (second base)))
             (:pitches (second trans))))))

  (testing "\\transpose c g shifts pitches up by 7 semitones"
    (let [base  (tokens "c4")
          trans (tokens "\\transpose c g (c4)")]
      (is (= (mapv (partial + 7) (:pitches (first base)))
             (:pitches (first trans)))))))

;; ── Key-implied accidentals ─────────────────────────────────
;; A bare (unmarked) pitch letter resolves against the active key's own
;; implied accidental by default (:accidentals :implied) -- an explicit
;; accidental always overrides it outright, same as real notation.
;; :accidentals :explicit switches back to literal/LilyPond-style
;; (bare letter always natural, key ignored), and C major (the context
;; default when no !key: is ever set) implies nothing either way, so
;; any piece that never sets a key is completely unaffected.

(defn- leaf-tokens
  "Like tokens, but drops instruction records (!key:/!accidentals:/etc.
   are ALSO top-level children, same as a note is -- not just the notes
   the test cares about)."
  [text]
  (filterv d/leaf? (tokens text)))

(deftest key-implies-accidentals-on-bare-letters
  (testing "D major sharps F and C; other bare letters stay natural"
    (let [ts (leaf-tokens "!key:D.major c4 d e f g a b c")]
      (is (= [61 62 64 66 67 69 71 73] (mapv (comp first :pitches) ts))
          "C# D E F# G A B C#, i.e. every pitch class altered exactly where D major alters it")))
  (testing "F major flats B only"
    ;; b is a major 7th from the default relative reference (c4) --
    ;; \relative's nearest-fourth rule folds that down an octave, so b
    ;; lands at octave 3, not 4 (58 = Bb3, key-implied flat); the
    ;; following c folds back up to octave 4 (60 = C4, unaltered).
    (let [ts (leaf-tokens "!key:F.major b4 c")]
      (is (= [58 60] (mapv (comp first :pitches) ts)) "Bb3, then C4 (unaltered)")))
  (testing "C major (no key set) implies nothing"
    (let [ts (leaf-tokens "c4 f4")]
      (is (= [60 65] (mapv (comp first :pitches) ts))))))

(deftest explicit-accidental-overrides-key
  (testing "an explicit accidental always wins, key notwithstanding"
    (let [ts (leaf-tokens "!key:D.major fn4 f4")]
      (is (= [65 66] (mapv (comp first :pitches) ts))
          "explicit natural first (65, F), then bare f deferring to the key (66, F#)"))))

(deftest accidentals-explicit-mode-disables-key-implication
  (testing "!accidentals:explicit makes every bare letter literal again, regardless of key"
    (let [ts (leaf-tokens "!key:D.major !accidentals:explicit c4 f4")]
      (is (= [60 65] (mapv (comp first :pitches) ts)) "natural C, natural F -- key ignored"))))

(deftest transpose-respell-uses-real-diatonic-spelling
  (testing "a transposed note that lands on a key's own scale degree is spelled with that degree's letter"
    ;; e (pc 4) transposed up a whole tone -> pc 6 (F#/Gb). Under D
    ;; major, pc 6 is genuinely the (sharped) 3rd scale degree, so it
    ;; should spell as f#, not gb (D major's own signature is sharps,
    ;; but more importantly pc 6 really is F# *in this key's scale*,
    ;; not just an arbitrary sharp-vs-flat sign guess).
    (let [t (first (leaf-tokens "!key:D.major \\transpose c d (e4)"))]
      (is (= "f#4" (:id t))))))

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

;; ── Ornaments glued onto notes ───────────────────────────────

(deftest ornament-note-modifier
  ;; Regression coverage: extract-modifiers' :Ornament case looked for
  ;; a :Name child, but Ornament = <'\'> OrnamentName tags its child
  ;; :OrnamentName, not :Name -- so the name always came through nil
  ;; ("ornament" nil), and expand's ornament dispatch (looked up by
  ;; name) silently no-op'd for every ornament ever written in real
  ;; source text, regardless of what modifier was actually asked for.
  (testing "c4\\trill adds an ornament modifier with the real name, not nil"
    (let [t (first-token "c4\\trill")]
      (is (some #(= ["ornament" "trill"] %) (:modifiers t)))))

  (testing "other ornament names also come through correctly"
    (doseq [name ["mordent" "turn" "prallup" "fermata"]]
      (let [t (first-token (str "c4\\" name))]
        (is (some #(= ["ornament" name] %) (:modifiers t))
            (str name " should be captured, not nil"))))))

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

(deftest note-dynamic-with-glued-direction-matches-the-two-backslash-spelling
  (testing "c4\\mf< (direction glued straight onto the mark, no second '\\')
            produces an identical crescendo to c4\\mf\\< (the older two-
            suffix spelling) -- same grammar-level Dynamic+Direction vs.
            Dynamic-then-separate-Hairpin, same extract-modifiers output
            either way, so this is purely a shorter spelling of the same
            thing, not a different mechanism"
    (let [seq-c (first-token "{c4 d4\\mf< e4 f4\\ff> g4}")
          ctx   (:context seq-c)]
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "mf = 60 at d4's onset")
      (is (= 70.0 (c/ctx-value-chain [ctx root-ctx] :volume 0.5))
          "midway between mf (60) and ff (80): a real interpolated crescendo")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.75))
          "ff = 80 at f4's onset")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 1.0))
          "holds at ff after the decrescendo's own point"))))

(deftest assignment-value-with-glued-direction-produces-a-standalone-crescendo
  (testing "!vol:mf< sets volume AND marks a ramp-start in one instruction --
            the standalone-Assignment equivalent of c4\\mf<, usable for any
            key (not just volume, and not tied to a note)"
    (let [seq-c (first-token "{!vol:mf< c4 d4 e4 !vol:ff f4}")
          ctx   (:context seq-c)]
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :volume 0.0))
          "mf = 60 right at the start")
      (is (= 70.0 (c/ctx-value-chain [ctx root-ctx] :volume 0.375))
          "midway between the ramp's own start (t=0) and end (t=0.75, f4's
           onset): mf (60) and ff (80) interpolated exactly halfway")
      (is (= 80 (c/ctx-value-chain [ctx root-ctx] :volume 0.75))
          "ff = 80 at f4's onset")))
  (testing "!vol:mf alone (no trailing direction) is unaffected -- still a
            plain :fixed point, no ramp-start"
    (let [seq-c (first-token "{!vol:mf c4 d4}")
          ctx   (:context seq-c)]
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :volume 0.0)))
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "still mf, no interpolation -- there's nothing to ramp toward"))))

(deftest note-bare-hairpin-matches-existing-open-ended-ramp-behavior
  (testing "c4\\< with no preceding dynamic on the same note behaves exactly
            like a bare !vol< Assignment -- same :ramp-start sentinel point,
            not a new/different mechanism. The point itself is appended
            with ip :invalid (regression coverage: it used to keep the
            hairpin's own direction, which meant ctx-value-chain treated
            'no numeric value yet' as a real, active answer and handed the
            bare :ramp-start keyword straight back to whatever numeric
            code sampled it -- a real ClassCastException downstream, not
            just an odd value here). :invalid makes ctx-value-chain treat
            this exactly like nothing had been said about volume at all,
            so it keeps searching -- past this context, to root's own
            real default (0.8) -- rather than stopping on the sentinel."
    (let [seq-c (first-token "{c4 d4\\< e4}")
          ctx   (:context seq-c)]
      (is (= 0.8 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "falls through to root's own default, same as if the hairpin
           had never been written at all"))))

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

;; ── Tempo markings (BangConst) ────────────────────────────────

(deftest tempo-marking-bang-const-single-word
  (testing "!allegro (and friends) resolve through instruction-context's
            merged tempo-markings straight to :Tempo -- no separate wiring
            needed for single-word names, since walk-bang-const already
            looks up any keyword generically"
    (doseq [[name bpm] {"largo" 50 "andante" 92 "moderato" 114
                        "allegro" 138 "vivace" 166 "presto" 184
                        "prestissimo" 200}]
      (let [seq-c (first-token (str "{!" name " c4}"))
            ctx   (:context seq-c)]
        (is (= bpm (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))
            (str "!" name " -> " bpm))))))

(deftest tempo-marking-bang-const-compound-camelcase
  (testing "Compound tempo-markings (music-data.clj's kebab-case keys,
            e.g. :marcia-moderato) aren't spellable as-is -- BangConst's
            Name token (musics.ebnf) can't contain a hyphen -- so they're
            reachable via a camelCase alias instead, same values"
    (doseq [[name bpm] {"marciaModerato" 84 "andanteModerato" 102
                        "allegroModerato" 118 "allegroVivace" 174}]
      (let [seq-c (first-token (str "{!" name " c4}"))
            ctx   (:context seq-c)]
        (is (= bpm (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))
            (str "!" name " -> " bpm))))))

;; ── Auto-id laziness ────────────────────────────────────────

(deftest named-container-never-spends-an-auto-id
  (testing "{verse: ...} never touches the :SEQ auto-id counter -- id
            assignment is lazy (ensure-id, at pop time), so an explicit
            name means the counter slot is never even requested"
    (let [{:keys [auto-ids]} (gp/parse-domain-string "{verse: c4 d4}")]
      (is (= {} auto-ids)))))

(deftest unnamed-sibling-still-gets-the-first-real-slot
  (testing "A later unnamed container gets :s1, not :s2 -- the earlier
            named sibling never consumed :s1 for itself"
    (let [{:keys [tree auto-ids]} (gp/parse-domain-string "{verse: c4 d4} {c4 d4}")]
      (is (= {:SEQ 1} auto-ids))
      (is (contains? tree :s1))
      (is (not (contains? tree :s2))))))

(deftest transient-container-never-spends-an-auto-id
  (testing "\\times is spliced away and never registered under any id --
            it must not consume an auto-id slot on the way either"
    (let [{:keys [auto-ids]} (gp/parse-domain-string "\\times 2/3 (c4 d4 e4)")]
      (is (= {} auto-ids)))))

(deftest repeat-source-still-gets-a-real-id
  (testing "walk-repeat/walk-tremolo peek a nested source container off
            the stack without ever calling pop-container (it must not
            register under a top-level id or link into the parent's own
            :children) -- but it still needs a real id of its own for
            print-structure/inspection to show"
    (let [{:keys [tree]} (gp/parse-domain-string "{v: \\repeat unfold 2 {c4 d4}}")
          iter (first (:children (get tree :v)))]
      (is (some? (:id (:source iter)))))))

;; ── Transient commands replay their context onto the parent ─

;; \times/\tuplet/\transpose/a grace decoration all push a transient
;; container with its own :context, then splice its children into the
;; parent and discard the container itself -- before flat-core-builder/
;; replay-context!, any instruction written against that container's own
;; context (standalone !f, or a note-suffix \f) vanished along with it.
;; Now it's replayed onto the parent at the beat the block started, so it
;; takes effect from there and sticks forward, same as any instruction --
;; even past the end of the transient block, exactly as if the wrapping
;; command had never been there.

(deftest times-standalone-instruction-survives-and-sticks
  (testing "!f inside \\times reaches :v's own context, and is still in
            effect for a later sibling outside the \\times block"
    (let [seq-c (first-token "{\\times 2/3 (!f c4 d4 e4) d4}")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 0.0)))
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest times-note-suffix-dynamic-survives-and-sticks
  (testing "c4\\f (note-glued dynamic) inside \\times reaches the same
            context the same way a standalone !f does"
    (let [seq-c (first-token "{\\times 2/3 (c4\\f d4 e4) d4}")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 0.0)))
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest tuplet-instruction-survives-and-sticks
  (testing "Same as \\times, for \\tuplet"
    (let [seq-c (first-token "{\\tuplet 3/2 (!f c4 d4 e4) d4}")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest transpose-instruction-survives-and-sticks
  (testing "Same as \\times, for \\transpose"
    (let [seq-c (first-token "{\\transpose c d' (!f c4 d4) d4}")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest grace-note-suffix-dynamic-survives-and-sticks
  (testing "A dynamic glued directly onto the grace note itself (not a
            separately-bracketed main note, which would be its own real,
            correctly-scoped Sequence) reaches :DECORATED's own context"
    (let [seq-c (first-token "{\\grace c8\\f d4 d4}")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest plain-nested-sequence-does-not-leak
  (testing "Sanity check: a GENUINE nested Sequence (not transient) keeps
            its own dynamic properly contained -- it must NOT reach a
            sibling outside its own brackets, unlike the transient cases
            above. Confirms the fix is specific to transient splicing,
            not a blanket change to how context scoping works"
    (let [seq-c (first-token "{{!f c4 d4} d4}")
          ctx   (:context seq-c)]
      (is (nil? (c/ctx-value-chain [ctx] :volume 100.0))
          "outer sequence's own context (no root fallback) sees nothing --
           !f never touched it, it's scoped to the inner Sequence alone"))))

;; ── Variables (name = ( ... ) / \name) ───────────────────────

;; Grammar-native now (musics.ebnf's VarDef/VarRef), resolved in the same
;; single top-to-bottom walk as everything else -- see flat-tree-walker's
;; walk-var-def/walk-var-ref. tokens/first-token (above) only ever look at
;; :ROOT's own children, and a VarDef is deliberately never one of those
;; (it's stashed in the walk's :var-map, not appended anywhere) -- so
;; these tests go through gp/parse-domain-string directly instead, the
;; same way the auto-id tests above already do.

(deftest var-def-splices-flat-into-the-reference-site
  (testing "\\motif's children land as direct siblings, not nested inside
            a separate container -- same flat-splice shape \\times/
            \\tuplet's own body already gets"
    (let [{:keys [tree]} (gp/parse-domain-string
                          "motif = (c4 d4)\n{v: \\motif e4}")]
      (is (= 3 (count (:children (get tree :v)))))
      (is (every? #(instance? core.domain.flat_domain.Leaf %)
                  (:children (get tree :v)))))))

(deftest var-ref-before-def-is-a-walk-error
  (testing "A variable must be defined before it's referenced -- this is
            structural (a single walk), not just a style rule: nothing
            is in :var-map yet for anything not yet walked"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"referenced before"
          (gp/parse-domain-string "{v: \\motif}\nmotif = (c4 d4)")))))

(deftest undefined-var-ref-is-a-walk-error
  (testing "Referencing a variable that's never defined at all fails the
            same way as referencing one too early"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"referenced before"
          (gp/parse-domain-string "{v: \\nope}")))))

(deftest var-reassignment-is-position-sensitive
  (testing "A later definition of the same name overwrites the map entry
            -- since the walk is sequential, a reference sees whichever
            value was current at that point, not always the last one"
    (let [{:keys [tree]} (gp/parse-domain-string
                          "motif = (c4)\n{a: \\motif}\nmotif = (d4)\n{b: \\motif}")]
      (is (= [60] (:pitches (first (:children (get tree :a))))))
      (is (= [62] (:pitches (first (:children (get tree :b)))))))))

(deftest var-def-instruction-sticks-forward-via-replay
  (testing "An instruction written inside a variable's own definition
            (!f, or a note-glued \\f) reaches the reference site's
            context and sticks forward, past the reference, exactly like
            \\times/\\tuplet/\\transpose/a grace decoration already do --
            same flat-core-builder/replay-context! mechanism"
    (let [{:keys [tree]} (gp/parse-domain-string
                          "motif = (!f c4 d4)\n{v: \\motif e4}")
          vctx (:context (get tree :v))]
      (is (= 70 (c/ctx-value-chain [vctx] :volume 0.0)))
      (is (= 70 (c/ctx-value-chain [vctx] :volume 100.0))))))
