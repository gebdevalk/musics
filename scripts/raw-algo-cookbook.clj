;; Generates doc/md/raw-algo-cookbook.md: the 53 recipes of the algo cookbook
;; (doc/md/algo-cookbook.md) written with the raw/ words and mu's own. Every
;; recipe's mu input is run here and the result shown is exactly what came back.
;;
;; Run from the repo root, then render it (html and pdf, both git-ignored):
;;   lein run -m clojure.main scripts/raw-algo-cookbook.clj
;;   scripts/docs.sh
;;
;; Nothing sounds: the play recipes are shown, not run.
(ns raw-algo-cookbook
  (:require [clojure.string :as str]
            [musics.algo.numeric.rng :as seed]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib]
            [musics.input.reader.builder :as builder]
            [musics.mu]
            [musics.mu.algos]
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
                   (:ok @r)    (str/join "  " (map p/show (:ok @r)))
                   :else       "(unfinished)")]
    (str/join "\n" (remove str/blank? [out res]))))

;; ---------------------------------------------------------------------------
;; The recipes: each is a title, what to say, and the lines typed at the prompt.
;; A line with :play is shown and not run.
;; ---------------------------------------------------------------------------

(def setup
  "Typed once before the recipes: the Clojure namespaces whose functions the recipes call,
   each word `namespace/function`, and a seed."
  ["\"musics.algo.numeric.rng\" require"
   "\"musics.algo.rhythmic.pattern\" require"
   "\"musics.algo.rhythmic.sieve\" require"
   "\"musics.algo.rhythmic.meter\" require"
   "\"musics.algo.rhythmic.indispensability\" require"
   "\"musics.algo.rhythmic.process\" require"
   "\"musics.algo.rhythmic.nature\" require"
   "\"musics.algo.rhythmic.data\" require"
   "\"musics.algo.rhythmic.world\" require"
   "\"musics.algo.rhythmic.feel\" require"
   "\"musics.algo.rhythmic.decompose\" require"
   "\"musics.algo.numeric.random\" require"
   "\"musics.algo.numeric.walk\" require"
   "\"musics.algo.numeric.markov\" require"
   "\"musics.algo.numeric.chaos.logistic\" require"
   "\"musics.algo.numeric.chaos.lorenz\" require"
   "\"musics.algo.melodic.melody\" require"
   "\"musics.algo.melodic.serial\" require"
   "\"musics.algo.counterpoint.jeppesen\" require"
   "\"musics.algo.bridge\" require"
   ": penta ( -- l ) '[60 62 64 67 69] ;"])

