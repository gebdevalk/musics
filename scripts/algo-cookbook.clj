;; Generates doc/md/algo-cookbook.md: 58 recipes of the algorithms as plain
;; functions called by name, with mu's own words. Every
;; recipe's mu input is run here and the result shown is exactly what came back.
;;
;; Run from the repo root, then render it (html, git-ignored):
;;   lein run -m clojure.main scripts/algo-cookbook.clj
;;   scripts/docs.sh
;;
;; Nothing sounds: the play recipes are shown, not run.
(ns algo-cookbook
  (:require [clojure.string :as str]
            [musics.algo.numeric.rng :as seed]
            [musics.input.reader.builder :as builder]
            [musics.mu]
            [musics.mu.eval :as e]
            [musics.mu.print :as p]
            [musics.repo :as repo]))

;; ---------------------------------------------------------------------------
;; Running mu
;; ---------------------------------------------------------------------------

(def ^:private words (:ns @e/state))

(defn- fresh! []
  (reset! e/state (assoc (e/fresh-state) :ns words))
  (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT)))

(defn- clip [s limit]
  (if (> (count s) limit) (str (subs s 0 (- limit 2)) " …") s))

(defn- run
  "What the input prints, then the stack it leaves as mu prints it, or the error."
  [input]
  (swap! e/state assoc :stack [])
  (let [r    (atom nil)
        out  (str/trim (with-out-str (reset! r (e/eval-input input))))
        res  (cond (:error @r) (str/trim (str "error: " (:error @r)))
                   (:ok @r)    (str/join "  " (map p/show-display (:ok @r)))
                   :else       "(unfinished)")]
    (str/join "\n" (remove str/blank? [out res]))))

;; ---------------------------------------------------------------------------
;; The recipes: each is a title, what to say, and the lines typed at the prompt.
;; A line with :play is shown and not run.
;; ---------------------------------------------------------------------------

(def setup
  "Typed once before the recipes: the Clojure namespaces of musics.algo whose functions the recipes call,
   each word `namespace/function`, and the scale the recipes play over."
  ["'numeric.rng reqalgo"
   "'rhythmic.pattern reqalgo"
   "'rhythmic.sieve reqalgo"
   "'rhythmic.meter reqalgo"
   "'rhythmic.indispensability reqalgo"
   "'rhythmic.process reqalgo"
   "'rhythmic.nature reqalgo"
   "'rhythmic.data reqalgo"
   "'rhythmic.world reqalgo"
   "'rhythmic.feel reqalgo"
   "'rhythmic.decompose reqalgo"
   "'numeric.random reqalgo"
   "'numeric.walk reqalgo"
   "'numeric.markov reqalgo"
   "'numeric.chaos.logistic reqalgo"
   "'numeric.chaos.lorenz reqalgo"
   "'melodic.melody reqalgo"
   "'melodic.serial reqalgo"
   "'melodic.slonimsky reqalgo"
   "'counterpoint.jeppesen reqalgo"
   "'bridge reqalgo"
   ": penta ( -- l ) '[60 62 64 67 69] ;"])

