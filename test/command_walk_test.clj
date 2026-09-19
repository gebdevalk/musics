(ns ^:parsing command-walk-test
  (:require [clojure.test :refer [deftest is testing are]]
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

(defn- wrapped-tokens
  "Like tokens, but wraps text in [ ] first and returns the WRAPPER's own
   children -- for content whose own top-level command is transient
   (transpose/reverse/grace splice their children into whatever's
   enclosing them, rather than registering their own container), so it
   can no longer sit bare at Program's own top level at all (see
   musics.ebnf's own TopElement comment: transient commands replay any
   instruction written inside them onto whatever's on the stack when
   they pop, which is :ROOT itself at the bare top level -- :ROOT is
   meant to be a read-only, guaranteed-value endpoint)."
  [text]
  (let [{:keys [tree root-id]} (gp/parse-domain-string (str "[" text "]"))
        root    (get tree root-id)
        wrapper (resolve-child tree (first (:children root)))]
    (mapv (partial resolve-child tree) (:children wrapper))))

(defn- first-wrapped-token [text] (first (wrapped-tokens text)))

;; ── Duration ratio (tuplets) ────────────────────────────────
;; No dedicated command at all -- a note's own Duration carries an
;; optional *Ratio suffix instead (see musics.ebnf's own DurationRatio),
;; inherited by every later note that omits its own Duration, until an
;; explicit new one is written again.

(deftest duration-ratio-scales-and-is-inherited
  (testing "c4*2/3 scales duration by 2/3, inherited by later notes with
            no explicit Duration of their own"
    (let [ts (wrapped-tokens "c4*2/3 d e")]
      (is (= 3 (count ts)))
      (is (every? #(= 1/6 (:duration %)) ts)
          "1/4 * 2/3 = 1/6, inherited by d4 and e4")))

  (testing "c2*3/4 scales duration by 3/4"
    (let [ts (wrapped-tokens "c2*3/4 d2*3/4")]
      (is (= 2 (count ts)))
      (is (every? #(= 3/8 (:duration %)) ts)
          "1/2 * 3/4 = 3/8")))

  (testing "c8*4/5 -- 5 in the time of 4 -- scales duration by 4/5"
    (let [ts (wrapped-tokens "c8*4/5 d e f g")]
      (is (= 5 (count ts)))
      (is (every? #(= 1/10 (:duration %)) ts)
          "1/8 * 4/5 = 4/40 = 1/10")))

  (testing "an explicit new Duration with no ratio of its own clears the
            inherited ratio back to 1, not just the base duration"
    (let [ts (wrapped-tokens "c4*1/3 d e f4 g")]
      (is (= [1/12 1/12 1/12 1/4 1/4] (mapv :duration ts))))))

;; ── Transpose ───────────────────────────────────────────────

(deftest transpose-shifts-pitches
  (testing "\\transpose c d shifts pitches up by 2 semitones"
    (let [base  (wrapped-tokens "c4 d4")
          trans (wrapped-tokens "\\transpose c d ( c4 d4 )")]
      (is (= 2 (count trans)))
      (is (= (mapv (partial + 2) (:pitches (first base)))
             (:pitches (first trans))))
      (is (= (mapv (partial + 2) (:pitches (second base)))
             (:pitches (second trans))))))

  (testing "\\transpose c g shifts pitches up by 7 semitones"
    (let [base  (wrapped-tokens "c4")
          trans (wrapped-tokens "\\transpose c g ( c4 )")]
      (is (= (mapv (partial + 7) (:pitches (first base)))
             (:pitches (first trans)))))))

;; ── Reverse ─────────────────────────────────────────────────

(deftest reverse-reorders-children
  (testing "reverses a flat run of leaves -- order only, pitches/durations
            on each leaf itself are untouched"
    (let [base (wrapped-tokens "c4 d4 e4")
          rev  (wrapped-tokens "\\reverse ( c4 d4 e4 )")]
      (is (= (reverse (mapv :pitches base)) (mapv :pitches rev)))
      (is (= (mapv :duration base) (mapv :duration rev))
          "same durations, just in reverse order along with everything else"))))

(deftest reverse-only-reorders-its-own-level-never-recurses-into-a-reference
  ;; Same silent-skip limitation transpose already has (see musics.ebnf's
  ;; own reverse rule and CLAUDE.md's "Known rough edges"): a nested
  ;; container reference reorders along with everything else at
  ;; reverse's own level, but its OWN internal content is never
  ;; recursed into or itself reversed.
  (let [{:keys [tree]} (gp/parse-domain-string
                          "[verse: \\reverse ( c4 [inner: d4 e4] f4 )]")
        verse (get tree :verse)
        inner (get tree :inner)]
    (is (= [65 :inner 60]
           (mapv (fn [c] (if (keyword? c) c (first (:pitches c)))) (:children verse)))
        "top level reversed: f4(65) then :inner then c4(60), was c4 :inner f4")
    (is (= [[62] [64]] (mapv :pitches (:children inner)))
        "inner's OWN content (d4 e4) is untouched -- neither reordered nor
         recursed into, exactly the documented limitation")))

;; ── Key-implied accidentals ─────────────────────────────────
;; A bare (unmarked) pitch letter resolves against the active key's own
;; implied accidental by default (:accidentals :implied) -- an explicit
;; accidental always overrides it outright, same as real notation.
;; :accidentals :explicit switches back to literal/LilyPond-style
;; (bare letter always natural, key ignored), and C major (the context
;; default when no !key: is ever set) implies nothing either way, so
;; any piece that never sets a key is completely unaffected.

(defn- leaf-tokens
  "Every leaf reachable from text's own root, at any depth -- a bare
   !key:/!accidentals:/etc. instruction can no longer sit directly at
   Program's own top level (see musics.ebnf's own TopElement comment:
   it would write straight into :ROOT's context, which is meant to be a
   read-only, guaranteed-value endpoint), so callers wrap their content
   in a real container and this recurses into it, rather than reading
   only :ROOT's own direct children the way `tokens` does."
  [text]
  (let [{:keys [tree root-id]} (gp/parse-domain-string text)]
    (letfn [(walk [node]
              (cond
                (d/leaf? node)      [node]
                (d/container? node) (mapcat walk (map (partial resolve-child tree) (:children node)))
                :else               []))]
      (vec (walk (get tree root-id))))))

(deftest key-implies-accidentals-on-bare-letters
  (testing "D major sharps F and C; other bare letters stay natural"
    (let [ts (leaf-tokens "[!key:D.major c4 d e f g a b c]")]
      (is (= [61 62 64 66 67 69 71 73] (mapv (comp first :pitches) ts))
          "C# D E F# G A B C#, i.e. every pitch class altered exactly where D major alters it")))
  (testing "F major flats B only"
    ;; b is a major 7th from the default relative reference (c4) --
    ;; \relative's nearest-fourth rule folds that down an octave, so b
    ;; lands at octave 3, not 4 (58 = Bb3, key-implied flat); the
    ;; following c folds back up to octave 4 (60 = C4, unaltered).
    (let [ts (leaf-tokens "[!key:F.major b4 c]")]
      (is (= [58 60] (mapv (comp first :pitches) ts)) "Bb3, then C4 (unaltered)")))
  (testing "C major (no key set) implies nothing"
    (let [ts (leaf-tokens "[c4 f4]")]
      (is (= [60 65] (mapv (comp first :pitches) ts))))))

(deftest explicit-accidental-overrides-key
  (testing "an explicit accidental always wins, key notwithstanding"
    (let [ts (leaf-tokens "[!key:D.major fn4 f4]")]
      (is (= [65 66] (mapv (comp first :pitches) ts))
          "explicit natural first (65, F), then bare f deferring to the key (66, F#)"))))

(deftest accidentals-explicit-mode-disables-key-implication
  (testing "!accidentals:explicit makes every bare letter literal again, regardless of key"
    (let [ts (leaf-tokens "[!key:D.major !accidentals:explicit c4 f4]")]
      (is (= [60 65] (mapv (comp first :pitches) ts)) "natural C, natural F -- key ignored"))))

(deftest pulse-letter-builds-a-pulse-not-a-leaf
  (testing "p<duration> -- PitchLetterRel's own p slot -- builds a Pulse,
            not an ordinary pitched note (confirmed live before this fix:
            resolving p as an actual pitch threw a NullPointerException,
            since common.music-data/diatonic-pcs has no p entry)"
    (let [t (first-wrapped-token "p4")]
      (is (d/pulse? t))
      (is (= 1/4 (:duration t)))
      (is (= 1 (:value t)) "fixed default value for now -- see doc/decisions.md")))
  (testing "duration follows the same rules as any other leaf -- an
            explicit Duration, or falling back to the last one written"
    (let [ts (wrapped-tokens "p4 p8 p")]
      (is (= [1/4 1/8 1/8] (mapv :duration ts)))
      (is (every? d/pulse? ts))))
  (testing "a p pulse never disturbs :last-pitch -- the next relative
            note still resolves against whatever was last actually sounded"
    (let [ts (wrapped-tokens "c4 p8 d4")]
      (is (= [60 nil 62] (mapv (fn [t] (first (:pitches t))) ts))
          "d resolves as the nearest fourth/fifth from c, not from p (which has no pitch)")))
  (testing "a p pulse letter inside a chord is a genuine PARSE-time error --
            musics.ebnf's own ChordPitch excludes it via a negative lookahead
            (!'p'), so it never even reaches the walker (confirmed live: this
            used to hit a NullPointerException at walk time before the
            walker-level guard, then a walk-time ex-info after that guard was
            added, and now a real instaparse parse failure since the grammar
            change)"
    (let [data (try (first-wrapped-token "<c e p>4")
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= 1 (get-in data [:failure :line])))
      (is (= 7 (get-in data [:failure :column]))
          "column 7 in \"[<c e p>4]\" -- right where p sits"))))

(deftest transpose-respell-uses-real-diatonic-spelling
  (testing "a transposed note that lands on a key's own scale degree is spelled with that degree's letter"
    ;; e (pc 4) transposed up a whole tone -> pc 6 (F#/Gb). Under D
    ;; major, pc 6 is genuinely the (sharped) 3rd scale degree, so it
    ;; should spell as f#, not gb (D major's own signature is sharps,
    ;; but more importantly pc 6 really is F# *in this key's scale*,
    ;; not just an arbitrary sharp-vs-flat sign guess).
    (let [t (first (leaf-tokens "[!key:D.major \\transpose c d ( e4 )]"))]
      (is (= "f#4" (:id t))))))

;; ── Grace ───────────────────────────────────────────────────

(deftest grace-borrows-duration
  (testing "\\grace borrows a capped duration from the main note (never zero)"
    ;; c8 (1/8) wants to borrow from d4 (1/4); cap = 1/4 * 1/4 = 1/16,
    ;; so the grace note is clamped down to 1/16.
    (let [t (first-wrapped-token "\\grace c8 d4")]
      (is (= 1/16 (:duration t)))))

  (testing "\\grace adds grace modifier"
    (let [t (first-wrapped-token "\\grace c8 d4")]
      (is (some #(= "grace" (first %)) (:modifiers t)))))

  (testing "\\grace shrinks the main note by exactly the borrowed amount"
    (let [ts (wrapped-tokens "\\grace c8 d4")]
      (is (= 3/16 (:duration (second ts)))))))

(deftest acciaccatura-tags-type
  (testing "\\acciaccatura tags with acciaccatura and borrows duration"
    (let [t (first-wrapped-token "\\acciaccatura c8 d4")]
      (is (= 1/16 (:duration t)))
      (is (some #(and (= "grace" (first %))
                      (= "acciaccatura" (second %)))
                (:modifiers t))))))

(deftest appoggiatura-tags-type
  (testing "\\appoggiatura tags with appoggiatura and borrows duration"
    (let [t (first-wrapped-token "\\appoggiatura c8 d4")]
      (is (= 1/16 (:duration t)))
      (is (some #(and (= "grace" (first %))
                      (= "appoggiatura" (second %)))
                (:modifiers t))))))

;; ── Tremolo (note/chord) ────────────────────────────────────

(deftest tremolo-note-modifier
  (testing "c4:32 preserves duration and adds tremolo modifier"
    (let [t (first-wrapped-token "c4:32")]
      (is (= 1/4 (:duration t)))
      (is (some #(and (= "tremolo" (first %))
                      (= 32 (second %)))
                (:modifiers t))))))

(deftest tremolo-chord-modifier
  (testing "<c e>4:32 adds tremolo modifier to chord"
    (let [t (first-wrapped-token "<c e>4:32")]
      (is (= 1/4 (:duration t)))
      (is (some #(= "tremolo" (first %)) (:modifiers t)))
      (is (< 1 (count (:pitches t)))
          "chord should have multiple pitches"))))

;; ── Chordmode ───────────────────────────────────────────────
;; \chordmode ( root:quality/bass ... ) -- LilyPond's own compact chord
;; shorthand, scoped inside its own backslash command (see musics.ebnf's
;; own comment on why: root:quality's ':' collides with Tremolo's
;; existing c4:32 suffix). Absolute (uppercase) roots throughout so each
;; assertion is self-evident against a known anchor (C4 = 60, same
;; convention flat_domain_test.clj/decompose_test.clj already use),
;; not entangled with relative-pitch resolution.

(deftest chordmode-common-qualities
  (testing "Each common-chord modifier resolves to LilyPond's own
            documented intervals (Notation Reference, 'Common chord
            modifiers') -- not invented"
    (are [text pitches] (= pitches (:pitches (first-wrapped-token text)))
      "\\chordmode ( C4:5 )"    [60 67]
      "\\chordmode ( C4:m )"    [60 63 67]
      "\\chordmode ( C4:aug )"  [60 64 68]
      "\\chordmode ( C4:dim )"  [60 63 66]
      "\\chordmode ( C4:7 )"    [60 64 67 70]
      "\\chordmode ( C4:maj7 )" [60 64 67 71]
      "\\chordmode ( C4:maj )"  [60 64 67 71]
      "\\chordmode ( C4:dim7 )" [60 63 66 69]
      "\\chordmode ( C4:m7 )"   [60 63 67 70]
      "\\chordmode ( C4:6 )"    [60 64 67 69]
      "\\chordmode ( C4:m6 )"   [60 63 67 69]
      "\\chordmode ( C4:sus2 )" [60 62 67]
      "\\chordmode ( C4:sus4 )" [60 65 67])))

(deftest chordmode-extended-qualities
  (testing "9/11/13 stack thirds up to the extent, defaulting to a
            minor 7th (LilyPond: 'the seventh step added as part of an
            extended chord will be the minor or flatted seventh, not
            the major seventh') -- verbatim from the docs, not invented"
    (are [text pitches] (= pitches (:pitches (first-wrapped-token text)))
      "\\chordmode ( C4:9 )"     [60 64 67 70 74]
      "\\chordmode ( C4:m9 )"    [60 63 67 70 74]
      "\\chordmode ( C4:maj9 )"  [60 64 67 71 74]
      "\\chordmode ( C4:11 )"    [60 64 67 70 74 77]
      "\\chordmode ( C4:m11 )"   [60 63 67 70 74 77]
      "\\chordmode ( C4:maj11 )" [60 64 67 71 74 77]))

  (testing "The 11 is dropped by default from a 13 chord built on a
            MAJOR third (:13 and :maj13 alike -- LilyPond: 'since an
            unaltered 11 does not sound good when combined with an
            unaltered 13, the 11 is removed from a :13 major chord
            unless it is added explicitly'), but kept for :m13 (minor
            third)"
    (is (= [60 64 67 70 74 81] (:pitches (first-wrapped-token "\\chordmode ( C4:13 )")))
        "13, 11 omitted")
    (is (= [60 64 67 71 74 81] (:pitches (first-wrapped-token "\\chordmode ( C4:maj13 )")))
        "maj13, 11 omitted")
    (is (= [60 63 67 70 74 77 81] (:pitches (first-wrapped-token "\\chordmode ( C4:m13 )")))
        "m13, 11 KEPT")))

(deftest chordmode-bare-note-is-a-major-triad
  (testing "A colon-less chordmode entry is still a full major triad,
            LilyPond's own documented default ('None: produces a major
            triad') -- not just a single note the way the identical
            text would read outside this block"
    (is (= [60 64 67] (:pitches (first-wrapped-token "\\chordmode ( C4 )"))))))

(deftest chordmode-unrecognized-quality-throws
  (testing "A quality word the grammar's own Quality regex can't match
            is a parse error, not a silent no-op or a walk-time failure
            -- the grammar and the table are meant to agree exactly"
    (is (thrown? Exception (gp/parse-domain-string "[\\chordmode ( C4:bogus )]")))))

(deftest chordmode-inversion-moves-existing-tone-without-duplicating
  (testing "Plain /bass, when that pitch CLASS is already part of the
            chord, moves it to the bottom rather than duplicating it --
            LilyPond's own documented distinction ('the pitch is not
            added but merely moved to the bottom of the chord')"
    (let [t (first-wrapped-token "\\chordmode ( C4:5/G3 )")]
      (is (= [55 60] (:pitches t))
          "the fifth (67) is replaced by a lower G, not duplicated -- 2 notes total, not 3"))))

(deftest chordmode-inversion-plus-always-adds
  (testing "/+bass always adds a new note regardless of whether that
            pitch class already sounds in the chord -- LilyPond's own
            documented distinction ('treated as an added note and thus
            printed twice')"
    (let [t (first-wrapped-token "\\chordmode ( C4:maj7/+E3 )")]
      (is (= [52 60 64 67 71] (:pitches t))
          "E (64) stays, PLUS a new lower E (52) added as the bass -- 5 notes, a duplicate pitch class"))))

(deftest chordmode-multiple-entries-splice-like-any-transient-command
  (testing "A run of chordmode entries splices flat into the enclosing
            container, same shape any transient command's own body
            already gets -- each entry its own Leaf, not one combined
            chord"
    (let [ts (wrapped-tokens "\\chordmode ( C4:m G4:7 )")]
      (is (= 2 (count ts)))
      (is (every? d/leaf? ts))
      (is (= [60 63 67] (:pitches (first ts))))
      (is (= [67 71 74 77] (:pitches (second ts)))))))

(deftest chordmode-dot-addition-overrides-not-accumulates
  (testing "LilyPond's own canonical worked example: c:3.5.5-.5+
            resolves to an augmented triad -- each .step[+/-] entry
            OVERRIDES step 5 outright, computed fresh from the step's
            own default (7) each time, not a cumulative -1 then +1 from
            whatever was there before (which would also land on 7, not
            8) -- confirmed against LilyPond's own docs, not assumed"
    (is (= [60 64 68] (:pitches (first-wrapped-token "\\chordmode ( C4:3.5.5-.5+ )")))))

  (testing "A bare .step addition uses that step's own canonical
            default interval"
    (is (= [60 64 67] (:pitches (first-wrapped-token "\\chordmode ( C4:3.5 )")))
        "3 + a bare added 5 = an ordinary major triad")))

(deftest chordmode-dot-addition-builds-altered-chords-not-in-the-fixed-table
  (testing "Half-diminished (m7 with a flatted 5th) is reachable only
            via the general addition mechanism, no fixed :quality word
            of its own -- m7.5- glues the alteration onto step 5"
    (is (= [60 63 66 70] (:pitches (first-wrapped-token "\\chordmode ( C4:m7.5- )")))
        "half-diminished: C Eb Gb Bb"))

  (testing "A minor-major7 (m triad + RAISED 7th) needs '.7+' -- an
            alteration glued directly onto the base quality word with
            no dot (e.g. 'm7+') is NOT valid grammar here, confirmed
            live: only ChordAddition's own '.step[+/-]' shape reaches
            +/-, matching how LilyPond's own modifier grammar keeps
            alterations tied to an explicit step, never bare-glued onto
            a quality word"
    (is (= [60 63 67 71] (:pitches (first-wrapped-token "\\chordmode ( C4:m.7+ )")))
        "m (minor triad) + an explicit major-7th addition")))

(deftest chordmode-caret-removal-drops-named-steps
  (testing "^step drops that scale degree outright -- LilyPond's own
            'no3'/'no5' style voicings"
    (is (= [60 67 70 74] (:pitches (first-wrapped-token "\\chordmode ( C4:9^3 )")))
        "9 chord, no 3rd")
    (is (= [60 70 74] (:pitches (first-wrapped-token "\\chordmode ( C4:9^3.5 )")))
        "9 chord, no 3rd AND no 5th -- one ^, dot-separated, not one ^ per step")))

(deftest chordmode-dot-addition-restores-the-13-chords-own-dropped-11
  (testing "LilyPond's own :13.11 -- explicitly adding the 11 back onto
            a 13 chord that would otherwise omit it by default (see
            chordmode-extended-qualities above)"
    (is (= [60 64 67 70 74 77 81] (:pitches (first-wrapped-token "\\chordmode ( C4:13.11 )"))))))

(deftest chordmode-addition-and-removal-combine-in-one-entry
  (testing "Additions (all of them) apply before removals, per
            LilyPond's own ordering ('Following any steps to be added,
            a series of steps to be removed...')"
    (is (= [60 67 74 77 81] (:pitches (first-wrapped-token "\\chordmode ( C4:13.11^3.7 )")))
        "13.11 with the 3rd and 7th both then removed")
    (is (= [60 63 67 70 74 81] (:pitches (first-wrapped-token "\\chordmode ( C4:m13^11 )")))
        "m13 keeps its 11 by default (see chordmode-extended-qualities), removed here explicitly")))

;; ── Ornaments glued onto notes ───────────────────────────────

(deftest ornament-note-modifier
  ;; Regression coverage: extract-modifiers' :Ornament case looked for
  ;; a :Name child, but Ornament = <'\'> OrnamentName tags its child
  ;; :OrnamentName, not :Name -- so the name always came through nil
  ;; ("ornament" nil), and expand's ornament dispatch (looked up by
  ;; name) silently no-op'd for every ornament ever written in real
  ;; source text, regardless of what modifier was actually asked for.
  (testing "c4\\trill adds an ornament modifier with the real name, not nil"
    (let [t (first-wrapped-token "c4\\trill")]
      (is (some #(= ["ornament" "trill"] %) (:modifiers t)))))

  (testing "other ornament names also come through correctly"
    (doseq [name ["mordent" "turn" "prallup" "fermata"]]
      (let [t (first-wrapped-token (str "c4\\" name))]
        (is (some #(= ["ornament" name] %) (:modifiers t))
            (str name " should be captured, not nil"))))))

;; ── Dynamic marks glued onto notes/chords ───────────────────

(deftest note-dynamic-modifier
  (testing "c4\\f adds a dynamic modifier tuple, same shape as tremolo/ornament"
    (let [t (first-wrapped-token "c4\\f")]
      (is (some #(= ["dynamic" "f"] %) (:modifiers t))))))

(deftest chord-dynamic-modifier
  (testing "<c e g>4\\mf adds a dynamic modifier tuple to the chord"
    (let [t (first-wrapped-token "<c e g>4\\mf")]
      (is (some #(= ["dynamic" "mf"] %) (:modifiers t))))))

(deftest note-hairpin-modifier
  (testing "c4\\< adds a hairpin modifier tuple"
    (let [t (first-wrapped-token "c4\\<")]
      (is (some #(= ["hairpin" "<"] %) (:modifiers t)))))
  (testing "c4\\> adds a hairpin modifier tuple"
    (let [t (first-wrapped-token "c4\\>")]
      (is (some #(= ["hairpin" ">"] %) (:modifiers t))))))

(deftest note-dynamic-hairpin-chain-modifier
  (testing "c4\\mf\\< carries both modifier tuples, dynamic then hairpin"
    (let [t (first-wrapped-token "c4\\mf\\<")]
      (is (some #(= ["dynamic" "mf"] %) (:modifiers t)))
      (is (some #(= ["hairpin" "<"] %) (:modifiers t))))))

;; note-dynamic-sets-volume-going-forward and the hairpin/chain equivalents
;; live further down, after root-ctx is defined -- see the "Instruction
;; timestamps" section.

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

(def root-ctx (c/context-root {"Tempo" 120 "volume" 0.8 "timbre" 42}))

(deftest note-dynamic-sets-volume-going-forward
  (testing "c4\\f behaves like a bare !f BangConst written just before d4 --
            volume changes at d4's own onset, same as a note-glued dynamic
            in LilyPond"
    (let [seq-c (first-token "[c4 d4\\f e4]")
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
    (let [seq-c (first-token "[c4 d4\\mf\\< e4 f4\\ff\\> g4]")
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
    (let [seq-c (first-token "[c4 d4\\mf< e4 f4\\ff> g4]")
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
    (let [seq-c (first-token "[!vol:mf< c4 d4 e4 !vol:ff f4]")
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
    (let [seq-c (first-token "[!vol:mf c4 d4]")
          ctx   (:context seq-c)]
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :volume 0.0)))
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "still mf, no interpolation -- there's nothing to ramp toward"))))

(deftest note-bare-hairpin-matches-existing-open-ended-ramp-behavior
  (testing "c4\\< with no preceding dynamic on the same note behaves exactly
            like a bare !vol< Assignment -- same insertion-time ambient-
            value resolution, not a new/different mechanism (see
            context.clj's own ambient-value/ctx-value-chain docstrings).
            The hairpin's own starting value is resolved immediately, at
            walk time, from whatever's ambient in the REAL session this
            walk actually runs against -- root's own real default (50.0
            on volume's 0-100 authoring scale, from common.defaults/
            root-defaults), not this test's own separate root-ctx
            fixture (only relevant for a chain built AFTER the fact,
            which never even gets reached here: ctx's own envelope
            already holds the resolved value directly)."
    (let [seq-c (first-token "[c4 d4\\< e4]")
          ctx   (:context seq-c)]
      (is (= 50.0 (c/ctx-value-chain [ctx root-ctx] :volume 0.25))
          "root's own real default, baked in at walk time, same as if the
           hairpin had never been written at all"))))

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

;; ── Meter ───────────────────────────────────────────────────

(deftest meter-bare-ratio-reaches-context
  (testing "!Meter:7/8 (bare ratio) actually lands in the context, not just
            the printed instruction -- this was silently a no-op before"
    (let [seq-c (first-token "[!Meter:7/8 c4 d4]")
          ctx   (:context seq-c)
          m     (c/ctx-value-chain [ctx root-ctx] :Meter 0.0)]
      (is (= 7 (:num m)))
      (is (= 8 (:den m)))
      (is (nil? (:subdivisions m))))))

(deftest meter-additive-string-reaches-context
  (testing "!Meter:\"7/8(2+2+3)\" (quoted, additive) sets an explicit grouping"
    (let [seq-c (first-token "[!Meter:\"7/8(2+2+3)\" c4 d4]")
          ctx   (:context seq-c)
          m     (c/ctx-value-chain [ctx root-ctx] :Meter 0.0)]
      (is (= 7 (:num m)))
      (is (= 8 (:den m)))
      (is (= [2 2 3] (:subdivisions m))))))

(deftest meter-alias-canonicalizes
  (testing "!M:3/4 (the :M alias) reads back under the canonical :Meter key"
    (let [seq-c (first-token "[!M:3/4 c4]")
          ctx   (:context seq-c)
          m     (c/ctx-value-chain [ctx root-ctx] :Meter 0.0)]
      (is (= 3 (:num m)))
      (is (= 4 (:den m))))))

;; ── Tempo ───────────────────────────────────────────────────

(deftest tempo-bare-int-reaches-context
  (testing "!tempo:120 (bare BPM, no note-value) lands under :Tempo"
    (let [seq-c (first-token "[!tempo:120 c4]")
          ctx   (:context seq-c)]
      (is (= 120 (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))))))

(deftest tempo-mark-quarter-equivalent
  (testing "!tempo:4=120 (quarter=120) is the same as the bare-BPM form"
    (let [seq-c (first-token "[!tempo:4=120 c4]")
          ctx   (:context seq-c)]
      (is (= 120 (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))))))

(deftest tempo-mark-eighth-note-halves
  (testing "!tempo:8=120 (eighth=120) is quarter-equivalent 60 -- an eighth
            note is half a quarter, so eighth=120 is the same speed as
            quarter=60"
    (let [seq-c (first-token "[!tempo:8=120 c4]")
          ctx   (:context seq-c)]
      (is (= 60 (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))))))

(deftest tempo-mark-ratio-note-value
  (testing "!tempo:3/8=120 (dotted-quarter=120) takes the ratio as-is:
            120 * 3/8 * 4 = 180"
    (let [seq-c (first-token "[!tempo:3/8=120 c4]")
          ctx   (:context seq-c)]
      (is (= 180 (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))))))

(deftest tempo-Tempo-and-T-aliases-canonicalize
  (testing "!Tempo:130 and !T:140 both read back under the same :Tempo key
            (previously broken -- resolve.clj queried lowercase :tempo,
            which only the bare !tempo: spelling happened to match)"
    (let [seq-Tempo (first-token "[!Tempo:130 c4]")
          seq-T     (first-token "[!T:140 c4]")]
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
      (let [seq-c (first-token (str "[!" name " c4]"))
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
      (let [seq-c (first-token (str "[!" name " c4]"))
            ctx   (:context seq-c)]
        (is (= bpm (c/ctx-value-chain [ctx root-ctx] :Tempo 0.0))
            (str "!" name " -> " bpm))))))

;; ── Auto-id laziness ────────────────────────────────────────

(deftest named-container-never-spends-an-auto-id
  (testing "[verse: ...] never touches the :SEQ auto-id counter -- id
            assignment is lazy (ensure-id, at pop time), so an explicit
            name means the counter slot is never even requested"
    (let [{:keys [auto-ids]} (gp/parse-domain-string "[verse: c4 d4]")]
      (is (= {} auto-ids)))))

(deftest unnamed-sibling-still-gets-the-first-real-slot
  (testing "A later unnamed container gets :s1, not :s2 -- the earlier
            named sibling never consumed :s1 for itself"
    (let [{:keys [tree auto-ids]} (gp/parse-domain-string "[verse: c4 d4] [c4 d4]")]
      (is (= {:SEQ 1} auto-ids))
      (is (contains? tree :s1))
      (is (not (contains? tree :s2))))))

(deftest transient-container-never-spends-an-auto-id
  (testing "\\transpose is spliced away and never registered under any id
            -- it must not consume an auto-id slot on the way either
            (the wrapping [ ] does spend exactly one, for itself --
            \\transpose can no longer sit bare at Program's own top
            level, see musics.ebnf's own TopElement comment)"
    (let [{:keys [auto-ids]} (gp/parse-domain-string "[\\transpose c d ( c4 d4 e4 )]")]
      (is (= {:SEQ 1} auto-ids)))))

(deftest repeat-source-still-gets-a-real-id
  (testing "walk-repeat/walk-tremolo peek a nested source container off
            the stack without ever calling pop-container (it must not
            register under a top-level id or link into the parent's own
            :children) -- but it still needs a real id of its own for
            print-structure/inspection to show"
    (let [{:keys [tree]} (gp/parse-domain-string "[v: \\repeat unfold 2 [c4 d4]]")
          iter (first (:children (get tree :v)))]
      (is (some? (:id (:source iter)))))))

;; ── Transient commands replay their context onto the parent ─

;; \transpose/\reverse/a grace decoration all push a transient
;; container with its own :context, then splice its children into the
;; parent and discard the container itself -- before flat-core-builder/
;; replay-context!, any instruction written against that container's own
;; context (standalone !f, or a note-suffix \f) vanished along with it.
;; Now it's replayed onto the parent at the beat the block started, so it
;; takes effect from there and sticks forward, same as any instruction --
;; even past the end of the transient block, exactly as if the wrapping
;; command had never been there.

(deftest reverse-standalone-instruction-survives-and-sticks
  (testing "!f inside \\reverse reaches :v's own context, and is still in
            effect for a later sibling outside the \\reverse block"
    (let [seq-c (first-token "[\\reverse ( !f c4 d4 e4 ) d4]")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 0.0)))
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest reverse-note-suffix-dynamic-survives-and-sticks
  (testing "c4\\f (note-glued dynamic) inside \\reverse reaches the same
            context the same way a standalone !f does"
    (let [seq-c (first-token "[\\reverse ( c4\\f d4 e4 ) d4]")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 0.0)))
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest transpose-instruction-survives-and-sticks
  (testing "Same as \\reverse, for \\transpose"
    (let [seq-c (first-token "[\\transpose c d' ( !f c4 d4 ) d4]")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest grace-note-suffix-dynamic-survives-and-sticks
  (testing "A dynamic glued directly onto the grace note itself (not a
            separately-bracketed main note, which would be its own real,
            correctly-scoped Sequence) reaches :DECORATED's own context"
    (let [seq-c (first-token "[\\grace c8\\f d4 d4]")
          ctx   (:context seq-c)]
      (is (= 70 (c/ctx-value-chain [ctx root-ctx] :volume 100.0))))))

(deftest plain-nested-sequence-does-not-leak
  (testing "Sanity check: a GENUINE nested Sequence (not transient) keeps
            its own dynamic properly contained -- it must NOT reach a
            sibling outside its own brackets, unlike the transient cases
            above. Confirms the fix is specific to transient splicing,
            not a blanket change to how context scoping works"
    (let [seq-c (first-token "[[!f c4 d4] d4]")
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
            a separate container -- same flat-splice shape \\transpose's
            own body already gets"
    (let [{:keys [tree]} (gp/parse-domain-string
                          "motif = [c4 d4]\n[v: \\motif e4]")]
      (is (= 3 (count (:children (get tree :v)))))
      (is (every? d/leaf? (:children (get tree :v)))))))

(deftest var-ref-before-def-is-a-walk-error
  (testing "A variable must be defined before it's referenced -- this is
            structural (a single walk), not just a style rule: nothing
            is in :var-map yet for anything not yet walked"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"referenced before"
          (gp/parse-domain-string "[v: \\motif]\nmotif = [c4 d4]")))))

(deftest undefined-var-ref-is-a-walk-error
  (testing "Referencing a variable that's never defined at all fails the
            same way as referencing one too early"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"referenced before"
          (gp/parse-domain-string "[v: \\nope]")))))

(deftest var-reassignment-is-position-sensitive
  (testing "A later definition of the same name overwrites the map entry
            -- since the walk is sequential, a reference sees whichever
            value was current at that point, not always the last one"
    (let [{:keys [tree]} (gp/parse-domain-string
                          "motif = [c4]\n[a: \\motif]\nmotif = [d4]\n[b: \\motif]")]
      (is (= [60] (:pitches (first (:children (get tree :a))))))
      (is (= [62] (:pitches (first (:children (get tree :b)))))))))

(deftest var-def-instruction-sticks-forward-via-replay
  (testing "An instruction written inside a variable's own definition
            (!f, or a note-glued \\f) reaches the reference site's
            context and sticks forward, past the reference, exactly like
            \\transpose/\\reverse/a grace decoration already do --
            same flat-core-builder/replay-context! mechanism"
    (let [{:keys [tree]} (gp/parse-domain-string
                          "motif = [!f c4 d4]\n[v: \\motif e4]")
          vctx (:context (get tree :v))]
      (is (= 70 (c/ctx-value-chain [vctx] :volume 0.0)))
      (is (= 70 (c/ctx-value-chain [vctx] :volume 100.0))))))
