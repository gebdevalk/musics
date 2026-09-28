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
            [core.async-engine :as engine]
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
    :steps [["(def riff (notes (gate euclid (cycled scale))))" "the tree: an immutable value"]
            ["(def ctx (t/tctx riff))" "its settings: an atom, every param at its default"]]
    :results [["tree" "riff"] ["params" "(:params @ctx)"] ["result" "(t/run riff ctx)"]]}
   {:title "Change a setting; the validator guards every change"
    :steps [["(t/set-param! ctx :k 5)" "five onsets"]
            ["(t/set-param! ctx :intervals [0 3 7 10])" "a minor-seventh arpeggio"]]
    :results [["result" "(t/run riff ctx)"]
              ["out of range" "(try (t/set-param! ctx :k 99) (catch Exception e (.getMessage e)))"]
              ["wrong type" "(try (swap! ctx assoc-in [:params :k] 2.5) (catch Exception e (.getMessage e)))"]]}
   {:title "Wrong shapes fail when built"
    :throws? true
    :steps [["(gate (tilt indisp) scale)" "tilt gives weights, gate wants a 0/1 grid"]
            ["(euclid scale)" "euclid is a source: no children"]]
    :results [["error" "(try (gate (tilt indisp) scale) (catch Exception e (.getMessage e)))"]
              ["error" "(try (euclid scale) (catch Exception e (.getMessage e)))"]]}

   {:group "Rhythm × melody"}
   {:title "Euclidean rhythm, rotated, over a reshuffled pentatonic"
    :steps [["(def r2 (notes (gate euclid (shuffled scale))))" "every pitch once per pass"]]
    :results [["result" "(t/run r2 {:k 5 :n 16 :rotation 2 :dur 1/16})"]]}
   {:title "Every algo/ generator is an algo: a Fibonacci rhythm"
    :steps [["(def r3 (notes (gate fibonacci (cycled scale))))" "algo.rhythmic.rhythm/fibonacci-rhythm, by its short name"]]
    :results [["result" "(t/run r3 {:length 16 :intervals [0 2 4 5 7 9 11] :dur 1/16})"]
              ["its spec" "(t/full-name :fibonacci)"]]}
   {:title "A Xenakis sieve as the gate"
    :steps [["(def r4 (notes (gate sieve (cycled scale))))" "vector params: moduli and their residues"]]
    :results [["result" "(t/run r4 {:moduli [3 4] :residues [[0 1] [2]] :dur 1/16})"]]}
   {:title "A clave timeline carrying an arpeggio"
    :steps [["(def r5 (notes (gate (world/named-bell-patterns \"clave\") (cycled [60 64 67 72]))))" "a literal child: its own data"]]
    :results [["result" "(t/run r5 {:dur 1/16})"]]}

   {:group "Isorhythm & durations"}
   {:title "Isorhythm: a 5-note color against a 3-value talea"
    :steps [["(def iso (pair-notes (color-talea [60 62 64 65 67] [1/4 1/8 1/8])))" "period lcm(5,3) = 15"]]
    :results [["result" "(t/run iso {})"]]}
   {:title "Stretch the durations, transpose the pitches"
    :steps [["(def iso2 (transpose (stretch iso)))" "both take notes as well as plain values"]]
    :results [["result" "(t/run iso2 {:factor 1/2 :semitones 7})"]]}

   {:group "Metre & indispensability"}
   {:title "Indispensability thins a 12/8 bar"
    :steps [["(def grid (density (tilt indisp)))" "ranks → probabilities → the strongest half"]
            ["(def bar (notes (gate grid (cycled [67 64 60]))))" "an arpeggio on the kept pulses"]]
    :results [["result" "(t/run bar {:adherence 0.8 :density 0.5})"]]}
   {:title "Swap one stage: strong beats silent"
    :steps [["(def bar2 (notes (gate (density (power indisp)) (cycled [67 64 60]))))" "power reads the same :adherence as tilt"]]
    :results [["result" "(t/run bar2 {:adherence -0.8 :density 0.5})"]]}
   {:title "One weighted choice"
    :steps [["(def one (pick (tilt indisp)))" "an index, drawn with the shaped weights"]]
    :results [["result" "(t/run one {:subdivisions [3 3] :adherence 1.0})"]]}

   {:group "Melody generators"}
   {:title "A Markov melody trained on a motif"
    :steps [["(def mk (notes (gate euclid (markov-gen (markov-train [60 62 64 62 60 67 65 64])))))" "train → a :model, generate → pitches"]]
    :results [["result" "(t/run mk {:order 1 :k 11 :n 16 :dur 1/16})"]]}
   {:title "Slonimsky: insertions before, between, after"
    :steps [["(def sl (notes (infra [60 64 67 72] [59 62])))" "two children: principal tones, insertion"]
            ["(def pol (notes (polations [60 64 67 72])))" "all three layers at once, as params"]]
    :results [["infra" "(t/run sl {:dur 1/16})"]
              ["polations" "(t/run pol {:inter [65] :ultra [62] :dur 1/16})"]]}
   {:title "An L-system melody, its rules in the tctx"
    :steps [["(def ls (notes lsys-melody))" "axiom, rules, note-map: all params"]
            ["(def lctx2 (t/tctx ls {:dur 1/16}))" ""]
            ["(t/set-params! lctx2 {:rules {\"A\" \"ABA\" \"B\" \"CB\" \"C\" \"A\"} :note-map {\\A 67 \\B 64 \\C 60}})" "a map param: checked to be a map"]]
    :results [["result" "(t/run ls lctx2)"]]}
   {:title "A constrained walk: constraints are a param"
    :steps [["(def sc [60 62 64 65 67 69 71 72])" ""]
            ["(def cw (notes (constrained sc)))" ""]
            ["(def cctx (t/tctx cw {:dur 1/16 :length 12}))" "default constraint: no repeated note"]
            ["(t/set-param! cctx :constraints [(melody/max-leap-constraint sc 1) melody/no-repeat-constraint])" "steps only"]]
    :results [["result" "(t/run cw cctx)"]]}
   {:title "A melody that modulates"
    :steps [["(def mo (notes (transpose modulating)))" "pitch classes 0–11, lifted 60 semitones"]]
    :results [["result" "(t/run mo {:segments [[[:C :major] 6] [[:E :minor] 6]] :semitones 60 :dur 1/16})"]]}
   {:title "Two-voice counterpoint, one layer at a time"
    :steps [["(def cp (counterpoint [55 57 59 60 62 64 65 67 69 71 72 74 76]))" "two :layers of [pitch quarters] pairs"]
            ["(def voice (stretch (pair-notes (layer cp))))" "durations in quarters → note values: ×1/4"]]
    :results [["voice 1" "(t/run voice {:index 0 :factor 1/4})"]
              ["voice 2" "(t/run voice {:index 1 :factor 1/4})"]]}

   {:group "Metric generators"}
   {:title "A number's bits, a fraction's expansion"
    :steps [["(def bt (notes (gate bits (cycled scale))))" "13 = 1101: onsets on bits 0, 2, 3"]
            ["(def cf (notes (gate cfrac (cycled scale))))" "π = [3; 7, 15, 1, …]"]]
    :results [["bits" "(t/run bt {:number 45 :length 8 :dur 1/16})"]
              ["cfrac" "(t/run cf {:dur 1/16})"]]}
   {:title "Modular arithmetic as a grid"
    :steps [["(def md (notes (gate modular (cycled scale))))" "onset where 3i ≡ 0 (mod 7)"]]
    :results [["result" "(t/run md {:modulus 5 :multiplier 2 :length 15 :dur 1/16})"]]}

   {:group "Random sources"}
   {:title "A sampler is a sequence: :len draws"
    :steps [["(def tri (notes int-triangular))" "one value per call, called :len times"]]
    :results [["result" "(t/run tri {:lo 60 :hi 73 :mode 67 :len 12 :dur 1/16})"]]}
   {:title "Any numbers, rescaled onto a scale"
    :steps [["(def nd (notes (degrees normal scale)))" "lowest draw → first degree, highest → last"]]
    :results [["result" "(t/run nd {:len 12 :octaves 2 :dur 1/16})"]]}
   {:title "A random walk, and a glide toward a target"
    :steps [["(def wk (notes (degrees walk scale)))" "a closure, pulled :len times"]
            ["(def gl (notes (degrees glide scale)))" ":target is passed on every pull"]]
    :results [["walk" "(t/run wk {:len 16 :step-bound 3.0 :octaves 2 :dur 1/16})"]
              ["glide" "(t/run gl {:len 16 :initial 0.0 :target 10.0 :inertia 0.7 :octaves 2 :dur 1/16})"]]}
   {:title "Chaos: the logistic map, the Lorenz attractor"
    :steps [["(def lg (notes (degrees logistic scale)))" "r = 3.9: chaotic"]
            ["(def lz (notes (degrees (axis lorenz) scale)))" "x of each [x y z] point"]]
    :results [["logistic" "(t/run lg {:r 3.9 :len 12 :octaves 2 :dur 1/16})"]
              ["lorenz" "(t/run lz {:len 24 :dt 0.03 :octaves 2 :dur 1/16})"]]}
   {:title "A Markov chain over pitches, its table in the tctx"
    :steps [["(def ch (notes chain))" ""]]
    :results [["result" "(t/run ch {:transitions {60 {64 1 67 1} 64 {60 1 65 2} 65 {67 1} 67 {60 2 64 1}} :start-state 60 :len 12 :dur 1/16})"]]}
   {:title "Poisson onsets become durations"
    :steps [["(def po (pair-notes (color-talea scale (gaps poisson))))" "onsets → the gaps between them"]]
    :results [["result" "(t/run po {:rate 3.0 :duration 4.0 :unit 1/4})"]]}
   {:title "Smooth noise as a grid"
    :steps [["(def nz (notes (gate (threshold noise) (cycled scale))))" "an onset where the curve is high"]]
    :results [["result" "(t/run nz {:n 6 :len 16 :level 0.4 :dur 1/16})"]]}

   {:group "Rhythm generators"}
   {:title "A tala's theka, a named bell pattern"
    :steps [["(def tk (notes (gate theka (cycled scale))))" ""]
            ["(def bl (notes (gate bell (cycled [60 64 67]))))" ":pattern-name is one of its :choices"]]
    :results [["jhaptal" "(t/run tk {:tala-name \"jhaptal\" :dur 1/16})"]
              ["bossanova" "(t/run bl {:meter [16 8] :pattern-name \"bossanova\" :dur 1/16})"]
              ["not a choice" "(try (t/run bl {:pattern-name \"polka\"}) (catch Exception e (ex-message e)))"]]}
   {:title "Fractal rhythms: Cantor set, dragon curve"
    :steps [["(def ct (notes (gate cantor (cycled scale))))" ""]
            ["(def dg (notes (gate dragon (cycled scale))))" ""]]
    :results [["cantor" "(t/run ct {:iterations 2 :length 9 :dur 1/16})"]
              ["dragon" "(t/run dg {:iterations 3 :dur 1/16})"]]}
   {:title "A polyrhythm, one layer per voice"
    :steps [["(def pr (notes (gate (layer polyrhythm) (cycled [48 55]))))" "3 against 2, 24 pulses"]]
    :results [["three" "(t/run pr {:index 0 :dur 1/16})"]
              ["two" "(t/run pr {:index 1 :dur 1/16})"]]}
   {:title "Clapping Music: the pattern against itself, shifted"
    :steps [["(def cm (notes (gate (layer (duet [1 1 1 0 1 1 0 1 0 1 1 0])) (cycled [72]))))" ""]]
    :results [["shifted by 3" "(t/run cm {:phase 3 :index 1 :dur 1/16})"]]}
   {:title "A genetic rhythm: the fitness fn is a param"
    :steps [["(def gn (notes (gate genetic (cycled scale))))" ":fitness-fn has no default: required"]
            ["(def gctx (t/tctx gn {:dur 1/16}))" ""]
            ["(t/set-param! gctx :fitness-fn (fn [p] (- (+ (abs (- 5 (reduce + p))) (if (= 1 (first p)) 0 3)))))" "five onsets, one on the downbeat"]]
    :results [["result" "(t/run gn gctx)"]]}
   {:title "Swing: a grid becomes onset times, then durations"
    :steps [["(def sw (pair-notes (color-talea scale (gaps (swing euclid)))))" ""]]
    :results [["result" "(t/run sw {:k 8 :n 8 :swing-ratio 0.67 :unit 1/4})"]]}
   {:title "A bouncing ball, in seconds"
    :steps [["(def bb (pair-notes (color-talea [72 67] (gaps bounce))))" "one second = a quarter note"]]
    :results [["result" "(t/run bb {:initial-height 1.0 :restitution 0.8 :unit 1/4 :quantum 1/64})"]]}
   {:title "Tuplet-like durations by repeated splitting"
    :steps [["(def sp (pair-notes (color-talea scale split)))" "each piece a :ratio of what remains"]]
    :results [["result" "(t/run sp {:duration 1 :depth 5 :ratio 2/3})"]]}
   {:title "Text as rhythm"
    :steps [["(def tx (notes (gate text-rhythm (cycled scale))))" "a beat on each word's first syllable"]]
    :results [["result" "(t/run tx {:text \"Composing trees of algorithms is plain Clojure.\" :dur 1/16})"]]}
   {:title "Vary a rhythm: EMI style, Oblique Strategies"
    :steps [["(def em (notes (gate (emi euclid) (cycled scale))))" ""]
            ["(def ob (notes (gate (oblique euclid) (cycled scale))))" ""]]
    :results [["emi" "(t/run em {:k 5 :n 16 :similarity 0.6 :dur 1/16})"]
              ["mirror" "(t/run ob {:k 3 :n 8 :strategy \"mirror\" :dur 1/16})"]]}

   {:group "Algorithms of your own"}
   {:title "A pitch transform"
    :steps [["(defalgo up \"Shift every pitch; rests stay.\"\n  {:algo {:in [:pitches] :out :pitches\n          :params {:by {:type :int :min -48 :max 48 :default 12}}}}\n  [pitches by] (map #(some-> % (+ by)) pitches))" "defalgo = defn + register; up* is the raw fn"]
            ["(def r6 (notes (up (gate euclid (cycled scale)))))" ""]]
    :results [["result" "(t/run r6 {:by -12})"] ["raw fn" "(up* [60 nil 64] 5)"]]}
   {:group "Two of a kind"}
   {:title "Two instances of one algo: name one"
    :steps [["(defalgo union \"Onset wherever either grid has one.\"\n  {:algo {:in [:grid :grid] :out :grid}}\n  [a b] (mapv max a b))" ""]
            ["(def two (notes (gate (union (euclid :as :bass) euclid) (cycled scale))))" "keys :bass/k … and :k …"]]
    :results [["keys" "(map :key (t/param-keys two))"]
              ["result" "(t/run two {:bass/k 2 :bass/n 16 :k 5 :n 16 :intervals [0 3 7 10] :dur 1/16})"]]}
   {:title "Different algos, same param name"
    :steps [["(defalgo accent \"Keep every k-th onset.\"\n  {:algo {:in [:grid] :out :grid\n          :params {:k {:type :int :min 1 :max 8 :default 2}}}}\n  [g k] (map-indexed (fn [i x] (if (zero? (mod i k)) x 0)) g))" "its :k means something else than euclid's"]]
    :results [["keys" "(map :key (t/param-keys (accent euclid)))"]]}

   {:group "Settings as data"}
   {:title "One tree, several tctxs"
    :steps [["(def calm (t/tctx riff {:k 2}))" ""]
            ["(def busy (t/tctx riff {:k 7}))" "the tree is shared, the settings aren't"]]
    :results [["calm" "(t/run riff calm)"] ["busy" "(t/run riff busy)"]]}
   {:title "Fit a tctx to another tree"
    :steps [["(def other (notes (transpose (head (cycled scale)))))" "needs :len and :semitones"]
            ["(t/fit! calm other)" "adds them at their defaults, keeps :k 2"]]
    :results [["params" "(:params @calm)"]]}
   {:title "Required params and open ranges"
    :steps [["(defalgo tone \"One pitch, which must be given.\"\n  {:algo {:in [] :out :pitches\n          :params {:p {:type :int :min 0 :max ##Inf :default ##NaN}}}}\n  [p] [p])" "##NaN default: required · ##Inf: no upper bound"]]
    :results [["unset" "(try (t/run (tone) {}) (catch Exception e (.getMessage e)))"]
              ["set" "(t/run (tone) {:p 67})"]]}
   {:title "Look inside: describe and trace"
    :steps [["(t/describe (t/tctx grid))" "one row per param"]]
    :results [["trace" "(mapv :node (t/trace grid {}))"]]}

   {:group "Live"}
   {:title "A live tree, changed while it plays"
    :steps [["(def lctx (t/tctx riff {:dur 1/16}))" ""]
            ["(t/live! :riff riff lctx)" "an endless voice follows :riff"]
            ["(t/set-param! lctx :k 7)" "heard on the next note"]
            ["(t/retree! :riff (notes (transpose (gate euclid (cycled scale)))))" "same tctx, fitted: gains :semitones"]
            ["(t/set-param! lctx :semitones 12)" ""]
            ["(t/stop! :riff)" ""]]
    :results [["params" "(:params @lctx)"]]}
   {:title "A transform: reshape any voice's own notes"
    :steps [["(t/live! :up5 (transpose :nodes) (t/tctx (transpose :nodes) {:semitones 5}))" "reads :nodes, so it only binds the name"]
            ["(m/parse \"[verse: c4 e4 g4]\")" ""]
            ["(m/play :verse :algo :up5)" "the verse, a fourth higher"]]
    :results [["applied" "((wall/algo :up5) (take 3 (m/sq :verse)) nil {:path [:TAA]})"]]}

   {:group "Material"}
   {:title "Generated notes become committed musics text"
    :steps [["(def text (notes->mus (t/run riff {:k 3})))" "readable, editable text"]
            ["(def part (m/parse text))" "now an ordinary repo part"]]
    :results [["text" "text"] ["committed" "(:ids part)"]]}])

