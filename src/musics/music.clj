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
            [musics.mu.feedback :as fb]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.tools :as tools]
            [musics.mu.value :as v]
            [musics.repo :as repo]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

(defn- musical? [x] (or (mt/leaf? x) (v/material? x) (and (sequential? x) (not (string? x)))))

;; -- the row forms ----------------------------------------------------------

(defn retrograde
  "The retrograde (the crab): in reverse order, at any depth, ties
   turned round; exactly [R] -- [ R ] is a line with a bar's rest."
  {:mu {:names ["[R]"]}}
  [x]
  (tools/retrograde x))

(defn inversion
  "The inversion around the first note (a chord's first written pitch),
   which stays; I5 is [I] 5 +."
  {:mu {:names ["[I]"]}}
  [x]
  (tools/invert-around first x))

(defn retrograde-inversion
  "The retrograde inversion: inverted around the first note, then backwards."
  {:mu {:names ["[RI]"]}}
  [x]
  (retrograde (inversion x)))

(defn mirror
  "The inversion around the mean pitch, keeping the register: M for mirror."
  {:mu {:names ["[M]"]}}
  [x]
  (tools/invert-around tools/on-mean x))

;; -- forms -----------------------------------------------------------------

(defn cycle
  "x endlessly, repeat ~ [ x ]: [c d] [~] > plays until stop; data or a
   stream gives its elements as an endless stream; take from either."
  {:mu {:names ["[~]"]}}
  [x]
  (if (tools/music? x)
    (mt/repetition x :infinite [])
    (mt/repeated (tools/finite! "[~]" x) :infinite)))

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
  (tools/times-of "repeat" x n))

(defn times
  "x played n times, as the text's repeat n [ x ]; data and streams
   written out n times."
  {:mu {:ns "tool" :names ["[*]"]}}
  [x n]
  (when-not (and (integer? n) (not (neg? n))) (fail "needs a whole number on top, got" (p/show n)))
  (tools/times-of "[*]" x n))