(def recipes
  [{:group "First steps"}

   {:title "A source, a bridge, a scale"
    :text "`pattern/euclid` is the Clojure function itself: its arguments, `onsets` and `pulses`, come from the stack and `see pattern/euclid` shows them. It gives a row of pulses, 1 for an onset and 0 for a rest. `bridge/pulses->durations` lets each onset last until the next one on a grid of the `pulse` you give it; `zip` puts durations and pitches together. `penta` is a word of our own, the scale the recipes play over."
    :lines ["see pattern/euclid"
            "3 8 pattern/euclid"
            "3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip"]}

   {:title "Change a parameter; a precondition guards it"
    :text "Write another number. A function that cannot work with its arguments says what it needs."
    :lines ["5 8 pattern/euclid 1/16 bridge/pulses->durations penta zip"
            "5 8 pattern/euclid 1/16 bridge/pulses->durations '[60 63 67 70] zip"
            "99 8 pattern/euclid"
            "0 0 pattern/euclid"]}

   {:title "Wrong shapes"
    :text "A word takes what its stack effect says from the stack. Too few arguments are refused; arguments of the wrong kind are not always: a bridge on durations runs and gives nonsense."
    :lines ["5 pattern/euclid"
            "3 8 pattern/euclid zip"
            "3 8 pattern/euclid 1/16 bridge/pulses->durations 1/16 bridge/pulses->durations"]}

   {:title "Raw material needs its bridges"
    :text "Pulses become durations with one bridge. Weights (`indisp` ranks) need two: `weights->pulses` keeps the strongest `density` of them, then `pulses->durations`."
    :lines ["3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip"
            "'[2 2 3] indispensability/indisp 0.5 bridge/weights->pulses 1/16 bridge/pulses->durations penta zip"]}

   {:title "One run, several parameters"
    :text "`dup` copies what is on the stack, so one run of a walk can be the pitches and the velocities: the higher the note, the louder. `pull` calls a function n times; `walk/walk` gives a function that keeps its place."
    :lines ["7 rng/seed"
            "60.0 3.0 walk/walk 8 pull dup 52 68 48 72 bridge/numbers->pitches swap 52 68 40 100 bridge/numbers->volumes"]}

   {:group "Rhythm × melody"}

   {:title "Euclidean rhythm, rotated, over a reshuffled pentatonic"
    :text "`rotate` and `shuffle` are mu's own list words: they work on a row of pulses and on a list of pitches alike."
    :lines ["7 rng/seed"
            "5 16 pattern/euclid 2 rotate 1/16 bridge/pulses->durations penta shuffle zip"]}

   {:title "Legato by default, detached by articulation"
    :text "`zip` takes a named part: `:articulation` before a list gives every note it."
    :lines ["5 8 pattern/euclid 1/16 bridge/pulses->durations penta :articulation '[:staccato] zip"]}

   {:title "A Fibonacci rhythm"
    :text "Every generator of `musics/algo/` is a function you can call."
    :lines ["16 pattern/fibonacci 1/16 bridge/pulses->durations '[60 62 64 65 67 69 71] zip"]}

   {:title "A Xenakis sieve as the rhythm"
    :text "The moduli and their residues are lists; a list inside a list is written `'[ '[0 1] '[2] ]`."
    :lines ["'[3 4] '[ '[0 1] '[2] ] 12 sieve/sieve 1/16 bridge/pulses->durations penta zip"]}

   {:title "A clave timeline carrying an arpeggio"
    :text "`world/bell` gives a named West African timeline for a meter, `[pulses subdivision]`."
    :lines ["'[12 8] \"clave\" world/bell 1/16 bridge/pulses->durations '[60 64 67 72] zip"]}

   {:group "Settings: the tctx"}

   {:title "Parameters live in the tctx"
    :text "Until here every number was typed into the line. The session also keeps a table of named values, the **tctx**. Where a word that is a Clojure function needs a number, a keyword the table holds stands for its value, so the line that makes the music is written once, with names, and the numbers are kept apart. A key is named for the argument it feeds (`see pattern/euclid` says `onsets` and `pulses`), in letters, digits and underscores (`:step_bound`), and never for a part name `zip` knows (`:pitch`, `:duration`, `:volume`). A computed key is a quotation: the line itself, stored once, run by `T@@` with the settings as they are. The whole table can be written out as `@{ :key value … }` (a context, `^{ }`, has string keys and its own brackets) and made the session's with `:tctx R!`, which replaces what was there; `:tctx R@` writes the session's back in the same form. `T!` sets one key of it (`3 :onsets T!`), `T@` reads one. From here on the recipes are written this way."
    :lines ["@{ :onsets 3 :pulses 8 :pulse 1/16 :euclid '( :onsets :pulses pattern/euclid :pulse bridge/pulses->durations penta zip ) } :tctx R!"
            ":tctx R@"
            ":onsets T@ :pulses T@"
            ":euclid T@"
            ":euclid T@@"]}

   {:title "Change a setting, run it again"
    :text "A setting changes and the recipe does not. Nothing is retyped, and a live voice that reads the key hears it at its next note."
    :lines ["5 :onsets T!"
            ":euclid T@@"
            "7 :onsets T! 12 :pulses T!"
            ":euclid T@@"]}

   {:title "A setting computed from others"
    :text "A computed key is asked for when it is read, so it follows what it is made of: `:onsets` here is half of `:pulses`, and `:euclid` goes on reading it as `:onsets`. A key that asks for itself, however far round, is refused."
    :lines ["'( :pulses 2 / int ) :onsets T!"
            ":onsets T@@"
            "16 :pulses T!"
            ":onsets T@@"
            ":euclid T@@"]}

   {:title "Play what the settings give"
    :text "`:euclid T@@` is a seq like any other, so a play word takes it. Shown, not run. A voice that reads the settings as it plays is recipe 55."
    :lines [[:play ":euclid T@@ [~] ->"]
            [:play "stop"]]}

   {:group "Isorhythm and durations"}

   {:title "Isorhythm: a 5-note color against a 3-value talea"
    :text "Two loops of different lengths zipped line up after their least common multiple, here 15 notes. The color, the talea and the length are settings."
    :lines ["'[1/4 1/8 1/8] :talea T! '[60 62 64 65 67] :color T! 15 :notes T!"
            "'( :talea [~] :color [~] zip :notes take ) :isorhythm T!"
            ":isorhythm T@@"]}

   {:title "Stretch the durations, transpose the pitches"
    :text "A computed key can read another: `:isorhythm` inside the quotation is the seq it makes now. `*` stretches the durations of a line and `+` transposes it."
    :lines ["1/2 :stretch T! 7 :shift T!"
            "'( :isorhythm :stretch * :shift + ) :moved T!"
            ":moved T@@"]}

   {:group "Metre and indispensability"}

   {:title "Indispensability thins a 12/8 bar"
    :text "`indisp` ranks the pulses of a meter given by its subdivisions, `tilt` turns ranks into probabilities (`adherence` how strongly rank decides), `weights->pulses` keeps the strongest `density` of them. `zip` reads keywords itself (`:articulation '[:staccato] zip` names a part), so a setting standing right before it is fetched with `T@`."
    :lines ["'[2 2 3] :subdivisions T! 0.8 :adherence T! 0.5 :density T! '[67 64 60] :tones T!"
            "'( :subdivisions indispensability/indisp :adherence indispensability/tilt :density bridge/weights->pulses :pulse bridge/pulses->durations :tones T@ zip ) :thinned T!"
            ":thinned T@@"]}

   {:title "Swap one stage: strong beats silent"
    :text "`power` in place of `tilt`, with a negative adherence: the weak pulses win. The settings are the same ones; only one word of the line changes."
    :lines ["-0.8 :adherence T!"
            "'( :subdivisions indispensability/indisp :adherence indispensability/power :density bridge/weights->pulses :pulse bridge/pulses->durations :tones T@ zip ) :weak_wins T!"
            ":weak_wins T@@"]}

   {:title "One weighted choice"
    :text "`random/weighted-choose` draws one of the values (here the nine pulses `0 9 range`) with the weights it is given. The seed is a setting too: `:seed rng/seed` at the start of a computed key makes every run of it the same."
    :lines ["7 :seed T! 0 9 range :vals T! '[3 3] :subdivisions T! 1.0 :adherence T!"
            "'( :seed rng/seed :vals :subdivisions indispensability/indisp :adherence indispensability/tilt random/weighted-choose ) :choice T!"
            ":choice T@@"]}

   {:group "Melody generators"}

   {:title "A Markov melody trained on a motif"
    :text "`markov-train` learns the motif (its `order`), `markov-gen` walks the model for `length` notes."
    :lines ["7 :seed T! 11 :onsets T! 16 :pulses T! '[60 62 64 62 60 67 65 64] :melody T! 1 :order T! 11 :length T!"
            "'( :seed rng/seed :onsets :pulses pattern/euclid :pulse bridge/pulses->durations :melody :order melody/markov-train :length melody/markov-gen zip ) :markov T!"
            ":markov T@@"]}

   {:title "Slonimsky: insertions below, between, above"
    :text "`slonimsky/infra` puts a pattern after each principal tone but the last (tones you chose to lie below them); `slonimsky/polation2` does infra, inter and ultra at once, in that order."
    :lines ["'[60 64 67 72] :principal T! '[59 62] :infra T! '[65] :inter T! '[62] :ultra T!"
            "'( :principal :infra slonimsky/infra ) :below T!"
            ":below T@@"
            "'( :principal '[] :inter :ultra slonimsky/polation2 ) :around T!"
            ":around T@@"]}

   {:title "An L-system melody"
    :text "`melody/lsys-melody` expands an axiom with rules and reads each letter as a note. The rules and the letters' pitches are maps, which mu text cannot write yet, so this one is only shown: `see` gives its parameters."
    :lines ["see melody/lsys-melody"]}

   {:title "A constrained walk"
    :text "`melody/constrained` walks a scale: each note is drawn from those that every constraint accepts. A constraint is a function of the melody so far and the candidate note, and a quotation is one: `( '( … ) '( … ) )` gathers quotations into the list, here inside the computed key. The first constraint refuses a repeated note (`swap last =` compares the candidate with the last note), the second keeps every step within a whole tone (`swap last - abs 3 <`)."
    :lines ["7 :seed T! '[60 62 64 65 67 69 71 72] :scale T! 12 :length T!"
            "'( :seed rng/seed :scale :length '[] melody/constrained ) :free_walk T!"
            ":free_walk T@@"
            "'( :seed rng/seed :scale :length ( '( swap last = not ) '( swap last - abs 3 < ) ) melody/constrained ) :constrained_walk T!"
            ":constrained_walk T@@"]}

   {:title "A melody that modulates"
    :text "Segments of `[key length]` pairs, a key being `'[:C :major]`, and the constraints they share, as in the walk above. The result is pitch classes of the keys; `60 +` lifts them to the middle of the keyboard."
    :lines ["7 :seed T! '[ '[ '[:C :major] 6] '[ '[:E :minor] 6] ] :segments T! 60 :lift T!"
            "'( :seed rng/seed :segments ( '( swap last = not ) ) melody/modulating :lift + ) :modulating T!"
            ":modulating T@@"]}

   {:title "Two-voice counterpoint"
    :text "`jeppesen/species` takes the cantus, the number of voices, the species of the added voice, which part holds the cantus, the mode (`:auto` finds both) and a seed for the search. It gives one line per voice. `:auto` is no setting, so it stays itself."
    :lines ["'[55 57 59 60 62 64 65 67 69 71 72 74 76] :cantus T! 2 :voices T! 1 :kind T! 1 :search_seed T!"
            "'( :cantus :voices :kind :auto :auto :search_seed jeppesen/species ) :counterpoint T!"
            ":counterpoint T@@"]}

   {:group "Metric generators"}

   {:title "A number's bits, a fraction's expansion"
    :text "45 is 101101: onsets on bits 0, 2, 3 and 5. A function with a keyword tail takes `:key value` pairs written before it: `:length 8`, the key `:length` staying itself because the table has no such setting. π is [3; 7, 15, 1, …]."
    :lines ["45 :number T! 8 :width T! 3.141592653589793 :fraction T! 16 :terms T!"
            "'( :number :length :width pattern/bits :pulse bridge/pulses->durations penta zip ) :bits T!"
            ":bits T@@"
            "'( :fraction :terms pattern/cfrac :pulse bridge/pulses->durations penta zip ) :expansion T!"
            ":expansion T@@"]}

   {:title "Modular arithmetic as a rhythm"
    :text "An onset wherever `multiplier · i + offset` is a multiple of `modulus`: `modulus multiplier length offset`. Name a setting for the argument, and none for a keyword another word reads: a table with a `:length` in it would make the `:length 8` of the recipe above a number."
    :lines ["5 :modulus T! 2 :multiplier T! 15 :span T! 0 :offset T!"
            "'( :modulus :multiplier :span :offset pattern/modular :pulse bridge/pulses->durations penta zip ) :modular T!"
            ":modular T@@"]}

   {:group "Random sources"}

   {:title "A sampler is a function: pull n draws"
    :text "`random/triangular` gives one draw per call; `pull` calls a quotation n times and collects the results. The quotation inside the computed key reads the settings as well. The bridge puts the draws on pitches, the range they really have being its second and third arguments."
    :lines ["7 :seed T! 0.0 :lo T! 1.0 :hi T! 0.5 :mode T! 12 :draws T! 60 :low T! 73 :high T!"
            "'( :seed rng/seed '( :lo :hi :mode random/triangular ) :draws pull :lo :hi :low :high bridge/numbers->pitches ) :triangular T!"
            ":triangular T@@"]}

   {:title "Any numbers, rescaled onto a scale"
    :text "Scale the draws to the scale steps you want and round them (`'( round ) map`); `degrees->pitches` spells whole steps in a key and octave. A key name with a hyphen goes in quotes."
    :lines ["0.0 :mean T! 1.0 :stdev T! 3 :spread T! 4 :centre T!"
            "'( :seed rng/seed '( :mean :stdev random/normal ) :draws pull :spread * :centre + '( round ) map \"C.pentatonic-major\" 4 bridge/degrees->pitches ) :normal T!"
            ":normal T@@"]}

   {:title "A random walk, and a biased one"
    :text "`walk/walk` moves by at most `step-bound`; `biased-walk` also leans one way (above 0.5 up). A function that keeps its place is `pull`ed."
    :lines ["16 :draws T! 4.0 :start T! 1.0 :step_bound T! 0.7 :bias T!"
            "'( :seed rng/seed :start :step_bound walk/walk :draws pull '( round ) map \"C.pentatonic-major\" 4 bridge/degrees->pitches ) :walking T!"
            ":walking T@@"
            "'( :seed rng/seed 60.0 :step_bound :bias walk/biased-walk :draws pull ) :leaning T!"
            ":leaning T@@"]}

   {:title "Chaos: the logistic map, the Lorenz attractor"
    :text "`logistic` gives numbers in (0, 1) from a growth rate and a start; `lorenz` a point per step, `points->pitches` reads one coordinate onto the steps of a key. `pull` also takes a generator that holds a `:value` function, as lorenz does."
    :lines ["3.9 :r T! 0.5 :x T! 12 :draws T!"
            "'( :r :x logistic/logistic :draws pull 9 * '( round ) map \"C.pentatonic-major\" 4 bridge/degrees->pitches ) :logistic T!"
            ":logistic T@@"
            "24 :points T!"
            "'( 10.0 28.0 2.667 1.0 1.0 1.0 0.03 lorenz/lorenz :points pull 0 -20.0 20.0 \"C.pentatonic-major\" 4 2 bridge/points->pitches ) :lorenz T!"
            ":lorenz T@@"]}

   {:title "A Markov chain over scale degrees"
    :text "`markov/chain` walks a table of transitions between states. The table is a map, which mu text cannot write yet, so this one is only shown."
    :lines ["see markov/chain"]}

   {:title "Poisson onsets become durations"
    :text "`poisson` gives event times from a rate and a duration; `onsets->durations` reads the gaps between them as note values, `unit` being the note value of one time unit and `quantum` the grid they are rounded to."
    :lines ["3.0 :rate T! 4.0 :duration T! 1/4 :unit T! 1/32 :quantum T!"
            "'( :seed rng/seed :rate :duration process/poisson :unit :quantum bridge/onsets->durations penta zip ) :poisson T!"
            ":poisson T@@"]}

   {:title "Smooth noise as a rhythm"
    :text "`walk/smooth-noise` makes a curve, a function of t, from `n` random points between `lo` and `hi`; `map` samples it at sixteen places, and `data-rhythm` puts an onset wherever a value is above the median."
    :lines ["6 :n T! 16 :places T!"
            "'( :seed rng/seed 0 :places range 3 / :n :lo :hi walk/smooth-noise map \"median\" 1 data/data-rhythm :pulse bridge/pulses->durations penta zip ) :noise T!"
            ":noise T@@"]}

   {:group "Rhythm generators"}

   {:title "A tala's theka, a named bell pattern"
    :text "Two lines of one kind, so two computed keys."
    :lines ["\"jhaptal\" :tala_name T! '[16 8] :meter T! \"bossanova\" :pattern_name T!"
            "'( :tala_name world/theka :pulse bridge/pulses->durations penta zip ) :theka T!"
            ":theka T@@"
            "'( :meter :pattern_name world/bell :pulse bridge/pulses->durations '[60 64 67] zip ) :bell T!"
            ":bell T@@"]}

   {:title "Fractal rhythms: Cantor set, dragon curve"
    :text "`cantor` takes `iterations` and `length`; `dragon` takes `iterations`."
    :lines ["2 :iterations T! 9 :length T!"
            "'( :iterations :length pattern/cantor :pulse bridge/pulses->durations penta zip ) :cantor T!"
            ":cantor T@@"
            "3 :dragon_iterations T!"
            "'( :dragon_iterations pattern/dragon :pulse bridge/pulses->durations penta zip ) :dragon T!"
            ":dragon T@@"]}

   {:title "A polyrhythm, one layer per voice"
    :text "`polyrhythm` takes the layers, each `[onsets grid]`, and a length, and gives a list of layers; `0 @` picks one: three against two. One setting, the layer, picks which."
    :lines ["'[ '[3 8] '[2 8] ] :layers T! 24 :length T! 0 :layer T!"
            "'( :layers :length meter/polyrhythm :layer @ :pulse bridge/pulses->durations '[48 55] zip ) :layer_line T!"
            ":layer_line T@@"
            "1 :layer T!"
            ":layer_line T@@"]}

   {:title "A polyrhythm as two parts"
    :text "Each layer its own line, each started by its own `|` mark so that `zip` joins only that part; `{2}` puts the two side by side."
    :lines ["'( | :layers :length meter/polyrhythm 0 @ :pulse bridge/pulses->durations '[48] zip | :layers :length meter/polyrhythm 1 @ :pulse bridge/pulses->durations '[55] zip {2} ) :two_parts T!"
            ":two_parts T@@"]}

   {:title "Clapping Music: the pattern against itself, shifted"
    :text "`duet` gives the pattern and the same pattern `phase` pulses on; `1 @` picks the shifted one."
    :lines ["'[1 1 1 0 1 1 0 1 0 1 1 0] :pattern T! 3 :phase T!"
            "'( :pattern :phase sieve/duet 1 @ :pulse bridge/pulses->durations '[72] zip ) :clapping T!"
            ":clapping T@@"
            "5 :phase T!"
            ":clapping T@@"]}

   {:title "A genetic rhythm: the fitness function is a quotation"
    :text "A quotation `'( … )` is a function of what it is given, so it can be the fitness function: this one scores a pattern by how close its onset count is to five. It is written inside the computed key, because a quotation given to `T!` would be taken for a computed setting."
    :lines ["16 :population_size T! 16 :pattern_length T! 20 :generations T!"
            "'( :seed rng/seed :population_size :pattern_length :generations '( sum 5 - abs negate ) process/genetic :pulse bridge/pulses->durations penta zip ) :genetic T!"
            ":genetic T@@"]}

   {:title "Swing: a grid becomes onset times, then durations"
    :text "`swing` turns pulses into swung onset times (a ratio and the steps per beat); `onsets->durations` reads them back, a beat being `unit`."
    :lines ["8 :onsets T! 8 :pulses T! 0.67 :swing_ratio T! 2 :subdivision T! 1/4 :unit T! 1/32 :quantum T!"
            "'( :onsets :pulses pattern/euclid :swing_ratio :subdivision feel/swing :unit :quantum bridge/onsets->durations penta zip ) :swing T!"
            ":swing T@@"]}

   {:title "A bouncing ball, in seconds"
    :text "Times in seconds: height, restitution, gravity and duration; `unit` says how long one second is as a note value."
    :lines ["1.0 :initial_height T! 0.8 :restitution T! 9.8 :gravity T! 5.0 :duration T! 1/4 :unit T! 1/64 :quantum T!"
            "'( :initial_height :restitution :gravity :duration nature/bounce :unit :quantum bridge/onsets->durations '[72 67] zip ) :bounce T!"
            ":bounce T@@"]}

   {:title "Tuplet-like durations by repeated splitting"
    :text "`decompose/split` takes a `ratio`-sized bite of what remains, `depth` times."
    :lines ["1 :duration T! 5 :depth T! 2/3 :ratio T!"
            "'( :duration :depth :ratio decompose/split penta zip ) :split T!"
            ":split T@@"]}

   {:title "Text as rhythm"
    :text "A beat on each word's first syllable; density 1.0 lets every accent sound."
    :lines ["\"Composing trees of algorithms is plain Clojure.\" :text T! 1.0 :density T!"
            "'( :text data/text-rhythm :density bridge/weights->pulses :pulse bridge/pulses->durations penta zip ) :text_rhythm T!"
            ":text_rhythm T@@"]}

   {:title "Vary a rhythm: EMI style, Oblique Strategies"
    :text "`emi` takes a pattern and a similarity (1 leaves it as it is); `oblique` a pattern and the name of a strategy."
    :lines ["5 :onsets T! 16 :pulses T! 0.6 :similarity T! \"mirror\" :strategy T!"
            "'( :seed rng/seed :onsets :pulses pattern/euclid :similarity feel/emi :pulse bridge/pulses->durations penta zip ) :emi T!"
            ":emi T@@"
            "'( 3 8 pattern/euclid :strategy feel/oblique :pulse bridge/pulses->durations penta zip ) :oblique T!"
            ":oblique T@@"]}

   {:title "Cross two rhythms"
    :text "`process/crossover` takes the first part of one pattern and the rest of the other, at a point; `child` 0 or 1 says which way round."
    :lines ["'[1 0 1 1 0 1 0 0] :a T! '[1 0 0 1 0 0 1 0] :b T! 4 :crossover_point T! 0 :child T!"
            "'( :a :b :crossover_point :child process/crossover :pulse bridge/pulses->durations penta zip ) :crossover T!"
            ":crossover T@@"
            "1 :child T!"
            ":crossover T@@"]}

   {:group "Algorithms of your own"}

   {:title "A pitch transform"
    :text "A word of your own is an algorithm: `: name ( inputs -- results ) … ;`. This one moves every pitch down an octave, and works on pitches before `zip`. It goes into a computed key like any word."
    :lines [": down ( l -- l' ) 12 - ;"
            "3 :onsets T! 8 :pulses T!"
            "'( :onsets :pulses pattern/euclid :pulse bridge/pulses->durations penta down zip ) :lower T!"
            ":lower T@@"]}

   {:title "Required arguments and their limits"
    :text "Every argument is required: there are no defaults, and a function says how many it needs and what it cannot take."
    :lines ["16 16 20 process/genetic"
            "99 8 pattern/euclid"]}

   {:group "Two of a kind"}

   {:title "Two instances of one algo"
    :text "A key has one value, so two instances of one algorithm need two sets of keys: name them for the part they play. A computed key gives one value, so `( … )` gathers the two into a list."
    :lines ["2 :bass_onsets T! 5 :lead_onsets T! 16 :pulses T!"
            "'( ( :bass_onsets :pulses pattern/euclid :lead_onsets :pulses pattern/euclid ) ) :two_euclids T!"
            ":two_euclids T@@"]}

   {:title "Different algos, one setting"
    :text "A setting is read wherever a word names it, so one key can feed two different algorithms, in whatever position each has its argument."
    :lines ["5 :onsets T! 8 :pulses T!"
            "'( :onsets :pulses pattern/euclid ) :euclid_row T!"
            ":euclid_row T@@"
            "'( :pulses :onsets 1 sieve/necklaces ) :necklace T!"
            ":necklace T@@"]}

   {:group "Parameters on the stack: a word with inputs"}

   {:title "One line, several parameters: a word with inputs"
    :text "The tctx is one way to name the parameters. The other is a word of your own with `$` inputs, which are local to it: the numbers go on the stack before the word, in the order of its stack effect, and the word needs no table."
    :lines [": riff ( $onsets $pulses -- notes ) $onsets $pulses pattern/euclid 1/16 bridge/pulses->durations penta zip ;"
            "2 8 riff"
            "7 8 riff"]}

   {:title "Another word over the same inputs"
    :text "Words that share inputs just share names. `see` shows a word's stack effect with its inputs."
    :lines ["see riff"
            ": lift ( $notes $by -- notes' ) $notes $by + ;"
            "2 8 riff 7 lift"]}

   {:title "Defaults are a word"
    :text "A default is a definition that fills in what you leave out; the arguments you give win."
    :lines [": groove ( $onsets -- pulses ) $onsets 16 pattern/euclid ;"
            "5 groove"
            "7 groove"]}

   {:title "Look inside: see and the stack"
    :text "`see` shows a word's stack effect and what it does; `.s` shows the stack."
    :lines ["see pattern/euclid"
            "see bridge/pulses->durations"
            "3 8 pattern/euclid .s"]}

   {:group "Live"}

   {:title "A voice that reads the settings as it plays"
    :text "A word with `$onset` among its inputs is played as a voice that is a function of the onset: the engine asks it for one note at the onset the voice has reached, and while it runs a keyword the tctx holds stands for its value, `:onset` being the onset itself. The settings are looked up note by note, so `3 :steps T!` is heard at the next note, with no input to hand over. This one climbs `:steps` semitones from middle C and begins again. Shown, not run: nothing sounds when this page is made."
    :lines ["8 :steps T!"
            ": tick ( $onset -- note ) | '[1/16] '[1] 60 :onset 16 * int :steps mod + * zip ;"
            [:play "'tick :v ->"]
            [:play "3 :steps T!"]
            [:play "stop"]]}

   {:title "A live word, changed while it plays"
    :text "A word with `$` inputs plays as a voice: the quoted name keeps it from running now, the voice runs it again whenever an input changes. `:bass/onsets` is an input, an entry of the voice's scope in the tctx, read with `T@` and stored with `T!`. Shown, not run: nothing sounds when this page is made."
    :lines [[:play "5 8 'riff :bass ->"]
            [:play ":bass settings"]
            [:play "3 :bass/onsets T!"]
            [:play ":bass/onsets T@"]
            [:play "stop"]]}

   {:title "A transform: reshape any voice's own notes"
    :text "A quotation under a play word runs on the music under it, note by note. Shown, not run."
    :lines [[:play "[c d e g] [~] '( 5 + ) :up ->"]
            [:play "'( 7 + ) :up retree"]]}

   {:group "Material"}

   {:title "Generated notes become committed musics text"
    :text "`R!` stores what is on the stack under a name; `R@` reads it back, and `write` or `save-session` keeps it."
    :lines ["3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip :demo R!"
            ":demo R@"]}])