(def recipes
  [{:group "First steps"}

   {:title "A source, a bridge, a scale"
    :text "The bare form of cookbook recipe 1. `pattern/euclid` is the Clojure function itself: its arguments, `onsets` and `pulses`, come from the stack and `see pattern/euclid` shows them. It gives a row of pulses, 1 for an onset and 0 for a rest. `bridge/pulses->durations` lets each onset last until the next one on a grid of the `pulse` you give it; `zip` puts durations and pitches together. `penta` is a word of our own, the scale the recipes play over."
    :lines ["see pattern/euclid"
            "3 8 pattern/euclid"
            "3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip"]}

   {:title "Change a parameter; a precondition guards it"
    :text "Cookbook recipe 2. Write another number. A function that cannot work with its arguments says what it needs."
    :lines ["5 8 pattern/euclid 1/16 bridge/pulses->durations penta zip"
            "5 8 pattern/euclid 1/16 bridge/pulses->durations '[60 63 67 70] zip"
            "99 8 pattern/euclid"
            "0 0 pattern/euclid"]}

   {:title "Wrong shapes"
    :text "Cookbook recipe 3. A word takes what its stack effect says from the stack. Too few arguments are refused; arguments of the wrong kind are not always: a bridge on durations runs and gives nonsense."
    :lines ["5 pattern/euclid"
            "3 8 pattern/euclid zip"
            "3 8 pattern/euclid 1/16 bridge/pulses->durations 1/16 bridge/pulses->durations"]}

   {:title "Raw material needs its bridges"
    :text "Cookbook recipe 4. Pulses become durations with one bridge. Weights (`indisp` ranks) need two: `weights->pulses` keeps the strongest `density` of them, then `pulses->durations`."
    :lines ["3 8 pattern/euclid 1/16 bridge/pulses->durations penta zip"
            "'[2 2 3] indispensability/indisp 0.5 bridge/weights->pulses 1/16 bridge/pulses->durations penta zip"]}

   {:title "One run, several parameters"
    :text "Cookbook recipe 5. `dup` copies what is on the stack, so one run of a walk can be the pitches and the velocities: the higher the note, the louder. `pull` calls a function n times; `walk/walk` gives a function that keeps its place."
    :lines ["7 rng/seed"
            "60.0 3.0 walk/walk 8 pull dup 52 68 48 72 bridge/numbers->pitches swap 52 68 40 100 bridge/numbers->volumes"]}

   {:group "Rhythm × melody"}

   {:title "Euclidean rhythm, rotated, over a reshuffled pentatonic"
    :text "Cookbook recipe 6. `rotate` and `shuffle` are mu's own list words: they work on a row of pulses and on a list of pitches alike."
    :lines ["7 rng/seed"
            "5 16 pattern/euclid 2 rotate 1/16 bridge/pulses->durations penta shuffle zip"]}

   {:title "Legato by default, detached by articulation"
    :text "Cookbook recipe 7. `zip` takes a named part: `:articulation` before a list gives every note it."
    :lines ["5 8 pattern/euclid 1/16 bridge/pulses->durations penta :articulation '[:staccato] zip"]}

   {:title "A Fibonacci rhythm"
    :text "Cookbook recipe 8. Every generator of `musics/algo/` is a function you can call."
    :lines ["16 pattern/fibonacci 1/16 bridge/pulses->durations '[60 62 64 65 67 69 71] zip"]}

   {:title "A Xenakis sieve as the rhythm"
    :text "Cookbook recipe 9. The moduli and their residues are lists; a list inside a list is written `'[ '[0 1] '[2] ]`."
    :lines ["'[3 4] '[ '[0 1] '[2] ] 12 sieve/sieve 1/16 bridge/pulses->durations penta zip"]}

   {:title "A clave timeline carrying an arpeggio"
    :text "Cookbook recipe 10. `world/bell` gives a named West African timeline for a meter, `[pulses subdivision]`."
    :lines ["'[12 8] \"clave\" world/bell 1/16 bridge/pulses->durations '[60 64 67 72] zip"]}

   {:group "Isorhythm and durations"}

   {:title "Isorhythm: a 5-note color against a 3-value talea"
    :text "Cookbook recipe 11. Two loops of different lengths zipped line up after their least common multiple, here 15 notes."
    :lines ["'[1/4 1/8 1/8] [~] '[60 62 64 65 67] [~] zip 15 take"]}

   {:title "Stretch the durations, transpose the pitches"
    :text "Cookbook recipe 12. `*` stretches the durations of a line and `+` transposes it."
    :lines ["'[1/4 1/8 1/8] [~] '[60 62 64 65 67] [~] zip 15 take 1/2 * 7 +"]}

   {:group "Metre and indispensability"}

   {:title "Indispensability thins a 12/8 bar"
    :text "Cookbook recipe 13. `indisp` ranks the pulses of a meter given by its subdivisions, `tilt` turns ranks into probabilities (`adherence` how strongly rank decides), `weights->pulses` keeps the strongest `density` of them."
    :lines ["'[2 2 3] indispensability/indisp 0.8 indispensability/tilt 0.5 bridge/weights->pulses 1/16 bridge/pulses->durations '[67 64 60] zip"]}

   {:title "Swap one stage: strong beats silent"
    :text "Cookbook recipe 14. `power` in place of `tilt`, with a negative adherence: the weak pulses win."
    :lines ["'[2 2 3] indispensability/indisp -0.8 indispensability/power 0.5 bridge/weights->pulses 1/16 bridge/pulses->durations '[67 64 60] zip"]}

   {:title "One weighted choice"
    :text "Cookbook recipe 15. `random/weighted-choose` draws one of the values (here the nine pulses `0 9 range`) with the weights it is given."
    :lines ["7 rng/seed"
            "0 9 range '[3 3] indispensability/indisp 1.0 indispensability/tilt random/weighted-choose"]}

   {:group "Melody generators"}

   {:title "A Markov melody trained on a motif"
    :text "Cookbook recipe 16. `markov-train` learns the motif (its `order`), `markov-gen` walks the model for `length` notes."
    :lines ["7 rng/seed"
            "11 16 pattern/euclid 1/16 bridge/pulses->durations '[60 62 64 62 60 67 65 64] 1 melody/markov-train 11 melody/markov-gen zip"]}

   {:title "Slonimsky: insertions before, between, after"
    :text "Cookbook recipe 17. `serial/infra` puts a pattern before each principal tone; `serial/polations` does infra, inter and ultra at once (the last argument: ultra after the last tone too)."
    :lines ["'[60 64 67 72] '[59 62] serial/infra"
            "'[60 64 67 72] '[] '[65] '[62] false serial/polations"]}

   {:title "An L-system melody"
    :text "Cookbook recipe 18. `melody/lsys-melody` expands an axiom with rules and reads each letter as a note. The rules and the letters' pitches are maps, which mu text cannot write yet, so this one is only shown: `see` gives its parameters."
    :lines ["see melody/lsys-melody"]}

   {:title "A constrained walk"
    :text "Cookbook recipe 19. `melody/constrained` walks a scale under constraints. With none (an empty list) it is a free walk; the constraints themselves are built in Clojure."
    :lines ["7 rng/seed"
            "'[60 62 64 65 67 69 71 72] 12 '[] melody/constrained"]}

   {:title "A melody that modulates"
    :text "Cookbook recipe 20. Segments of `[key length]` pairs, a key being `'[:C :major]`; the result is pitch classes, and `60 +` lifts them to the middle of the keyboard."
    :lines ["7 rng/seed"
            "'[ '[ '[:C :major] 6] '[ '[:E :minor] 6] ] '[] melody/modulating 60 +"]}

   {:title "Two-voice counterpoint"
    :text "Cookbook recipe 21. `jeppesen/species` takes the cantus, the number of voices, the species of the added voice, which part holds the cantus, the mode (`:auto` finds both) and a seed for the search. It gives one line per voice."
    :lines ["'[55 57 59 60 62 64 65 67 69 71 72 74 76] 2 1 :auto :auto 1 jeppesen/species"]}

   {:group "Metric generators"}

   {:title "A number's bits, a fraction's expansion"
    :text "Cookbook recipe 22. 45 is 101101: onsets on bits 0, 2, 3 and 5. A function with a keyword tail takes `:key value` pairs written before it: `:length 8`. π is [3; 7, 15, 1, …]."
    :lines ["45 :length 8 pattern/bits 1/16 bridge/pulses->durations penta zip"
            "3.141592653589793 16 pattern/cfrac 1/16 bridge/pulses->durations penta zip"]}

   {:title "Modular arithmetic as a rhythm"
    :text "Cookbook recipe 23. An onset wherever `multiplier · i + offset` is a multiple of `modulus`: `modulus multiplier length offset`."
    :lines ["5 2 15 0 pattern/modular 1/16 bridge/pulses->durations penta zip"]}

   {:group "Random sources"}

   {:title "A sampler is a function: pull n draws"
    :text "Cookbook recipe 24. `random/triangular` gives one draw per call; `pull` calls a quotation n times and collects the results. The bridge puts the draws on pitches, the range they really have being its second and third arguments."
    :lines ["7 rng/seed"
            "'( 0.0 1.0 0.5 random/triangular ) 12 pull 0.0 1.0 60 73 bridge/numbers->pitches"]}

   {:title "Any numbers, rescaled onto a scale"
    :text "Cookbook recipe 25. Scale the draws to the scale steps you want and round them (`'( round ) map`); `degrees->pitches` spells whole steps in a key and octave. A key name with a hyphen goes in quotes."
    :lines ["7 rng/seed"
            "'( 0.0 1.0 random/normal ) 12 pull 3 * 4 + '( round ) map \"C.pentatonic-major\" 4 bridge/degrees->pitches"]}

   {:title "A random walk, and a biased one"
    :text "Cookbook recipe 26. `walk/walk` moves by at most `step-bound`; `biased-walk` also leans one way (above 0.5 up). A function that keeps its place is `pull`ed."
    :lines ["7 rng/seed"
            "4.0 1.0 walk/walk 16 pull '( round ) map \"C.pentatonic-major\" 4 bridge/degrees->pitches"
            "60.0 2.0 0.7 walk/biased-walk 16 pull"]}

   {:title "Chaos: the logistic map, the Lorenz attractor"
    :text "Cookbook recipe 27. `logistic` gives numbers in (0, 1) from a growth rate and a start; `lorenz` a point per step, `points->pitches` reads one coordinate onto the steps of a key. `pull` also takes a generator that holds a `:value` function, as lorenz does."
    :lines ["3.9 0.5 logistic/logistic 12 pull 9 * '( round ) map \"C.pentatonic-major\" 4 bridge/degrees->pitches"
            "10.0 28.0 2.667 1.0 1.0 1.0 0.03 lorenz/lorenz 24 pull 0 -20.0 20.0 \"C.pentatonic-major\" 4 2 bridge/points->pitches"]}

   {:title "A Markov chain over scale degrees"
    :text "Cookbook recipe 28. `markov/chain` walks a table of transitions between states. The table is a map, which mu text cannot write yet, so this one is only shown."
    :lines ["see markov/chain"]}

   {:title "Poisson onsets become durations"
    :text "Cookbook recipe 29. `poisson` gives event times from a rate and a duration; `onsets->durations` reads the gaps between them as note values, `unit` being the note value of one time unit and `quantum` the grid they are rounded to."
    :lines ["7 rng/seed"
            "3.0 4.0 process/poisson 1/4 1/32 bridge/onsets->durations penta zip"]}

   {:title "Noise as a rhythm"
    :text "Cookbook recipe 30. Pink noise, `pull`ed, then `data-rhythm`: an onset wherever a value is above the median."
    :lines ["7 rng/seed"
            "5 0.0 1.0 walk/pink 16 pull \"median\" 1 data/data-rhythm 1/16 bridge/pulses->durations penta zip"]}

   {:group "Rhythm generators"}

   {:title "A tala's theka, a named bell pattern"
    :text "Cookbook recipe 31."
    :lines ["\"jhaptal\" world/theka 1/16 bridge/pulses->durations penta zip"
            "'[16 8] \"bossanova\" world/bell 1/16 bridge/pulses->durations '[60 64 67] zip"]}

   {:title "Fractal rhythms: Cantor set, dragon curve"
    :text "Cookbook recipe 32."
    :lines ["2 9 pattern/cantor 1/16 bridge/pulses->durations penta zip"
            "3 pattern/dragon 1/16 bridge/pulses->durations penta zip"]}

   {:title "A polyrhythm, one layer per voice"
    :text "Cookbook recipe 33. `polyrhythm` takes the layers, each `[onsets grid]`, and a length, and gives a list of layers; `0 @` picks one: three against two."
    :lines ["'[ '[3 8] '[2 8] ] 24 meter/polyrhythm 0 @ 1/16 bridge/pulses->durations '[48 55] zip"
            "'[ '[3 8] '[2 8] ] 24 meter/polyrhythm 1 @ 1/16 bridge/pulses->durations '[48 55] zip"]}

   {:title "A polyrhythm as two parts"
    :text "Cookbook recipe 34. Each layer its own line, each started by its own `|` mark so that `zip` joins only that part; `{2}` puts the two side by side."
    :lines ["| '[ '[3 8] '[2 8] ] 24 meter/polyrhythm 0 @ 1/16 bridge/pulses->durations '[48] zip | '[ '[3 8] '[2 8] ] 24 meter/polyrhythm 1 @ 1/16 bridge/pulses->durations '[55] zip {2}"]}

   {:title "Clapping Music: the pattern against itself, shifted"
    :text "Cookbook recipe 35. `duet` gives the pattern and the same pattern `phase` pulses on; `1 @` picks the shifted one."
    :lines ["'[1 1 1 0 1 1 0 1 0 1 1 0] 3 sieve/duet 1 @ 1/16 bridge/pulses->durations '[72] zip"]}

   {:title "A genetic rhythm: the fitness function is a quotation"
    :text "Cookbook recipe 36. A quotation `'( … )` is a function of what it is given, so it can be the fitness function: this one scores a pattern by how close its onset count is to five."
    :lines ["7 rng/seed"
            "16 16 20 '( sum 5 - abs negate ) process/genetic 1/16 bridge/pulses->durations penta zip"]}

   {:title "Swing: a grid becomes onset times, then durations"
    :text "Cookbook recipe 37. `swing` turns pulses into swung onset times (a ratio and the steps per beat); `onsets->durations` reads them back, a beat being `unit`."
    :lines ["8 8 pattern/euclid 0.67 2 feel/swing 1/4 1/32 bridge/onsets->durations penta zip"]}

   {:title "A bouncing ball, in seconds"
    :text "Cookbook recipe 38. Times in seconds: height, restitution, gravity and duration; `unit` says how long one second is as a note value."
    :lines ["1.0 0.8 9.8 5.0 nature/bounce 1/4 1/64 bridge/onsets->durations '[72 67] zip"]}

   {:title "Tuplet-like durations by repeated splitting"
    :text "Cookbook recipe 39. `decompose/split` takes a `ratio`-sized bite of what remains, `depth` times."
    :lines ["1 5 2/3 decompose/split penta zip"]}

   {:title "Text as rhythm"
    :text "Cookbook recipe 40. A beat on each word's first syllable; density 1.0 lets every accent sound."
    :lines ["\"Composing trees of algorithms is plain Clojure.\" data/text-rhythm 1.0 bridge/weights->pulses 1/16 bridge/pulses->durations penta zip"]}

   {:title "Vary a rhythm: EMI style, Oblique Strategies"
    :text "Cookbook recipe 41. `emi` takes a pattern and a similarity (1 leaves it as it is); `oblique` a pattern and the name of a strategy."
    :lines ["7 rng/seed"
            "5 16 pattern/euclid 0.6 feel/emi 1/16 bridge/pulses->durations penta zip"
            "3 8 pattern/euclid \"mirror\" feel/oblique 1/16 bridge/pulses->durations penta zip"]}

   {:title "Cross two rhythms"
    :text "Cookbook recipe 42. `process/crossover` takes the first part of one pattern and the rest of the other, at a point; `child` 0 or 1 says which way round."
    :lines ["'[1 0 1 1 0 1 0 0] '[1 0 0 1 0 0 1 0] 4 0 process/crossover 1/16 bridge/pulses->durations penta zip"
            "'[1 0 1 1 0 1 0 0] '[1 0 0 1 0 0 1 0] 4 1 process/crossover 1/16 bridge/pulses->durations penta zip"]}

   {:group "Algorithms of your own"}

   {:title "A pitch transform"
    :text "Cookbook recipe 43. A word of your own is an algorithm: `: name ( inputs -- results ) … ;`. This one moves every pitch down an octave, and works on pitches before `zip`."
    :lines [": down ( l -- l' ) 12 - ;"
            "3 8 pattern/euclid 1/16 bridge/pulses->durations penta down zip"]}

   {:title "Required arguments and their limits"
    :text "Cookbook recipe 44. Every argument is required: there are no defaults, and a function says how many it needs and what it cannot take."
    :lines ["16 16 20 process/genetic"
            "99 8 pattern/euclid"]}

   {:group "Two of a kind"}

   {:title "Two instances of one algo"
    :text "Cookbook recipe 45. No naming is needed: each call takes its own arguments, so two euclids are two calls."
    :lines ["2 16 pattern/euclid 5 16 pattern/euclid"]}

   {:title "Different algos, same parameter name"
    :text "Cookbook recipe 46. Arguments are by position, so `onsets` in `euclid` and in `necklaces` cannot meet."
    :lines ["5 8 pattern/euclid"
            "8 5 1 sieve/necklaces"]}

   {:group "Parameters as data"}

   {:title "One line, several parameters: a word with inputs"
    :text "Cookbook recipe 47. The parameters of a word of your own are its `$` inputs."
    :lines [": riff ( $onsets $pulses -- notes ) $onsets $pulses pattern/euclid 1/16 bridge/pulses->durations penta zip ;"
            "2 8 riff"
            "7 8 riff"]}

   {:title "Another word over the same inputs"
    :text "Cookbook recipe 48. Words that share inputs just share names. `see` shows a word's stack effect with its inputs."
    :lines ["see riff"
            ": lift ( $notes $by -- notes' ) $notes $by + ;"
            "2 8 riff 7 lift"]}

   {:title "Defaults are a word"
    :text "Cookbook recipe 49. A default is a definition that fills in what you leave out; the arguments you give win."
    :lines [": groove ( $onsets -- pulses ) $onsets 16 pattern/euclid ;"
            "5 groove"
            "7 groove"]}

   {:title "Look inside: see and the stack"
    :text "Cookbook recipe 50. `see` shows a word's stack effect and what it does; `.s` shows the stack."
    :lines ["see pattern/euclid"
            "see bridge/pulses->durations"
            "3 8 pattern/euclid .s"]}

   {:group "Live"}

   {:title "A live word, changed while it plays"
    :text "Cookbook recipe 51. A word with `$` inputs plays as a voice: the quoted name keeps it from running now, the voice runs it again whenever an input changes. `:bass/onsets` is an input, read with `@` and stored with `!`. Shown, not run: nothing sounds when this page is made."
    :lines [[:play "5 8 'riff :bass ->"]
            [:play ":bass settings"]
            [:play "3 :bass/onsets !"]
            [:play ":bass/onsets @"]
            [:play "stop"]]}

   {:title "A transform: reshape any voice's own notes"
    :text "Cookbook recipe 52. A quotation under a play word runs on the music under it, note by note. Shown, not run."
    :lines [[:play "[c d e g] [~] '( 5 + ) :up ->"]
            [:play "'( 7 + ) :up retree"]]}

   {:group "Material"}

   {:title "Generated notes become committed musics text"
    :text "Cookbook recipe 53. `R!` stores what is on the stack under a name; `R@` reads it back, and `write` or `save-session` keeps it."
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
        md (str "# Raw algo cookbook\n\n"
                "The " n-recipes " recipes of the [algo cookbook](algo-cookbook.md), written with the algorithms as plain Clojure functions, called by name with their parameters on the stack. "
                "Every recipe was run for real, the line typed at the `mu>` prompt and what came back. "
                "The numbers are the same as in the algo cookbook, so recipe 13 here is recipe 13 there.\n\n"
                "## The idea\n\n"
                "An algorithm of `musics/algo/` is an ordinary function with clear argument names. `\"musics.algo.rhythmic.pattern\" require` makes its public functions words, `pattern/euclid`, and a word takes its arguments from the stack in the order `see pattern/euclid` shows them: `3 8 pattern/euclid`. "
                "There are no settings objects, no defaults and no registry in between: what a function needs, you put on the stack. "
                "A function that makes *raw material* (pulses, numbers, weights, onsets, points) gives a plain list; a **bridge** from `musics.algo.bridge` (`pulses->durations`, `numbers->pitches` …) turns it into a duration, a pitch or a volume, with the range or the grid you give it; `zip` puts the parts together into notes. "
                "A function that makes one number per call is called n times with `pull`: `'( 0.0 1.0 random/normal ) 12 pull`; one that keeps its place (a walk, the logistic map) is `pull`ed as it is.\n\n"
                "Three things to keep in mind:\n\n"
                "- **Nothing is checked between words.** A bridge takes what is on the stack. Pair the types right, and give a bridge the range its input really has.\n"
                "- **`N rng/seed` repeats the random words that follow it**, so the recipes that draw numbers start with `7 rng/seed`.\n"
                "- **A parameter that is a map** (Markov transitions, L-system rules) cannot be written in mu text yet; those recipes are shown with `see`.\n\n"
                "## Setup\n\n"
                "Type these once. Each `require` loads a Clojure namespace; `penta` is a word of our own, the scale the recipes play over.\n\n"
                setup-block "\n"
                "## Recipes (" n-recipes ")\n\n"
                body "\n")]
    (spit "doc/md/raw-algo-cookbook.md" md)
    (println "wrote doc/md/raw-algo-cookbook.md," n-recipes "recipes")))

(-main)
(shutdown-agents)