(defn beside
  "The parts of the two in one { }: {[a] [b]} {[c] [d]} {+} gives
   {[a] [b] [c] [d]}; a line is one part."
  {:mu {:names ["{+}"]}}
  [a b]
  (if (= :PAR (mt/kind a))
    (tools/reshape a #(concat % (tools/parts b)))
    (tools/par-of (cons a (tools/parts b)))))

(defn zip
  "durations and pitches as a line; a rest among the durations stays a
   rest, using no pitch."
  [durations pitches]
  (tools/zip-of durations pitches))

(defn mute
  "Each note or drum q keeps becomes a rest of its length: the rhythm
   stays -- [c e g c'] '( pitch 65 gt ) mute."
  [x q]
  (when-not (v/quotation? q) (fail "needs a quotation '( … ) on top"))
  (mt/map-leaves #(if (and (not= :REST (:type %)) (tools/result "mute" q %))
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
  (if (and (tools/key? x) (integer? n))
    (el/transpose-key x n)
    (by-number "+" x n (fn [n x] (mt/transposed x n)) whole!)))

(defn down
  "Every pitch down n semitones, its key with it: [c e g] 12 -"
  {:mu {:names ["-"]}}
  [x n]
  (if (and (tools/key? x) (integer? n))
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
    (tools/read-notes word f rests? x)
    (fail "reads a line, got" (p/show x))))

(defn pitch
  "The pitch: 60 for c4, '[60 64 67] for <c e g>, nil for a rest or drum; for a line, pitches"
  [note] (one-note "pitch" "pitches" tools/note-pitch note))
(defn pitches
  "Each note's pitch, as a stream, a rest's too: x durations x pitches zip is x again"
  [line] (each-note "pitches" tools/note-pitch true line))
(defn duration
  "The length, in whole notes; for a line, durations"
  [note] (one-note "duration" "durations" :duration note))
(defn durations
  "Each note's duration, as a stream, a rest's too: x durations x pitches zip is x again"
  [line] (each-note "durations" :duration true line))
(defn- own-volume [x] (get-in x [:overrides :volume]))
(defn volume
  "Its own volume (c4\\f, c4\\vol:90), else nil; for a line, volumes"
  [note] (one-note "volume" "volumes" own-volume note))
(defn volumes
  "Each note's volume, as a stream, rests left out: x volumes with-volumes is x again"
  [line] (each-note "volumes" own-volume false line))
(defn articulation
  "Its articulation (:staccato, :accent …), else nil; for a line, articulations"
  [note] (one-note "articulation" "articulations" tools/articulation-name note))
(defn articulations
  "Each note's articulation, as a stream, rests left out: x articulations with-articulations is x again"
  [line] (each-note "articulations" tools/articulation-name false line))
(defn instrument
  "Its own instrument (c4\\i:40); nil for a drum or none; for a line, instruments"
  [note] (one-note "instrument" "instruments" tools/instrument-of note))
(defn instruments
  "Each note's instrument, as a stream, rests left out: x instruments with-instruments is x again"
  [line] (each-note "instruments" tools/instrument-of false line))
(defn drum
  "A drum's key (x8\\38: 38); nil for a note; for a line, drums"
  [note] (one-note "drum" "drums" tools/drum-of note))
(defn drums
  "Each note's drum, as a stream, rests left out: x drums with-drums is x again"
  [line] (each-note "drums" tools/drum-of false line))

(defn note?  "True for a note with one pitch" [leaf] (one-note "note?" nil #(and (= :LEAF (:type %)) (= 1 (count (:pitches %)))) leaf))
(defn chord? "True for a chord" [leaf] (one-note "chord?" nil #(and (= :LEAF (:type %)) (< 1 (count (:pitches %)))) leaf))
(defn rest?  "True for a rest" [leaf] (one-note "rest?" nil #(= :REST (:type %)) leaf))
(defn drum?  "True for a drum" [leaf] (one-note "drum?" nil #(= :DRUM (:type %)) leaf))

;; -- a stream onto a line ---------------------------------------------------

(defn with-volumes
  "Each note's own volume (0-100) from the stream in turn; rests take
   none: [c d e] volume/euclid with-volumes"
  [line volumes]
  (tools/onto "with-volumes" #(assoc-in %1 [:overrides :volume] %2) line volumes))

(defn with-articulations
  "Each note's articulation (:accent, :staccato …) from the stream in turn; rests take none"
  [line articulations]
  (tools/onto "with-articulations" tools/set-articulation line articulations))

(defn with-instruments
  "Each note's instrument (a program, a General MIDI name or short name)
   from the stream in turn; rests take none, a drum is refused (with-drums)"
  [line instruments]
  (tools/onto "with-instruments" tools/set-instrument line instruments))

(defn with-drums
  "Each drum's sound, a key of the kit (38, :sna, :kick), from the stream
   in turn; a note is refused (as-drums)"
  [line drums]
  (tools/onto "with-drums" tools/set-drum line drums))

(defn as-drums
  "Each note made the drum from the stream in turn, keeping its length
   and volume: [c8 d e f] '[:kick :hhc :sna :hhc] as-drums"
  [line drums]
  (tools/onto "as-drums" tools/as-drum line drums))

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
  (let [nums (tools/finite! "sounds" (mt/elements numbers))]
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
  (when-not (tools/key? key) (fail "needs a key on top, as D.major, got" (p/show key)))
  (let [nums (tools/finite! "degrees" (mt/elements numbers))]
    (when-not (every? integer? nums) (fail "needs whole numbers under the key, as '[0 2 4]"))
    (tools/key-line key (map #(bridge/degree->pitch % (el/key->str key) 4) nums))))

(defn tonic
  "The key's tonic, in octave 4"
  [key]
  (when-not (tools/key? key) (fail "needs a key, as D.major, got" (p/show key)))
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
  (let [ctx (if (tools/key? settings) (mt/read-text (str "^{ !K:" (el/key->str settings) " }")) settings)]
    (when-not (= :CONTEXT (mt/kind ctx)) (fail "needs a context ^{ … } on top"))
    (tools/with-settings x (str/join " " (map :raw (:children (v/node ctx)))))))

(defn transpose
  "Moved by n semitones, or by the interval from one note to the other,
   as the text's transpose: [c e g] 5 transpose, [c e g] c d transpose"
  {:mu {:names ["TRP"] :optional {:to mt/leaf?}}}
  ([x n]
   (when-not (integer? n) (fail "needs a whole number of semitones, or two notes, got" (p/show n)))
   (mt/transposed x n))
  ([x from to]
   (mt/transposed x (- (tools/pitch-of to) (tools/pitch-of from)))))

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
   (let [cs (vec (for [l (tools/played-leaves "walking-bass" chords)]
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
