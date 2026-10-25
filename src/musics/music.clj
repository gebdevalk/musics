(ns musics.music
  "Music as Clojure functions, which are mu's words too: a public
   function here is the mu word of its name (a trailing ! left off), its
   arguments taken from mu's stack, music first, the one value it gives
   pushed back -- a function whose name ends in ! is done for its effect
   and leaves nothing. Its :mu metadata says what else mu needs, when it
   needs more than the name, the docstring and the arglists: :ns, the mu
   namespace (music by default); :names, the signs it is written as too
   ([R], +); :optional, a test per argument name, for an arity whose
   extra arguments are taken only when the values in their places pass
   (musics.mu.library registers them, musics.mu.eval runs them).
   An error is an ex-info whose message mu prefixes with the word."
  (:refer-clojure :exclude [key repeat cycle])
  (:require [clojure.string :as str]
            [musics.algo.bridge :as bridge]
            [musics.algo.harmonic.walking-bass :as walking-bass]
            [musics.algo.tree.lib :as lib]
            [musics.common.music-data :as data]
            [musics.common.music-elements :as el]
            [musics.domain :as d]
            [musics.domain.context :as c]
            [musics.mu.eval :as e]
            [musics.mu.feedback :as fb]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.repo :as repo]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

(defn- musical? [x] (or (mt/leaf? x) (v/material? x) (and (sequential? x) (not (string? x)))))

;; -- helpers, the words' own ---------------------------------------------

(defn- changing-keys
  "{key envelope} of the settings ctx changes after its start: what a
   line's own instructions written among its notes do."
  [ctx]
  (into {} (for [[k env] (some-> ctx :envelopes-atom deref)
                 :when (some pos? (some-> env :points-atom deref keys))]
             [k env])))

(defn- bake
  "x with each note's value of what its line's instructions change (a
   !f, a ramp) written into the note as its own override, read where the
   note is written: so the music sounds the same retrograde, the
   instructions themselves left behind. A note's own override wins."
  [x]
  (let [ctx  (when (v/material? x) (:context (v/node x)))
        keys (changing-keys ctx)]
    (if (empty? keys)
      x
      (mt/map-leaves
       (fn [leaf]
         (if-let [off (some (fn [[cx o]] (when (identical? (:envelopes-atom cx) (:envelopes-atom ctx)) o))
                            (:ctx-chain leaf))]
           (reduce (fn [l [k env]]
                     (let [v (c/sample-at env off)]
                       (if (or (nil? v) (contains? (:overrides l) (keyword k))) l (assoc-in l [:overrides (keyword k)] v))))
                   leaf keys)
           leaf))
       x))))

(declare retrograde)

