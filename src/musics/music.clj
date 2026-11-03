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
  (:refer-clojure :exclude [key repeat cycle delay])
  (:require [clojure.string :as str]
            [musics.algo.bridge :as bridge]
            [musics.algo.harmonic.walking-bass :as walking-bass]
            [musics.algo.melodic.serial :as serial]
            [musics.algo.tree.lib :as lib]
            [musics.common.music-data :as data]
            [musics.common.context-keys :as ck]
            [musics.common.music-elements :as el]
            [musics.domain :as d]
            [musics.domain.context :as c]
            [musics.domain.resolve :as r]
            [musics.mu.eval :as e]
            [musics.mu.feedback :as fb]
            [musics.mu.material :as mt]
            [musics.mu.pcset :as pcset]
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
  "x as its retrograde (the crab), backwards, at any depth -- its elements
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

(defn- as-drum
  "The note as the drum i, keeping its length, its articulation's weight
   and its own settings (volume and the like); its pitch and instrument
   go."
  [l i]
  (if (= :DRUM (:type l))
    (assoc l :program (mt/drum-key "as-drums" i))
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
  "x inverted around the first note (a chord's first written pitch),
   which stays; I5 is [I] 5 +."
  {:mu {:names ["[I]"]}}
  [x]
  (invert-around first x))

(defn retrograde-inversion
  "x inverted around the first note, then backwards (the retrograde inversion)."
  {:mu {:names ["[RI]"]}}
  [x]
  (retrograde (inversion x)))

(defn mirror
  "x inverted around the mean pitch, keeping the register: M for mirror."
  {:mu {:names ["[M]"]}}
  [x]
  (invert-around on-mean x))

;; -- forms -----------------------------------------------------------------

(defn cycle
  "x endlessly, repeat ~ [ x ]: [c d] [~] > plays until stop; data or a
   stream gives its elements as an endless stream; take from either."
  {:mu {:names ["[~]"]}}
  [x]
  (when (and (mt/music? x) (empty? (mt/elements x))) (fail "an empty line loops without end"))
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

(defn- pitch-numbers
  "The pitch numbers of x: a note, a chord, a line (rests and drums have
   none), a data list or stream of numbers, or a set of classes."
  [word x]
  (let [ps (cond
             (v/pcset? x)     (do (when (= :vector (:kind x)) (fail "reads classes, not an interval vector"))
                                  (:items x))
             (mt/leaf? x)     (:pitches x)
             (v/material? x)  (if (= :DATA (mt/kind x))
                                (mt/numbers-of word x)
                                (mapcat :pitches (mt/played-leaves (mt/finite! word x))))
             (sequential? x)  (mt/numbers-of word x)
             :else            (fail "needs a line, a chord or pitch numbers, got" (p/show x)))]
    (when-not (every? integer? ps) (fail "needs whole pitch numbers, got" (p/show (first (remove integer? ps)))))
    (vec ps)))

(defn classes
  "The pitch classes of a line, a chord or a list of pitch numbers: each
   pitch's place above C in the octave (C 0 … B 11), every one once, the
   smallest first, as a set $[047]: [c e g c'] classes gives $[047].
   Rests and drums have none"
  [x]
  (pcset/make :classes (pitch-numbers "classes" x)))

(defn prime-form
  "The prime form of the classes of x, a line, a chord, pitch numbers or
   a set: the most packed of their rotations and of their inversion's,
   from 0 (Forte): [c e g] prime-form gives $(037), the major and the
   minor triad alike"
  [x]
  (pcset/make :prime (serial/prime-form (:items (pcset/make :classes (pitch-numbers "prime-form" x))))))

(defn interval-vector
  "How many times each interval class 1-6 occurs between the classes of
   x (a line, a chord, pitch numbers or a set): [c e g] interval-vector
   gives $<001110>"
  [x]
  (let [cs (:items (pcset/make :classes (pitch-numbers "interval-vector" x)))]
    (pcset/make :vector (serial/interval-vector cs))))

(defn >list
  "A set or any list of numbers as a plain list '[0 4 7], its kind gone:
   $[047] >list"
  [x]
  (cond
    (v/pcset? x) (mt/read-text (str "'[" (str/join " " (:items x)) "]"))
    (and (v/material? x) (= :DATA (mt/kind x))) x
    (sequential? x) (mt/read-text (str "'[" (str/join " " (map p/show (mt/finite! ">list" x))) "]"))
    :else (fail "needs a set or a list, got" (p/show x))))

(defn- class-weights
  "The time each pitch class sounds in a line or a chord, a vector of
   twelve ratios, C first: a chord's time shared among its pitches."
  [word x]
  (when-not (or (mt/leaf? x) (v/material? x)) (fail "needs a line or a chord, got" (p/show x)))
  (when (and (v/material? x) (= :DATA (mt/kind x))) (fail "needs music with durations, not data -- for pitch numbers use classes"))
  (let [leaves (if (mt/leaf? x) [x] (mt/played-leaves (mt/finite! word x)))]
    (reduce (fn [w leaf]
              (let [ps (seq (:pitches leaf))]
                (if (and ps (:duration leaf))
                  (reduce (fn [w p] (update w (mod p 12) + (/ (:duration leaf) (count ps)))) w ps)
                  w)))
            (vec (clojure.core/repeat 12 0)) leaves)))

(defn profile
  "How much each pitch class counts in a line, as twelve scores from 0 to
   1 (C first, B last): the time each class sounds, a chord's time shared
   among its pitches, divided by the largest so the strongest scores 1, as
   exact ratios: [c4 e4 g4 c2] profile gives '[1 0 0 0 1/3 0 0 1/3 0 0 0 0]"
  [x]
  (let [w      (class-weights "profile" x)
        top    (reduce max w)
        scores (map #(if (pos? top) (/ % top) 0) w)]
    (mt/read-text (str "'[" (str/join " " scores) "]"))))

(def ^:private key-profile-major
  "Krumhansl and Kessler's probe-tone ratings (1982): how well each degree
   of a major key, from its tonic up, fits it."
  [6.35 2.23 3.48 2.33 4.38 4.09 2.52 5.19 2.39 3.66 2.29 2.88])

(def ^:private key-profile-minor
  [6.33 2.68 3.52 5.38 2.60 3.53 2.54 4.75 3.98 2.69 3.34 3.17])

(def ^:private key-tonics
  {:major ["C" "D&" "D" "E&" "E" "F" "F#" "G" "A&" "A" "B&" "B"]
   :minor ["C" "C#" "D" "E&" "E" "F" "F#" "G" "G#" "A" "B&" "B"]})

(defn- correlation
  "Pearson's r of two equal-length number vectors, nil when either is flat."
  [xs ys]
  (let [n   (double (count xs))
        mx  (/ (reduce + xs) n) my (/ (reduce + ys) n)
        dx  (map #(- % mx) xs) dy (map #(- % my) ys)
        sxy (reduce + (map * dx dy))
        sxx (reduce + (map * dx dx)) syy (reduce + (map * dy dy))]
    (when (and (pos? sxx) (pos? syy)) (/ sxy (Math/sqrt (* sxx syy))))))

(defn key-of
  "The key a line, a chord, a set of classes or a list of pitch numbers
   most likely is in, major or minor, Krumhansl and Schmuckler's way: the
   class weights (time for music, once each for a set, how often for
   numbers) correlated with Krumhansl and Kessler's major and minor
   profile from each of the twelve tonics, the best of the 24:
   [c d e f g a b c'] key-of gives C.major. A guess, and a short phrase can
   fit two keys nearly as well; key is the key a line was written in"
  [x]
  (let [w (cond
            (v/pcset? x)     (do (when (= :vector (:kind x)) (fail "reads classes, not an interval vector"))
                                 (reduce #(update %1 (mod %2 12) inc) (vec (clojure.core/repeat 12 0)) (:items x)))
            (or (sequential? x) (and (v/material? x) (= :DATA (mt/kind x))))
            (reduce #(update %1 (mod %2 12) inc) (vec (clojure.core/repeat 12 0)) (pitch-numbers "key-of" x))
            :else            (class-weights "key-of" x))
        w (mapv double w)]
    (when-not (some pos? w) (fail "nothing sounds in" (p/show x) "-- it has no key"))
    (let [candidates (for [mode [:major :minor] t (range 12)
                           :let [prof (if (= mode :major) key-profile-major key-profile-minor)
                                 r    (correlation w (mapv #(nth prof (mod (- % t) 12)) (range 12)))]
                           :when r]
                       [r (str (get-in key-tonics [mode t]) "." (name mode))])]
      (when (empty? candidates) (fail "the classes in" (p/show x) "have no emphasis to tell a key by"))
      (el/parse-key (second (reduce #(if (> (first %2) (first %1)) %2 %1) candidates))))))

(def ^:private chord-name-order
  "The quality words chord-name tries, the simplest first: the same
   words a chord symbol is written with (musics.common.music-data/
   chord-qualities), m7.5- being m7 with its fifth lowered."
  ["M" "m" "d" "A" "5" "s2" "s4" "7" "M7" "m7" "d7" "m7.5-" "6" "m6"
   "9" "M9" "m9" "11" "M11" "m11" "13" "M13" "m13"])

(defn- quality-classes
  "The classes above the root a quality word makes (0 the root)."
  [word]
  (if (= word "m7.5-")
    #{0 3 6 10}
    (set (map #(mod % 12) (vals (get data/chord-qualities (keyword word)))))))

(def ^:private chord-qualities-by-name
  (mapv (fn [w] [w (quality-classes w)]) chord-name-order))

(defn- chord-root-spelling [flat? pc]
  (nth (get key-tonics (if flat? :minor :major)) pc))

(defn chord-name
  "The name of a chord as a chord symbol is written, root, a colon and a
   quality word, a slash and the lowest note when it is not the root:
   <c e g>1 chord-name gives \"C:M\", <e g c'> \"C:M/E\", <c e& g b&> \"C:m7\".
   Of a line, a set of classes or a list of pitch numbers, the chord
   they make together. The root is the lowest note when it fits (C6, not
   Am7/C); without a lowest note the simplest quality, lowest root first
   (Am7 before C6). Refused when no chord symbol quality fits"
  [x]
  (let [pitches (cond (mt/leaf? x)    (:pitches x)
                      (v/pcset? x)    nil
                      :else           (pitch-numbers "chord-name" x))
        classes (if (v/pcset? x) (:items (pcset/make :classes (pitch-numbers "chord-name" x))) (distinct (map #(mod % 12) pitches)))
        bass    (when (seq pitches) (mod (reduce min pitches) 12))
        cs      (set classes)
        fits    (for [[word qs] chord-qualities-by-name root (sort classes)
                      :when (= qs (set (map #(mod (- % root) 12) cs)))]
                  [root word])
        [root word] (or (when bass (first (filter #(= bass (first %)) fits))) (first fits))]
    (when (< (count cs) 2) (fail "needs at least two different classes to name a chord, got" (p/show x)))
    (when-not root
      (fail "no chord symbol fits" (p/show (pcset/make :classes (vec cs))) "-- m, M, d, A, the sevenths, sixths, suspensions, ninths …"))
    (let [flat? (boolean (re-find #"^(m|d)" word))]
      (str (chord-root-spelling flat? root) ":" word
           (when (and bass (not= bass root)) (str "/" (chord-root-spelling flat? bass)))))))

(declare amount)

(defn- par-inside?
  "Does x hold a parallel anywhere? Its parts sound together, so they
   have no one rhythm."
  [x]
  (and (v/material? x)
       (or (= :PAR (mt/kind x)) (some par-inside? (mt/finite! "pulses" (mt/elements x))))))

(defn- ratio-gcd [a b] (if (zero? b) a (recur b (mod a b))))

(defn pulses
  "A line's rhythm as a grid of pulses: a 1 where a note starts, a 0
   while it holds or a rest is silent, a chord one onset, a tie's second
   note a hold. The pulse is the largest note value every length is a
   whole number of; a pulse on top (1/8) is the grid instead, each length
   rounded to a whole number of them, at least one -- what the algos'
   pulses->durations reads back: [c4 d8 e8 f2] pulses gives
   '[1 0 1 1 1 0 0 0]"
  {:mu {:optional {:pulse number?}}}
  ([x] (pulses x nil))
  ([x pulse]
   (when-not (or (mt/leaf? x) (v/material? x)) (fail "needs a line, got" (p/show x)))
   (when (and (v/material? x) (= :DATA (mt/kind x))) (fail "needs music with durations, not data"))
   (when (par-inside? x) (fail "needs one line, not parallel parts: take a part with nth first"))
   (let [leaves (filterv #(pos? (or (:duration %) 0))
                         (if (mt/leaf? x) [x] (mt/played-leaves (mt/finite! "pulses" x))))
         unit   (if pulse (amount "pulses" pulse true) (reduce ratio-gcd (map :duration leaves)))
         steps  (loop [ls leaves prev nil acc []]
                  (if-let [leaf (first ls)]
                    (let [n    (max 1 (Math/round (double (/ (:duration leaf) unit))))
                          rest? (= :REST (:type leaf))
                          hold? (and prev (:tied prev) (not rest?) (not= :REST (:type prev)))]
                      (recur (rest ls) leaf
                             (into acc (if (or rest? hold?)
                                         (clojure.core/repeat n 0)
                                         (cons 1 (clojure.core/repeat (dec n) 0))))))
                    acc))]
     (mt/read-text (str "'[" (str/join " " steps) "]")))))

(defn- length-of
  "x's length in whole notes, as written: a line's parts one after the
   other, a parallel's longest part."
  [x]
  (cond (mt/leaf? x)   (:duration x)
        (mt/marker? x) 0
        (v/material? x) (let [parts (map length-of (mt/finite! "length" (mt/elements x)))]
                          (if (= :PAR (mt/kind x)) (reduce max 0 parts) (reduce + 0 parts)))
        :else 0))

(defn length
  "The length in whole notes, as written (jumps not followed, [!] first
   writes them out): [c4 d4 e2] length gives 1; a { } is its longest part"
  [x]
  (when-not (or (mt/leaf? x) (v/material? x)) (fail "needs music, got" (p/show x)))
  (let [n (length-of x)] (if (integer? n) (long n) n)))

(defn- note-at
  "[leaf nil] for the note sounding t whole notes into x, else [nil what
   is left of t after x]."
  [x t]
  (cond
    (mt/leaf? x)   (if (< t (:duration x)) [x nil] [nil (- t (:duration x))])
    (mt/marker? x) [nil t]
    (v/material? x)
    (do (when (= :PAR (mt/kind x)) (fail "needs a line, not parallel parts: take one with nth first"))
        (reduce (fn [[_ left] e] (let [[hit left'] (note-at e left)] (if hit (reduced [hit nil]) [nil left'])))
                [nil t] (mt/finite! "at" (mt/elements x))))
    :else [nil t]))

(defn at
  "The note, rest or drum sounding t whole notes into the line, counted
   from its start as written: [c4 d4 e2] 1/4 at gives D4/4, 3/8 at the
   same (a note sounds until the next starts)"
  [x t]
  (when-not (and (number? t) (>= t 0)) (fail "needs a time from 0 in whole notes on top, as 1/4 or 3/8, got" (p/show t)))
  (when-not (or (mt/leaf? x) (v/material? x)) (fail "needs music, got" (p/show x)))
  (or (first (note-at x (rationalize t)))
      (fail t "is past the end of" (p/show x) "-- it is" (length-of x) "long")))

(defn- bars-slots
  "The children of x's measures start up to end as one line's: every
   setting written before them first (what is in effect where they
   begin), then the measures' own children, the settings between them
   in place."
  [x start end]
  (let [outer (mt/measures x)
        slots (mt/items outer)
        pos   (vec (keep-indexed (fn [i [tag]] (when (= tag :el) i)) slots))
        n     (count pos)]
    (when-not (and (integer? start) (integer? end) (<= 0 start end n))
      (fail "needs bars counted from 0, 0 <= start <= end <=" n "-- got" (p/show start) (p/show end)))
    (when (= start end) (fail "needs at least one bar: start below end"))
    (let [from (nth pos start)
          upto (inc (nth pos (dec end)))]
      [outer
       (into (vec (filter (fn [[tag]] (= tag :ins)) (subvec slots 0 from)))
             (mapcat (fn [[tag v :as slot]] (if (= tag :el) (mt/items v) [slot]))
                     (subvec slots from upto)))])))

(defn bar
  "The n-th measure of the line by its meter, counted from 0 as nth
   counts, a line of its own with the settings in effect where it
   begins: [c2 d2 e2 f2] 1 bar gives [E4/2 F4]"
  [x n]
  (when-not (integer? n) (fail "needs a bar counted from 0, got" (p/show n)))
  (let [[outer slots] (bars-slots x n (inc n))]
    (mt/from-items outer slots)))

(defn bars
  "The measures from start up to (not including) end, one line, with
   the settings in effect where they begin: [c2 d2 e2 f2 c2 d2] 1 3 bars
   gives the second and third measure's notes together"
  [x start end]
  (let [[outer slots] (bars-slots x start end)]
    (mt/from-items outer slots)))

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
  "x with each note or drum q keeps made a rest of its length: the rhythm
   stays -- [c e g c'] '( pitch 65 gt ) mute."
  [x q]
  (when-not (v/quotation? q) (fail "needs a quotation '( … ) on top"))
  (mt/map-leaves #(if (and (not= :REST (:type %)) (mt/result "mute" q %))
                    (merge (d/rest* nil nil (:duration %)) (select-keys % [:ratio]))
                    %)
                 x))

;; -- the musical symbols ----------------------------------------------------

(defn- by-number
  "(g n x) for material x and a number n, after (check n); numbers, a
   data list or a stream are refused, pointing to the sign, which works
   on them too (musics.arith)."
  [sign x n g check]
  (cond
    (or (number? x) (mt/numeric-data? x)) (fail "is for music; on numbers write" sign)
    (not (and (musical? x) (number? n))) (fail "needs material and a number, got" (p/show x) "and" (p/show n))
    :else (do (check n) (g n x))))

(defn- whole! [n] (when-not (integer? n) (fail "transposes by a whole number of semitones, got" n)))
(defn- positive! [n] (when-not (pos? n) (fail "needs a positive factor, got" n)))

(defn up
  "x with every pitch up n semitones, its key with it (!K: at the start of its
   line): [c e g] 7 up, or 7 +; a key moves up too"
  {:mu {:also ["+"]}}
  [x n]
  (if (and (mt/key? x) (integer? n))
    (el/transpose-key x n)
    (by-number "+" x n (fn [n x] (mt/transposed x n)) whole!)))

(defn down
  "x with every pitch down n semitones, its key with it: [c e g] 12 down, or 12 -"
  {:mu {:also ["-"]}}
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
  "x with every duration times f: [c d] 2 stretch, or 2 *, gives halves"
  {:mu {:also ["*"]}}
  [x f]
  (by-number "*" x f (fn [f x] (mt/map-leaves #(scale-duration % f) x)) positive!))

(defn shrink
  "x with every duration divided by f: [c d e] 3 shrink, or 3 /, gives a triplet"
  {:mu {:also ["/"]}}
  [x f]
  (by-number "/" x f (fn [f x] (mt/map-leaves #(scale-duration % (/ 1 f)) x)) positive!))

(defn- hairpin [x target dir]
  (when-not (and (number? target) (<= 0 target 100)) (fail "needs a target volume 0-100 on top, got" (p/show target)))
  (when-not (musical? x) (fail "needs material under the target, got" (p/show x)))
  (mt/hairpin (mt/->material x) dir target))

(defn cresc
  "x with a crescendo over its material, up to a volume 0-100."
  [x target]
  (hairpin x target "<"))

(defn dim
  "x with a diminuendo over its material, down to a volume 0-100."
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
  "Each note's volume, as a stream, a rest's nil: x x volumes zip is x again"
  [line] (mt/as-part :volume (each-note "volumes" own-volume true line)))
(defn articulation
  "Its articulation (:staccato, :accent …), else nil; for a line, articulations"
  [note] (one-note "articulation" "articulations" articulation-name note))
(defn articulations
  "Each note's articulation, as a stream, a rest's nil: x x articulations zip is x again"
  [line] (mt/as-part :articulation (each-note "articulations" articulation-name true line)))
(defn instrument
  "Its own instrument (c4\\i:40); nil for a drum or none; for a line, instruments"
  [note] (one-note "instrument" "instruments" instrument-of note))
(defn instruments
  "Each note's instrument, as a stream, a rest's nil: x x instruments zip is x again"
  [line] (mt/as-part :instrument (each-note "instruments" instrument-of true line)))
(defn drum
  "A drum's key (x8\\38: 38); nil for a note; for a line, drums"
  [note] (one-note "drum" "drums" drum-of note))
(defn drums
  "Each note's drum, as a stream, a rest's nil: x x drums zip is x again"
  [line] (mt/as-part :drum (each-note "drums" drum-of true line)))

(defn note?  "True for a note with one pitch" [leaf] (one-note "note?" nil #(and (= :LEAF (:type %)) (= 1 (count (:pitches %)))) leaf))
(defn chord? "True for a chord" [leaf] (one-note "chord?" nil #(and (= :LEAF (:type %)) (< 1 (count (:pitches %)))) leaf))
(defn rest?  "True for a rest" [leaf] (one-note "rest?" nil #(= :REST (:type %)) leaf))
(defn drum?  "True for a drum" [leaf] (one-note "drum?" nil #(= :DRUM (:type %)) leaf))

;; -- notes made drums -------------------------------------------------------

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

(defn- context-articulation
  "The articulation a note plays with: its own, else the context's
   factor under the name of the staccato, tenuto … it equals, else the
   factor itself."
  [x]
  (or (articulation-name x)
      (let [f (r/sample-leaf x :articulation)]
        (or (some (fn [[k v]] (when (and (= f (:duration v)) (= 0 (:dynamic v))) k)) data/articulations)
            f))))

(defn setting
  "The setting x plays with: :volume, :instrument, :Key … (any context key,
   as !key: writes it). A note, rest or drum: its own value, else the
   context's where it sits (a drum's instrument is its key of the kit,
   x8\\38: 38); a line: its context at its start."
  {:mu {:names ["context-of"]}}
  [x k]
  (let [k (ck/canonical-key (keyword k))
        whole (fn [v] (if (and (integer? v) (not (instance? Long v))) (long v) v))] ; an interpolated 60, not 60N
    (whole
     (cond
      (mt/leaf? x) (cond
                     (and (= k :instrument) (= :DRUM (:type x))) (:program x)
                     (= k :articulation) (context-articulation x)
                     (contains? (:overrides x) k) (get (:overrides x) k)
                     :else (r/sample-leaf x k))
      (v/material? x) (c/ctx-value-chain (keep :context [(v/node x) (repo/current :ROOT)]) k 0)
      :else (fail "needs a note or a line, got" (p/show x))))))

(defn- amount
  "d as an exact length in whole notes, from 0 (above 0 when positive?)."
  [word d positive?]
  (when-not (and (number? d) (if positive? (pos? d) (>= d 0)))
    (fail (str word ":") "needs a length in whole notes" (if positive? "above 0" "from 0") "(as 1/4 or 3/8), got" (p/show d)))
  (rationalize d))

(defn- delayed
  "line (or a note) with a rest of length d in front of its first element,
   its own settings staying first."
  [line d]
  (let [line  (if (mt/leaf? line) (mt/line [line]) line)
        slots (mt/items line)
        at    (or (first (keep-indexed (fn [i [tag]] (when (= tag :el) i)) slots)) (count slots))]
    (when-not slots (fail "delays one line, got" (p/show line)))
    (when (= :PAR (mt/kind line)) (fail "delays one line; for the parts of a parallel use delays"))
    (mt/from-items line (into (subvec slots 0 at) (cons [:el (d/rest* nil nil d)] (subvec slots at))))))

(defn delay
  "x starting d late, a rest of d whole notes in front of it (a
   rational: 1/4, 3/8, 1/3): [a b c] 1/4 delay is [r4 a b c]"
  [x d]
  (when-not (or (mt/leaf? x) (v/material? x)) (fail "needs a line, got" (p/show x)))
  (delayed x (amount "delay" d true)))

(defn delays
  "x with the parts of a parallel starting late, each by a rest in front: a
   number d delays part k by k times d (a canon at d), a data list gives
   each part its own, one for each part:
   {[a b c] [a b c] [a b c]} 1/4 delays, {[a b c] [a b c]} '[0 1/3] delays"
  [x d]
  (when-not (and (v/material? x) (= :PAR (mt/kind x))) (fail "needs a parallel { }, got" (p/show x) "-- for one line use delay"))
  (let [slots (mt/items x)
        parts (vec (keep-indexed (fn [i [tag v]] (when (and (= tag :el) (not (mt/marker? v))) i)) slots))
        by    (if (or (v/material? d) (sequential? d))
                (let [ds (mapv #(amount "delays" % false) (mt/finite! "delays" (mt/elements d)))]
                  (when-not (= (count ds) (count parts))
                    (fail "needs one delay for each of the" (count parts) "parts, got" (count ds)))
                  ds)
                (let [step (amount "delays" d false)] (mapv #(* % step) (range (count parts)))))
        slots' (reduce (fn [sl [i k]]
                         (let [v (second (nth sl i)) dk (nth by k)]
                           (if (zero? dk) sl (assoc sl i [:el (delayed v dk)]))))
                       slots (map vector parts (range)))]
    (mt/from-items x slots')))

(defn- setting-text
  "!key:value as the text reads it, for a key keyword and a value on the
   stack: a number, a ratio, a key, a meter or a name as it is written, a
   string bare when it is a name, else quoted."
  [k v]
  (let [n (name k)
        ck (try (ck/context-key k) (catch clojure.lang.ExceptionInfo _ nil))]
    (when (and ck (#{:float :int} (:type ck)) (not (number? v)) (not (#{"instrument" "kit"} (:name ck))))
      (fail (str ":" n) "is a number, got" (p/show v)))
    (str "!" n ":"
         (cond (number? v)           (if (ratio? v) (str (numerator v) "/" (denominator v)) (pr-str v))
               (mt/key? v)           (el/key->str v)
               (instance? musics.common.music_elements.Meter v) (el/meter->str v)
               (keyword? v)          (name v)
               (and (string? v) (re-matches #"[a-zA-Z][a-zA-Z0-9_]*" v)) v
               (string? v)           (pr-str v)
               :else (fail "can't set" (str ":" n) "to" (p/show v) "-- a number, a ratio, a key, a meter or a name")))))

(defn with
  "x with the settings in front of it (its own come after, so they win): a
   context ^{ !mf }, a key D.major, or one key and its value, :volume 70
   with; a note becomes a line; a { } or a repeat is wrapped"
  {:mu {:arity (fn [stack] (if (and (>= (count stack) 3) (keyword? (peek (pop stack)))) 3 2))}}
  ([x k value]
   (with x (mt/read-text (str "^{ " (setting-text k value) " }"))))
  ([x settings]
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
         (str "[" settings " " shown "]")))))))

(defn transpose
  "x moved by n semitones, or by the interval from one note to the other,
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