;; ---------------------------------------------------------------------------
;; The page
;; ---------------------------------------------------------------------------

(defn- setup! [] (doseq [l setup] (run l)))

(defn- setup-md []
  (str "```\n" (str/join "\n" (for [l setup] (str "mu> " l "\n" (clip (run l) 200)))) "\n```\n"))

(defn- recipe-md [n {:keys [title text lines]}]
  (str "#### " n ". " title "\n\n" text "\n\n"
       "```\n"
       (str/join "\n"
                 (for [l lines
                       :let [[play? input] (if (vector? l) [true (second l)] [false l])]]
                   (if play?
                     (str "mu> " input)
                     (str "mu> " input "\n" (clip (run input) 260)))))
       "\n```\n"))

(defn- recipes-md []
  (loop [[r & more] recipes n 1 out []]
    (cond (nil? r)      (str/join "\n" out)
          (:group r)    (recur more n (conj out (str "### " (:group r) "\n")))
          :else         (recur more (inc n) (conj out (recipe-md n r))))))

(def ^:private meant-to-fail
  "Recipes whose lines show an error on purpose."
  #{"Change a parameter; a precondition guards it" "Wrong shapes" "Required arguments and their limits"})

(defn- errors-in-recipes
  "Recipes whose lines fail, other than the ones meant to."
  []
  (for [{:keys [title lines]} (remove :group recipes)
        l lines :when (string? l)
        :let [r (run l)] :when (and (str/starts-with? r "error:") (not (meant-to-fail title)))]
    [title l r]))

(def n-recipes (count (remove :group recipes)))

(defn -main []
  (seed/seed! 2026)
  (fresh!)
  (setup!)
  (doseq [[title l r] (errors-in-recipes)]
    (binding [*out* *err*] (println "RECIPE ERROR in" title ":" l "=>" r)))
  (fresh!)
  (let [setup-block (setup-md)
        body (recipes-md)
        md (str "# Algo cookbook\n\n"
                "The algorithms of `musics/algo/` as plain Clojure functions, called by name with their parameters on the stack: " n-recipes " recipes. "
                "Every recipe was run for real, the line typed at the `mu>` prompt and what came back. "
                "\n\n"
                "## The idea\n\n"
                "An algorithm of `musics/algo/` is an ordinary function with clear argument names. `\"musics.algo.rhythmic.pattern\" require` makes its public functions words, `pattern/euclid`, and a word takes its arguments from the stack in the order `see pattern/euclid` shows them: `3 8 pattern/euclid`. "
                "There is nothing in between, no settings object and no defaults: what a function needs, you put on the stack. "
                "A function that makes *raw material* (pulses, numbers, weights, onsets, points) gives a plain list; a **bridge** from `musics.algo.bridge` (`pulses->durations`, `numbers->pitches` …) turns it into a duration, a pitch or a volume, with the range or the grid you give it; `zip` puts the parts together into notes. "
                "A function that makes one number per call is called n times with `pull`: `'( 0.0 1.0 random/normal ) 12 pull`; one that keeps its place (a walk, the logistic map) is `pull`ed as it is.\n\n"
                "The recipes are written in two styles. The first ten put every number into the line, so that each word and its arguments can be seen at once. From recipe 11 the numbers are **settings** in the tctx, the session's table of named values (`3 :onsets T!`), and the line that makes the music is stored once as a computed key (`'( :onsets :pulses pattern/euclid … ) :name T!`) and run with `:name T@@`. That is the pattern to use for anything you will change, repeat or play live: a change is one `T!`, and a voice that reads the table hears it at its next note.\n\n"
                "Four things to keep in mind:\n\n"
                "- **Nothing is checked between words.** A bridge takes what is on the stack. Pair the types right, and give a bridge the range its input really has.\n"
                "- **`N rng/seed` repeats the random words that follow it**, so the recipes that draw numbers start with `7 rng/seed`.\n"
                "- **A parameter that is a map** (Markov transitions, L-system rules) cannot be written in mu text yet; those recipes are shown with `see`.\n"
                "- **Floating point values are shown with two decimals** (`0.5234` is shown `0.52`, `1.0` is `1.00`). The value keeps every digit; only what `.`, `.s` and the lines below print is rounded.\n\n"
                "## Setup\n\n"
                "Type these once. `reqalgo` loads a Clojure namespace of `musics.algo`, named without that start and quoted (`'rhythmic.pattern reqalgo` is `\"musics.algo.rhythmic.pattern\" require`; a second quoted name, `'rhythmic.pattern 'p reqalgo`, is its alias); `penta` is a word of our own, the scale the recipes play over.\n\n"
                setup-block "\n"
                "## Recipes (" n-recipes ")\n\n"
                body "\n")]
    (spit "doc/md/algo-cookbook.md" md)
    (println "wrote doc/md/algo-cookbook.md," n-recipes "recipes")))

(-main)
(shutdown-agents)
