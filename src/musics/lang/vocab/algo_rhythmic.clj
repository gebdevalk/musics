(ns musics.lang.vocab.algo-rhythmic
  "musics.lang's own `algo-rhythmic` vocabulary -- a mechanical bridge
   of algo/rhythmic/*.clj's own public API: Euclidean/Fibonacci/prime/
   L-system/Markov rhythm generators (rhythm.clj), plus the ten
   advanced_rhythm.py ports covering Reich phase music (phase-sieve),
   Xenakis sieve theory, polyrhythm/polymeter (poly), rhythm necklaces/
   Vuza canons (necklace), genetic/Markov/RNN rhythm generation
   (stochastic), physical-simulation/natural-process rhythms
   (physical), EMI-style/Oblique-Strategies transforms and groove/
   humanization (transform/micro), data/text sonification
   (sonification), constraint satisfaction (constraint), fractal and
   geometric rhythms (fractal-geometric), and Indian tala/West African
   timeline patterns (world). A handful of optional keyword args on the
   Clojure side (euclidean-rhythm's :rotation, markov-rhythm's
   :initial-state/:states, prime-rhythm's :include-one?) are bridged at
   their required-positional-args-only arity, not exposed here."
  (:require [algo.rhythmic.constraint :as constraint]
            [algo.rhythmic.decompose :as decompose]
            [algo.rhythmic.fractal-geometric :as fractal]
            [algo.rhythmic.micro :as micro]
            [algo.rhythmic.necklace :as necklace]
            [algo.rhythmic.phase-sieve :as phase-sieve]
            [algo.rhythmic.physical :as physical]
            [algo.rhythmic.poly :as poly]
            [algo.rhythmic.rhythm :as rhythm]
            [algo.rhythmic.sonification :as sonification]
            [algo.rhythmic.stochastic :as stochastic]
            [algo.rhythmic.transform :as transform]
            [algo.rhythmic.world :as world]
            [musics.lang.runtime :refer [push! pop-val! builtin callable->fn]]))

(defn vocab []
  (merge
    (builtin "all-interval-rhythm" (fn [ctx] (push! ctx (constraint/all-interval-rhythm (pop-val! ctx)))) "( length -- grid )" "a binary pattern where consecutive-onset intervals are all distinct")
    (builtin "constraint-satisfaction-rhythm" (fn [ctx] (let [length (pop-val! ctx) constraints (pop-val! ctx)] (push! ctx (constraint/constraint-satisfaction-rhythm constraints length))))
             "( constraints length -- grid )" "a binary pattern of length satisfying every predicate in constraints")
    (builtin "isorhythm-strength" (fn [ctx] (let [repetitions (pop-val! ctx) color (pop-val! ctx) talea (pop-val! ctx)] (push! ctx (constraint/isorhythm-strength talea color repetitions))))
             "( talea color repetitions -- pattern )" "isorhythmic beat-strength pattern from a talea/color pairing")

    (builtin "binary-decompose" (fn [ctx] (let [depth (pop-val! ctx) duration (pop-val! ctx)] (push! ctx (decompose/binary-decompose duration depth))))
             "( duration depth -- durations )" "greedily decomposes duration into powers of two, largest first")
    (builtin "split-decompose" (fn [ctx] (let [ratio (pop-val! ctx) depth (pop-val! ctx) duration (pop-val! ctx)] (push! ctx (decompose/split-decompose duration depth ratio))))
             "( duration depth ratio -- durations )" "splits duration into depth pieces, taking a ratio-sized bite each time")

    (builtin "cantor-set-rhythm" (fn [ctx] (let [length (pop-val! ctx) iterations (pop-val! ctx)] (push! ctx (fractal/cantor-set-rhythm iterations length))))
             "( iterations length -- grid )" "the Cantor set as a rhythm")
    (builtin "circle-of-fifths-rhythm" (fn [ctx] (let [pattern-length (pop-val! ctx) notes (pop-val! ctx)] (push! ctx (fractal/circle-of-fifths-rhythm notes pattern-length))))
             "( notes pattern-length -- grid )" "a beat stepping around the circle of fifths")
    (builtin "dragon-curve-rhythm" (fn [ctx] (push! ctx (fractal/dragon-curve-rhythm (pop-val! ctx)))) "( iterations -- grid )" "the dragon-curve folding sequence as a binary rhythm")
    (builtin "golden-ratio-rhythm" (fn [ctx] (let [phi (pop-val! ctx) length (pop-val! ctx)] (push! ctx (fractal/golden-ratio-rhythm length phi))))
             "( length phi -- grid )" "a beat at each position reached by repeatedly stepping by phi")
    (builtin "polygon-rotation-rhythm" (fn [ctx] (let [offset (pop-val! ctx) rotations (pop-val! ctx) sides (pop-val! ctx)] (push! ctx (fractal/polygon-rotation-rhythm sides rotations offset))))
             "( sides rotations offset -- grid )" "a beat wherever a rotating point comes close to a vertex")

    (builtin "humanize-rhythm" (fn [ctx] (let [velocity-variance (pop-val! ctx) timing-variance (pop-val! ctx) timings (pop-val! ctx)]
                                             (push! ctx (micro/humanize-rhythm timings timing-variance velocity-variance))))
             "( timings timing-variance velocity-variance -- timings' )" "adds human-like timing/velocity imperfections")
    (builtin "pocket-groove" (fn [ctx] (let [accent-pattern (pop-val! ctx) pocket-depth (pop-val! ctx) base-pattern (pop-val! ctx)]
                                           (push! ctx (micro/pocket-groove base-pattern pocket-depth accent-pattern))))
             "( base-pattern pocket-depth accent-pattern -- pattern' )" "a laid-back groove from base-pattern")
    (builtin "swing-quantization" (fn [ctx] (let [subdivision (pop-val! ctx) swing-ratio (pop-val! ctx) pattern (pop-val! ctx)]
                                                (push! ctx (micro/swing-quantization pattern swing-ratio subdivision))))
             "( pattern swing-ratio subdivision -- onsets )" "swung onset timings for pattern")

    (builtin "all-binary-necklaces" (fn [ctx] (let [k (pop-val! ctx) n (pop-val! ctx)] (push! ctx (necklace/all-binary-necklaces n k))))
             "( n k -- necklaces )" "one representative pattern per rotation-equivalence class among length-n k-onset patterns")
    (builtin "rhythm-bracelet" (fn [ctx] (push! ctx (necklace/rhythm-bracelet (pop-val! ctx)))) "( pattern -- bracelet )" "all unique rotations AND reversals of pattern")
    (builtin "rhythm-necklace" (fn [ctx] (push! ctx (necklace/rhythm-necklace (pop-val! ctx)))) "( pattern -- necklace )" "all unique rotations of pattern")
    (builtin "rhythmic-tiling" (fn [ctx] (let [length (pop-val! ctx) pattern-b (pop-val! ctx) pattern-a (pop-val! ctx)] (push! ctx (necklace/rhythmic-tiling pattern-a pattern-b length))))
             "( pattern-a pattern-b length -- tiling )" "tries to tile length positions with copies of pattern-a/pattern-b")
    (builtin "vuza-canon" (fn [ctx] (push! ctx (necklace/vuza-canon (pop-val! ctx)))) "( n -- pairs )" "candidate Vuza canon pairs for length n")

    (builtin "clapping-music-duet" (fn [ctx] (let [phase (pop-val! ctx) pattern (pop-val! ctx)] (push! ctx (phase-sieve/clapping-music-duet pattern phase))))
             "( pattern phase -- [voice1 voice2] )" "the two parts of Reich's Clapping Music at phase")
    (builtin "clapping-music-phases" (fn [ctx] (let [total-phases (pop-val! ctx) pattern (pop-val! ctx)] (push! ctx (phase-sieve/clapping-music-phases pattern total-phases))))
             "( pattern total-phases -- phases )" "phase-shifting patterns in the style of Clapping Music")
    (builtin "sieve-from-intervals" (fn [ctx] (let [length (pop-val! ctx) intervals (pop-val! ctx)] (push! ctx (phase-sieve/sieve-from-intervals intervals length))))
             "( intervals length -- grid )" "beats at the cumulative sums of intervals, cycling")
    (builtin "xenakis-sieve" (fn [ctx] (let [length (pop-val! ctx) residues (pop-val! ctx) moduli (pop-val! ctx)] (push! ctx (phase-sieve/xenakis-sieve moduli residues length))))
             "( moduli residues length -- grid )" "a Xenakis sieve binary pattern")

    (builtin "bird-song-rhythm" (fn [ctx] (let [duration (pop-val! ctx) species (pop-val! ctx)] (push! ctx (physical/bird-song-rhythm species duration))))
             "( species duration -- onsets )" "a stylized birdsong's syllable onset timings")
    (builtin "bouncing-ball-rhythm" (fn [ctx] (let [duration (pop-val! ctx) gravity (pop-val! ctx) restitution (pop-val! ctx) initial-height (pop-val! ctx)]
                                                  (push! ctx (physical/bouncing-ball-rhythm initial-height restitution gravity duration))))
             "( initial-height restitution gravity duration -- onsets )" "a bouncing ball's impact timings")
    (builtin "heartbeat-rhythm" (fn [ctx] (let [duration (pop-val! ctx) variability (pop-val! ctx) bpm (pop-val! ctx)] (push! ctx (physical/heartbeat-rhythm bpm variability duration))))
             "( bpm variability duration -- onsets )" "a heartbeat's inter-beat-interval timings")
    (builtin "logistic-map-rhythm" (fn [ctx] (let [threshold (pop-val! ctx) length (pop-val! ctx) x0 (pop-val! ctx) r (pop-val! ctx)]
                                                 (push! ctx (physical/logistic-map-rhythm r x0 length threshold))))
             "( r x0 length threshold -- grid )" "a binary pattern from the logistic map, thresholded")
    (builtin "pendulum-rhythm" (fn [ctx] (let [sample-rate (pop-val! ctx) duration (pop-val! ctx) gravity (pop-val! ctx) length (pop-val! ctx) initial-angle (pop-val! ctx)]
                                             (push! ctx (physical/pendulum-rhythm initial-angle length gravity duration sample-rate))))
             "( initial-angle length gravity duration sample-rate -- onsets )" "a simplified pendulum's downward-swing timings")
    (builtin "rainfall-rhythm" (fn [ctx] (let [duration (pop-val! ctx) intensity (pop-val! ctx)] (push! ctx (physical/rainfall-rhythm intensity duration))))
             "( intensity duration -- onsets )" "raindrop impact timings, a Poisson process")

    (builtin "metric-modulation" (fn [ctx] (let [subdivisions (pop-val! ctx) pattern (pop-val! ctx) ratio (pop-val! ctx) base-tempo (pop-val! ctx)]
                                               (push! ctx (poly/metric-modulation base-tempo ratio pattern subdivisions))))
             "( base-tempo ratio pattern subdivisions -- onsets )" "onset timings after a tempo/subdivision modulation")
    (builtin "nested-tuplets" (fn [ctx] (let [depth (pop-val! ctx) tuplet-ratio (pop-val! ctx) base-pattern (pop-val! ctx)] (push! ctx (poly/nested-tuplets base-pattern tuplet-ratio depth))))
             "( base-pattern tuplet-ratio depth -- pattern' )" "recursively expands base-pattern's beats into nested tuplet groups")
    (builtin "polymeter" (fn [ctx] (let [length (pop-val! ctx) meters (pop-val! ctx)] (push! ctx (poly/polymeter meters length))))
             "( meters length -- layers )" "multiple simultaneous meters")
    (builtin "polyrhythm" (fn [ctx] (let [length (pop-val! ctx) layers (pop-val! ctx)] (push! ctx (poly/polyrhythm layers length))))
             "( layers length -- layers' )" "multiple simultaneous rhythmic layers")

    (builtin "euclidean-rhythm" (fn [ctx] (let [rotation (pop-val! ctx) n (pop-val! ctx) k (pop-val! ctx)] (push! ctx (rhythm/euclidean-rhythm k n :rotation rotation))))
             "( k n rotation -- grid )" "distributes k beats evenly among n pulses (Bjorklund's algorithm)")
    (builtin "fibonacci-rhythm" (fn [ctx] (let [ab (pop-val! ctx) length (pop-val! ctx)] (push! ctx (rhythm/fibonacci-rhythm length ab))))
             "( length [a b] -- grid )" "onsets at Fibonacci-numbered positions, seeded by [a b]")
    (builtin "lindenmayer-rhythm" (fn [ctx] (let [length (pop-val! ctx) iterations (pop-val! ctx) rules (pop-val! ctx) axiom (pop-val! ctx)]
                                                (push! ctx (rhythm/lindenmayer-rhythm axiom rules iterations length))))
             "( axiom rules iterations length -- grid )" "expands axiom through an L-system's rules into a rhythm")
    (builtin "markov-rhythm" (fn [ctx] (let [transition-matrix (pop-val! ctx) length (pop-val! ctx)] (push! ctx (rhythm/markov-rhythm length transition-matrix))))
             "( length transition-matrix -- onsets )" "walks transition-matrix to produce length onset values")
    (builtin "prime-rhythm" (fn [ctx] (push! ctx (rhythm/prime-rhythm (pop-val! ctx)))) "( length -- grid )" "onsets at prime-numbered positions")

    (builtin "data-to-rhythm" (fn [ctx] (let [smoothing (pop-val! ctx) threshold-type (pop-val! ctx) data (pop-val! ctx)] (push! ctx (sonification/data-to-rhythm data threshold-type smoothing))))
             "( data threshold-type smoothing -- grid )" "a binary pattern from any numerical data sequence")
    (builtin "stock-market-rhythm" (fn [ctx] (let [lookback (pop-val! ctx) prices (pop-val! ctx)] (push! ctx (sonification/stock-market-rhythm prices lookback))))
             "( prices lookback -- grid )" "a rhythm from price trend reversals")
    (builtin "text-to-rhythm" (fn [ctx] (let [mode (pop-val! ctx) text (pop-val! ctx)] (push! ctx (sonification/text-to-rhythm text mode))))
             "( text mode -- grid )" "a rhythmic pattern from text's own linguistic structure")

    (builtin "cloud-rhythm" (fn [ctx] (let [time-std (pop-val! ctx) duration (pop-val! ctx) num-events (pop-val! ctx)] (push! ctx (stochastic/cloud-rhythm num-events duration time-std))))
             "( num-events duration time-std -- onsets )" "a Xenakis-style granular cloud of event timings")
    (builtin "crossover-genomes" (fn [ctx] (let [crossover-point (pop-val! ctx) b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (stochastic/crossover-genomes a b crossover-point))))
             "( a b crossover-point -- [child1 child2] )" "single-point crossover of two equal-length patterns")
    (builtin "genetic-rhythm" (fn [ctx] (let [fitness-fn (callable->fn ctx (pop-val! ctx)) generations (pop-val! ctx) pattern-length (pop-val! ctx) population-size (pop-val! ctx)]
                                            (push! ctx (stochastic/genetic-rhythm population-size pattern-length generations fitness-fn))))
             "( population-size pattern-length generations fitness-fn -- pattern )" "evolves a rhythmic pattern, scored by fitness-fn")
    (builtin "markov-chain-rhythm" (fn [ctx] (let [length (pop-val! ctx) initial-state (pop-val! ctx) transitions (pop-val! ctx)] (push! ctx (stochastic/markov-chain-rhythm transitions initial-state length))))
             "( transitions initial-state length -- states )" "higher-order Markov chain rhythm generation")
    (builtin "mutate-genome" (fn [ctx] (let [mutation-rate (pop-val! ctx) pattern (pop-val! ctx)] (push! ctx (stochastic/mutate-genome pattern mutation-rate))))
             "( pattern mutation-rate -- pattern' )" "flips each bit of pattern independently with probability mutation-rate")
    (builtin "rnn-rhythm" (fn [ctx] (let [iterations (pop-val! ctx) weights (pop-val! ctx) seed-pattern (pop-val! ctx)] (push! ctx (stochastic/rnn-rhythm seed-pattern weights iterations))))
             "( seed-pattern weights iterations -- pattern )" "deterministic toy-RNN rhythm continuation")
    (builtin "stochastic-rhythm" (fn [ctx] (let [density (pop-val! ctx) length (pop-val! ctx) params (pop-val! ctx) distribution (pop-val! ctx)]
                                               (push! ctx (stochastic/stochastic-rhythm distribution params length density))))
             "( distribution params length density -- grid )" "a binary pattern placed according to a named distribution")

    (builtin "emi-style-variation" (fn [ctx] (let [similarity (pop-val! ctx) pattern (pop-val! ctx)] (push! ctx (transform/emi-style-variation pattern similarity))))
             "( pattern similarity -- pattern' )" "varies pattern in the style of David Cope's EMI")
    (builtin "oblique-strategies" (fn [ctx] (push! ctx transform/oblique-strategies)) "( -- name->fn )" "Brian Eno's Oblique Strategies, named transforms over a pattern")
    (builtin "oblique-strategies-transform" (fn [ctx] (let [strategy (pop-val! ctx) pattern (pop-val! ctx)] (push! ctx (transform/oblique-strategies-transform pattern strategy))))
             "( pattern strategy -- pattern' )" "applies one named oblique-strategies transform to pattern")

    (builtin "african-polyrhythm" (fn [ctx] (let [base-length (pop-val! ctx) layers (pop-val! ctx)] (push! ctx (world/african-polyrhythm layers base-length))))
             "( layers base-length -- layers' )" "layers interlocking West African rhythmic patterns")
    (builtin "bell-pattern" (fn [ctx] (let [pattern-name (pop-val! ctx) meter (pop-val! ctx)] (push! ctx (world/bell-pattern meter pattern-name))))
             "( meter pattern-name -- grid )" "a West African bell (timeline) pattern for meter")
    (builtin "common-talas" (fn [ctx] (push! ctx world/common-talas)) "( -- talas )" "name -> {:matras :vibhags :tali-khali}, the standard Indian talas")
    (builtin "cross-rhythm-3-2" (fn [ctx] (push! ctx (world/cross-rhythm-3-2 (pop-val! ctx)))) "( length -- [layer1 layer2] )" "the classic 3:2 cross-rhythm (hemiola)")
    (builtin "djembe-pattern" (fn [ctx] (let [length (pop-val! ctx) technique (pop-val! ctx)] (push! ctx (world/djembe-pattern technique length))))
             "( technique length -- strokes )" "a djembe stroke sequence for technique")
    (builtin "konnakol-pattern" (fn [ctx] (let [pattern (pop-val! ctx) syllables (pop-val! ctx)] (push! ctx (world/konnakol-pattern syllables pattern))))
             "( syllables pattern -- syllables' )" "South Indian vocal-percussion syllables for a binary rhythm pattern")
    (builtin "named-bell-patterns" (fn [ctx] (push! ctx world/named-bell-patterns)) "( -- patterns )" "every named West African bell pattern")
    (builtin "tala-pattern" (fn [ctx] (let [laya (pop-val! ctx) tala-name (pop-val! ctx)] (push! ctx (world/tala-pattern tala-name laya))))
             "( tala-name laya -- structure )" "the full matra-by-matra structure of tala-name's cycle, at tempo laya")
    (builtin "theka-pattern" (fn [ctx] (let [instrument (pop-val! ctx) tala-name (pop-val! ctx)] (push! ctx (world/theka-pattern tala-name instrument))))
             "( tala-name instrument -- pattern )" "a simplified binary stroke pattern (theka) for tala-name")))