(def api
  [["(t/tctx tree)", "(t/tctx tree overrides)" "an atom of {:params :specs}; the tree isn't stored"]
   ["(t/run tree ctx-or-map)" "" "the tree's result; a map's missing keys take their defaults"]
   ["(t/set-param! ctx k v)" "(t/set-params! ctx m)" "checked against the spec"]
   ["(t/fit! ctx tree)" "" "add a tree's missing keys, keep values"]
   ["(t/describe ctx-or-tree)" "(t/trace tree src)" "a table of params; every node's result"]
   ["(t/param-keys tree)" "" "every key a tree reads, with its spec"]
   ["(t/live! name tree ctx)" "(t/retree! name tree) · (t/stop! name)" "bind a name; hear every tctx change on the next note"]
   ["(t/play! tree src)" "" "play once"]
   ["(t/algo :short)" "(t/full-name :short) · (t/short-name 'ns/fn) · (t/algos)" "the registry"]
   ["(defalgo name doc {:algo …} [args] body)" "(t/expose ns/fn …)" "define a new algo; make an annotated fn one"]
   ["(algo … :as :name)" "" "name an instance: keys :name/…"]
   ["(notes->mus parts)" "" "notes as musics text, ready for (m/parse …)"]])

(defn short-value [v]
  (let [s (pr-str (clojure.walk/postwalk #(if (fn? %) 'fn %) v))]
    (if (> (count s) 36) (str (subs s 0 34) " …") s)))

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
        types [:grid :weights :pitches :numbers :durations :onsets :pairs :points :layers :model :strokes :notes :index :any]]
    (for [ty types]
      (str "<tr><td><code>" (name ty) "</code></td><td>"
           (esc (str/join ", " (for [[s e] lib :when (= ty (:out e))] (name s))))
           "</td><td>"
           (esc (str/join ", " (for [[s e] lib :when (some #{ty} (:in e))] (name s))))
           "</td></tr>"))))

(defn recipe-html [n {:keys [title steps results throws?]}]
  (seed/seed! 2026)
  (let [step-out (doall (for [[code note] steps] (let [[v out] (ev code)] {:code code :note note :v v :out out})))
        res      (doall (for [[label code] results] [label (first (ev code))]))
        printed  (apply str (map :out step-out))
        errors   (filter #(and (string? (:v %)) (str/starts-with? (:v %) "error:")) step-out)]
    (doseq [e (when-not throws? errors)] (binding [*out* *err*] (println "STEP ERROR in" title ":" (:v e))))
    (str "<section class=\"recipe\"><h3><span class=\"n\">" n "</span>" (esc title) "</h3><table class=\"code\">"
         (apply str (for [{:keys [code note]} step-out]
                      (str "<tr><td class=\"c\"><code>" (esc code) "</code></td><td class=\"w\">" (esc note) "</td></tr>")))
         "</table>"
         (when (seq printed) (str "<div class=\"res\"><span>printed</span><code>" (esc (str/trimr printed)) "</code></div>"))
         (apply str (for [[label v] res] (str "<div class=\"res\"><span>" (esc label) "</span><code>" (esc (show v)) "</code></div>")))
         "</section>")))

(defn recipes-html []
  (loop [[r & more] recipes n 1 out []]
    (cond (nil? r) (apply str out)
          (:group r) (recur more n (conj out (str "<h2 class=\"grp\">" (esc (:group r)) "</h2>")))
          :else (recur more (inc n) (conj out (recipe-html n r))))))

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
       "<li><b>Params come by name, not by position.</b> Each param is a key in the tctx. Change it with <code>set-param!</code> (or a plain <code>swap!</code>); a validator checks every value against its spec.</li>\n"
       "<li><b>A name makes it live.</b> <code>(t/live! :riff tree ctx)</code> plays the tree endlessly; every later change to the tctx is heard on the next note, and the GUI's Wall window draws a slider for each ranged param.</li>\n</ul>\n"
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

       "<h2>5. Writing an algorithm</h2>\n"
       "<p>Give an ordinary <code>defn</code> an <code>:algo</code> attr-map, then <code>(t/expose ns/the-fn)</code> defines its constructor under the short name. For a new function, <code>defalgo</code> does both (the raw fn stays callable as <code>name*</code>).</p>\n"
       "<table class=\"code\"><tr><td class=\"c\"><code>" (esc "(defn density-grid\n  \"Binary onset grid ...\"\n  {:algo {:short :density :in [:weights] :out :grid\n          :params {:density {:type :double :min 0.0 :max 1.0 :default 0.5\n                             :doc \"fraction of pulses kept\"}}}}\n  [ranks density] ...)") "</code></td><td class=\"w\">the leading args named in <code>:in</code> are children; every later arg is a param, named by the arg itself</td></tr></table>\n"
       "<ul><li><b><code>:params</code></b>: per param a <code>:type</code> (<code>:int :double :ratio :string :keyword :vector :map :fn :bool :any</code>), a <code>:default</code>, and for a number <code>:min</code>/<code>:max</code>. <code>##-Inf</code>/<code>##Inf</code> leave a range end open; a <code>##NaN</code> default makes the param required. <code>:choices</code> limits a string or keyword. Registration refuses an incomplete spec.</li>\n"
       "<li><b>One value per call?</b> <code>:repeat :len</code> calls the fn <code>:len</code> times (a sampler: <code>normal</code>); <code>:pull {:via :value}</code> calls it once for a generator and pulls <code>:len</code> values (a closure: <code>walk</code>, <code>logistic</code>). No wrapper fn needed; <code>:len</code> is a param like any other.</li>\n"
       "<li><b>Children not first?</b> <code>:children [:coll]</code> names the args that are children.</li>\n"
       "<li><b>Keyword args</b> (<code>&amp; {:keys [rotation] :or {rotation 0}}</code>) are params too, their <code>:or</code> the default. A multi-arity fn names the arity to wrap with <code>:arity</code>.</li>\n"
       "<li><b>Keys</b>: a param keeps its bare name unless two different algos in one tree read it with different specs; then each becomes <code>:short.name</code> (recipe " (recipe-number "Different algos, same param name") "). A named instance's are <code>:as/name</code>.</li></ul>\n"

       "<h2 class=\"pb\">6. Recipes (" n-recipes ")</h2>\n"
       "<p>Each recipe is plain Clojure. The grey column is the code and the right column a note on it; results are shown as <code>notes-&gt;mus</code> text where they are notes, which is exactly what <code>(m/parse …)</code> would commit.</p>\n"
       body-recipes

       "<h2 class=\"pb\">7. Parameters and the GUI</h2>\n"
       "<p>The tctx is the GUI's model. The Wall window lists every live name with its tree, and one control per param: a slider when the spec has a finite range, an EDN field otherwise. A value the spec rejects isn't applied; the window says why.</p>\n"
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

       "<h2>8. Limits worth knowing</h2>\n<ul>\n"
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