(defn- backwards
  "An element of a line as it plays backwards: a nested line retrograde
   too, a parallel's parts each, a repeat's body (one with endings is
   refused: no repeat plays its passes backwards)."
  [x]
  (cond
    (mt/repetition? x)
    (let [[t n body alt bars] (mt/repeat-parts x)]
      (when alt (e/mu-error "[R]: a repeat with volta endings can't be played backwards -- write it out first with [!]"))
      (when t (e/mu-error "[R]: a tremolo can't be played backwards"))
      (mt/repetition (retrograde body) n [] bars))
    (= :SEQ (mt/kind x)) (retrograde x)
    (= :PAR (mt/kind x)) (mt/reshape x #(mapv retrograde %))
    :else x))

(defn- tie-backwards
  "The elements retrograde, each nested one backwards too, each tie moved
   to the note now before its partner: c~ c d retrograde is d c~ c."
  [els]
  (let [r (mapv backwards (reverse els))
        n (count r)]
    (vec (for [j (range n)
               :let [x (r j) nxt (when (< (inc j) n) (r (inc j)))]]
           (if (and (map? x) (contains? x :tied))
             (assoc x :tied (boolean (and (map? nxt) (:tied nxt))))
             x)))))

(defn retrograde
  "The retrograde (the crab): x backwards, at any depth -- its elements
   retrograde (nested lines, parallels' parts and repeats' bodies too),
   ties turned round, what its own instructions change written into the
   notes; a line with jumps ($ DS DC Fine Coda ./.) is refused -- write
   it out first ([!]). Exactly [R]: [ R ] is a line with a bar's rest."
  {:mu {:names ["[R]"]}}
  [x]
  (when (and (v/material? x) (not (mt/repetition? x)) (some d/jump? (mt/elements x)))
    (e/mu-error "[R]: a line with jumps can't be played backwards -- write it out first with [!]"))
  (cond
    (mt/repetition? x)                 (backwards x)
    (or (mt/leaf? x) (v/material? x))  (mt/reshape (bake x) #(tie-backwards (mt/finite! "[R]" %)))
    :else                              (mt/reshape x #(reverse (mt/finite! "[R]" %)))))

(defn- pitches-of [x] (vec (mapcat :pitches (mt/leaves x))))

(defn- invert-around
  "x mirrored through d/invert around (axis pitches), unchanged when it
   has no pitch."
  [axis x]
  (let [ps (pitches-of x)]
    (if (seq ps) (mt/map-leaves (d/invert (axis ps)) x) x)))

(def ^:private on-mean
  "The rounded mean, musics.algo.common.reshape/invert's axis."
  #(Math/round (double (/ (reduce + %) (count %)))))

(defn- read-notes
  "f of every note of x, as a stream: a repeat or a loop around it kept
   (a loop's notes as a loop of their values, printed as '[ … ] [~]), a
   repeat with endings written out."
  [word f rests? x]
  (if-let [[body n] (mt/repeat-of x)]
    (mt/repeated (read-notes word f rests? body) n)
    (apply list (map f (cond->> (mt/played-leaves (mt/finite! word x))
                         (not rests?) (remove #(= :REST (:type %))))))))

(defn- articulation-name
  "The articulation a leaf was written with, by its length and dynamic
   (a drum by its dynamic alone), else nil."
  [x]
  (let [drum? (= :DRUM (:type x))
        a     (:articulation x)
        dyn   (or (:dynamic x) 0)]
    (when (or a (not= 0 dyn))
      (some (fn [k] (let [v (data/articulations k)]
                      (when (and (= dyn (:dynamic v)) (or drum? (= a (:duration v)))) k)))
            mt/articulation-order))))

(defn- note-pitch
  "A note's pitch, a chord's pitches lowest first, nil for a rest or drum."
  [x]
  (let [ps (some-> (:pitches x) seq sort)]
    (if (next ps) (apply list ps) (first ps))))

(defn- instrument-of
  "A note's own instrument; nil for a drum, whose sound is a key of the
   drum kit, not a program (drum-of)."
  [x]
  (when-not (= :DRUM (:type x)) (get-in x [:overrides :instrument])))

(defn- drum-of
  "A drum's key; nil for anything else."
  [x]
  (when (= :DRUM (:type x)) (:program x)))

(defn- onto
  "x with (f note value) for each note of the line in written order, the
   stream's values in turn, a rest using its value unused, as zip does;
   a nil writes nothing, and notes past the stream's end stay as they
   are. The line keeps its
   form: repeats, loops and nested lines."
  [word f x vs]
  (when-not (or (mt/leaf? x) (v/material? x))
    (e/mu-error (str word ":") "puts a stream onto a line or a note, got" (p/show x)))
  (let [cur (volatile! (seq (mt/elements (mt/streamed vs))))
        put (fn [l]
              (if-let [[v & more] @cur]
                (do (vreset! cur more) (if (or (nil? v) (= :REST (:type l))) l (f l v)))
                l))]
    (mt/map-leaves put x)))

(defn- set-drum [l i]
  (when-not (= :DRUM (:type l))
    (e/mu-error "+drums: a note is no drum -- as-drums turns notes into drums"))
  (assoc l :program (mt/drum-key "+drums" i)))

(defn- as-drum
  "The note as the drum i, keeping its length, its articulation's weight
   and its own settings (volume and the like); its pitch and instrument
   go."
  [l i]
  (if (= :DRUM (:type l))
    (set-drum l i)
    (merge (d/drum nil nil (:duration l) (mt/drum-key "as-drums" i))
           (select-keys l [:dynamic :tied])
           {:overrides (dissoc (:overrides l) :instrument)})))

(defn- pitch-of [x]
  (cond (number? x) x
        (mt/leaf? x) (or (first (:pitches x)) (e/mu-error "transpose: a rest has no pitch"))
        :else (e/mu-error "transpose: needs two notes or pitch numbers, got" (p/show x))))

(defn- times-of
  "x played n times, the text's repeat n [ x ]; data and streams written
   out n times, printed as x n times (data stays data). word names it in
   an error."
  [word x n]
  (when (zero? n) (e/mu-error (str word ":") "needs 1 or more times"))
  (if (mt/music? x)
    (mt/repetition x n [])
    (let [x (mt/finite! word x)]
      (if (v/material? x)
        (vary-meta (mt/reshape x #(apply concat (clojure.core/repeat n %))) assoc :mu/repeat [x n])
        (mt/repeated x n)))))

(defn- played-leaves
  "x's notes, chords and rests in the order they play: nested lines and
   repeats (pass after pass, with their endings) walked through."
  [word x]
  (cond
    (mt/leaf? x)                                    [x]
    (= :PAR (mt/kind x))                            (e/mu-error (str word ":") "walks under one line of chords, not a parallel")
    (and (mt/repetition? x) (= :infinite (mt/repeat-count x)))
    (e/mu-error (str word ":") "needs all of the chords, and an endless repeat has no end -- repeat it a number of times")
    (or (v/material? x) (sequential? x))            (mapcat #(played-leaves word %) (mt/elements x))
    :else                                           []))

;; -- the row forms ----------------------------------------------------------

(defn inversion
  "The inversion around the first note (a chord's first written pitch),
   which stays; I5 is [I] 5 +."
  {:mu {:names ["[I]"]}}
  [x]
  (invert-around first x))

(defn retrograde-inversion
  "The retrograde inversion: inverted around the first note, then backwards."
  {:mu {:names ["[RI]"]}}
  [x]
  (retrograde (inversion x)))

(defn mirror
  "The inversion around the mean pitch, keeping the register: M for mirror."
  {:mu {:names ["[M]"]}}
  [x]
  (invert-around on-mean x))

;; -- forms -----------------------------------------------------------------

(defn cycle
  "x endlessly, repeat ~ [ x ]: [c d] [~] > plays until stop; data or a
   stream gives its elements as an endless stream; take from either."
  {:mu {:names ["[~]"]}}
  [x]
  (if (mt/music? x)
    (mt/repetition x :infinite [])
    (mt/repeated (mt/finite! "[~]" x) :infinite)))

(defn repeats
  "x in repeat signs, played twice."
  {:mu {:names ["[:]"]}}
  [x]
  (mt/repetition x 2 [] true))

(defn unfold
  "x written out as it plays: repeats as their passes, jumps and ./. followed."
  {:mu {:names ["[!]"]}}
  [x]
  (mt/written-out x))

(defn measures
  "x split by its meter into its measures, each a line of its own:
   [c2 d2 e2] [[]] gives [[C4/2 D4] [E4]], a note across a bar line cut
   and tied as [|] does."
  {:mu {:names ["[[]]"]}}
  [x]
  (mt/measures x))

(defn barred
  "x barred by its meter: a | every bar, a note across one cut and tied."
  {:mu {:names ["[||]"]}}
  [x]
  (mt/barred x))

(defn repeat
  "x played n times, as the text's repeat n [ x ] and LilyPond's (also
   times, [*])."
  [x n]
  (when-not (and (integer? n) (not (neg? n))) (fail "needs a whole number on top, got" (p/show n)))
  (times-of "repeat" x n))

(defn times
  "x played n times, as the text's repeat n [ x ]; data and streams
   written out n times."
  {:mu {:ns "tool" :names ["[*]"]}}
  [x n]
  (when-not (and (integer? n) (not (neg? n))) (fail "needs a whole number on top, got" (p/show n)))
  (times-of "[*]" x n))

(defn beside
  "The parts of the two in one { }: {[a] [b]} {[c] [d]} {+} gives
   {[a] [b] [c] [d]}; a line is one part."
  {:mu {:names ["{+}"]}}
  [a b]
  (let [bs (if (= :PAR (mt/kind b)) (mt/elements b) [b])]
    (if (= :PAR (mt/kind a))
      (mt/reshape a #(concat % bs))
      (mt/par-of (cons a bs)))))

(defn mute
  "Each note or drum q keeps becomes a rest of its length: the rhythm
   stays -- [c e g c'] '( pitch 65 gt ) mute."
  [x q]
  (when-not (v/quotation? q) (fail "needs a quotation '( … ) on top"))
  (mt/map-leaves #(if (and (not= :REST (:type %)) (mt/result "mute" q %))
                    (merge (d/rest* nil nil (:duration %)) (select-keys % [:ratio]))
                    %)
                 x))

;; -- the musical symbols ----------------------------------------------------

(defn- by-number
  "(g n x) for material x and a number n, after (check n); the symbol
   word sign is musical -- on two numbers it points to the numeric word."
  [sign x n g check]
  (cond
    (and (number? x) (number? n)) (fail "is musical; on numbers write" ({"+" "add" "-" "sub" "*" "mul" "/" "div"} sign))
    (not (and (musical? x) (number? n))) (fail "needs material and a number, got" (p/show x) "and" (p/show n))
    :else (do (check n) (g n x))))

(defn- whole! [n] (when-not (integer? n) (fail "transposes by a whole number of semitones, got" n)))
(defn- positive! [n] (when-not (pos? n) (fail "needs a positive factor, got" n)))

(defn up
  "Every pitch up n semitones, its key with it (!K: at the start of its
   line): [c e g] 7 +; a key moves up too"
  {:mu {:names ["+"]}}
  [x n]
  (if (and (mt/key? x) (integer? n))
    (el/transpose-key x n)
    (by-number "+" x n (fn [n x] (mt/transposed x n)) whole!)))

(defn down
  "Every pitch down n semitones, its key with it: [c e g] 12 -"
  {:mu {:names ["-"]}}
  [x n]
  (if (and (mt/key? x) (integer? n))
    (el/transpose-key x (- n))
    (by-number "-" x n (fn [n x] (mt/transposed x (- n))) whole!)))

(defn- scale-duration
  "The factor kept as the leaf's :ratio, as c4*1/3 keeps it, so a length
   that is no note value prints as written: c4 1/3 * as C4/4*1/3."
  [leaf f]
  (-> leaf (update :duration * f) (update :ratio (fnil * 1) f)))

(defn stretch
  "Every duration times f: [c d] 2 * gives halves"
  {:mu {:names ["*"]}}
  [x f]
  (by-number "*" x f (fn [f x] (mt/map-leaves #(scale-duration % f) x)) positive!))

(defn shrink
  "Every duration divided by f: [c d e] 3 / gives a triplet"
  {:mu {:names ["/"]}}
  [x f]
  (by-number "/" x f (fn [f x] (mt/map-leaves #(scale-duration % (/ 1 f)) x)) positive!))

(defn- hairpin [x target dir]
  (when-not (and (number? target) (<= 0 target 100)) (fail "needs a target volume 0-100 on top, got" (p/show target)))
  (when-not (musical? x) (fail "needs material under the target, got" (p/show x)))
  (mt/hairpin (mt/->material x) dir target))

(defn cresc
  "A crescendo over the material, up to a volume 0-100."
  [x target]
  (hairpin x target "<"))

(defn dim
  "A diminuendo over the material, down to a volume 0-100."
  [x target]
  (hairpin x target ">"))

;; -- reading notes ----------------------------------------------------------

(defn- one-note
  "f of the note, chord, rest or drum x; on a line, a pointer to plural,
   which reads every note."
  [word plural f x]
  (cond
    (mt/leaf? x) (f x)
    (or (v/material? x) (sequential? x)) (fail "reads one note; for every note of a line, use" (or plural (str "'( " word " ) map")))
    :else (fail "reads a note, chord, rest or drum, got" (p/show x))))

(defn- each-note
  "f of every note of x (a line, a note, a stream of them) as a stream;
   without rests? a rest gives no value."
  [word f rests? x]
  (if (musical? x)
    (read-notes word f rests? x)
    (fail "reads a line, got" (p/show x))))

(defn pitch
  "The pitch: 60 for c4, '[60 64 67] for <c e g>, nil for a rest or drum; for a line, pitches"
  [note] (one-note "pitch" "pitches" note-pitch note))
(defn pitches
  "Each note's pitch, as a stream, a rest's too: x durations x pitches zip is x again"
  [line] (mt/as-part :pitch (each-note "pitches" note-pitch true line)))
(defn duration
  "The length, in whole notes; for a line, durations"
  [note] (one-note "duration" "durations" :duration note))
(defn durations
  "Each note's duration, as a stream, a rest's too: x durations x pitches zip is x again"
  [line] (mt/as-part :duration (each-note "durations" :duration true line)))
(defn- own-volume [x] (get-in x [:overrides :volume]))
(defn volume
  "Its own volume (c4\\f, c4\\vol:90), else nil; for a line, volumes"
  [note] (one-note "volume" "volumes" own-volume note))
(defn volumes
  "Each note's volume, as a stream, a rest's nil: x volumes +volumes is x again"
  [line] (mt/as-part :volume (each-note "volumes" own-volume true line)))
(defn articulation
  "Its articulation (:staccato, :accent …), else nil; for a line, articulations"
  [note] (one-note "articulation" "articulations" articulation-name note))
(defn articulations
  "Each note's articulation, as a stream, a rest's nil: x articulations +articulations is x again"
  [line] (mt/as-part :articulation (each-note "articulations" articulation-name true line)))
(defn instrument
  "Its own instrument (c4\\i:40); nil for a drum or none; for a line, instruments"
  [note] (one-note "instrument" "instruments" instrument-of note))
(defn instruments
  "Each note's instrument, as a stream, a rest's nil: x instruments +instruments is x again"
  [line] (mt/as-part :instrument (each-note "instruments" instrument-of true line)))
(defn drum
  "A drum's key (x8\\38: 38); nil for a note; for a line, drums"
  [note] (one-note "drum" "drums" drum-of note))
(defn drums
  "Each note's drum, as a stream, a rest's nil: x drums +drums is x again"
  [line] (mt/as-part :drum (each-note "drums" drum-of true line)))

(defn note?  "True for a note with one pitch" [leaf] (one-note "note?" nil #(and (= :LEAF (:type %)) (= 1 (count (:pitches %)))) leaf))
(defn chord? "True for a chord" [leaf] (one-note "chord?" nil #(and (= :LEAF (:type %)) (< 1 (count (:pitches %)))) leaf))
(defn rest?  "True for a rest" [leaf] (one-note "rest?" nil #(= :REST (:type %)) leaf))
(defn drum?  "True for a drum" [leaf] (one-note "drum?" nil #(= :DRUM (:type %)) leaf))

;; -- a stream onto a line ---------------------------------------------------

(defn +volumes
  "Each note's own volume (0-100) from the stream in turn, a rest's
   unused: [c d e] volume/euclid +volumes"
  [line volumes]
  (onto "+volumes" #(assoc-in %1 [:overrides :volume] (mt/volume-value "+volumes" %2)) line volumes))

(defn +articulations
  "Each note's articulation (:accent, :staccato …) from the stream in turn, a rest's unused"
  [line articulations]
  (onto "+articulations" (partial mt/articulate "+articulations") line articulations))

(defn +instruments
  "Each note's instrument (a program, a General MIDI name or short name)
   from the stream in turn, a rest's unused; a drum is refused (+drums)"
  [line instruments]
  (onto "+instruments" (partial mt/set-instrument "+instruments") line instruments))

(defn +drums
  "Each drum's sound, a key of the kit (38, :sna, :kick), from the stream
   in turn; a note is refused (as-drums)"
  [line drums]
  (onto "+drums" set-drum line drums))

(defn as-drums
  "Each note made the drum from the stream in turn, keeping its length
   and volume: [c8 d e f] '[:kick :hhc :sna :hhc] as-drums"
  [line drums]
  (onto "as-drums" as-drum line drums))

(defn- layered
  "A copy of leaf x per value, each a drum's keys or a note's instruments."
  [x values]
  (mt/line (for [n values] (if (= :DRUM (:type x)) (assoc x :program n) (assoc-in x [:overrides :instrument] n)))))

(defn- sound-numbers! [nums example]
  (when-not (every? #(and (integer? %) (<= 0 % 127)) nums)
    (fail (str "needs whole numbers 0-127 (drum keys or instruments), as " example))))

(defn sounds
  "A copy of the leaf per number: a drum's key, a note's instrument (\\i:n)"
  [leaf numbers]
  (when-not (and (mt/leaf? leaf) (not= :REST (:type leaf)))
    (fail "needs a note, chord or drum under the numbers, got" (p/show leaf)))
  (let [nums (mt/finite! "sounds" (mt/elements numbers))]
    (sound-numbers! nums "35 82 range or '[36 38 42]")
    (layered leaf nums)))

(defn combos
  "A copy of the leaf per k of the numbers layered: a drum's keys, a note's instruments"
  [leaf numbers k]
  (when-not (fb/sound? leaf) (fail "needs a note, chord or drum under the numbers, got" (p/show leaf)))
  (let [nums (vec (distinct (mt/elements numbers)))]
    (sound-numbers! nums "'[40 41 71 73]")
    (when-not (and (integer? k) (<= 1 k (count nums))) (fail "needs a count 1 to" (count nums) "on top, got" k))
    (layered leaf (for [c (fb/combinations k nums)] (if (= 1 k) (first c) (vec c))))))

;; -- keys -------------------------------------------------------------------

(defn degrees
  "Scale steps of the key from its tonic in octave 4: 0 the tonic, 7 an
   octave up, -1 the step below"
  [numbers key]
  (when-not (mt/key? key) (fail "needs a key on top, as D.major, got" (p/show key)))
  (let [nums (mt/finite! "degrees" (mt/elements numbers))]
    (when-not (every? integer? nums) (fail "needs whole numbers under the key, as '[0 2 4]"))
    (mt/key-line key (map #(bridge/degree->pitch % (el/key->str key) 4) nums))))

(defn tonic
  "The key's tonic, in octave 4"
  [key]
  (when-not (mt/key? key) (fail "needs a key, as D.major, got" (p/show key)))
  (lib/->part (+ 60 (first (el/key-pitches key))) 1/4))

(defn key
  "The key a note was written in, or a line's own (else the defaults')"
  [x]
  (cond
    (mt/leaf? x) (or (:key x) (fail "this note has no written key (written under !acc:explicit, or not written at all)"))
    (v/material? x) (c/ctx-value-chain (keep :context [(v/node x) (repo/current :ROOT)]) :Key 0)
    :else (fail "needs a note or a line, got" (p/show x))))

(defn with
  "The settings in front of a line (its own come after, so they win), or
   played in that key; a { } or a repeat is wrapped"
  [x settings]
  (let [ctx (if (mt/key? settings) (mt/read-text (str "^{ !K:" (el/key->str settings) " }")) settings)]
    (when-not (= :CONTEXT (mt/kind ctx)) (fail "needs a context ^{ … } on top"))
    (let [settings (str/join " " (map :raw (:children (v/node ctx))))
          shown    (p/show (mt/->material x))]
      ;; at the start of a line (or a note made a line); a { } or a
      ;; repeat wrapped in a line holding them
      (mt/read-text
       (if-let [[_ id body] (and (or (mt/leaf? x) (and (= :SEQ (mt/kind x)) (not (mt/repetition? x))))
                                 (re-matches #"(?s)\[([a-zA-Z][a-zA-Z0-9_\-]*: )?(.*)\]" shown))]
         (str "[" id settings " " body "]")
         (str "[" settings " " shown "]"))))))

(defn transpose
  "Moved by n semitones, or by the interval from one note to the other,
   as the text's transpose: [c e g] 5 transpose, [c e g] c d transpose"
  {:mu {:names ["TRP"] :optional {:to mt/leaf?}}}
  ([x n]
   (when-not (integer? n) (fail "needs a whole number of semitones, or two notes, got" (p/show n)))
   (mt/transposed x n))
  ([x from to]
   (mt/transposed x (- (pitch-of to) (pitch-of from)))))

;; -- harmony ----------------------------------------------------------------

(defn walking-bass
  "A walking bass under a line of chords, made as it runs, new each
   time: a quarter a beat, the root on each chord's first, a step into
   the next, on an acoustic bass. With n, as long as the chords n times,
   each time walked anew, the last chord held; without, an ostinato: one
   line leading back into its own start, to loop with the chords."
  {:mu {:optional {:n integer?}}}
  ([chords] (walking-bass chords nil))
  ([chords n]
   (when (and n (< n 1)) (fail "walks a number of times from 1, got" n))
   (let [cs (vec (for [l (played-leaves "walking-bass" chords)]
                   {:pitches (when (= :LEAF (:type l)) (:pitches l)) :duration (:duration l) :token (str (:id l))}))]
     (when-not (some :pitches cs) (fail "there are no chords to walk under"))
     (mt/read-text (if n
                     (walking-bass/line->mus (vec (apply concat (clojure.core/repeat n cs))) {:end true})
                     (walking-bass/line->mus cs {:end false}))))))

;; -- finding names ----------------------------------------------------------

(defn- name-of
  "The start of a name: a string or a keyword (\"viol\", :viol)."
  [start]
  (cond (string? start) start
        (keyword? start) (name start)
        :else (fail "needs the start of a name on top, as a string or keyword: \"viol\" or :viol")))

(defn- list-names! [what start rows row]
  (if (seq rows)
    (doseq [r rows] (println (row r)))
    (println (str "no " what " is called or starts with " (pr-str start)))))

(defn find-instrument!
  "List every instrument a name, short name or its start can mean, one a
   line: program, name, short name; \"\" lists all."
  [start]
  (let [start (name-of start)]
    (list-names! "instrument" start (data/instrument-matches start)
                 (fn [{:keys [program name short]}] (format "%3d  %-22s %s" program name short)))))

(defn find-drum!
  "List every drum a name or its start can mean: key, name, short name,
   other names; \"\" lists all."
  [start]
  (let [start (name-of start)]
    (list-names! "drum" start (data/drum-matches start)
                 (fn [{:keys [key name short also]}] (str/trimr (format "%3d  %-20s %-5s %s" key name short (str/join " " also)))))))

(defn find-kit!
  "List every drum kit a name or its start can mean: program, name; \"\" lists all."
  [start]
  (let [start (name-of start)]
    (list-names! "kit" start (data/kit-matches start)
                 (fn [{:keys [program name]}] (format "%3d  %s" program name)))))
