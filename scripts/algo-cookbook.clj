;; Generates doc/algo-cookbook.html: every recipe's code is evaluated
;; here, and the result shown is exactly what came back. The reference
;; tables (the lib, what feeds what) are read from the registry.
;;
;; Run from the repo root, then print the PDF (git-ignored):
;;   lein run -m clojure.main scripts/algo-cookbook.clj
;;   google-chrome --headless=new --no-pdf-header-footer ;;     --print-to-pdf=doc/algo-cookbook.pdf doc/algo-cookbook.html
;;
;; A stand-in MIDI receiver keeps musics.core/play from connecting to
;; real MIDI; nothing is sent anywhere.
(ns cookbook-gen
  (:require [clojure.string :as str]
            [clojure.walk]
            [algo.tree :as t]
            [algo.tree.registry :as reg]
            [algo.tree.lib :as lib]
            [algo.random.core :as seed]
            [core.engine :as engine]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.repo :as repo]
            [core.wall :as wall]
            [musics.core :as m]))

(m/reset)
(repo/commit-node! :ROOT (assoc (repo/current :ROOT) :context (c/context-root {"Tempo" 6000 "volume" 80})))
(alter-var-root #'engine/*engine* (constantly (engine/engine nil (repo/registry) :ROOT)))
;; a stand-in receiver, so musics.core/play never connects to real MIDI;
;; the engine above has no receiver at all, so nothing is sent anywhere
(reset! m/receiver ::no-midi)

(create-ns 'cookbook)
(binding [*ns* (the-ns 'cookbook)]
  (eval '(do (clojure.core/refer-clojure)
             (require '[algo.tree :as t :refer [defalgo]]
                      '[algo.tree.lib :refer :all]
                      '[musics.core :as m]
                      '[core.wall :as wall]
                      '[algo.rhythmic.rhythm :as rhythm]
                      '[algo.rhythmic.phase-sieve :as sieve]
                      '[algo.rhythmic.world :as world]
                      '[algo.melodic.melody :as melody]
                      '[algo.melodic.slonimsky :as slon]))))

(defn esc [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn notes? [v] (and (sequential? v) (seq v) (every? #(and (map? %) (#{:LEAF :REST} (:type %))) v)))

(defn show [v]
  (cond (string? v) v
        (notes? v)  (lib/notes->mus v)
        :else       (let [s (binding [*print-length* 40] (pr-str v))] (if (> (count s) 400) (str (subs s 0 400) " …") s))))

(defn ev [code]
  (let [out (java.io.StringWriter.)
        v (binding [*ns* (the-ns 'cookbook) *out* out]
            (try (load-string code) (catch Throwable e (str "error: " (.getMessage (or (.getCause e) e))))))]
    [v (str out)]))

(def recipes
  [{:group "First steps"}
   {:title "A tree, and a tctx for its settings"
    :tree [["(def riff (notes (gate euclid (cycled scale))))" "an immutable value: what is computed"]]
    :params [["(def tctx (t/tctx riff))" "an atom of settings, every param at its default"]]
    :runs [["result" "riff" "tctx"]]
    :extra [["riff prints as" "riff"]]}
   {:title "Change a setting; the validator guards every change"
    :tree [["riff" "from recipe 1"]]
    :params [["(t/setp! tctx :k 5)" "five onsets"]
             ["(t/setp! tctx :intervals [0 3 7 10])" "a minor-seventh arpeggio"]]
    :runs [["result" "riff" "tctx"]]
    :extra [["out of range" "(try (t/setp! tctx :k 99) (catch Exception e (ex-message e)))"]
            ["wrong type" "(try (swap! tctx assoc-in [:params :k] 2.5) (catch Exception e (ex-message e)))"]]}
   {:title "Wrong shapes fail when built"
    :throws? true
    :tree [["(gate (tilt indisp) scale)" "tilt gives weights, gate wants a 0/1 grid"]
           ["(euclid scale)" "euclid is a source: no children"]]
    :no-params "none: these trees fail before any settings exist"
    :extra [["error" "(try (gate (tilt indisp) scale) (catch Exception e (ex-message e)))"]
            ["error" "(try (euclid scale) (catch Exception e (ex-message e)))"]]}

   {:group "Rhythm × melody"}
   {:title "Euclidean rhythm, rotated, over a reshuffled pentatonic"
    :tree [["(def r2 (notes (gate euclid (shuffled scale))))" "every pitch once per pass"]]
    :params [["(def p {:k 5 :n 16 :rotation 2 :dur 1/16})" "a plain map: missing keys take their defaults"]]
    :runs [["result" "r2" "p"]]}
   {:title "Every algo/ generator is an algo: a Fibonacci rhythm"
    :tree [["(def r3 (notes (gate fibonacci (cycled scale))))" "algo.rhythmic.rhythm/fibonacci-rhythm, by its short name"]]
    :params [["(def p {:length 16 :intervals [0 2 4 5 7 9 11] :dur 1/16})" ""]]
    :runs [["result" "r3" "p"]]
    :extra [["full name" "(t/full-name :fibonacci)"]]}
   {:title "A Xenakis sieve as the gate"
    :tree [["(def r4 (notes (gate sieve (cycled scale))))" ""]]
    :params [["(def p {:moduli [3 4] :residues [[0 1] [2]] :dur 1/16})" "vector params: moduli and their residues"]]
    :runs [["result" "r4" "p"]]}
   {:title "A clave timeline carrying an arpeggio"
    :tree [["(def r5 (notes (gate (world/named-bell-patterns \"clave\") (cycled [60 64 67 72]))))" "literal children: their own data"]]
    :params [["(def p {:dur 1/16})" ""]]
    :runs [["result" "r5" "p"]]}

   {:group "Isorhythm & durations"}
   {:title "Isorhythm: a 5-note color against a 3-value talea"
    :tree [["(def iso (pair-notes (color-talea [60 62 64 65 67] [1/4 1/8 1/8])))" "period lcm(5,3) = 15"]]
    :params [["(def p {})" "all defaults"]]
    :runs [["result" "iso" "p"]]}
   {:title "Stretch the durations, transpose the pitches"
    :tree [["(def iso2 (transpose (stretch iso)))" "both take notes as well as plain values"]]
    :params [["(def p {:factor 1/2 :semitones 7})" ""]]
    :runs [["result" "iso2" "p"]]}

   {:group "Metre & indispensability"}
   {:title "Indispensability thins a 12/8 bar"
    :tree [["(def grid (density (tilt indisp)))" "ranks → probabilities → the strongest half"]
           ["(def bar (notes (gate grid (cycled [67 64 60]))))" "an arpeggio on the kept pulses"]]
    :params [["(def p {:adherence 0.8 :density 0.5})" ""]]
    :runs [["result" "bar" "p"]]}
   {:title "Swap one stage: strong beats silent"
    :tree [["(def bar2 (notes (gate (density (power indisp)) (cycled [67 64 60]))))" "power reads the same :adherence as tilt"]]
    :params [["(def p {:adherence -0.8 :density 0.5})" "negative: the weak pulses win"]]
    :runs [["result" "bar2" "p"]]}
   {:title "One weighted choice"
    :tree [["(def one (pick (tilt indisp)))" "an index, drawn with the shaped weights"]]
    :params [["(def p {:subdivisions [3 3] :adherence 1.0})" ""]]
    :runs [["result" "one" "p"]]}

   {:group "Melody generators"}
   {:title "A Markov melody trained on a motif"
    :tree [["(def mk (notes (gate euclid (markov-gen (markov-train [60 62 64 62 60 67 65 64])))))" "train → a :model, generate → pitches"]]
    :params [["(def p {:order 1 :k 11 :n 16 :dur 1/16})" ""]]
    :runs [["result" "mk" "p"]]}
   {:title "Slonimsky: insertions before, between, after"
    :tree [["(def sl (notes (infra [60 64 67 72] [59 62])))" "two children: principal tones, insertion"]
           ["(def pol (notes (polations [60 64 67 72])))" "all three layers at once, as params"]]
    :params [["(def p1 {:dur 1/16})" ""]
             ["(def p2 {:inter [65] :ultra [62] :dur 1/16})" ""]]
    :runs [["infra" "sl" "p1"] ["polations" "pol" "p2"]]}
   {:title "An L-system melody, its rules in the tctx"
    :tree [["(def ls (notes lsys-melody))" "axiom, rules, note-map: all params"]]
    :params [["(def ls-tctx (t/tctx ls {:dur 1/16}))" ""]
             ["(t/setp! ls-tctx {:rules {\"A\" \"ABA\" \"B\" \"CB\" \"C\" \"A\"} :note-map {\\A 67 \\B 64 \\C 60}})" "a map param: checked to be a map"]]
    :runs [["result" "ls" "ls-tctx"]]}
   {:title "A constrained walk: constraints are a param"
    :tree [["(def sc [60 62 64 65 67 69 71 72])" ""]
           ["(def cw (notes (constrained sc)))" ""]]
    :params [["(def cw-tctx (t/tctx cw {:dur 1/16 :length 12}))" "default constraint: no repeated note"]
             ["(t/setp! cw-tctx :constraints [(melody/max-leap-constraint sc 1) melody/no-repeat-constraint])" "steps only"]]
    :runs [["result" "cw" "cw-tctx"]]}
   {:title "A melody that modulates"
    :tree [["(def mo (notes (transpose modulating)))" "pitch classes 0–11, lifted 60 semitones"]]
    :params [["(def p {:segments [[[:C :major] 6] [[:E :minor] 6]] :semitones 60 :dur 1/16})" ""]]
    :runs [["result" "mo" "p"]]}
   {:title "Two-voice counterpoint, one layer at a time"
    :tree [["(def cp (counterpoint [55 57 59 60 62 64 65 67 69 71 72 74 76]))" "two :part of [pitch quarters] pairs"]
           ["(def voice (stretch (pair-notes (layer cp))))" "durations in quarters → note values: ×1/4"]]
    :params [["(def p1 {:index 0 :factor 1/4})" ""]
             ["(def p2 {:index 1 :factor 1/4})" ""]]
    :runs [["voice 1" "voice" "p1"] ["voice 2" "voice" "p2"]]}

   {:group "Metric generators"}
   {:title "A number's bits, a fraction's expansion"
    :tree [["(def bt (notes (gate bits (cycled scale))))" "45 = 101101: onsets on bits 0, 2, 3, 5"]
           ["(def cf (notes (gate cfrac (cycled scale))))" "π = [3; 7, 15, 1, …]"]]
    :params [["(def p1 {:number 45 :length 8 :dur 1/16})" ""]
             ["(def p2 {:dur 1/16})" ""]]
    :runs [["bits" "bt" "p1"] ["cfrac" "cf" "p2"]]}
   {:title "Modular arithmetic as a grid"
    :tree [["(def md (notes (gate modular (cycled scale))))" "onset where multiplier·i + offset ≡ 0 (mod modulus)"]]
    :params [["(def p {:modulus 5 :multiplier 2 :length 15 :dur 1/16})" ""]]
    :runs [["result" "md" "p"]]}

   {:group "Random sources"}
   {:title "A sampler is a sequence: :len draws"
    :tree [["(def tri (notes int-triangular))" "one value per call, called :len times"]]
    :params [["(def p {:lo 60 :hi 73 :mode 67 :len 12 :dur 1/16})" ""]]
    :runs [["result" "tri" "p"]]}
   {:title "Any numbers, rescaled onto a scale"
    :tree [["(def nd (notes (degrees normal scale)))" "lowest draw → first degree, highest → last"]]
    :params [["(def p {:len 12 :octaves 2 :dur 1/16})" ""]]
    :runs [["result" "nd" "p"]]}
   {:title "A random walk, and a glide toward a target"
    :tree [["(def wk (notes (degrees walk scale)))" "a closure, pulled :len times"]
           ["(def gl (notes (degrees glide scale)))" ":target is passed on every pull"]]
    :params [["(def p1 {:len 16 :step-bound 3.0 :octaves 2 :dur 1/16})" ""]
             ["(def p2 {:len 16 :initial 0.0 :target 10.0 :inertia 0.7 :octaves 2 :dur 1/16})" ""]]
    :runs [["walk" "wk" "p1"] ["glide" "gl" "p2"]]}
   {:title "Chaos: the logistic map, the Lorenz attractor"
    :tree [["(def lg (notes (degrees logistic scale)))" ""]
           ["(def lz (notes (degrees (axis lorenz) scale)))" "x of each [x y z] point"]]
    :params [["(def p1 {:r 3.9 :len 12 :octaves 2 :dur 1/16})" "r = 3.9: chaotic"]
             ["(def p2 {:len 24 :dt 0.03 :octaves 2 :dur 1/16})" ""]]
    :runs [["logistic" "lg" "p1"] ["lorenz" "lz" "p2"]]}
   {:title "A Markov chain over pitches, its table in the tctx"
    :tree [["(def ch (notes chain))" ""]]
    :params [["(def p {:transitions {60 {64 1 67 1} 64 {60 1 65 2} 65 {67 1} 67 {60 2 64 1}} :start-state 60 :len 12 :dur 1/16})" "state → {next weight}"]]
    :runs [["result" "ch" "p"]]}
   {:title "Poisson onsets become durations"
    :tree [["(def po (pair-notes (color-talea scale (gaps poisson))))" "onsets → the gaps between them"]]
    :params [["(def p {:rate 3.0 :duration 4.0 :unit 1/4})" "a time unit = a quarter note"]]
    :runs [["result" "po" "p"]]}
   {:title "Smooth noise as a grid"
    :tree [["(def nz (notes (gate (threshold noise) (cycled scale))))" "an onset where the curve is high"]]
    :params [["(def p {:n 6 :len 16 :level 0.4 :dur 1/16})" ""]]
    :runs [["result" "nz" "p"]]}

   {:group "Rhythm generators"}
   {:title "A tala's theka, a named bell pattern"
    :tree [["(def tk (notes (gate theka (cycled scale))))" ""]
           ["(def bl (notes (gate bell (cycled [60 64 67]))))" ":pattern-name is one of its :choices"]]
    :params [["(def p1 {:tala-name \"jhaptal\" :dur 1/16})" ""]
             ["(def p2 {:meter [16 8] :pattern-name \"bossanova\" :dur 1/16})" ""]]
    :runs [["jhaptal" "tk" "p1"] ["bossanova" "bl" "p2"]]
    :extra [["not a choice" "(try (t/run bl {:pattern-name \"polka\"}) (catch Exception e (ex-message e)))"]]}
   {:title "Fractal rhythms: Cantor set, dragon curve"
    :tree [["(def ct (notes (gate cantor (cycled scale))))" ""]
           ["(def dg (notes (gate dragon (cycled scale))))" ""]]
    :params [["(def p1 {:iterations 2 :length 9 :dur 1/16})" ""]
             ["(def p2 {:iterations 3 :dur 1/16})" "2^(n+1) − 1 = 15 pulses"]]
    :runs [["cantor" "ct" "p1"] ["dragon" "dg" "p2"]]}
   {:title "A polyrhythm, one layer per voice"
    :tree [["(def pr (notes (gate (layer polyrhythm) (cycled [48 55]))))" "3 against 2, 24 pulses"]]
    :params [["(def p1 {:index 0 :dur 1/16})" ""]
             ["(def p2 {:index 1 :dur 1/16})" ""]]
    :runs [["three" "pr" "p1"] ["two" "pr" "p2"]]}
   {:title "Clapping Music: the pattern against itself, shifted"
    :tree [["(def cm (notes (gate (layer (duet [1 1 1 0 1 1 0 1 0 1 1 0])) (cycled [72]))))" ""]]
    :params [["(def p {:phase 3 :index 1 :dur 1/16})" "layer 1: the shifted part"]]
    :runs [["shifted by 3" "cm" "p"]]}
   {:title "A genetic rhythm: the fitness fn is a param"
    :tree [["(def gn (notes (gate genetic (cycled scale))))" ""]]
    :params [["(def gn-tctx (t/tctx gn {:dur 1/16}))" ":fitness-fn has no default: required"]
             ["(t/setp! gn-tctx :fitness-fn (fn [p] (- (+ (abs (- 5 (reduce + p))) (if (= 1 (first p)) 0 3)))))" "five onsets, one on the downbeat"]]
    :runs [["result" "gn" "gn-tctx"]]}
   {:title "Swing: a grid becomes onset times, then durations"
    :tree [["(def sw (pair-notes (color-talea scale (gaps (swing euclid)))))" "grid → onsets → gaps → pairs → notes"]]
    :params [["(def p {:k 8 :n 8 :swing-ratio 0.67 :unit 1/4})" "a beat = a quarter note"]]
    :runs [["result" "sw" "p"]]}
   {:title "A bouncing ball, in seconds"
    :tree [["(def bb (pair-notes (color-talea [72 67] (gaps bounce))))" ""]]
    :params [["(def p {:initial-height 1.0 :restitution 0.8 :unit 1/4 :quantum 1/64})" "one second = a quarter note"]]
    :runs [["result" "bb" "p"]]}
   {:title "Tuplet-like durations by repeated splitting"
    :tree [["(def sp (pair-notes (color-talea scale split)))" "each piece a :ratio of what remains"]]
    :params [["(def p {:duration 1 :depth 5 :ratio 2/3})" ""]]
    :runs [["result" "sp" "p"]]}
   {:title "Text as rhythm"
    :tree [["(def tx (notes (gate text-rhythm (cycled scale))))" "a beat on each word's first syllable"]]
    :params [["(def p {:text \"Composing trees of algorithms is plain Clojure.\" :dur 1/16})" ""]]
    :runs [["result" "tx" "p"]]}
   {:title "Vary a rhythm: EMI style, Oblique Strategies"
    :tree [["(def em (notes (gate (emi euclid) (cycled scale))))" ""]
           ["(def ob (notes (gate (oblique euclid) (cycled scale))))" ""]]
    :params [["(def p1 {:k 5 :n 16 :similarity 0.6 :dur 1/16})" ""]
             ["(def p2 {:k 3 :n 8 :strategy \"mirror\" :dur 1/16})" ""]]
    :runs [["emi" "em" "p1"] ["mirror" "ob" "p2"]]}

   {:group "Algorithms of your own"}
   {:title "A pitch transform"
    :tree [["(defalgo up \"Shift every pitch; rests stay.\"\n  {:algo {:in [:pitch] :out :pitch\n          :params {:by {:type :int :min -48 :max 48 :default 12}}}}\n  [pitches by] (map #(some-> % (+ by)) pitches))" "defalgo = defn + register; up* is the raw fn"]
           ["(def r6 (notes (up (gate euclid (cycled scale)))))" ""]]
    :params [["(def p {:by -12})" ""]]
    :runs [["result" "r6" "p"]]
    :extra [["raw fn" "(up* [60 nil 64] 5)"]]}
   {:title "Required params and open ranges"
    :tree [["(defalgo tone \"One pitch, which must be given.\"\n  {:algo {:in [] :out :pitch\n          :params {:p {:type :int :min 0 :max ##Inf :default ##NaN}}}}\n  [p] [p])" "##NaN default: required · ##Inf: no upper bound"]
           ["(def tn (tone))" ""]]
    :params [["(def p1 {})" "nothing set"] ["(def p2 {:p 67})" ""]]
    :runs [["unset" "tn" "p1" "(try (t/run tn p1) (catch Exception e (ex-message e)))"]
           ["set" "tn" "p2"]]}

   {:group "Two of a kind"}
   {:title "Two instances of one algo: name one"
    :tree [["(defalgo union \"Onset wherever either grid has one.\"\n  {:algo {:in [:pulse :pulse] :out :pulse}}\n  [a b] (mapv max a b))" ""]
           ["(def two (notes (gate (union (euclid :as :bass) euclid) (cycled scale))))" "keys :bass/k … and :k …"]]
    :params [["(def p {:bass/k 2 :bass/n 16 :k 5 :n 16 :intervals [0 3 7 10] :dur 1/16})" ""]]
    :runs [["result" "two" "p"]]}
   {:title "Different algos, same param name"
    :tree [["(defalgo accent \"Keep every k-th onset.\"\n  {:algo {:in [:pulse] :out :pulse\n          :params {:k {:type :int :min 1 :max 8 :default 2}}}}\n  [g k] (map-indexed (fn [i x] (if (zero? (mod i k)) x 0)) g))" "its :k means something else than euclid's"]
           ["(def ac (accent euclid))" "so both become :<short>.k"]]
    :params [["(def p {:euclid.k 5 :accent.k 2})" ""]]
    :runs [["result" "ac" "p"]]}

   {:group "Settings as data"}
   {:title "One tree, several tctxs"
    :tree [["riff" "from recipe 1"]]
    :params [["(def calm (t/tctx riff {:k 2}))" ""]
             ["(def busy (t/tctx riff {:k 7}))" "the tree is shared, the settings aren't"]]
    :runs [["calm" "riff" "calm"] ["busy" "riff" "busy"]]}
   {:title "Fit a tctx to another tree"
    :tree [["(def other (notes (transpose (head (cycled scale)))))" "reads :len and :semitones too"]]
    :params [["(t/fit! calm other)" "adds them at their defaults, keeps :k 2"]]
    :runs [["other, with calm" "other" "calm"]]}
   {:title "Look inside: describe and trace"
    :tree [["grid" "from recipe 10"]]
    :params [["(def grid-tctx (t/tctx grid))" ""]
             ["(t/describe grid-tctx)" "one row per param: key, value, range, default, algo, doc"]]
    :runs [["result" "grid" "grid-tctx"]]
    :extra [["trace" "(mapv :node (t/trace grid grid-tctx))"]]}

   {:group "Live"}
   {:title "A live tree, changed while it plays"
    :tree [["riff" "from recipe 1"]
           ["(def riff2 (notes (transpose (gate euclid (cycled scale)))))" "the tree swapped in later"]]
    :params [["(def live-tctx (t/tctx riff {:dur 1/16}))" "the values shown are those after the steps below"]]
    :play [["(t/live! :riff riff live-tctx)" "an endless voice follows :riff"]
           ["(t/setp! live-tctx :k 7)" "heard on the next note"]
           ["(t/retree! :riff riff2)" "same tctx, fitted: gains :semitones"]
           ["(t/setp! live-tctx :semitones 12)" ""]
           ["(t/stop! :riff)" ""]]
    :runs [["what :riff played last" "riff2" "live-tctx"]]}
   {:title "A transform: reshape any voice's own notes"
    :tree [["(def up5 (transpose :nodes))" "reads :nodes: the voice's own notes"]]
    :params [["(def up5-tctx (t/tctx up5 {:semitones 5}))" ""]]
    :play [["(t/live! :up5 up5 up5-tctx)" "a transform only binds the name"]
           ["(m/parse \"[verse: c4 e4 g4]\")" ""]
           ["(m/play :verse :algo :up5)" "the verse, a fourth higher"]]
    :runs [["applied" "up5" "up5-tctx" "((wall/algo :up5) (take 3 (m/sq :verse)) nil {:path [:TAA]})"]]}

   {:group "Material"}
   {:title "Generated notes become committed musics text"
    :tree [["riff" "from recipe 1"]]
    :params [["(def p {:k 3})" ""]]
    :runs [["text" "riff" "p" "(notes->mus (t/run riff p))"]]
    :extra [["committed" "(:ids (m/parse (notes->mus (t/run riff p))))"]]}])

(def api
  [["(t/tctx tree)", "(t/tctx tree overrides)" "an atom of {:params :specs}; the tree isn't stored"]
   ["(t/run tree tctx-or-map)" "" "the tree's result; a map's missing keys take their defaults, its values are checked"]
   ["(t/setp! tctx k v)" "(t/setp! tctx m)" "checked against the spec"]
   ["(t/fit! tctx tree)" "" "add a tree's missing keys, keep values"]
   ["(t/describe tctx-or-tree)" "(t/trace tree src)" "a table of params; every node's result"]
   ["(t/param-keys tree)" "" "every key a tree reads, with its spec"]
   ["(t/live! name tree tctx)" "(t/retree! name tree) · (t/stop! name)" "bind a name; hear every tctx change on the next note"]
   ["(t/play! tree src)" "" "play once"]
   ["(build-tree)" "(build-tree tree tctx) · (build-tree :repl)" "compose by drag and drop (or step by step at the REPL); returns [tree tctx]"]
   ["(gui tree)" "(gui tctx) · (gui tree tctx)" "a settings window: a control per param, result preview, Play once / Live as; returns the tctx"]
   ["(t/algo :short)" "(t/full-name :short) · (t/short-name 'ns/fn) · (t/algos)" "the registry"]
   ["(defalgo name doc {:algo …} [args] body)" "(t/expose ns/fn …)" "define a new algo; make an annotated fn one"]
   ["(algo … :as :name)" "" "name an instance: keys :name/…"]
   ["(notes->mus parts)" "" "notes as musics text, ready for (m/parse …)"]])

(defn short-value
  ([v] (short-value v 36))
  ([v limit]
   (let [s (pr-str (clojure.walk/postwalk #(if (or (fn? %) (var? %)) 'fn %) v))]
     (if (> (count s) limit) (str (subs s 0 (- limit 2)) " …") s))))

(defn spec-text [{:keys [name type min max default]}]
  (str (clojure.core/name name) " "
       (cond (#{:int :double :ratio} type) (str (pr-str min) ".." (pr-str max))
             :else (clojure.core/name type))
       (if (and (double? default) (Double/isNaN default)) " required" (str " = " (short-value default)))))

(defn lib-rows []
  (for [[short e] (reg/algos)
        :when (= "algo.tree.lib" (namespace (:full e)))
        :let [e (reg/algo short)]]
    (str "<tr><td><code>" (esc (name short)) "</code></td><td><code>"
         (esc (str/join " " (map name (:in e)))) " → " (name (:out e)) "</code></td><td><code>"
         (esc (str/join ", " (map spec-text (:params e)))) "</code></td><td>" (esc (:doc e)) "</td></tr>")))

(defn exposed-rows []
  (for [[short e] (reg/algos)
        :when (not (str/starts-with? (namespace (:full e)) "algo.tree.lib"))
        :when (not (str/starts-with? (namespace (:full e)) "cookbook"))]
    (str "<tr><td><code>" (esc (name short)) "</code></td><td><code>" (esc (:full e)) "</code></td><td><code>"
         (esc (str/join " " (map name (:in e)))) " → " (name (:out e)) "</code></td><td><code>"
         (esc (str/join ", " (map spec-text (:params e)))) "</code></td></tr>")))

(defn type-rows []
  (let [lib (filter #(not (str/starts-with? (namespace (:full (val %))) "cookbook")) (reg/algos))
        types [:pulse :weight :pitch :number :duration :onset :pair :point :part :model :stroke :leaf :index :any]]
    (for [ty types]
      (str "<tr><td><code>" (name ty) "</code></td><td>"
           (esc (str/join ", " (for [[s e] lib :when (= ty (:out e))] (name s))))
           "</td><td>"
           (esc (str/join ", " (for [[s e] lib :when (some #{ty} (:in e))] (name s))))
           "</td></tr>"))))

(defn full-params
  "Every param `tree` reads, with the value it runs with: given in `src`
   (a map or a tctx), else its default."
  [tree src]
  (let [given (if (t/tctx? src) (:params @src) src)]
    (str "{" (str/join ", " (for [{:keys [key default]} (t/param-keys tree)
                                  :let [v (get given key default)]]
                              (str (pr-str key) " "
                                   (cond (= :nodes key) "‹the voice's notes›"
                                         (t/nan? v)     "‹required›"
                                         :else          (short-value v 60)))))
         "}")))

(defn- code-rows [out]
  (apply str (for [{:keys [code note]} out]
               (str "<tr><td class=\"c\"><code>" (esc code) "</code></td><td class=\"w\">" (esc note) "</td></tr>"))))

(defn- res-row [label v]
  (str "<div class=\"res\"><span>" (esc label) "</span><code>" (esc (show v)) "</code></div>"))

(defn- part [label body]
  (str "<div class=\"part\"><div class=\"plabel\">" label "</div><div class=\"pbody\">" body "</div></div>"))

(defn recipe-html [n {:keys [title tree params play runs extra no-params throws?]}]
  (seed/seed! 2026)
  (let [run-steps (fn [steps] (doall (for [[code note] steps] (let [[v out] (ev code)] {:code code :note note :v v :out out}))))
        tree-out   (run-steps tree)
        params-out (run-steps params)
        play-out   (run-steps play)
        run-out    (doall (for [[label tr src custom] runs]
                            (let [full (first (ev (str "(cookbook-gen/full-params " tr " " src ")")))
                                  code (or custom (str "(t/run " tr " " src ")"))]
                              {:label label :full full :code code :v (first (ev code))})))
        extra-out  (doall (for [[label code] extra] [label (first (ev code))]))
        all-steps  (concat tree-out params-out play-out)
        printed    (apply str (map :out all-steps))
        errors     (filter #(and (string? (:v %)) (str/starts-with? (:v %) "error:")) all-steps)]
    (doseq [e (when-not throws? errors)] (binding [*out* *err*] (println "STEP ERROR in" title ":" (:v e))))
    (str "<section class=\"recipe\"><h3><span class=\"n\">" n "</span>" (esc title) "</h3>"
         (part "Tree" (str "<table class=\"code\">" (code-rows tree-out) "</table>"))
         (part "Params"
               (if (and (empty? params-out) (empty? run-out))
                 (str "<div class=\"none\">" (esc (or no-params "none")) "</div>")
                 (str (when (seq params-out) (str "<table class=\"code\">" (code-rows params-out) "</table>"))
                      (apply str (for [{:keys [label full]} run-out]
                                   (res-row (if (> (count run-out) 1) label "values") full))))))
         (part "Result"
               (str (when (seq play-out) (str "<table class=\"code\">" (code-rows play-out) "</table>"))
                    (apply str (for [{:keys [label code v]} run-out]
                                 (str "<table class=\"code\"><tr><td class=\"c\"><code>" (esc code) "</code></td><td class=\"w\"></td></tr></table>"
                                      (res-row label v))))
                    (when (seq printed) (res-row "printed" (str/trimr printed)))
                    (apply str (for [[label v] extra-out] (res-row label v)))))
         "</section>")))

(defn recipes-html []
  (loop [[r & more] recipes n 1 out []]
    (cond (nil? r) (apply str out)
          (:group r) (recur more n (conj out (str "<h2 class=\"grp\">" (esc (:group r)) "</h2>")))
          :else (recur more (inc n) (conj out (recipe-html n r))))))

(def post
  "What must follow an algo before its result is notes: [type-or-names label steps then note]."
  [[:pulse nil "gate (with :pitch) → notes" "a nonzero value (2 = accent) counts as an onset" "(notes (gate euclid (cycled scale)))"]
   [:weight nil "density → :pulse, or pick → one :index" "tilt / power only reshape weights: something else must follow" "(notes (gate (density (tilt indisp)) …))"]
   [:pitch nil "notes (one :dur each), or color-talea with :duration → pair-notes" "modulating gives pitch classes 0–11: transpose (+60) first" "(notes (transpose modulating))"]
   [:number nil "degrees (with a scale) → :pitch, or threshold → :pulse" "a sampler/walk/chaos value is not a pitch; degrees rescales min..max onto the scale" "(notes (degrees walk scale))"]
   [:onset nil "gaps → :duration, then color-talea → pair-notes" "set gaps' :unit to the note value of one time unit (seconds for bounce/rain/heartbeat, beats for swing/cloud); n onsets give n−1 durations" "(pair-notes (color-talea scale (gaps poisson)))"]
   [:duration nil "color-talea (with :pitch) → pair-notes" "note values: 1/4 is a quarter" "(pair-notes (color-talea scale split))"]
   [:pair nil "pair-notes" "counterpoint's durations are in quarters: stretch 1/4 after pair-notes" "(stretch (pair-notes (layer cp)))"]
   [:point nil "axis → :number → degrees" "henon/lorenz give a point per step" "(notes (degrees (axis lorenz) scale))"]
   [:part nil "layer (:index) → one layer, then as its own type" "rhythm layers are grids, counterpoint layers are pairs" "(notes (gate (layer polyrhythm) …))"]
   [:model nil "markov-gen → :pitch" "" "(markov-gen (markov-train motif))"]
   [:index nil "none — one value, not a sequence" "use it to choose, e.g. in your own algo" "(pick (tilt indisp))"]
   [:stroke nil "none in the lib" "syllables to read or print (konnakol)" ""]
   [nil ["tala" "djembe" "humanize" "pocket" "patch" "tiling" "trend-rhythm" "text-rhythm"] "none in the lib (maps or mixed values)" "text-rhythm and trend-rhythm give 0/1/2 (−1 = rest): gate takes them; the map-valued ones need your own defalgo, e.g. mapping :time" ""]
   [:leaf nil "none — t/play!, t/live!, or notes->mus → m/parse" "an infinite tree (cycled/shuffled with no finite gate) needs head before t/play! or printing" "(notes->mus (t/run riff tctx))"]])

(defn post-rows []
  (let [lib (filter #(not (str/starts-with? (namespace (:full (val %))) "cookbook")) (reg/algos))]
    (for [[ty names steps then example] post]
      (str "<tr><td><code>" (esc (if ty (name ty) "(maps)")) "</code></td><td>"
           (esc (str/join ", " (or names (for [[sh e] lib :when (= ty (:out e))] (name sh)))))
           "</td><td><b>" (esc steps) "</b>" (when (seq then) (str "<br><span class=\"small\">" (esc then) "</span>"))
           "</td><td><code>" (esc example) "</code></td></tr>"))))

(def css
  "<style>
  :root { --ink:#1d1d1f; --muted:#5b6070; --rule:#d9dce3; --accent:#2f5d8a; --soft:#f3f5f8; --code:#0f2940; }
  @page { size: A4; margin: 16mm 15mm 16mm 15mm; }
  * { box-sizing: border-box; }
  body { font: 10pt/1.45 \"Source Serif 4\", \"Georgia\", serif; color: var(--ink); background: #fff; margin: 0; }
  h1 { font: 600 22pt/1.15 \"Inter\", \"Helvetica Neue\", Arial, sans-serif; margin: 0 0 4pt; letter-spacing: -0.01em; }
  h2 { font: 600 13pt/1.2 \"Inter\", \"Helvetica Neue\", Arial, sans-serif; margin: 18pt 0 6pt; color: var(--accent); break-after: avoid; }
  h2.grp { font-size: 11pt; text-transform: uppercase; letter-spacing: 0.06em; color: var(--muted); border-bottom: 1px solid var(--rule); padding-bottom: 3pt; }
  h3 { font: 600 10.5pt/1.3 \"Inter\", \"Helvetica Neue\", Arial, sans-serif; margin: 0 0 4pt; }
  h3 .n { display:inline-block; min-width: 16pt; color: var(--accent); }
  p { margin: 0 0 6pt; }
  .sub { color: var(--muted); font-size: 10.5pt; margin-bottom: 12pt; }
  .meta { font: 8.5pt \"Inter\", Arial, sans-serif; color: var(--muted); margin-bottom: 14pt; }
  code, .mono { font-family: \"JetBrains Mono\", \"DejaVu Sans Mono\", Menlo, monospace; font-size: 8.3pt; }
  p code, li code, td code { color: var(--code); }
  ul { margin: 0 0 8pt 14pt; padding: 0; }
  li { margin-bottom: 2pt; }
  table { border-collapse: collapse; width: 100%; }
  .tbl { margin: 4pt 0 10pt; font-size: 8.6pt; }
  .tbl th, .tbl td { border-bottom: 1px solid var(--rule); padding: 3pt 5pt; text-align: left; vertical-align: top; }
  .tbl th { font: 600 8pt \"Inter\", Arial, sans-serif; color: var(--muted); text-transform: uppercase; letter-spacing: 0.04em; }
  .matrix th, .matrix td { text-align: center; font-size: 7.6pt; padding: 3pt 2pt; border: 1px solid var(--rule); }
  .matrix th.row { text-align: left; background: var(--soft); white-space: nowrap; }
  .matrix td.x { color: #b7bcc7; }
  .matrix td code { font-size: 7.2pt; }
  .recipe { border: 1px solid var(--rule); border-radius: 4pt; padding: 7pt 8pt 6pt; margin: 0 0 8pt; break-inside: avoid; }
  table.code td { padding: 1pt 4pt; vertical-align: top; }
  table.code td.c { width: 64%; background: var(--soft); }
  table.code td.c code { white-space: pre-wrap; color: var(--code); }
  table.code td.w { color: var(--muted); font-size: 8.3pt; font-style: italic; }
  .part { display: flex; gap: 6pt; margin-top: 3pt; }
  .plabel { font: 700 6.8pt \"Inter\", Arial, sans-serif; letter-spacing: 0.08em; text-transform: uppercase; color: var(--accent, #7a4b00); min-width: 38pt; padding-top: 2pt; }
  .pbody { flex: 1; min-width: 0; }
  .pbody .res:first-child, .pbody table + .res { margin-top: 2pt; }
  .small { color: var(--muted); font-size: 7.6pt; }
  .none { color: var(--muted); font-style: italic; font-size: 8.3pt; padding-top: 1pt; }
  .res { margin-top: 4pt; display: flex; gap: 6pt; align-items: baseline; }
  .res span { font: 600 7pt \"Inter\", Arial, sans-serif; text-transform: uppercase; color: var(--muted); min-width: 58pt; letter-spacing: 0.05em; }
  .res code { white-space: pre-wrap; word-break: break-word; font-size: 7.8pt; }
  .note { border-left: 3pt solid var(--accent); background: var(--soft); padding: 5pt 8pt; margin: 6pt 0 10pt; font-size: 9.2pt; }
  .mock { border: 1.5px solid var(--ink); border-radius: 4pt; padding: 0; margin: 6pt 0 10pt; font: 8.5pt \"Inter\", Arial, sans-serif; break-inside: avoid; }
  .mock .bar { background: var(--ink); color: #fff; padding: 3pt 8pt; font-weight: 600; }
  .mock .body { padding: 6pt 8pt; }
  .mock .panel { border: 1px solid var(--rule); border-radius: 3pt; padding: 4pt 6pt; margin-bottom: 5pt; }
  .mock .panel b { display: block; font-size: 7.8pt; color: var(--muted); margin-bottom: 3pt; }
  .mock .sl { display: flex; align-items: center; gap: 6pt; margin: 2pt 0; }
  .mock .sl .lab { width: 60pt; } .mock .sl .v { width: 32pt; text-align: right; font-family: monospace; }
  .mock .track { flex: 1; height: 3pt; background: var(--rule); position: relative; border-radius: 2pt; }
  .mock .track i { position: absolute; top: -3pt; width: 9pt; height: 9pt; border-radius: 50%; background: var(--accent); }
  .mock .btn { display: inline-block; border: 1px solid var(--muted); border-radius: 3pt; padding: 1pt 6pt; margin-right: 4pt; }
  .pb { break-before: page; }
  .two { display: grid; grid-template-columns: 1fr 1fr; gap: 10pt; }
</style>
<style>
  table.code td.c { width: 74%; }
  table.code td.c code { font-size: 7.6pt; }
  .res code { font-size: 7.4pt; }
</style>")

(def body-recipes (recipes-html))
(def n-recipes (count (remove :group recipes)))

(defn recipe-number [title]
  (inc (.indexOf (mapv :title (remove :group recipes)) title)))

(def html
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>Algo Cookbook</title>\n" css "\n</head>\n<body>\n"
       "<h1>Algo cookbook</h1>\n"
       "<div class=\"sub\">Composing <code>algo/</code> generators with <code>algo.tree</code> at the Clojure REPL: trees, their settings, your own algorithms, and live playback.</div>\n"
       "<div class=\"meta\">musics · branch <code>simple-composition</code> · 2026-09-28 · every recipe below was run for real, each starting from seed 2026; results are copied from that run</div>\n"

       "<h2>1. The idea</h2>\n"
       "<p>Two separate things. A <b>tree</b> says <i>what</i> is computed: algorithms nested as ordinary Clojure calls. A <b>tctx</b> holds the <i>settings</i> it runs with: an atom of params, derived from a tree but never holding it.</p>\n<ul>\n"
       "<li><b>Composition is nesting.</b> <code>(notes (gate euclid (cycled scale)))</code>: each algo takes its children and returns a node. A bare algo name is a leaf, <code>euclid</code> = <code>(euclid)</code>.</li>\n"
       "<li><b>Algorithms describe themselves.</b> Each carries <code>:algo</code> metadata: what its children must be (<code>:in</code>), what it makes (<code>:out</code>), and a spec per param: type, range, default. Nothing else about the function changes.</li>\n"
       "<li><b>Wrong shapes fail when built.</b> The child count and types are checked the moment you write the expression, not while it plays.</li>\n"
       "<li><b>Params come by name, not by position.</b> Each param is a key in the tctx. Change it with <code>setp!</code>, an <code>assoc</code> for its params (or a plain <code>swap!</code>); a validator checks every value against its spec.</li>\n"
       "<li><b>A name makes it live.</b> <code>(t/live! :riff tree tctx)</code> plays the tree endlessly; every later change to the tctx is heard on the next note, and the GUI's Wall window draws a slider for each ranged param.</li>\n</ul>\n"
       "<p>At <code>lein repl</code>, <code>algo.tree</code> is <code>t</code> and every ready-made algo is referred. Elsewhere: <code>(require '[algo.tree :as t :refer [defalgo]] '[algo.tree.lib :refer :all])</code>.</p>\n"

       "<h2>2. The API on one page</h2>\n<table class=\"tbl\"><tr><th>Call</th><th>Also</th><th>Does</th></tr>\n"
       (apply str (for [[a b c] api] (str "<tr><td><code>" (esc a) "</code></td><td><code>" (esc b) "</code></td><td>" (esc c) "</td></tr>\n")))
       "</table>\n"

       "<h2 class=\"pb\">3. The ready-made algos</h2>\n"
       "<p>Generated from the registry by introspection, so it matches the code. <code>in → out</code> gives the child types and the result type; <code>same</code> means its first child's type.</p>\n"
       "<p><b>Exposed from <code>algo/</code></b> (their own <code>:algo</code> metadata):</p>\n"
       "<table class=\"tbl\"><tr><th>Name</th><th>Function</th><th>In → out</th><th>Params (range = default)</th></tr>\n" (apply str (exposed-rows)) "</table>\n"
       "<p><b>Defined in <code>algo.tree.lib</code></b>:</p>\n"
       "<table class=\"tbl\"><tr><th>Name</th><th>In → out</th><th>Params (range = default)</th><th>Does</th></tr>\n" (apply str (lib-rows)) "</table>\n"

       "<h2>4. What feeds what</h2>\n"
       "<p>A child fits a slot when its <code>:out</code> equals the slot's <code>:in</code>; <code>any</code> fits everything, and a literal or a <code>:keyword</code> child counts as <code>any</code>.</p>\n"
       "<table class=\"tbl\"><tr><th>Type</th><th>Produced by</th><th>Consumed by</th></tr>\n" (apply str (type-rows)) "</table>\n"

       "<h2 class=\"pb\">5. From an algo to notes: the steps that must follow</h2>\n"
       "<p>Only <code>notes</code> and <code>pair-notes</code> make notes. Every other result needs particular steps after it; this is the chain, per result type. Algos that produce the type are listed from the registry.</p>\n"
       "<table class=\"tbl\"><tr><th>Result</th><th>Produced by</th><th>Must be followed by</th><th>Example</th></tr>\n" (apply str (post-rows)) "</table>\n"

       "<h2>6. Writing an algorithm</h2>\n"
       "<p>Give an ordinary <code>defn</code> an <code>:algo</code> attr-map, then <code>(t/expose ns/the-fn)</code> defines its constructor under the short name. For a new function, <code>defalgo</code> does both (the raw fn stays callable as <code>name*</code>).</p>\n"
       "<table class=\"code\"><tr><td class=\"c\"><code>" (esc "(defn density-grid\n  \"Binary onset grid ...\"\n  {:algo {:short :density :in [:weight] :out :pulse\n          :params {:density {:type :double :min 0.0 :max 1.0 :default 0.5\n                             :doc \"fraction of pulses kept\"}}}}\n  [ranks density] ...)") "</code></td><td class=\"w\">the leading args named in <code>:in</code> are children; every later arg is a param, named by the arg itself</td></tr></table>\n"
       "<ul><li><b><code>:params</code></b>: per param a <code>:type</code> (<code>:int :double :ratio :string :keyword :vector :map :fn :bool :any</code>), a <code>:default</code>, and for a number <code>:min</code>/<code>:max</code>. <code>##-Inf</code>/<code>##Inf</code> leave a range end open; a <code>##NaN</code> default makes the param required. <code>:choices</code> limits a string or keyword. Registration refuses an incomplete spec.</li>\n"
       "<li><b>One value per call?</b> <code>:repeat :len</code> calls the fn <code>:len</code> times (a sampler: <code>normal</code>); <code>:pull {:via :value}</code> calls it once for a generator and pulls <code>:len</code> values (a closure: <code>walk</code>, <code>logistic</code>). No wrapper fn needed; <code>:len</code> is a param like any other.</li>\n"
       "<li><b>Children not first?</b> <code>:children [:coll]</code> names the args that are children.</li>\n"
       "<li><b>Keyword args</b> (<code>&amp; {:keys [rotation] :or {rotation 0}}</code>) are params too, their <code>:or</code> the default. A multi-arity fn names the arity to wrap with <code>:arity</code>.</li>\n"
       "<li><b>Keys</b>: a param keeps its bare name unless two different algos in one tree read it with different specs; then each becomes <code>:short.name</code> (recipe " (recipe-number "Different algos, same param name") "). A named instance's are <code>:as/name</code>.</li></ul>\n"

       "<h2 class=\"pb\">7. Recipes (" n-recipes ")</h2>\n"
       "<p>Each recipe is plain Clojure in three parts. <b>Tree</b>: the code that builds it. <b>Params</b>: the settings code, then <i>every</i> param the tree reads with the value it ran with — given, or its default. <b>Result</b>: the run and what came back. The grey column is code, the right column a note on it; notes are shown as <code>notes-&gt;mus</code> text, exactly what <code>(m/parse …)</code> would commit.</p>\n"
       body-recipes

       "<h2 class=\"pb\">8. Parameters and the GUI</h2>\n"
       "<p><code>(gui tree)</code> opens a settings window for one tree without the rest of the GUI: a control per param, grouped by algo, a preview of the result that follows every change, and Play once / Live as buttons. It makes the tctx and returns it; <code>(gui tctx)</code> and <code>(gui tree tctx)</code> use one you have. The window and the REPL share the tctx: <code>setp!</code> at the REPL moves the control.</p>\n"
       "<p>The tctx is the GUI's model. The Wall window lists every live name with its tree, and one control per param: a slider when the spec has a finite range, a dropdown for <code>:choices</code>, a note for a function (set it at the REPL), a text field otherwise. A value the spec rejects isn't applied; the window says why.</p>\n"
       "<div class=\"mock\"><div class=\"bar\">Musics — Wall Algorithms</div><div class=\"body\">"
       "<div class=\"panel\"><b>Live trees (t/live!) — each change heard on the next note</b>"
       "<div>riff  (notes (gate (euclid) (cycled (scale))))</div>"
       "<div class=\"sl\"><span class=\"lab\">k</span><span class=\"v\">7</span><span>0</span><span class=\"track\"><i style=\"left:22%\"></i></span><span>32</span></div>"
       "<div class=\"sl\"><span class=\"lab\">n</span><span class=\"v\">8</span><span>1</span><span class=\"track\"><i style=\"left:22%\"></i></span><span>32</span></div>"
       "<div class=\"sl\"><span class=\"lab\">root</span><span class=\"v\">60</span><span>24</span><span class=\"track\"><i style=\"left:50%\"></i></span><span>96</span></div>"
       "<div>intervals  <span class=\"mono\">[0 2 4 7 9]</span></div>"
       "<div class=\"sl\"><span class=\"lab\">dur</span><span class=\"v\">0.063</span><span>0.016</span><span class=\"track\"><i style=\"left:1%\"></i></span><span>4.000</span></div></div>"
       "<div class=\"panel\"><b>Assign (prepares the NEXT mint at path)</b><span class=\"mono\">TAA</span> &nbsp; <span class=\"mono\">riff</span> &nbsp;<span class=\"btn\">Assign</span></div>"
       "<span class=\"btn\">Close</span></div></div>\n"

       "<h2>9. Limits worth knowing</h2>\n<ul>\n"
       "<li>Generated notes carry pitch and duration only. Dynamics and articulation come from the context (<code>!mf</code> …) once the notes are committed as text.</li>\n"
       "<li><code>notes-&gt;mus</code> covers MIDI 24–119 (octaves 1–8) and throws outside that range.</li>\n"
       "<li>An infinite tree (<code>cycled</code>, <code>shuffled</code> without a <code>head</code> or a finite <code>gate</code>) is fine live, but <code>t/play!</code> and printing need a finite result.</li>\n"
       "<li>All random algos share one RNG (<code>algo.random.core/default-rng</code>). <code>(algo.random.core/seed! 2026)</code> makes a recipe reproducible, but reseeds every live voice too.</li>\n"
       "<li>A <code>defalgo</code> lives in the namespace that defines it: re-run it (keep it in a source file) in a fresh session. A tctx is live state, not saved by <code>persist-session</code>.</li>\n"
       "<li>Keys are checked per tree: two separate trees that happen to share a param name share it only if they share a tctx.</li>\n</ul>\n"
       "</body>\n</html>\n"))

(spit "doc/algo-cookbook.html" html)
(println "wrote doc/algo-cookbook.html," n-recipes "recipes")
(engine/stop!)
(shutdown-agents)
(System/exit 0)
