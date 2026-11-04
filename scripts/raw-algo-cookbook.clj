;; Generates doc/md/raw-algo-cookbook.md: every recipe's mu input is run here
;; and the result shown is exactly what came back. The reference table is read
;; from the registry.
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

(def recipes
  [{:group "First steps"}

   {:title "A raw source"
    :text "A raw word gives raw material, not music: `raw/euclid` is a row of pulses, 1 for an onset and 0 for a rest. Settings go in an `@^{ }` just before the word, `name:value` each; the defaults are `onsets:3 pulses:8`."
    :lines ["raw/euclid"
            "@^{ onsets:5 pulses:16 } raw/euclid"]}

   {:title "Pulses become durations"
    :text "A bridge turns raw material into a half product. `raw/pulses->durations` lets each onset last until the next one, on a grid of `pulse` (1/16 of a whole note by default). It takes the pulses from the stack, so the source comes first, and its settings sit before it."
    :lines ["raw/euclid raw/pulses->durations"
            "@^{ onsets:5 pulses:8 } raw/euclid @^{ pulse:1/8 } raw/pulses->durations"]}

   {:title "Durations and pitches make notes"
    :text "`zip` joins a note's parts. A stream a raw word gave knows what it is, so durations and pitches meet in the right place. The pitch list is data (`'[ ]`); it cycles over the notes."
    :lines ["raw/euclid raw/pulses->durations '[60 62 64 67] zip"
            "@^{ onsets:5 pulses:8 } raw/euclid raw/pulses->durations '[60 62 64 67] zip"]}

   {:title "mu's own words work on raw material"
    :text "Raw material is a plain list, so the words that act on lists apply: `rotate` moves the front round to the back, `retrograde` reverses, `take` keeps the first n."
    :lines ["raw/euclid 2 rotate"
            "raw/euclid retrograde"
            "raw/fibonacci 8 take"]}

   {:group "Rhythm material"}

   {:title "Grids from number theory"
    :text "Each of these is a row of pulses from a formula: Fibonacci numbers, primes, the Cantor set, the golden ratio, the binary digits of a number, a modular formula, an L-system. Rows longer than 16 are shown cut."
    :lines ["raw/fibonacci"
            "raw/primes"
            "raw/cantor"
            "raw/golden"
            "@^{ number:13 length:8 } raw/bits"
            "raw/modular"
            "@^{ length:8 iterations:3 } raw/lsys-rhythm"]}

   {:title "A cellular automaton and a sieve"
    :text "Two more ways to a row of pulses: an elementary cellular automaton, and a Xenakis sieve (an onset wherever a position is in a residue class of a modulus)."
    :lines ["raw/ca"
            "raw/sieve"]}

   {:title "Weights: what to keep"
    :text "`raw/indisp` ranks the pulses of a meter by Barlow's indispensability, the downbeat highest. Ranks are weights, and weights go several ways: `weights->pulses` keeps the strongest fraction, `weights->volumes` turns them into velocities."
    :lines ["raw/indisp"
            "raw/indisp @^{ density:0.5 } raw/weights->pulses"
            "raw/indisp @^{ lo:50 hi:100 } raw/weights->volumes"]}

   {:title "Rotations and reflections"
    :text "`bracelet` picks the index-th pattern among a pattern's rotations and reversals; `phases` gives every rotation as a layer; `necklaces` picks a necklace class of n pulses and k onsets."
    :lines ["@^{ onsets:3 pulses:8 } raw/euclid @^{ index:3 } raw/bracelet"
            "raw/euclid raw/phases"
            "@^{ pulses:8 onsets:3 index:1 } raw/necklaces"]}

   {:title "Layers"
    :text "A layer is several rows at once. `polyrhythm` and `hemiola` give them; `layer` picks one by `index`; `duet` gives a pattern and the same pattern shifted."
    :lines ["raw/hemiola"
            "raw/polyrhythm @^{ index:1 } raw/layer"
            "raw/euclid raw/duet"]}

   {:title "Onsets in time"
    :text "Some sources give times, not a grid: a Poisson process, a Xenakis cloud. `onsets->durations` reads the gaps between them as note values (`unit` is the note value of one time unit)."
    :lines ["@^{ seed:7 rate:6 duration:4 } raw/poisson"
            "@^{ seed:7 rate:6 duration:4 } raw/poisson @^{ unit:1/8 } raw/onsets->durations"
            "@^{ seed:7 num-events:6 duration:2 } raw/cloud"]}

   {:title "Varying a rhythm"
    :text "Algos that take a pattern and give one back: `emi` mutates it in Cope's style (`similarity` 1 leaves it as it is), `oblique` applies one of Eno's strategies. Each word has its own `seed`."
    :lines ["@^{ seed:7 } raw/euclid @^{ seed:9 similarity:0.5 } raw/emi"
            "@^{ seed:7 } raw/euclid @^{ seed:9 } raw/oblique"]}

   {:title "Syllables and strokes"
    :text "`konnakol` gives each onset of a rhythm a syllable and each rest a dash; `strokes->durations` reads that as note values."
    :lines ["raw/euclid raw/konnakol"
            "raw/euclid raw/konnakol raw/strokes->durations"]}

   {:group "Number material"}

   {:title "Random draws"
    :text "Samplers draw `len` numbers from a distribution. Give each a `seed` to repeat it. The ranged ones take `lo` and `hi`; `triangular` peaks at `mode`."
    :lines ["@^{ seed:7 len:6 } raw/normal"
            "@^{ seed:7 len:6 lo:48 hi:72 mode:60 } raw/triangular"
            "@^{ seed:7 len:6 } raw/uniform"]}

   {:title "Walks and noise"
    :text "A random walk moves by at most `step-bound` each step from `start`; pink noise wanders between white noise and a walk, inside [0, 1) by default."
    :lines ["@^{ seed:7 len:8 start:60 step-bound:3 } raw/walk"
            "@^{ seed:7 len:8 } raw/pink"]}

   {:title "Chaos"
    :text "The logistic map gives numbers in (0, 1); at `r` between 3.57 and 4 it is chaotic. The Lorenz system gives a point per step, [x y z]; `axis` picks one coordinate. (The first steps barely move: `dt` is the step.)"
    :lines ["@^{ r:3.9 len:8 } raw/logistic"
            "@^{ len:4 } raw/lorenz"
            "@^{ len:6 dt:0.1 } raw/lorenz @^{ axis:0 } raw/axis"]}

   {:title "Numbers become music: a bridge needs its range"
    :text "`numbers->pitches` maps a number's place in `from-lo..from-hi` onto `lo..hi`. Its input range defaults to 0..1, which a walk around 60 is far outside: every number lands on the top pitch. Give the range the numbers really have."
    :lines ["@^{ seed:7 len:8 start:60 step-bound:3 } raw/walk raw/numbers->pitches"
            "@^{ seed:7 len:8 start:60 step-bound:3 } raw/walk @^{ from-lo:52 from-hi:68 lo:48 hi:72 } raw/numbers->pitches"]}

   {:title "One source, several parameters"
    :text "`dup` copies what is on the stack, so one run of a walk can be the pitches and the velocities: the higher the note, the louder."
    :lines ["@^{ seed:7 len:8 start:60 step-bound:3 } raw/walk dup @^{ from-lo:52 from-hi:68 lo:48 hi:72 } raw/numbers->pitches swap @^{ from-lo:52 from-hi:68 lo:40 hi:100 } raw/numbers->volumes"]}

   {:title "Scale steps"
    :text "`degrees->pitches` reads whole numbers as steps of a key from its tonic. `points->pitches` maps a point's coordinate onto the steps of a key over `octaves`."
    :lines ["'[0 1 2 3 4 5 6 7] @^{ key:D.minor } raw/degrees->pitches"
            "@^{ seed:7 len:12 dt:0.1 } raw/lorenz @^{ axis:0 from-lo:-20 from-hi:20 key:A.minor octaves:2 } raw/points->pitches"]}

   {:title "Instruments"
    :text "`numbers->instruments` picks from a `palette` of MIDI programs or General MIDI names; an empty palette is all 128."
    :lines ["@^{ seed:5 len:8 } raw/uniform @^{ from-lo:0 from-hi:1 palette:(violin flute cello) } raw/numbers->instruments"]}

   {:title "A twelve-tone row"
    :text "`row` is a random permutation of the twelve pitch classes; `row-form` gives a form of it (P, I, R, RI); `classes->pitches` puts classes on pitches."
    :lines ["@^{ seed:3 } raw/row"
            "@^{ seed:3 } raw/row @^{ form:R } raw/row-form"
            "@^{ seed:3 } raw/row raw/classes->pitches"]}

   {:title "A Markov model"
    :text "`markov-train` learns what follows each run of `order` notes in a melody; `markov-gen` walks that model for `length` notes. `chain` is a Markov chain over numeric states, with its own transition table."
    :lines ["'[60 62 64 62 60 64] raw/markov-train @^{ seed:3 } raw/markov-gen"
            "@^{ seed:3 } raw/chain"]}

   {:group "Putting it together"}

   {:title "A rhythm, pitches and accents"
    :text "Above a `|` mark `zip` takes every stream with its part: durations, pitches and volumes (written on each note as `\\volume:`). `[~]` loops a stream, so the shorter ones keep cycling."
    :lines ["| @^{ onsets:5 pulses:8 } raw/euclid raw/pulses->durations '[60 62 64 67] [~] raw/indisp @^{ lo:50 hi:100 } raw/weights->volumes [~] zip"]}

   {:title "A walk plays the pitches"
    :text "The rhythm is a looped Euclid, the pitches a random walk laid on the notes."
    :lines ["@^{ onsets:5 pulses:8 } raw/euclid raw/pulses->durations [~] @^{ seed:7 len:16 start:60 step-bound:2 } raw/walk @^{ from-lo:54 from-hi:66 lo:55 hi:79 } raw/numbers->pitches zip 8 take"]}

   {:title "Playing"
    :text "A recipe that ends in music plays with `->`, a loop of it with `[~]` before. These are shown, not run here: nothing sounds when this page is made."
    :lines [[:play "@^{ onsets:5 pulses:8 } raw/euclid raw/pulses->durations '[60 62 64 67] [~] zip [~] ->"]
            [:play "stop"]]}

   {:group "The functions themselves"}

   {:title "Calling the Clojure function"
    :text "`require` loads a Clojure namespace and makes its public functions words. This is the function itself: no settings, no defaults, its arguments from the stack. A keyword tail (`& {:keys [length]}`) is `:key value` pairs written before the word, or nothing."
    :lines ["\"musics.algo.rhythmic.pattern\" 'r require"
            "3 8 r/euclid"
            "13 :length 8 r/bits"]}

   {:group "When it goes wrong"}

   {:title "A bridge is not checked"
    :text "A stream carries what it is, but a word does not check it. A wrong pairing fails inside the algo, or gives a bundle that cannot sound. `lorenz` gives points, so `numbers->pitches` is the wrong bridge (`points->pitches` is right):"
    :lines ["@^{ len:4 } raw/lorenz raw/numbers->pitches"
            "raw/euclid raw/pulses->durations @^{ len:3 } raw/lorenz zip"]}])

;; ---------------------------------------------------------------------------
;; The reference table: every raw word, by what it gives
;; ---------------------------------------------------------------------------

(defn- short-value [v]
  (cond (and (double? v) (Double/isNaN v)) "required"
        (and (double? v) (Double/isInfinite v)) (if (pos? v) "∞" "-∞")
        (nil? v) "nil"
        (fn? v) "fn"
        (string? v) (pr-str v)
        :else (clip (pr-str v) 24)))

(defn- raw-entries []
  (for [[short e] (t/algos) :when (and (not (:via e)) (= "workshop" (:shelf e)))] [short (t/algo short)]))

(defn- table-row [[short e]]
  (let [types #(if (seq %) (str/join " " (map name %)) "—")
        doc   (first (str/split (str/replace (str (:doc e)) #"\s+" " ") #"(?<=\.)\s"))]
    (str "| `raw/" (name short) "` | " (types (:in e)) " | " (name (:out e)) " | "
         (str/join ", " (for [p (:params e) :when (not= :extra (:kind p))]
                          (str "`" (name (:name p)) "` " (short-value (:default p)))))
         " | " (str/replace (let [d (clip doc 100)] (if (re-find #"[.…]$" d) d (str d " …"))) "|" "\\|") " |")))

(defn- reference []
  (let [by-out (group-by (comp :out second) (raw-entries))]
    (str/join "\n\n"
              (for [ty [:pulse :weight :number :point :onset :layer :stroke :model :index
                        :pitch :duration :volume :articulation :instrument]
                    :let [es (sort-by (comp name first) (get by-out ty))]
                    :when (seq es)]
                (str "### Gives " (name ty) " (" (count es) ")\n\n"
                     "| Word | Takes | Gives | Settings (default) | Does |\n|---|---|---|---|---|\n"
                     (str/join "\n" (map table-row es)))))))

;; ---------------------------------------------------------------------------
;; The page
;; ---------------------------------------------------------------------------

(defn- recipe-md [n {:keys [title text lines]}]
  (str "#### " n ". " title "\n\n" text "\n\n"
       "```\n"
       (str/join "\n"
                 (for [l lines
                       :let [[play? input] (if (vector? l) [true (second l)] [false l])]]
                   (if play?
                     (str "mu> " input)
                     (str "mu> " input "\n" (clip (run input) 200)))))
       "\n```\n"))

(defn- recipes-md []
  (loop [[r & more] recipes n 1 out []]
    (cond (nil? r)      (str/join "\n" out)
          (:group r)    (recur more n (conj out (str "### " (:group r) "\n")))
          :else         (recur more (inc n) (conj out (recipe-md n r))))))

(defn- errors-in-recipes
  "Recipes whose lines fail, other than the ones meant to."
  []
  (for [{:keys [title lines]} (remove :group recipes)
        l lines :when (string? l)
        :let [r (run l)] :when (and (str/starts-with? r "error:") (not= title "A bridge is not checked"))]
    [title l r]))

(def n-recipes (count (remove :group recipes)))

(defn -main []
  (seed/seed! 2026)
  (fresh!)
  (doseq [[title l r] (errors-in-recipes)]
    (binding [*out* *err*] (println "RECIPE ERROR in" title ":" l "=>" r)))
  (fresh!)
  (let [body (recipes-md)
        n-words (count (raw-entries))
        md (str "# Raw algo cookbook\n\n"
                "The `raw/` words of mu, composed with mu's own words: " n-words " algorithms from the source files, one word each, no half-product wrapping. "
                "Every recipe below was run for real, the line typed at the `mu>` prompt and what came back; each random word has its own `seed`, so the results repeat.\n\n"
                "## The idea\n\n"
                "An algorithm of `musics/algo/` that makes or changes *raw material* (pulses, numbers, weights, onsets, points, layers, strokes, models) is a word `raw/<name>`. "
                "It takes what its stack effect says from the stack, its settings from an `@^{ name:value … }` written just before it, and leaves one value. "
                "The value is a plain list that knows what it is (a `pulse` row, a `number` stream …), so mu's list words work on it and `zip` can tell a rhythm from a melody. "
                "A **bridge** (`pulses->durations`, `numbers->pitches` …) turns raw material into a half product, a duration, a pitch, a volume; the finished words `duration/euclid` and `pitch/lorenz` are a source and a bridge welded together, with the bridge's range read from the source. "
                "Here you do the welding yourself, one word at a time, and choose which bridge.\n\n"
                "Three things to keep in mind:\n\n"
                "- **Settings go before their word.** `@^{ onsets:5 pulses:8 } raw/euclid @^{ pulse:1/8 } raw/pulses->durations`: each word reads only its own settings.\n"
                "- **Nothing is checked between words.** A bridge takes what is on the stack. Pair the types right (the table at the end says what each takes and gives), and give a bridge the range its input really has.\n"
                "- **`seed:N` repeats a word.** A word with no seed draws from the shared generator.\n\n"
                "## Recipes (" n-recipes ")\n\n"
                body "\n"
                "## Every raw word\n\n"
                "Generated from the registry, so it matches the code. *Takes* are the stack inputs, *Settings* the `@^{ }` names with their defaults.\n\n"
                (reference) "\n\n"
                "## Limits worth knowing\n\n"
                "- **Bridges default to a 0..1 input range** (`from-lo`, `from-hi`). The finished words (`pitch/lorenz`) read the range from their source; here you set it.\n"
                "- **Types are not checked** between words (see the last recipe); a wrong pairing fails or gives a bundle.\n"
                "- **Some algos need a value mu text cannot write**: `genetic` needs a `fitness-fn`, `constrained` and `filter` need functions. Use them from Clojure.\n"
                "- **Long rows are cut** with `…` when printed; the value on the stack is whole.\n")]
    (spit "doc/md/raw-algo-cookbook.md" md)
    (println "wrote doc/md/raw-algo-cookbook.md," n-recipes "recipes," n-words "raw words")))

(-main)
(shutdown-agents)
