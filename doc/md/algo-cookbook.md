# Algo cookbook

Composing `musics/algo/` generators with `musics.algo.tree` at the Clojure REPL: trees, their settings, your own algorithms, and live playback.

musics · every recipe below was run for real, each starting from seed 2026; results are copied from that run

## 1. The idea

Two separate things. A **tree** says *what* is computed: algorithms nested as ordinary Clojure calls. A **tctx** holds the *settings* it runs with: an atom of params, derived from a tree but never holding it.

- **Composition is nesting.** `(zip (pulses->durations euclid) (cycle scale))`: each algo takes its children and returns a node. A bare algo name is a leaf, `euclid` = `(euclid)`.

- **Algorithms describe themselves.** Each carries `:algo` metadata: what its children must be (`:in`), what it makes (`:out`), and a spec per param: type, range, default. Nothing else about the function changes.

- **Wrong shapes fail when built.** The child count and types are checked the moment you write the expression, not while it plays.

- **Params come by name, not by position.** Each param is a key in the tctx. Change it with `setp!`, an `assoc` for its params (or a plain `swap!`); a validator checks every value against its spec.

- **A name makes it live.** `(t/live! :riff tree tctx)` plays the tree endlessly; every later change to the tctx is heard on the next note, and the GUI's Wall window draws a slider for each ranged param.

Trees are Clojure code, run from a namespace that requires them: `(require '[musics.algo.tree :as t :refer [defalgo]] '[musics.algo.tree.lib :refer :all])`. The tools (`take`, `map`, `rotate` …) are in `musics.algo.tree.tools` and take `clojure.core`'s names: refer them in a namespace that excludes those (this page does), or call `tools/take`. In mu the same algos are words, see the raw algo cookbook.

## 2. The API on one page

| Call | Also | Does |
|---|---|---|
| `(t/tctx tree)` | `(t/tctx tree overrides)` | {:params-atom}, its settings under the names an @^{ } writes ("onsets"); the tree isn't stored, its specs are |
| `(t/run tree src)` | `(t/run tree src chain)` | the tree's result; src a tctx, a map or a vector of them nearest first, then -- for key and meter -- the music's contexts in chain, then the defaults; every value checked |
| `(t/setp! tctx k v)` | `(t/setp! tctx m) · (t/unsetp! tctx k)` | checked against the spec; unset falls through again |
| `(t/params tctx)` | `(t/values tctx) · (t/specs tctx)` | every param's value / what it sets itself / the specs |
| `(t/fit! tctx tree)` |  | add a tree's missing specs, keep values |
| `(t/describe tctx-or-tree)` | `(t/trace tree src)` | a table of params; every node's result |
| `(t/param-keys tree)` |  | every key a tree reads, with its spec |
| `(t/live! name tree tctx)` | `(t/retree! name tree) · (t/stop! name)` | bind a name; hear every tctx change on the next note |
| `(t/play! tree src)` |  | play once |
| `(build-tree)` | `(build-tree tree tctx) · (build-tree :repl)` | compose by drag and drop (or step by step at the REPL); returns [tree tctx] |
| `(gui tree)` | `(gui tctx) · (gui tree tctx)` | a settings window: a control per param, result preview, Play once / Live as; returns the tctx |
| `(t/algo :short)` | `(t/full-name :short) · (t/short-name 'ns/fn) · (t/algos)` | the registry |
| `(defalgo name doc {:algo …} [args] body)` | `(t/expose ns/fn …)` | define a new algo; make an annotated fn one |
| `(algo … :as :name)` |  | name an instance: keys :name/… |
| `(notes->mus parts)` |  | notes as musics text, ready for (store/read! …) |

## 3. The ready-made algos

Generated from the registry by introspection, so it matches the code. `in → out` gives the child types and the result type; `same` means its first child's type.

**Exposed from `musics/algo/`** (their own `:algo` metadata):

| Name | Function | In → out | Params (range = default) |
|---|---|---|---|
| `african` | `musics.algo.rhythmic.world/african` | ` → layer` | `layers 1..8 = 3, base-length 1..96 = 12` |
| `all-interval` | `musics.algo.rhythmic.process/all-interval` | ` → pulse` | `length 1..256 = 12` |
| `arcsine` | `musics.algo.numeric.random/arcsine` | ` → number` | `lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, len 1..1024 = 16` |
| `bell` | `musics.algo.rhythmic.world/bell` | ` → pulse` | `meter vector = [12 8], pattern-name string = "standard"` |
| `beta` | `musics.algo.numeric.random/beta` | ` → number` | `a 0.01..##Inf = 2.0, b 0.01..##Inf = 2.0, len 1..1024 = 16` |
| `biased-walk` | `musics.algo.numeric.walk/biased-walk` | ` → number` | `start ##-Inf..##Inf = 60.0, step-bound 0.0..##Inf = 2.0, bias 0.0..1.0 = 0.5, clip-lo ##-Inf..##Inf = ##-Inf, clip-hi ##-Inf..##Inf = ##Inf, boundary keyword = :clamp, len 1..1024 = 16` |
| `birdsong` | `musics.algo.rhythmic.nature/birdsong` | ` → onset` | `species string = "sparrow", duration 0.0..##Inf = 5.0` |
| `bisect` | `musics.algo.rhythmic.decompose/bisect` | ` → duration` | `duration 1/64..16 = 1, depth 1..8 = 2` |
| `bits` | `musics.algo.rhythmic.pattern/bits` | ` → pulse` | `number 0..##Inf = 13, length 1..64 = 8` |
| `bounce` | `musics.algo.rhythmic.nature/bounce` | ` → onset` | `initial-height 0.0..100.0 = 2.0, restitution 0.0..0.99 = 0.7, gravity 0.1..100.0 = 9.8, duration 0.0..##Inf = 5.0` |
| `bracelet` | `musics.algo.rhythmic.sieve/bracelet` | `pulse → pulse` | `index 0..63 = 1` |
| `ca` | `musics.algo.rhythmic.sieve/ca` | ` → pulse` | `rule 0..255 = 90, width 3..64 = 16, steps 1..64 = 8, start keyword = :single` |
| `cantor` | `musics.algo.rhythmic.pattern/cantor` | ` → pulse` | `iterations 0..8 = 3, length 1..729 = 27` |
| `cauchy` | `musics.algo.numeric.random/cauchy` | ` → number` | `median ##-Inf..##Inf = 0.0, scale 0.0..##Inf = 1.0, len 1..1024 = 16` |
| `cfrac` | `musics.algo.rhythmic.pattern/cfrac` | ` → pulse` | `fraction 0.0..##Inf = 3.141592653589793, length 1..256 = 16` |
| `chain` | `musics.algo.numeric.markov/chain` | ` → number` | `transitions map = {0 {4 2, 3 1}, 4 {0 1, 5 1}, 3 {0 …, start-state -128..128 = 0, len 1..1024 = 16` |
| `chain-rhythm` | `musics.algo.rhythmic.process/chain-rhythm` | ` → pulse` | `transitions map = {[1 0] {[0 1] 0.7, [1 1] 0.3}, [0 …, initial-state vector = [1 0], length 1..256 = 16` |
| `chi-square` | `musics.algo.numeric.random/chi-square` | ` → number` | `dof 1..100 = 3, len 1..1024 = 16` |
| `choose-from` | `musics.algo.numeric.random/choose-from` | `any → same` |  |
| `choose-n` | `musics.algo.numeric.random/choose-n` | `any → same` | `how-many 0..256 = 4` |
| `classes->pitches` | `musics.algo.melodic.serial/classes->pitches` | `number → pitch` | `root 24..119 = 60` |
| `cloud` | `musics.algo.rhythmic.process/cloud` | ` → onset` | `num-events 1..256 = 16, duration 0.0..##Inf = 4.0, time-std 0.0..1.0 = 0.1` |
| `constrained` | `musics.algo.melodic.melody/constrained` | `pitch → pitch` | `length 1..256 = 16, constraints vector = [fn], start any = nil` |
| `counterpoint` | `musics.algo.counterpoint.imitation/counterpoint` | `pitch → part` | `pitch-range vector = [55 84], rules map = {:no-parallel-fifths? true, :no-pa …, beat-durations vector = [0.5 0.5 1.0 0.5 0.5 1.0 0.5 0.5], voice-specs vector = [{:motif {:steps [0 2 -1 2], :dura …` |
| `counts->pulses` | `musics.algo.bridge/counts->pulses` | `weight number → pulse` |  |
| `crossover` | `musics.algo.rhythmic.process/crossover` | `pulse pulse → pulse` | `crossover-point 0..256 = 4, child 0..1 = 0` |
| `csp` | `musics.algo.rhythmic.process/csp` | ` → pulse` | `constraints vector = [fn fn], length 1..16 = 8` |
| `cycle` | `musics.algo.tree.tools/cycle*` | `any → same` |  |
| `cyclic` | `musics.algo.numeric.walk/cyclic` | `any → same` | `len 1..1024 = 16` |
| `data-rhythm` | `musics.algo.rhythmic.data/data-rhythm` | `number → pulse` | `threshold-type string = "median", smoothing 1..16 = 1` |
| `deep-shuffle` | `musics.algo.numeric.random/deep-shuffle` | `any → same` | `depth 0..16 = 16` |
| `degrees->pitches` | `musics.algo.bridge/degrees->pitches` | `number → pitch` | `key string = "C.major", octave 0..8 = 4` |
| `djembe` | `musics.algo.rhythmic.world/djembe` | ` → leaf` | `technique string = "basic", length 1..64 = 8` |
| `dragon` | `musics.algo.rhythmic.pattern/dragon` | ` → pulse` | `iterations 0..10 = 4` |
| `drums` | `musics.algo.rhythmic.drums/drums` | ` → part` | `style keyword = :rock, bars 1..64 = 8, density 0.0..1.0 = 0.7, seed 0..1000000 = 1` |
| `duet` | `musics.algo.rhythmic.sieve/duet` | `pulse → layer` | `phase 0..64 = 1` |
| `emi` | `musics.algo.rhythmic.feel/emi` | `pulse → pulse` | `similarity 0.0..1.0 = 0.7` |
| `euclid` | `musics.algo.rhythmic.pattern/euclid` | ` → pulse` | `onsets 0..32 = 3, pulses 1..32 = 8` |
| `exponential` | `musics.algo.numeric.random/exponential` | ` → number` | `mean 0.0..##Inf = 1.0, len 1..1024 = 16` |
| `falling` | `musics.algo.numeric.random/falling` | ` → number` | `lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, bias 0.0..1.0 = 0.5, len 1..1024 = 16` |
| `fibonacci` | `musics.algo.rhythmic.pattern/fibonacci` | ` → pulse` | `length 1..256 = 16` |
| `fifths-rhythm` | `musics.algo.rhythmic.pattern/fifths-rhythm` | ` → pulse` | `notes 1..48 = 12, pattern-length 1..256 = 24` |
| `filter` | `musics.algo.tree.tools/filter*` | `any → same` | `fn fn = fn` |
| `gamma` | `musics.algo.numeric.random/gamma` | ` → number` | `shape 0.0..##Inf = 2.0, scale 0.0..##Inf = 1.0, len 1..1024 = 16` |
| `genetic` | `musics.algo.rhythmic.process/genetic` | ` → pulse` | `population-size 2..512 = 20, pattern-length 1..256 = 16, generations 0..500 = 20, fitness-fn fn required, mutation-rate 0.0..1.0 = 0.1, elitism 0.0..1.0 = 0.1` |
| `glide` | `musics.algo.numeric.walk/glide` | ` → number` | `initial ##-Inf..##Inf = 60.0, inertia 0.0..1.0 = 0.8, step 0.0..##Inf = 0.5, len 1..1024 = 16, target ##-Inf..##Inf = 72.0` |
| `golden` | `musics.algo.rhythmic.pattern/golden` | ` → pulse` | `length 1..256 = 34, phi 1.0..3.0 = 1.61803398875` |
| `grammar` | `musics.algo.melodic.melody/grammar` | ` → pitch` | `rules map = {S [[A B] [B A S]], A [[60 64] [67 …, terminals any = #{65 62 60 69 "REST" 64 67}, start any = S, max-depth 1..32 = 10, remove-rests? bool = true` |
| `heartbeat` | `musics.algo.rhythmic.nature/heartbeat` | ` → onset` | `bpm 20.0..240.0 = 72.0, variability 0.0..0.5 = 0.1, duration 0.0..##Inf = 10.0` |
| `hemiola` | `musics.algo.rhythmic.world/hemiola` | ` → layer` | `length 1..96 = 12` |
| `henon` | `musics.algo.numeric.chaos.henon/henon` | ` → point` | `a 0.0..2.0 = 1.4, b 0.0..1.0 = 0.3, x0 -1.0..1.0 = 0.1, y0 -1.0..1.0 = 0.1, len 1..1024 = 16` |
| `hi-emph` | `musics.algo.numeric.random/hi-emph` | ` → number` | `lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, len 1..1024 = 16` |
| `indisp` | `musics.algo.rhythmic.indispensability/indisp` | ` → weight` | `subdivisions vector = [2 2 3]` |
| `infra` | `musics.algo.melodic.serial/infra` | `pitch pitch → pitch` |  |
| `inter` | `musics.algo.melodic.serial/inter` | `pitch pitch → pitch` |  |
| `interval-sieve` | `musics.algo.rhythmic.sieve/interval-sieve` | ` → pulse` | `intervals vector = [2 3], length 1..256 = 16` |
| `inverse-gamma` | `musics.algo.numeric.random/inverse-gamma` | ` → number` | `shape 0.0..##Inf = 3.0, scale 0.0..##Inf = 1.0, len 1..1024 = 16` |
| `iso-strength` | `musics.algo.rhythmic.process/iso-strength` | `pulse → weight` | `color vector = [0 1], repetitions 1..16 = 3` |
| `konnakol` | `musics.algo.rhythmic.world/konnakol` | `pulse → stroke` | `syllables vector = ["Ta" "Ka" "Di" "Mi" "Tom" "Nam"]` |
| `laplace` | `musics.algo.numeric.random/laplace` | ` → number` | `mean ##-Inf..##Inf = 0.0, scale 0.0..##Inf = 1.0, len 1..1024 = 16` |
| `leaves->durations` | `musics.algo.bridge/leaves->durations` | `leaf → duration` |  |
| `leaves->pitches` | `musics.algo.bridge/leaves->pitches` | `leaf → pitch` |  |
| `linear` | `musics.algo.numeric.random/linear` | ` → number` | `lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, rising? bool = true, len 1..1024 = 16` |
| `lo-emph` | `musics.algo.numeric.random/lo-emph` | ` → number` | `lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, len 1..1024 = 16` |
| `log-normal` | `musics.algo.numeric.random/log-normal` | ` → number` | `mu ##-Inf..##Inf = 0.0, sigma 0.0..##Inf = 0.5, len 1..1024 = 16` |
| `logistic` | `musics.algo.numeric.chaos.logistic/logistic` | ` → number` | `r 0.0..4.0 = 3.8, x 0.0..1.0 = 0.5, len 1..1024 = 16` |
| `logistic-grid` | `musics.algo.rhythmic.nature/logistic-grid` | ` → pulse` | `r 0.0..4.0 = 3.9, x0 0.0..1.0 = 0.5, length 1..256 = 16, threshold 0.0..1.0 = 0.5` |
| `lorenz` | `musics.algo.numeric.chaos.lorenz/lorenz` | ` → point` | `sigma 0.0..50.0 = 10.0, rho 0.0..100.0 = 28.0, beta 0.0..10.0 = 2.6666666666666665, x0 -50.0..50.0 = 1.0, y0 -50.0..50.0 = 1.0, z0 0.0..100.0 = 1.0, dt 0.001..0.1 = 0.01, len 1..1024 = 16` |
| `lsys-melody` | `musics.algo.melodic.melody/lsys-melody` | ` → pitch` | `axiom string = "A", rules map = {"A" "AB", "B" "CA", "C" "A"}, note-map map = {\A 60, \B 64, \C 67}, iterations 0..12 = 5, max-notes 1..256 = 16` |
| `lsys-rhythm` | `musics.algo.rhythmic.pattern/lsys-rhythm` | ` → pulse` | `axiom string = "A", rules map = {"A" "AB", "B" "A"}, iterations 0..12 = 5, length 1..256 = 16` |
| `map` | `musics.algo.tree.tools/map*` | `any → same` | `fn fn = fn` |
| `markov-gen` | `musics.algo.melodic.melody/markov-gen` | `model → pitch` | `length 1..256 = 16, opening any = nil` |
| `markov-train` | `musics.algo.melodic.melody/markov-train` | `pitch → model` | `order 1..4 = 1` |
| `mean-emph` | `musics.algo.numeric.random/mean-emph` | ` → number` | `lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, len 1..1024 = 16` |
| `metric-mod` | `musics.algo.rhythmic.meter/metric-mod` | `pulse → onset` | `base-tempo 30..300 = 100, ratio 1/16..16 = 3/2, subdivisions 1..16 = 4` |
| `modular` | `musics.algo.rhythmic.pattern/modular` | ` → pulse` | `modulus 1..64 = 7, multiplier 0..64 = 3, length 1..256 = 21, offset 0..64 = 0` |
| `modulating` | `musics.algo.melodic.melody/modulating` | ` → pitch` | `segments vector = [[[:C :major] 8] [[:A :minor] 8]], constraints vector = [fn]` |
| `mutate` | `musics.algo.rhythmic.process/mutate` | `pulse → pulse` | `mutation-rate 0.0..1.0 = 0.1` |
| `mute` | `musics.algo.tree.tools/mute*` | `leaf → leaf` | `fn fn = fn` |
| `necklaces` | `musics.algo.rhythmic.sieve/necklaces` | ` → pulse` | `pulses 1..16 = 8, onsets 0..16 = 3, index 0..1023 = 0` |
| `normal` | `musics.algo.numeric.random/normal` | ` → number` | `mean ##-Inf..##Inf = 0.0, stdev 0.0..##Inf = 1.0, len 1..1024 = 16` |
| `numbers->durations` | `musics.algo.bridge/numbers->durations` | `number → duration` | `from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, lo 1/64..4 = 1/16, hi 1/64..4 = 1/2, quantum 1/128..1 = 1/32` |
| `numbers->instruments` | `musics.algo.bridge/numbers->instruments` | `number → instrument` | `from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, palette vector = []` |
| `numbers->pitches` | `musics.algo.bridge/numbers->pitches` | `number → pitch` | `from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, lo 24..119 = 48, hi 24..119 = 84` |
| `numbers->volumes` | `musics.algo.bridge/numbers->volumes` | `number → volume` | `from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, lo 0..100 = 40.0, hi 0..100 = 80.0` |
| `oblique` | `musics.algo.rhythmic.feel/oblique` | `pulse → pulse` | `strategy string = "random"` |
| `only` | `musics.algo.numeric.random/only` | `any → same` | `notes vector = [0 2 4]` |
| `onsets->durations` | `musics.algo.bridge/onsets->durations` | `onset → duration` | `unit 1/64..4 = 1/4, quantum 1/128..1 = 1/32` |
| `pendulum` | `musics.algo.rhythmic.nature/pendulum` | ` → onset` | `initial-angle 0.0..3.14 = 0.3, length 0.01..100.0 = 1.0, gravity 0.1..100.0 = 9.8, duration 0.0..##Inf = 5.0, sample-rate 1.0..10000.0 = 100.0` |
| `phases` | `musics.algo.rhythmic.sieve/phases` | `pulse → layer` | `total-phases 1..64 = 12` |
| `pink` | `musics.algo.numeric.walk/pink` | ` → number` | `dice 1..16 = 5, lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, len 1..1024 = 16` |
| `pocket` | `musics.algo.rhythmic.feel/pocket` | `leaf → leaf` | `pocket-depth 0.0..0.5 = 0.05` |
| `points->durations` | `musics.algo.bridge/points->durations` | `point → duration` | `axis 0..2 = 0, from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, lo 1/64..4 = 1/16, hi 1/64..4 = 1/2, quantum 1/128..1 = 1/32` |
| `points->instruments` | `musics.algo.bridge/points->instruments` | `point → instrument` | `axis 0..2 = 0, from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, palette vector = []` |
| `points->pitches` | `musics.algo.bridge/points->pitches` | `point → pitch` | `axis 0..2 = 0, from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, key string = "C.major", octave 0..8 = 4, octaves 1..4 = 2` |
| `points->volumes` | `musics.algo.bridge/points->volumes` | `point → volume` | `axis 0..2 = 0, from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, lo 0..100 = 40.0, hi 0..100 = 80.0` |
| `poisson` | `musics.algo.rhythmic.process/poisson` | ` → onset` | `rate 0.01..##Inf = 2.0, duration 0.0..##Inf = 4.0` |
| `polations` | `musics.algo.melodic.serial/polations` | `pitch → pitch` | `infra vector = [], inter vector = [], ultra vector = [], ultra-after-last? bool = false` |
| `polygon` | `musics.algo.rhythmic.pattern/polygon` | ` → pulse` | `sides 1..32 = 5, rotations 1..256 = 8, offset 0.0..1.0 = 0.0` |
| `polymeter` | `musics.algo.rhythmic.meter/polymeter-layers` | ` → layer` | `meters vector = [[3 4] [4 4]], length 1..256 = 12` |
| `polyrhythm` | `musics.algo.rhythmic.meter/polyrhythm` | ` → layer` | `layers vector = [[3 8] [2 8]], length 1..256 = 24` |
| `power` | `musics.algo.rhythmic.indispensability/power` | `weight → weight` | `adherence -1.0..1.0 = 0.5` |
| `primes` | `musics.algo.rhythmic.pattern/primes` | ` → pulse` | `length 1..256 = 16, include-one? bool = true` |
| `pulses->articulations` | `musics.algo.bridge/pulses->articulations` | `pulse → articulation` | `accent keyword = :metric, pulse 1/64..4 = 1/16, meter any = "4/4", levels vector = [:ghost nil :accent :marcato]` |
| `pulses->durations` | `musics.algo.bridge/pulses->durations` | `pulse → duration` | `pulse 1/64..4 = 1/16` |
| `pulses->volumes` | `musics.algo.bridge/pulses->volumes` | `pulse → volume` | `accent keyword = :metric, pulse 1/64..4 = 1/16, meter any = "4/4", lo 0..100 = 40.0, hi 0..100 = 80.0` |
| `rain` | `musics.algo.rhythmic.nature/rain` | ` → onset` | `intensity 0.01..1.0 = 0.5, duration 0.0..##Inf = 10.0` |
| `random-onsets` | `musics.algo.rhythmic.process/random-onsets` | ` → onset` | `beat-duration 0.0..##Inf = 0.25, num-beats 1..256 = 16, density 0.0..1.0 = 0.5` |
| `remove` | `musics.algo.tree.tools/remove*` | `any → same` | `fn fn = fn` |
| `rescale` | `musics.algo.tree.tools/rescale*` | `any → same` | `from-lo ##-Inf..##Inf = 0.0, from-hi ##-Inf..##Inf = 1.0, to-lo ##-Inf..##Inf = 0.0, to-hi ##-Inf..##Inf = 1.0` |
| `reverse` | `musics.algo.tree.tools/reverse*` | `any → same` |  |
| `rising` | `musics.algo.numeric.random/rising` | ` → number` | `lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, bias 0.0..1.0 = 0.5, len 1..1024 = 16` |
| `rnn` | `musics.algo.rhythmic.process/rnn` | `pulse → pulse` | `weights vector = [[0.1 0.8 -0.3] [-0.2 0.5 0.7] [0. …, iterations 0..256 = 8` |
| `rotate` | `musics.algo.tree.tools/rotate*` | `any → same` | `steps -256..256 = 1` |
| `row` | `musics.algo.melodic.serial/row` | ` → number` | `kind keyword = :random` |
| `row-form` | `musics.algo.melodic.serial/row-form` | `number → number` | `form keyword = :P, transposition 0..11 = 0` |
| `shuffle` | `musics.algo.tree.tools/shuffle*` | `any → same` |  |
| `sieve` | `musics.algo.rhythmic.sieve/sieve` | ` → pulse` | `moduli vector = [3 4], residues vector = [[0 1] [2]], length 1..256 = 12` |
| `species` | `musics.algo.counterpoint.jeppesen/species` | `pitch → part` | `voices 2..4 = 2, kind 1..5 = 1, cantus-in keyword = :auto, mode keyword = :auto, seed 0..1000000 = 1` |
| `split` | `musics.algo.rhythmic.decompose/split` | ` → duration` | `duration 1/64..16 = 1, depth 1..32 = 4, ratio 1/16..15/16 = 1/2` |
| `sputter` | `musics.algo.numeric.random/sputter` | `any → same` | `prob 0.0..1.0 = 0.25, max-length 1..1024 = 100` |
| `stochastic` | `musics.algo.rhythmic.process/stochastic` | ` → pulse` | `distribution string = "gaussian", length 1..256 = 16, density 0.0..1.0 = 0.5, mean any = nil, std any = nil, decay 0.0..100.0 = 2.0` |
| `stretch` | `musics.algo.tree.tools/stretch*` | `any → same` | `factor 1/16..16 = 1` |
| `strokes->durations` | `musics.algo.bridge/strokes->durations` | `stroke → duration` | `pulse 1/64..4 = 1/16` |
| `student-t` | `musics.algo.numeric.random/student-t` | ` → number` | `dof 1..100 = 5, len 1..1024 = 16` |
| `swing` | `musics.algo.rhythmic.feel/swing` | `pulse → onset` | `swing-ratio 0.0..1.0 = 0.6, subdivision 1..8 = 2` |
| `take` | `musics.algo.tree.tools/take*` | `any → same` | `len 0..256 = 16` |
| `tala` | `musics.algo.rhythmic.world/tala` | ` → weight` | `tala-name string = "teental"` |
| `tendency` | `musics.algo.numeric.walk/tendency` | ` → number` | `len 1..1024 = 16, lo-start ##-Inf..##Inf = 48.0, lo-end ##-Inf..##Inf = 66.0, hi-start ##-Inf..##Inf = 84.0, hi-end ##-Inf..##Inf = 66.0` |
| `text-rhythm` | `musics.algo.rhythmic.data/text-rhythm` | ` → weight` | `text string = "Hello world this is rhythm.", mode string = "syllables"` |
| `theka` | `musics.algo.rhythmic.world/theka` | ` → pulse` | `tala-name string = "teental", instrument string = "tabla"` |
| `tiling` | `musics.algo.rhythmic.sieve/tiling` | `pulse pulse → pulse` | `length 1..256 = 12` |
| `tilt` | `musics.algo.rhythmic.indispensability/tilt` | `weight → weight` | `adherence -1.0..1.0 = 0.5` |
| `time-points->durations` | `musics.algo.melodic.serial/time-points->durations` | `number → duration` | `unit 1/64..4 = 1/16` |
| `trend-rhythm` | `musics.algo.rhythmic.data/trend-rhythm` | `number → weight` | `lookback 1..64 = 5` |
| `triangular` | `musics.algo.numeric.random/triangular` | ` → number` | `lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, mode ##-Inf..##Inf = 0.5, len 1..1024 = 16` |
| `tuplets` | `musics.algo.rhythmic.meter/tuplets` | `pulse → pulse` | `tuplet-ratio 1/16..16 = 3/2, depth 0..4 = 1` |
| `ultra` | `musics.algo.melodic.serial/ultra` | `pitch pitch → pitch` |  |
| `uniform` | `musics.algo.numeric.random/uniform` | ` → number` | `a ##-Inf..##Inf = 0.0, b ##-Inf..##Inf = 1.0, len 1..1024 = 16` |
| `walk` | `musics.algo.numeric.walk/walk` | ` → number` | `start ##-Inf..##Inf = 60.0, step-bound 0.0..##Inf = 2.0, clip-lo ##-Inf..##Inf = ##-Inf, clip-hi ##-Inf..##Inf = ##Inf, boundary keyword = :clamp, len 1..1024 = 16` |
| `weibull` | `musics.algo.numeric.random/weibull` | ` → number` | `shape 0.0..##Inf = 1.5, scale 0.0..##Inf = 1.0, len 1..1024 = 16` |
| `weights->articulations` | `musics.algo.bridge/weights->articulations` | `weight → articulation` | `levels vector = [:ghost nil :accent :marcato]` |
| `weights->pulses` | `musics.algo.bridge/weights->pulses` | `weight → pulse` | `density 0.0..1.0 = 0.5` |
| `weights->volumes` | `musics.algo.bridge/weights->volumes` | `weight → volume` | `lo 0..100 = 40.0, hi 0..100 = 80.0` |

**Defined in `musics.algo.tree.lib`**:

| Name | In → out | Params (range = default) | Does |
|---|---|---|---|
| `+part` | `part leaf → part` |  | One more stream of leaves, alongside the parts. |
| `axis` | `point → number` | `axis 0..2 = 0` | One coordinate of each point. |
| `layer` | `layer → pulse` | `index 0..63 = 0` | One of several pulse layers meant to sound together (wrapping) -- name each pick, (layer polyrhythm :as :top), so each has its own :index. |
| `noise` | ` → number` | `points 2..64 = 8, lo ##-Inf..##Inf = 0.0, hi ##-Inf..##Inf = 1.0, len 1..1024 = 16` | Smooth value noise: random points, blended, sampled :len times. |
| `part` | `part → leaf` | `index 0..63 = 0` | One of several parallel parts (wrapping): a stream of leaves. |
| `parts` | `leaf leaf → part` |  | Two streams of leaves played together, as parallel parts. |
| `pick` | `weight → index` |  | One index, drawn with the child's weights. |
| `scale` | ` → pitch` | `root 24..119 = 60, intervals vector = [0 2 4 7 9]` | Root plus offsets, as absolute pitches. |
| `threshold` | `number → pulse` | `level 0.0..1.0 = 0.5` | An onset where a number is above :level of its range. |
| `transpose` | `any → same` | `semitones -60..60 = 0` | Shift pitches, chords or notes; rests stay. |
| `zip` | `duration pitch → leaf` |  | A note's parts joined, two at a time -- (zip durs pitches vols) is (zip (zip durs pitches) vols): durations and pitches (or drums) into notes, a chord for several pitches, a rest for a nil pitch or a Rest duration (its other values unused); onto notes, a part written on each, replacing its own; anything else a bundle of its parts, joined again later. A literal gives durations, then pitches -- (as-part :volume …) names another. Ends with the shortest -- cycle a source for an isorhythm. |

**Specialised: every source as the half products it delivers** (`musics.algo.tree.half-products`). Each is its source then a bridge, the bridge's params prefixed with the half product (`:pitch-lo`), its input range defaulting to what the source gives; it is named by its registry key, `duration/euclid` for `:duration/euclid`, as in mu. The same `:as` on two of them means one run of their source.

| Half product | Bridge | Specialised algos |
|---|---|---|
| `pitch` | `classes->pitches` | pitch/arcsine-classes, pitch/beta-classes, pitch/biased-walk-classes, pitch/cauchy-classes, pitch/chain-classes, pitch/chi-square-classes, pitch/exponential-classes, pitch/falling-classes, pitch/gamma-classes, pitch/glide-classes, pitch/hi-emph-classes, pitch/inverse-gamma-classes, pitch/laplace-classes, pitch/linear-classes, pitch/lo-emph-classes, pitch/log-normal-classes, pitch/logistic-classes, pitch/mean-emph-classes, pitch/noise-classes, pitch/normal-classes, pitch/pink-classes, pitch/rising-classes, pitch/row-classes, pitch/student-t-classes, pitch/tendency-classes, pitch/triangular-classes, pitch/uniform-classes, pitch/walk-classes, pitch/weibull-classes |
| `pitch` | `degrees->pitches` | pitch/arcsine-degrees, pitch/beta-degrees, pitch/biased-walk-degrees, pitch/cauchy-degrees, pitch/chain-degrees, pitch/chi-square-degrees, pitch/exponential-degrees, pitch/falling-degrees, pitch/gamma-degrees, pitch/glide-degrees, pitch/hi-emph-degrees, pitch/inverse-gamma-degrees, pitch/laplace-degrees, pitch/linear-degrees, pitch/lo-emph-degrees, pitch/log-normal-degrees, pitch/logistic-degrees, pitch/mean-emph-degrees, pitch/noise-degrees, pitch/normal-degrees, pitch/pink-degrees, pitch/rising-degrees, pitch/row-degrees, pitch/student-t-degrees, pitch/tendency-degrees, pitch/triangular-degrees, pitch/uniform-degrees, pitch/walk-degrees, pitch/weibull-degrees |
| `pitch` | `numbers->pitches` | pitch/arcsine, pitch/beta, pitch/biased-walk, pitch/cauchy, pitch/chain, pitch/chi-square, pitch/exponential, pitch/falling, pitch/gamma, pitch/glide, pitch/hi-emph, pitch/inverse-gamma, pitch/laplace, pitch/linear, pitch/lo-emph, pitch/log-normal, pitch/logistic, pitch/mean-emph, pitch/noise, pitch/normal, pitch/pink, pitch/rising, pitch/row, pitch/student-t, pitch/tendency, pitch/triangular, pitch/uniform, pitch/walk, pitch/weibull |
| `pitch` | `points->pitches` | pitch/henon, pitch/lorenz |
| `duration` | `numbers->durations` | duration/arcsine, duration/beta, duration/biased-walk, duration/cauchy, duration/chain, duration/chi-square, duration/exponential, duration/falling, duration/gamma, duration/glide, duration/hi-emph, duration/inverse-gamma, duration/laplace, duration/linear, duration/lo-emph, duration/log-normal, duration/logistic, duration/mean-emph, duration/noise, duration/normal, duration/pink, duration/rising, duration/row, duration/student-t, duration/tendency, duration/triangular, duration/uniform, duration/walk, duration/weibull |
| `duration` | `onsets->durations` | duration/birdsong, duration/bounce, duration/cloud, duration/heartbeat, duration/pendulum, duration/poisson, duration/rain, duration/random-onsets |
| `duration` | `points->durations` | duration/henon, duration/lorenz |
| `duration` | `pulses->durations` | duration/all-interval, duration/bell, duration/bits, duration/bracelet, duration/ca, duration/cantor, duration/cfrac, duration/chain-rhythm, duration/crossover, duration/csp, duration/dragon, duration/emi, duration/euclid, duration/fibonacci, duration/fifths-rhythm, duration/genetic, duration/golden, duration/interval-sieve, duration/logistic-grid, duration/lsys-rhythm, duration/modular, duration/mutate, duration/necklaces, duration/oblique, duration/polygon, duration/primes, duration/rnn, duration/sieve, duration/stochastic, duration/theka, duration/tiling, duration/tuplets |
| `duration` | `time-points->durations` | duration/arcsine-time-points, duration/beta-time-points, duration/biased-walk-time-points, duration/cauchy-time-points, duration/chain-time-points, duration/chi-square-time-points, duration/exponential-time-points, duration/falling-time-points, duration/gamma-time-points, duration/glide-time-points, duration/hi-emph-time-points, duration/inverse-gamma-time-points, duration/laplace-time-points, duration/linear-time-points, duration/lo-emph-time-points, duration/log-normal-time-points, duration/logistic-time-points, duration/mean-emph-time-points, duration/noise-time-points, duration/normal-time-points, duration/pink-time-points, duration/rising-time-points, duration/row-time-points, duration/student-t-time-points, duration/tendency-time-points, duration/triangular-time-points, duration/uniform-time-points, duration/walk-time-points, duration/weibull-time-points |
| `volume` | `numbers->volumes` | volume/arcsine, volume/beta, volume/biased-walk, volume/cauchy, volume/chain, volume/chi-square, volume/exponential, volume/falling, volume/gamma, volume/glide, volume/hi-emph, volume/inverse-gamma, volume/laplace, volume/linear, volume/lo-emph, volume/log-normal, volume/logistic, volume/mean-emph, volume/noise, volume/normal, volume/pink, volume/rising, volume/row, volume/student-t, volume/tendency, volume/triangular, volume/uniform, volume/walk, volume/weibull |
| `volume` | `points->volumes` | volume/henon, volume/lorenz |
| `volume` | `pulses->volumes` | volume/all-interval, volume/bell, volume/bits, volume/ca, volume/cantor, volume/cfrac, volume/chain-rhythm, volume/csp, volume/dragon, volume/euclid, volume/fibonacci, volume/fifths-rhythm, volume/genetic, volume/golden, volume/interval-sieve, volume/logistic-grid, volume/lsys-rhythm, volume/modular, volume/necklaces, volume/polygon, volume/primes, volume/sieve, volume/stochastic, volume/theka |
| `volume` | `weights->volumes` | volume/indisp, volume/tala, volume/text-rhythm |
| `articulation` | `pulses->articulations` | articulation/all-interval, articulation/bell, articulation/bits, articulation/ca, articulation/cantor, articulation/cfrac, articulation/chain-rhythm, articulation/csp, articulation/dragon, articulation/euclid, articulation/fibonacci, articulation/fifths-rhythm, articulation/genetic, articulation/golden, articulation/interval-sieve, articulation/logistic-grid, articulation/lsys-rhythm, articulation/modular, articulation/necklaces, articulation/polygon, articulation/primes, articulation/sieve, articulation/stochastic, articulation/theka |
| `articulation` | `weights->articulations` | articulation/indisp, articulation/tala, articulation/text-rhythm |

## 4. What feeds what

A child fits a slot when its `:out` equals the slot's `:in`; `any` fits everything, and a literal or a `:keyword` child counts as `any`.

| Type | Produced by | Consumed by |
|---|---|---|
| `pulse` | all-interval, bell, bits, bracelet, ca, cantor, cfrac, chain-rhythm, counts-&gt;pulses, crossover, csp, data-rhythm, dragon, emi, euclid, fibonacci, fifths-rhythm, genetic, golden, interval-sieve, layer, logistic-grid, lsys-rhythm, modular, mutate, necklaces, oblique, polygon, primes, rnn, sieve, stochastic, theka, threshold, tiling, tuplets, weights-&gt;pulses | bracelet, crossover, duet, emi, iso-strength, konnakol, metric-mod, mutate, oblique, phases, pulses-&gt;articulations, pulses-&gt;durations, pulses-&gt;volumes, rnn, swing, tiling, tuplets |
| `weight` | indisp, iso-strength, power, tala, text-rhythm, tilt, trend-rhythm | counts-&gt;pulses, pick, power, tilt, weights-&gt;articulations, weights-&gt;pulses, weights-&gt;volumes |
| `pitch` | classes-&gt;pitches, constrained, degrees-&gt;pitches, grammar, infra, inter, leaves-&gt;pitches, lsys-melody, markov-gen, modulating, numbers-&gt;pitches, points-&gt;pitches, polations, scale, ultra | constrained, counterpoint, infra, inter, markov-train, polations, species, ultra, zip |
| `number` | arcsine, axis, beta, biased-walk, cauchy, chain, chi-square, exponential, falling, gamma, glide, hi-emph, inverse-gamma, laplace, linear, lo-emph, log-normal, logistic, mean-emph, noise, normal, pink, rising, row, row-form, student-t, tendency, triangular, uniform, walk, weibull | classes-&gt;pitches, counts-&gt;pulses, data-rhythm, degrees-&gt;pitches, numbers-&gt;durations, numbers-&gt;instruments, numbers-&gt;pitches, numbers-&gt;volumes, row-form, threshold, time-points-&gt;durations, trend-rhythm |
| `duration` | bisect, leaves-&gt;durations, numbers-&gt;durations, onsets-&gt;durations, points-&gt;durations, pulses-&gt;durations, split, strokes-&gt;durations, time-points-&gt;durations | zip |
| `onset` | birdsong, bounce, cloud, heartbeat, metric-mod, pendulum, poisson, rain, random-onsets, swing | onsets-&gt;durations |
| `pair` |  |  |
| `point` | henon, lorenz | axis, points-&gt;durations, points-&gt;instruments, points-&gt;pitches, points-&gt;volumes |
| `layer` | african, duet, hemiola, phases, polymeter, polyrhythm | layer |
| `part` | +part, counterpoint, drums, parts, species | +part, part |
| `model` | markov-train | markov-gen |
| `stroke` | konnakol | strokes-&gt;durations |
| `leaf` | djembe, mute, part, pocket, zip | +part, leaves-&gt;durations, leaves-&gt;pitches, mute, parts, pocket |
| `index` | pick |  |
| `any` |  | choose-from, choose-n, cycle, cyclic, deep-shuffle, filter, map, only, remove, rescale, reverse, rotate, shuffle, sputter, stretch, take, transpose |

## 5. From an algo to leaves: the steps that must follow

A tree ends in leaves: `zip` makes them from durations and pitches (drums and counterpoint make their own), and zip writes volume, articulation and instrument onto them (`as-part` names a literal's part). Every other result needs particular steps first; this is the chain, per result type, one way only. Algos that produce the type are listed from the registry.

| Result | Produced by | Must be followed by | Example |
|---|---|---|---|
| `pulse` | all-interval, bell, bits, bracelet, ca, cantor, cfrac, chain-rhythm, counts-&gt;pulses, crossover, csp, data-rhythm, dragon, emi, euclid, fibonacci, fifths-rhythm, genetic, golden, interval-sieve, layer, logistic-grid, lsys-rhythm, modular, mutate, necklaces, oblique, polygon, primes, rnn, sieve, stochastic, theka, threshold, tiling, tuplets, weights-&gt;pulses | **pulses-&gt;durations → :duration, then zip with pitches** each onset lasts until the next; a nonzero value (2 = accent) is an onset | `(zip (pulses->durations euclid) (cycle scale))` |
| `weight` | indisp, iso-strength, power, tala, text-rhythm, tilt, trend-rhythm | **weights-&gt;pulses → :pulse, weights-&gt;volumes / weights-&gt;articulations → zip onto leaves, or pick → one :index** tilt / power only reshape weights: something else must follow | `(zip (pulses->durations (weights->pulses (tilt indisp))) …)` |
| `pitch` | classes-&gt;pitches, constrained, degrees-&gt;pitches, grammar, infra, inter, leaves-&gt;pitches, lsys-melody, markov-gen, modulating, numbers-&gt;pitches, points-&gt;pitches, polations, scale, ultra | **zip with durations** modulating gives pitch classes 0–11: transpose (+60) first | `(zip (cycle [1/16]) (transpose modulating))` |
| `number` | arcsine, axis, beta, biased-walk, cauchy, chain, chi-square, exponential, falling, gamma, glide, hi-emph, inverse-gamma, laplace, linear, lo-emph, log-normal, logistic, mean-emph, noise, normal, pink, rising, row, row-form, student-t, tendency, triangular, uniform, walk, weibull | **numbers-&gt;pitches / numbers-&gt;durations, rescale then degrees-&gt;pitches, or threshold → :pulse** a sampler/walk/chaos value is not a pitch; give its range as :from-lo/:from-hi | `(zip (cycle [1/16]) (degrees->pitches (rescale walk)))` |
| `onset` | birdsong, bounce, cloud, heartbeat, metric-mod, pendulum, poisson, rain, random-onsets, swing | **onsets-&gt;durations → :duration, then zip** set :unit to the note value of one time unit (seconds for bounce/rain/heartbeat, beats for swing/cloud); n onsets give n−1 durations | `(zip (onsets->durations poisson) (cycle scale))` |
| `duration` | bisect, leaves-&gt;durations, numbers-&gt;durations, onsets-&gt;durations, points-&gt;durations, pulses-&gt;durations, split, strokes-&gt;durations, time-points-&gt;durations | **zip with pitches** note values: 1/4 is a quarter; a Rest in the stream uses its pitch unwritten | `(zip split (cycle scale))` |
| `point` | henon, lorenz | **points-&gt;pitches / points-&gt;durations, or axis → :number** henon/lorenz give a point per step | `(zip (cycle [1/16]) (degrees->pitches (rescale (axis lorenz))))` |
| `layer` | african, duet, hemiola, phases, polymeter, polyrhythm | **layer (:index) → one :pulse layer, then as pulses; parts joins the voices** name each layer you pick (:as) so each has its own :index | `(parts (zip (pulses->durations (layer polyrhythm :as :a)) …) (zip (pulses->durations (layer polyrhythm :as :b)) …))` |
| `part` | +part, counterpoint, drums, parts, species | **already leaves, in parallel: play it; part (:index) → one part's leaves** drums, counterpoint, species, parts and +part give parts | `(part (counterpoint …))` |
| `model` | markov-train | **markov-gen → :pitch** | `(markov-gen (markov-train motif))` |
| `index` | pick | **none — one value, not a sequence** use it to choose, e.g. in your own algo | `(pick (tilt indisp))` |
| `stroke` | konnakol | **strokes-&gt;durations → :duration, then zip** a syllable starts a note, "-" lengthens it | `(zip (strokes->durations (konnakol euclid)) …)` |
| `volume` | numbers-&gt;volumes, points-&gt;volumes, pulses-&gt;volumes, weights-&gt;volumes | **zip onto leaves** the note's own volume, 0–100 | `(zip riff (cycle (weights->volumes indisp)))` |
| `articulation` | pulses-&gt;articulations, weights-&gt;articulations | **zip onto leaves** staccato, accent, ghost … | `(zip riff (as-part :articulation (cycle [:staccato])))` |
| `instrument` | numbers-&gt;instruments, points-&gt;instruments | **zip onto leaves** a MIDI program, a General MIDI name or its short name | `(zip riff (as-part :instrument (cycle ["violin"])))` |
| `drum` |  | **zip onto leaves** each note made a drum: a key of the kit or a drum's name | `(zip riff (as-part :drum (cycle ["kick" "hhc"])))` |
| `(maps)` | tala, djembe, humanize, pocket, patch, tiling, trend-rhythm, text-rhythm | **none in the lib (maps or mixed values)** text-rhythm and trend-rhythm give 0/1/2: pulses-&gt;durations takes them; the map-valued ones need your own defalgo, e.g. mapping :time |  |
| `leaf` | djembe, mute, part, pocket, zip | **none — t/play!, t/live!, or notes-&gt;mus → store/read!** an infinite tree (cycle/shuffle with no finite rhythm) needs take before t/play! or printing | `(notes->mus (t/run riff tctx))` |

## 6. Writing an algorithm

Give an ordinary `defn` an `:algo` attr-map, then `(t/expose ns/the-fn)` defines its constructor under the short name. For a new function, `defalgo` does both (the raw fn stays callable as `name*`).

```clojure
(defn weights->pulses
  "The strongest :density fraction of the pulses on ..."
  {:algo {:category "bridge" :works :whole :in [:weight] :out :pulse
          :params {:density {:type :double :min 0.0 :max 1.0 :default 0.5
                             :doc "fraction of pulses kept"}}}}
  [weights density] ...)
```

- **`:params`**: per param a `:type` (`:int :double :ratio :string :keyword :vector :map :fn :bool :any`), a `:default`, and for a number `:min`/`:max`. `##-Inf`/`##Inf` leave a range end open; a `##NaN` default makes the param required. `:choices` limits a string or keyword. Registration refuses an incomplete spec.

- **One value per call?** `:repeat :len` calls the fn `:len` times (a sampler: `normal`); `:pull {:via :value}` calls it once for a generator and pulls `:len` values (a closure: `walk`, `logistic`). No wrapper fn needed; `:len` is a param like any other.

- **Children not first?** `:children [:coll]` names the args that are children.

- **Keyword args** (`& {:keys [clip-lo clip-hi boundary] :or {boundary :clamp}}`) are params too, their `:or` the default. A multi-arity fn names the arity to wrap with `:arity`.

- **Keys**: a param keeps its bare name unless two different algos in one tree read it with different specs; then each becomes `:short.name` (recipe 46). A named instance's are `:as/name`.

## 7. Recipes (53)

Each recipe is plain Clojure in three parts. **Tree**: the code that builds it. **Params**: the settings code, then *every* param the tree reads with the value it ran with — given, or its default. **Result**: the run and what came back. The grey column is code, the right column a note on it; notes are shown as `notes->mus` text, exactly what `(store/read! …)` would commit.


### First steps

#### 1. A tree, and a tctx for its settings

**Tree**

```clojure
(def riff (zip (pulses->durations euclid) (cycle scale)))   ;; an immutable value: what is computed -- each onset lasts until the next
```

**Params**

```clojure
(def tctx (t/tctx riff))   ;; an ordinary Context for its settings: it sets nothing yet, so every param is at its default
;; values {:onsets 3, :pulses 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run riff tctx)
;; => [!acc:explicit C4/8. D4/8. E4/8]
;; riff prints as
;; => #node (zip (pulses->durations (euclid)) (cycle (scale)))
```

#### 2. Change a setting; the validator guards every change

**Tree**

```clojure
riff   ;; from recipe 1
```

**Params**

```clojure
(t/setp! tctx :onsets 5)   ;; five onsets
(t/setp! tctx :intervals [0 3 7 10])   ;; a minor-seventh arpeggio
;; values {:onsets 5, :pulses 8, :pulse 1/16, :root 60, :intervals [0 3 7 10]}
```

**Result**

```clojure
(t/run riff tctx)
;; => [!acc:explicit C4/8 D#4/16 G4/8 A#4/16 C4/8]
;; out of range
;; => musics.algo.tree: :onsets 99 should be at most 32 (euclid, beats to place)
;; wrong type
;; => musics.algo.tree: :onsets 2.5 should be an integer (euclid, beats to place)
;; what it holds
;; => {"onsets" 5, "intervals" [0 3 7 10]}
```

#### 3. Wrong shapes fail when built

**Tree**

```clojure
(zip [1/4] (pick indisp))   ;; pick gives one index, no part of a note, and no bridge makes it one
(euclid scale)   ;; euclid is a source: no children
```

**Params**

*none: these trees fail before any settings exist*

**Result**

```clojure
;; error
;; => musics.algo.tree: zip: child 2, (pick (indisp)), gives :index, not a part of a note -- name its part: (as-part :volume …)
;; error
;; => musics.algo.tree: euclid takes 0 children, got 1
```

#### 4. Raw material gets its bridges

**Tree**

```clojure
(def br (zip euclid scale))   ;; euclid gives pulses where durations go: the smallest chain of bridges goes around it
(def br2 (zip (tilt indisp) scale))   ;; weights take two: weights->pulses, then pulses->durations
```

**Params**

```clojure
(def p {:onsets 3 :pulses 8})   ;; three onsets in eight
;; values {:onsets 3, :pulses 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run br p)
;; => [!acc:explicit C4/8. D4/8. E4/8]
;; the tree as built
;; => (zip (pulses->durations (euclid)) (scale))
;; two bridges
;; => (zip (pulses->durations (weights->pulses (tilt (indisp)))) (scale))
```

#### 5. Algos as half products: what they deliver, not how

**Tree**

```clojure
(def hp (zip (duration/euclid) (pitch/lorenz)))   ;; euclid as durations, lorenz as pitches: each a specialised algo, its source and bridge in one
(def same (zip (cycle [1/8]) (pitch/walk :as :w) (volume/walk :as :w)))   ;; one :as, one run: the higher note is the louder
```

**Params**

```clojure
(def p {:onsets 5 :pulses 16})   ;; lorenz at its defaults: the bridge's range defaults to what the source gives
;; euclid × lorenz values {:onsets 5, :pulses 16, :duration-pulse 1/16, :sigma 10.0, :rho 28.0, :beta 2.6666666666666665, :x0 1.0, :y0 1.0, :z0 1.0, :dt 0.01, :len 16, :pitch-axis 0, :pitch-from-lo 0.9, :pitch-from-hi 9.0, :pitch-key "C.major", :pitch-octave 4, :pitch-octaves 2}
;; walk as pitch and volume values {:w/start 60.0, :w/step-bound 2.0, :w/clip-lo ##-Inf, :w/clip-hi ##Inf, :w/boundary :clamp, :w/len 16, :w/pitch-from-lo 46.0, :w/pitch-from-hi 66.0, :w/pitch-lo 48, :w/pitch-hi 84, :w/volume-from-lo 46.0, :w/volume-from-hi 66.0, :w/volume-lo 40.0, :w/volume-hi 80.0}
```

**Result**

```clojure
(t/run hp p)
;; euclid × lorenz
;; => [!acc:explicit C4/8. C4/8. C4/8. C4/8. D4/4]
(t/run same {})
;; walk as pitch and volume
;; => [!acc:explicit A#4/8\volume:64.97921232156902 B4/8\volume:65.7013021180642 B4/8\volume:65.19379956675549 D5/8\volume:68.65772642583067 E5/8\volume:71.12475134411937 D#5/8\volume:69.74859949614587 F5/8\volume:71.70856233306894 E5/8\volume:70.90005231343676 C5/8\volume:67.20099774636351 C5/8\volume:66.9439527240917 B4/8\volume:65.94838212289578 C#5/8\volume:68.26044634735689 D#5/8\volume:70.37840269468224 D5/8\volume:68.47398447628926 E5/8\volume:70.62407909301672 F5/8\volume:71.74853260157364]
;; a bridge's params carry the half product
;; => [:start :step-bound :clip-lo :clip-hi :boundary :len :pitch-from-lo :pitch-from-hi :pitch-lo :pitch-hi]
```

### Rhythm × melody

#### 6. Euclidean rhythm, rotated, over a reshuffled pentatonic

**Tree**

```clojure
(def r2 (zip (pulses->durations (rotate euclid)) (shuffle scale)))   ;; rotate moves the onsets two places; every pitch once per pass
```

**Params**

```clojure
(def p {:onsets 5 :pulses 16 :steps 2 :pulse 1/16})   ;; a plain map: missing keys take their defaults
;; values {:onsets 5, :pulses 16, :steps 2, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run r2 p)
;; => [!acc:explicit r16 A4/8. D4/8. G4/8. E4/4 A4/8]
```

#### 7. Legato by default, detached by articulation

**Tree**

```clojure
(def det (zip riff (as-part :articulation (cycle [:staccato]))))   ;; every note staccato: the onsets stay, the notes sound shorter
```

**Params**

```clojure
(def p {:onsets 5})
;; values {:onsets 5, :pulses 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run det p)
;; => [!acc:explicit C4/8-. D4/16-. E4/8-. G4/16-. A4/8-.]
```

#### 8. Every musics/algo/ generator is an algo: a Fibonacci rhythm

**Tree**

```clojure
(def r3 (zip (pulses->durations fibonacci) (cycle scale)))   ;; musics.algo.rhythmic.pattern/fibonacci, by its short name
```

**Params**

```clojure
(def p {:length 16 :intervals [0 2 4 5 7 9 11] :pulse 1/16})
;; values {:length 16, :pulse 1/16, :root 60, :intervals [0 2 4 5 7 9 11]}
```

**Result**

```clojure
(t/run r3 p)
;; => [!acc:explicit C4/16 D4/16 E4/16 F4/8 G4/8. A4/1*5/16 B4/8.]
;; full name
;; => musics.algo.rhythmic.pattern/fibonacci
```

#### 9. A Xenakis sieve as the rhythm

**Tree**

```clojure
(def r4 (zip (pulses->durations sieve) (cycle scale)))
```

**Params**

```clojure
(def p {:moduli [3 4] :residues [[0 1] [2]] :pulse 1/16})   ;; vector params: moduli and their residues
;; values {:moduli [3 4], :residues [[0 1] [2]], :length 12, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run r4 p)
;; => [!acc:explicit C4/16 D4/16 E4/16 G4/16 A4/8 C4/16 D4/8 E4/16 G4/8]
```

#### 10. A clave timeline carrying an arpeggio

**Tree**

```clojure
(def r5 (zip (pulses->durations (world/named-bell-patterns "clave")) (cycle [60 64 67 72])))   ;; literal children: their own data
```

**Params**

```clojure
(def p {:pulse 1/16})
;; values {:pulse 1/16}
```

**Result**

```clojure
(t/run r5 p)
;; => [!acc:explicit C4/8. E4/8. G4/4 C5/8. C4/8.]
```

### Isorhythm & durations

#### 11. Isorhythm: a 5-note color against a 3-value talea

**Tree**

```clojure
(def iso (take (zip (cycle [1/4 1/8 1/8]) (cycle [60 62 64 65 67]))))   ;; two cycles of different length, zipped: the period is lcm(5,3) = 15
```

**Params**

```clojure
(def p {:len 15})   ;; one period
;; values {:len 15}
```

**Result**

```clojure
(t/run iso p)
;; => [!acc:explicit C4/4 D4/8 E4/8 F4/4 G4/8 C4/8 D4/4 E4/8 F4/8 G4/4 C4/8 D4/8 E4/4 F4/8 G4/8]
```

#### 12. Stretch the durations, transpose the pitches

**Tree**

```clojure
(def iso2 (transpose (stretch iso)))   ;; both work on leaves
```

**Params**

```clojure
(def p {:len 15 :factor 1/2 :semitones 7})
;; values {:len 15, :factor 1/2, :semitones 7}
```

**Result**

```clojure
(t/run iso2 p)
;; => [!acc:explicit G4/8 A4/16 B4/16 C5/8 D5/16 G4/16 A4/8 B4/16 C5/16 D5/8 G4/16 A4/16 B4/8 C5/16 D5/16]
```

### Metre & indispensability

#### 13. Indispensability thins a 12/8 bar

**Tree**

```clojure
(def grid (weights->pulses (tilt indisp)))   ;; ranks → probabilities → the strongest half
(def bar (zip (pulses->durations grid) (cycle [67 64 60])))   ;; an arpeggio on the kept pulses
```

**Params**

```clojure
(def p {:adherence 0.8 :density 0.5})
;; values {:subdivisions [2 2 3], :adherence 0.8, :density 0.5, :pulse 1/16}
```

**Result**

```clojure
(t/run bar p)
;; => [!acc:explicit G4/8. E4/8 C4/16 G4/8. E4/8 C4/16]
```

#### 14. Swap one stage: strong beats silent

**Tree**

```clojure
(def bar2 (zip (pulses->durations (weights->pulses (power indisp))) (cycle [67 64 60])))   ;; power reads the same :adherence as tilt
```

**Params**

```clojure
(def p {:adherence -0.8 :density 0.5})   ;; negative: the weak pulses win
;; values {:subdivisions [2 2 3], :adherence -0.8, :density 0.5, :pulse 1/16}
```

**Result**

```clojure
(t/run bar2 p)
;; => [!acc:explicit r16 E4/16 C4/8 G4/8. E4/16 C4/8 G4/8]
```

#### 15. One weighted choice

**Tree**

```clojure
(def one (pick (tilt indisp)))   ;; an index, drawn with the shaped weights
```

**Params**

```clojure
(def p {:subdivisions [3 3] :adherence 1.0})
;; values {:subdivisions [3 3], :adherence 1.0}
```

**Result**

```clojure
(t/run one p)
;; => 0
```

### Melody generators

#### 16. A Markov melody trained on a motif

**Tree**

```clojure
(def mk (zip (pulses->durations euclid) (markov-gen (markov-train [60 62 64 62 60 67 65 64]))))   ;; train → a :model, generate → pitches
```

**Params**

```clojure
(def p {:order 1 :onsets 11 :pulses 16 :pulse 1/16})
;; values {:onsets 11, :pulses 16, :pulse 1/16, :order 1, :length 16, :opening nil}
```

**Result**

```clojure
(t/run mk p)
;; => [!acc:explicit C4/8 D4/16 C4/8 D4/16 C4/8 D4/16 C4/8 D4/16 C4/8 D4/16 C4/16]
```

#### 17. Slonimsky: insertions before, between, after

**Tree**

```clojure
(def sl (zip (cycle [1/16]) (infra [60 64 67 72] [59 62])))   ;; two children: principal tones, insertion
(def pol (zip (cycle [1/16]) (polations [60 64 67 72])))   ;; all three layers at once, as params
```

**Params**

```clojure
(def p1 {})
(def p2 {:inter [65] :ultra [62]})
;; infra values {}
;; polations values {:infra [], :inter [65], :ultra [62], :ultra-after-last? false}
```

**Result**

```clojure
(t/run sl p1)
;; infra
;; => [!acc:explicit B3/16 D4/16 C4/16 B3/16 D4/16 E4/16 B3/16 D4/16 G4/16 B3/16 D4/16 C5/16]
(t/run pol p2)
;; polations
;; => [!acc:explicit C4/16 D4/16 F4/16 E4/16 D4/16 F4/16 G4/16 D4/16 F4/16 C5/16]
```

#### 18. An L-system melody, its rules in the tctx

**Tree**

```clojure
(def ls (zip (cycle [1/16]) lsys-melody))   ;; axiom, rules, note-map: all params
```

**Params**

```clojure
(def ls-tctx (t/tctx ls))
(t/setp! ls-tctx {:rules {"A" "ABA" "B" "CB" "C" "A"} :note-map {\A 67 \B 64 \C 60}})   ;; a map param: checked to be a map
;; values {:axiom "A", :rules {"A" "ABA", "B" "CB", "C" "A"}, :note-map {\A 67, \B 64, \C 60}, :iterations 5, :max-notes 16}
```

**Result**

```clojure
(t/run ls ls-tctx)
;; => [!acc:explicit G4/16 E4/16 G4/16 C4/16 E4/16 G4/16 E4/16 G4/16 G4/16 C4/16 E4/16 G4/16 E4/16 G4/16 C4/16 E4/16]
```

#### 19. A constrained walk: constraints are a param

**Tree**

```clojure
(def sc [60 62 64 65 67 69 71 72])
(def cw (zip (cycle [1/16]) (constrained sc)))
```

**Params**

```clojure
(def cw-tctx (t/tctx cw {:length 12}))   ;; default constraint: no repeated note
(t/setp! cw-tctx :constraints [(melody/max-leap-constraint sc 1) melody/no-repeat-constraint])   ;; steps only
;; values {:length 12, :constraints [fn fn], :start nil}
```

**Result**

```clojure
(t/run cw cw-tctx)
;; => [!acc:explicit G4/16 A4/16 G4/16 A4/16 B4/16 A4/16 B4/16 A4/16 B4/16 A4/16 B4/16 A4/16]
```

#### 20. A melody that modulates

**Tree**

```clojure
(def mo (zip (cycle [1/16]) (transpose modulating)))   ;; pitch classes 0–11, lifted 60 semitones
```

**Params**

```clojure
(def p {:segments [[[:C :major] 6] [[:E :minor] 6]] :semitones 60})
;; values {:segments [[[:C :major] 6] [[:E :minor] 6]], :constraints [fn], :semitones 60}
```

**Result**

```clojure
(t/run mo p)
;; => [!acc:explicit C4/16 G4/16 A4/16 B4/16 D4/16 F4/16 G4/16 A4/16 B4/16 E4/16 D4/16 E4/16]
```

#### 21. Two-voice counterpoint, one part at a time

**Tree**

```clojure
(def cp (counterpoint [55 57 59 60 62 64 65 67 69 71 72 74 76]))   ;; two parts, each a stream of leaves
(def voice (part cp))   ;; one voice, ready to play
```

**Params**

```clojure
(def p1 {:index 0})
(def p2 {:index 1})
;; voice 1 values {:pitch-range [55 84], :rules {:no-parallel-fifths? true, :no-parallel-octaves? true, :c …, :beat-durations [0.5 0.5 1.0 0.5 0.5 1.0 0.5 0.5], :voice-specs [{:motif {:steps [0 2 -1 2], :durations [0.5 0.5 0.5 0.5]} …, :index 0}
;; voice 2 values {:pitch-range [55 84], :rules {:no-parallel-fifths? true, :no-parallel-octaves? true, :c …, :beat-durations [0.5 0.5 1.0 0.5 0.5 1.0 0.5 0.5], :voice-specs [{:motif {:steps [0 2 -1 2], :durations [0.5 0.5 0.5 0.5]} …, :index 1}
```

**Result**

```clojure
(t/run voice p1)
;; voice 1
;; => [!acc:explicit B4/8 D5/8 E5/4 E5/8 E5/8 D5/4 E5/8 D5/8]
(t/run voice p2)
;; voice 2
;; => [!acc:explicit C4/8 C4/8 F4/4 D4/8 D4/8 G4/4 D4/8 G4/8]
```

### Metric generators

#### 22. A number's bits, a fraction's expansion

**Tree**

```clojure
(def bt (zip (pulses->durations bits) (cycle scale)))   ;; 45 = 101101: onsets on bits 0, 2, 3, 5
(def cf (zip (pulses->durations cfrac) (cycle scale)))   ;; π = [3; 7, 15, 1, …]
```

**Params**

```clojure
(def p1 {:number 45 :length 8 :pulse 1/16})
(def p2 {:pulse 1/16})
;; bits values {:number 45, :length 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
;; cfrac values {:fraction 3.141592653589793, :length 16, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run bt p1)
;; bits
;; => [!acc:explicit C4/8 D4/16 E4/8 G4/8.]
(t/run cf p2)
;; cfrac
;; => [!acc:explicit C4/16 D4/16 E4/16 G4/16 A4/16 C4/16 D4/16 E4/16 G4/16 A4/4 C4/16 D4/16 E4/16]
```

#### 23. Modular arithmetic as a rhythm

**Tree**

```clojure
(def md (zip (pulses->durations modular) (cycle scale)))   ;; onset where multiplier·i + offset ≡ 0 (mod modulus)
```

**Params**

```clojure
(def p {:modulus 5 :multiplier 2 :length 15 :pulse 1/16})
;; values {:modulus 5, :multiplier 2, :length 15, :offset 0, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run md p)
;; => [!acc:explicit C4/1*5/16 D4/1*5/16 E4/1*5/16]
```

### Random sources

#### 24. A sampler is a sequence: :len draws

**Tree**

```clojure
(def tri (zip (cycle [1/16]) (pitch/triangular)))   ;; a source delivered as pitches: one value per call, called :len times
```

**Params**

```clojure
(def p {:pitch-lo 60 :pitch-hi 73 :mode 0.5 :len 12})
;; values {:lo 0.0, :hi 1.0, :mode 0.5, :len 12, :pitch-from-lo 0.07, :pitch-from-hi 0.98, :pitch-lo 60, :pitch-hi 73}
```

**Result**

```clojure
(t/run tri p)
;; => [!acc:explicit D#4/16 G4/16 F#4/16 B4/16 A4/16 F4/16 G#4/16 F4/16 C#4/16 F#4/16 F4/16 A4/16]
```

#### 25. Any numbers, rescaled onto a scale

**Tree**

```clojure
(def nd (zip (cycle [1/16]) (degrees->pitches (rescale normal))))   ;; rescale puts the draws onto scale steps, degrees->pitches spells them in a key
```

**Params**

```clojure
(def p {:len 12 :from-lo -2.0 :from-hi 2.0 :to-lo 0.0 :to-hi 9.0 :key "C.pentatonic-major"})
;; values {:mean 0.0, :stdev 1.0, :len 12, :from-lo -2.0, :from-hi 2.0, :to-lo 0.0, :to-hi 9.0, :key "C.pentatonic-major", :octave 4}
```

**Result**

```clojure
(t/run nd p)
;; => [!acc:explicit E4/16 G4/16 D5/16 D5/16 D5/16 D4/16 D5/16 G4/16 D5/16 C5/16 C5/16 G4/16]
```

#### 26. A random walk, and a glide toward a target

**Tree**

```clojure
(def wk (zip (cycle [1/16]) (degrees->pitches (rescale walk))))   ;; a closure, pulled :len times
(def gl (zip (cycle [1/16]) (degrees->pitches (rescale glide))))   ;; :target is passed on every pull
```

**Params**

```clojure
(def p1 {:len 16 :step-bound 3.0 :from-lo 52.0 :from-hi 68.0 :to-lo 0.0 :to-hi 9.0 :key "C.pentatonic-major"})   ;; it starts at 60 and wanders: its range is a guess
(def p2 {:len 16 :initial 0.0 :target 10.0 :inertia 0.7 :from-lo 0.0 :from-hi 10.0 :to-lo 0.0 :to-hi 9.0 :key "C.pentatonic-major"})
;; walk values {:start 60.0, :step-bound 3.0, :clip-lo ##-Inf, :clip-hi ##Inf, :boundary :clamp, :len 16, :from-lo 52.0, :from-hi 68.0, :to-lo 0.0, :to-hi 9.0, :key "C.pentatonic-major", :octave 4}
;; glide values {:initial 0.0, :inertia 0.7, :step 0.5, :len 16, :target 10.0, :from-lo 0.0, :from-hi 10.0, :to-lo 0.0, :to-hi 9.0, :key "C.pentatonic-major", :octave 4}
```

**Result**

```clojure
(t/run wk p1)
;; walk
;; => [!acc:explicit G4/16 A4/16 G4/16 C5/16 D5/16 C5/16 D5/16 D5/16 A4/16 A4/16 A4/16 C5/16 D5/16 C5/16 D5/16 D5/16]
(t/run gl p2)
;; glide
;; => [!acc:explicit G4/16 A4/16 D5/16 E5/16 E5/16 E5/16 G5/16 A5/16 A5/16 A5/16 A5/16 A5/16 A5/16 A5/16 A5/16 C6/16]
```

#### 27. Chaos: the logistic map, the Lorenz attractor

**Tree**

```clojure
(def lg (zip (cycle [1/16]) (degrees->pitches (rescale logistic))))
(def lz (zip (cycle [1/16]) (degrees->pitches (rescale (axis lorenz)))))   ;; x of each [x y z] point
```

**Params**

```clojure
(def p1 {:r 3.9 :len 12 :to-hi 9.0 :key "C.pentatonic-major"})   ;; r = 3.9: chaotic, between 0 and 1
(def p2 {:len 24 :dt 0.03 :from-lo -20.0 :from-hi 20.0 :to-lo 0.0 :to-hi 9.0 :key "C.pentatonic-major"})
;; logistic values {:r 3.9, :x 0.5, :len 12, :from-lo 0.0, :from-hi 1.0, :to-lo 0.0, :to-hi 9.0, :key "C.pentatonic-major", :octave 4}
;; lorenz values {:sigma 10.0, :rho 28.0, :beta 2.6666666666666665, :x0 1.0, :y0 1.0, :z0 1.0, :dt 0.03, :len 24, :axis 0, :from-lo -20.0, :from-hi 20.0, :to-lo 0.0, :to-hi 9.0, :key "C.pentatonic-major", :octave 4}
```

**Result**

```clojure
(t/run lg p1)
;; logistic
;; => [!acc:explicit A5/16 D4/16 G4/16 G5/16 A4/16 A5/16 D4/16 A4/16 A5/16 D4/16 G4/16 G5/16]
(t/run lz p2)
;; lorenz
;; => [!acc:explicit C5/16 C5/16 C5/16 C5/16 C5/16 D5/16 D5/16 E5/16 E5/16 G5/16 A5/16 A5/16 G5/16 E5/16 D5/16 C5/16 C5/16 A4/16 A4/16 G4/16 G4/16 G4/16 G4/16 G4/16]
```

#### 28. A Markov chain over scale degrees, its table in the tctx

**Tree**

```clojure
(def ch (zip (cycle [1/16]) (degrees->pitches chain)))   ;; degrees, then pitches in the key
```

**Params**

```clojure
(def p {:transitions {0 {2 1 4 1} 2 {0 1 3 2} 3 {4 1} 4 {0 2 2 1}} :start-state 0 :len 12 :key "D.dorian"})   ;; state → {next weight}
;; values {:transitions {0 {2 1, 4 1}, 2 {0 1, 3 2}, 3 {4 1}, 4 {0 2, 2 1}}, :start-state 0, :len 12, :key "D.dorian", :octave 4}
```

**Result**

```clojure
(t/run ch p)
;; => [!acc:explicit F4/16 G4/16 A4/16 F4/16 G4/16 A4/16 F4/16 G4/16 A4/16 D4/16 F4/16 G4/16]
```

#### 29. Poisson onsets become durations

**Tree**

```clojure
(def po (zip (onsets->durations poisson) (cycle scale)))   ;; onsets → the time between them
```

**Params**

```clojure
(def p {:rate 3.0 :duration 4.0 :unit 1/4})   ;; a time unit = a quarter note
;; values {:rate 3.0, :duration 4.0, :unit 1/4, :quantum 1/32, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run po p)
;; => [!acc:explicit C4/32 D4/16 E4/32 G4/16. A4/32 C4/16 D4/1*9/32 E4/16 G4/16. A4/32 C4/32]
```

#### 30. Smooth noise as a rhythm

**Tree**

```clojure
(def nz (zip (pulses->durations (threshold noise)) (cycle scale)))   ;; an onset where the curve is high
```

**Params**

```clojure
(def p {:pulses 6 :len 16 :level 0.4 :pulse 1/16})
;; values {:points 8, :lo 0.0, :hi 1.0, :len 16, :level 0.4, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run nz p)
;; => [!acc:explicit r8 D4/16 E4/8 G4/16 A4/16 C4/16 D4/16 E4/8. G4/16 A4/16 C4/8]
```

### Rhythm generators

#### 31. A tala's theka, a named bell pattern

**Tree**

```clojure
(def tk (zip (pulses->durations theka) (cycle scale)))
(def bl (zip (pulses->durations bell) (cycle [60 64 67])))   ;; :pattern-name is one of its :choices
```

**Params**

```clojure
(def p1 {:tala-name "jhaptal" :pulse 1/16})
(def p2 {:meter [16 8] :pattern-name "bossanova" :pulse 1/16})
;; jhaptal values {:tala-name "jhaptal", :instrument "tabla", :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
;; bossanova values {:meter [16 8], :pattern-name "bossanova", :pulse 1/16}
```

**Result**

```clojure
(t/run tk p1)
;; jhaptal
;; => [!acc:explicit C4/8 D4/16 E4/8 G4/8 A4/16 C4/8]
(t/run bl p2)
;; bossanova
;; => [!acc:explicit C4/8. E4/8. G4/4 C4/8 E4/4]
;; not a choice
;; => musics.algo.tree: :pattern-name "polka" should be one of ["standard" "clave" "bossanova" "funk" "ghanian" "plain"] (bell, a named timeline)
```

#### 32. Fractal rhythms: Cantor set, dragon curve

**Tree**

```clojure
(def ct (zip (pulses->durations cantor) (cycle scale)))
(def dg (zip (pulses->durations dragon) (cycle scale)))
```

**Params**

```clojure
(def p1 {:iterations 2 :length 9 :pulse 1/16})
(def p2 {:iterations 3 :pulse 1/16})   ;; 2^(n+1) − 1 = 15 pulses
;; cantor values {:iterations 2, :length 9, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
;; dragon values {:iterations 3, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run ct p1)
;; cantor
;; => [!acc:explicit C4/8 D4/4 E4/8 G4/16]
(t/run dg p2)
;; dragon
;; => [!acc:explicit C4/16 D4/8 E4/16 G4/8. A4/16 C4/16 D4/8. E4/8.]
```

#### 33. A polyrhythm, one layer per voice

**Tree**

```clojure
(def pr (zip (pulses->durations (layer polyrhythm)) (cycle [48 55])))   ;; 3 against 2, 24 pulses
```

**Params**

```clojure
(def p1 {:index 0 :pulse 1/16})
(def p2 {:index 1 :pulse 1/16})
;; three values {:layers [[3 8] [2 8]], :length 24, :index 0, :pulse 1/16}
;; two values {:layers [[3 8] [2 8]], :length 24, :index 1, :pulse 1/16}
```

**Result**

```clojure
(t/run pr p1)
;; three
;; => [!acc:explicit C3/2 G3/2 C3/2]
(t/run pr p2)
;; two
;; => [!acc:explicit C3/2. G3/2.]
```

#### 34. A polyrhythm as two parts: each layer its own voice

**Tree**

```clojure
(def pp (parts (zip (pulses->durations (layer polyrhythm :as :low)) (cycle [48]))
               (zip (pulses->durations (layer polyrhythm :as :high)) (cycle [55]))))   ;; each layer picked by its own name, then joined
```

**Params**

```clojure
(def p {:low/index 0 :high/index 1 :pulse 1/16})   ;; play it: (t/play! pp p)
;; values {:layers [[3 8] [2 8]], :length 24, :low/index 0, :pulse 1/16, :high/index 1}
```

**Result**

```clojure
(t/run pp p)
;; => [[{:tied false, :articulation nil, :type :LEAF, :duration 1/2, :pitches [48], :modifiers [], :dynamic nil, :id nil, :context nil} {:tied false, :articulation nil, :type :LEAF, :duration 1/2, :pitches [48], :modifiers [], :dynamic nil, :id nil, :context nil} {:tied false, :articulation nil, :type :LEAF, :duration 1/2, :pitches [48], :modifiers [], :dynamic nil, :id nil, :context nil}] [{:tied false …
```

#### 35. Clapping Music: the pattern against itself, shifted

**Tree**

```clojure
(def cm (zip (pulses->durations (layer (duet [1 1 1 0 1 1 0 1 0 1 1 0]))) (cycle [72])))
```

**Params**

```clojure
(def p {:phase 3 :index 1 :pulse 1/16})   ;; layer 1: the shifted part
;; values {:phase 3, :index 1, :pulse 1/16}
```

**Result**

```clojure
(t/run cm p)
;; => [!acc:explicit r16 C5/16 C5/8 C5/8 C5/16 C5/8 C5/16 C5/16 C5/16]
```

#### 36. A genetic rhythm: the fitness fn is a param

**Tree**

```clojure
(def gn (zip (pulses->durations genetic) (cycle scale)))
```

**Params**

```clojure
(def gn-tctx (t/tctx gn {:pulse 1/16}))   ;; :fitness-fn has no default: required
(t/setp! gn-tctx :fitness-fn (fn [p] (- (+ (abs (- 5 (reduce + p))) (if (= 1 (first p)) 0 3)))))   ;; five onsets, one on the downbeat
;; values {:population-size 20, :pattern-length 16, :generations 20, :fitness-fn fn, :mutation-rate 0.1, :elitism 0.1, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run gn gn-tctx)
;; => [!acc:explicit C4/4.. D4/8. E4/8 G4/8 A4/8]
```

#### 37. Swing: a grid becomes onset times, then durations

**Tree**

```clojure
(def sw (zip (onsets->durations (swing euclid)) (cycle scale)))   ;; pulses → swung onsets → durations → leaves
```

**Params**

```clojure
(def p {:onsets 8 :pulses 8 :swing-ratio 0.67 :unit 1/4})   ;; a beat = a quarter note
;; values {:onsets 8, :pulses 8, :swing-ratio 0.67, :subdivision 2, :unit 1/4, :quantum 1/32, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run sw p)
;; => [!acc:explicit C4/1*5/32 D4/16. E4/1*5/32 G4/16. A4/1*5/32 C4/16. D4/1*5/32]
```

#### 38. A bouncing ball, in seconds

**Tree**

```clojure
(def bb (zip (onsets->durations bounce) (cycle [72 67])))
```

**Params**

```clojure
(def p {:initial-height 1.0 :restitution 0.8 :unit 1/4 :quantum 1/64})   ;; one second = a quarter note
;; values {:initial-height 1.0, :restitution 0.8, :gravity 9.8, :duration 5.0, :unit 1/4, :quantum 1/64}
```

**Result**

```clojure
(t/run bb p)
;; => [!acc:explicit C5/16.. G4/16. C5/1*5/64 G4/16 C5/32. G4/32 C5/32 G4/32 C5/64 G4/64 C5/64 G4/64]
```

#### 39. Tuplet-like durations by repeated splitting

**Tree**

```clojure
(def sp (zip split (cycle scale)))   ;; each piece a :ratio of what remains
```

**Params**

```clojure
(def p {:duration 1 :depth 5 :ratio 2/3})
;; values {:duration 1, :depth 5, :ratio 2/3, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run sp p)
;; => [!acc:explicit C4/1*2/3 D4/1*2/9 E4/1*2/27 G4/1*2/81 A4/1*1/81]
```

#### 40. Text as rhythm

**Tree**

```clojure
(def tx (zip (pulses->durations (weights->pulses text-rhythm)) (cycle scale)))   ;; a beat on each word's first syllable
```

**Params**

```clojure
(def p {:text "Composing trees of algorithms is plain Clojure." :density 1.0 :pulse 1/16})   ;; density 1.0: every accent sounds
;; values {:text "Composing trees of algorithms is plain Clojure.", :mode "syllables", :density 1.0, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run tx p)
;; => [!acc:explicit C4/8. D4/16 E4/16 G4/8. A4/16 C4/16 D4/4]
```

#### 41. Vary a rhythm: EMI style, Oblique Strategies

**Tree**

```clojure
(def em (zip (pulses->durations (emi euclid)) (cycle scale)))
(def ob (zip (pulses->durations (oblique euclid)) (cycle scale)))
```

**Params**

```clojure
(def p1 {:onsets 5 :pulses 16 :similarity 0.6 :pulse 1/16})
(def p2 {:onsets 3 :pulses 8 :strategy "mirror" :pulse 1/16})
;; emi values {:onsets 5, :pulses 16, :similarity 0.6, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
;; mirror values {:onsets 3, :pulses 8, :strategy "mirror", :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run em p1)
;; emi
;; => [!acc:explicit C4/4 D4/16 E4/16 G4/4 A4/1*5/16]
(t/run ob p2)
;; mirror
;; => [!acc:explicit C4/8. D4/8. E4/8. G4/8. A4/8. C4/16]
```

#### 42. Vary durations: the same algos on the shop floor

**Tree**

```clojure
(def dv (zip (duration/emi [1/8 1/8 1/4 3/8 1/8]) (cycle scale)))   ;; durations laid on a grid of :duration-pulse, varied, read back
(def dx (zip (duration/crossover [1/8 1/8 1/4 3/8 1/8] [1/4 1/4 1/4 1/4]) (cycle scale)))   ;; two rhythms crossed
```

**Params**

```clojure
(def p1 {:similarity 0.6})
(def p2 {:crossover-point 4})   ;; the first half of one, the rest of the other
;; emi values {:similarity 0.6, :duration-pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
;; crossover values {:crossover-point 4, :child 0, :duration-pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run dv p1)
;; emi
;; => [!acc:explicit C4/8 D4/16 E4/8 G4/8. A4/8 C4/16 D4/16 E4/8.]
(t/run dx p2)
;; crossover
;; => [!acc:explicit C4/8 D4/8 E4/4 G4/4 A4/4]
```

### Algorithms of your own

#### 43. A pitch transform

**Tree**

```clojure
(defalgo up "Shift every pitch; rests stay."
  {:algo {:in [:pitch] :out :pitch
          :params {:by {:type :int :min -48 :max 48 :default 12}}}}
  [pitches by] (clojure.core/map #(some-> % (+ by)) pitches))   ;; defalgo = defn + register; up* is the raw fn
(def r6 (zip (pulses->durations euclid) (up (cycle scale))))   ;; up works on pitches, before zip
```

**Params**

```clojure
(def p {:by -12})
;; values {:onsets 3, :pulses 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9], :by -12}
```

**Result**

```clojure
(t/run r6 p)
;; => [!acc:explicit C3/8. D3/8. E3/8]
;; raw fn
;; => (65 nil 69)
```

#### 44. Required params and open ranges

**Tree**

```clojure
(defalgo tone "One pitch, which must be given."
  {:algo {:in [] :out :pitch
          :params {:p {:type :int :min 0 :max ##Inf :default ##NaN}}}}
  [p] [p])   ;; ##NaN default: required · ##Inf: no upper bound
(def tn (tone))
```

**Params**

```clojure
(def p1 {})   ;; nothing set
(def p2 {:p 67})
;; unset values {:p ‹required›}
;; set values {:p 67}
```

**Result**

```clojure
(try (t/run tn p1) (catch Exception e (ex-message e)))
;; unset
;; => musics.algo.tree: (tone) needs :p -- not set
(t/run tn p2)
;; set
;; => [67]
```

### Two of a kind

#### 45. Two instances of one algo: name one

**Tree**

```clojure
(defalgo union "Onset wherever either grid has one."
  {:algo {:in [:pulse :pulse] :out :pulse}}
  [a b] (mapv max a b))
(def two (zip (pulses->durations (union (euclid :as :bass) euclid)) (cycle scale)))   ;; keys :bass/onsets … and :onsets …
```

**Params**

```clojure
(def p {:bass/onsets 2 :bass/pulses 16 :onsets 5 :pulses 16 :intervals [0 3 7 10] :pulse 1/16})
;; values {:bass/onsets 2, :bass/pulses 16, :onsets 5, :pulses 16, :pulse 1/16, :root 60, :intervals [0 3 7 10]}
```

**Result**

```clojure
(t/run two p)
;; => [!acc:explicit C4/8. D#4/8. G4/8 A#4/16 C4/8. D#4/4]
```

#### 46. Different algos, same param name

**Tree**

```clojure
(defalgo first-onsets "Keep only the first onsets onsets."
  {:algo {:in [:pulse] :out :pulse
          :params {:onsets {:type :int :min 1 :max 8 :default 2}}}}
  [g onsets] (second (reduce (fn [[n out] x] (if (and (pos? x) (< n onsets)) [(inc n) (conj out x)] [n (conj out 0)])) [0 []] g)))   ;; its :onsets means something else than euclid's
(def ac (first-onsets euclid))   ;; so both become :<short>.onsets
```

**Params**

```clojure
(def p {:euclid.onsets 5 :first-onsets.onsets 2})
;; values {:euclid.onsets 5, :pulses 8, :first-onsets.onsets 2}
```

**Result**

```clojure
(t/run ac p)
;; => [1 0 1 0 0 0 0 0]
```

### Settings as data

#### 47. One tree, several tctxs

**Tree**

```clojure
riff   ;; from recipe 1
```

**Params**

```clojure
(def calm (t/tctx riff {:onsets 2}))
(def busy (t/tctx riff {:onsets 7}))   ;; the tree is shared, the settings aren't
;; calm values {:onsets 2, :pulses 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
;; busy values {:onsets 7, :pulses 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run riff calm)
;; calm
;; => [!acc:explicit C4/4 D4/4]
(t/run riff busy)
;; busy
;; => [!acc:explicit C4/16 D4/16 E4/16 G4/16 A4/16 C4/16 D4/8]
```

#### 48. Fit a tctx to another tree

**Tree**

```clojure
(def other (transpose (take (zip (cycle [1/8]) (cycle scale)))))   ;; reads :len and :semitones too
```

**Params**

```clojure
(t/fit! calm other)   ;; adds their specs, keeps :onsets 2
;; values {:root 60, :intervals [0 2 4 7 9], :len 16, :semitones 0}
```

**Result**

```clojure
(t/run other calm)
;; => [!acc:explicit C4/8 D4/8 E4/8 G4/8 A4/8 C4/8 D4/8 E4/8 G4/8 A4/8 C4/8 D4/8 E4/8 G4/8 A4/8 C4/8]
```

#### 49. Settings written as @^{ }, from several sources

**Tree**

```clojure
riff   ;; from recipe 1
```

**Params**

```clojure
(def written (into {} (:params (v/node (mt/read-text "@^{ onsets:5 root:67 euclid.pulses:16 }")))))   ;; @^{ name:value } is an algo's settings, kept apart from the music's ^{ }
(def mine (t/tctx riff {:onsets 2}))   ;; a tctx that sets onsets itself
;; the written settings values {:onsets 5, :pulses 16, :pulse 1/16, :root 67, :intervals [0 2 4 7 9]}
;; the tctx first, nearest first values {:onsets 2, :pulses 16, :pulse 1/16, :root 67, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(t/run riff written)
;; the written settings
;; => [!acc:explicit G4/8. A4/8. B4/8. D5/8. E5/4]
(t/run riff [mine written])
;; the tctx first, nearest first
;; => [!acc:explicit G4/2 A4/2]
```

#### 50. Look inside: describe and trace

**Tree**

```clojure
grid   ;; from recipe 10
```

**Params**

```clojure
(def grid-tctx (t/tctx grid))
(t/describe grid-tctx)   ;; one row per param: key, value, range, default, algo, doc
;; values {:subdivisions [2 2 3], :adherence 0.5, :density 0.5}
```

**Result**

```clojure
(t/run grid grid-tctx)
;; => [1 0 0 1 0 1 1 0 0 1 0 1]
;; printed
;; :subdivisions  [2 2 3]      vector           [2 2 3]    indisp       meter's factor grouping, each 2, 3, 5 or 7
;; :adherence     0.5          -1.0..1.0        0.5        tilt         how strongly rank predicts sounding (negative inverts)
;; :density       0.5          0.0..1.0         0.5        weights->pulses fraction of pulses kept
;; trace
;; => [(indisp) (tilt (indisp)) (weights->pulses (tilt (indisp)))]
```

### Live

#### 51. A live tree, changed while it plays

**Tree**

```clojure
riff   ;; from recipe 1
(def riff2 (transpose riff))   ;; the tree swapped in later
```

**Params**

```clojure
(def live-tctx (t/tctx riff {:pulse 1/16}))   ;; the values shown are those after the steps below
;; values {:onsets 7, :pulses 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9], :semitones 12}
```

**Result**

```clojure
(t/live! :riff riff live-tctx)   ;; an endless voice follows :riff
(t/setp! live-tctx :onsets 7)   ;; heard on the next note
(t/retree! :riff riff2)   ;; same tctx, fitted: gains :semitones
(t/setp! live-tctx :semitones 12)
(t/stop! :riff)
(t/run riff2 live-tctx)
;; => [!acc:explicit C5/16 D5/16 E5/16 G5/16 A5/16 C5/16 D5/8]
```

#### 52. A transform: reshape any voice's own notes

**Tree**

```clojure
(def up5 (transpose :nodes))   ;; reads :nodes: the voice's own notes
```

**Params**

```clojure
(def up5-tctx (t/tctx up5 {:semitones 5}))
;; values {:nodes ‹the voice's notes›, :semitones 5}
```

**Result**

```clojure
(t/live! :up5 up5 up5-tctx)   ;; a transform only binds the name
(store/read! "[verse: c4 e4 g4]")
(engine/play-connected :verse :algo :up5)   ;; the verse, a fourth higher
((wall/algo :up5) (clojure.core/take 3 (compose/sq :verse)) nil {:path [:TA]})
;; => [!acc:explicit F4/4 A4/4 C5/4]
```

### Material

#### 53. Generated notes become committed musics text

**Tree**

```clojure
riff   ;; from recipe 1
```

**Params**

```clojure
(def p {:onsets 3})
;; values {:onsets 3, :pulses 8, :pulse 1/16, :root 60, :intervals [0 2 4 7 9]}
```

**Result**

```clojure
(notes->mus (t/run riff p))
;; => [!acc:explicit C4/8. D4/8. E4/8]
;; committed
;; => [:s1]
```

## 8. Parameters and the GUI

`(gui tree)` opens a settings window for one tree without the rest of the GUI: a control per param, grouped by algo, a preview of the result that follows every change, and Play once / Live as buttons. It makes the tctx and returns it; `(gui tctx)` and `(gui tree tctx)` use one you have. The window and the REPL share the tctx: `setp!` at the REPL moves the control.

The tctx is the GUI's model. The Wall window lists every live name with its tree, and one control per param: a slider when the spec has a finite range, a dropdown for `:choices`, a note for a function (set it at the REPL), a text field otherwise. A value the spec rejects isn't applied; the window says why.

## 9. Limits worth knowing

- Generated leaves carry what the tree gives them: duration and pitch, and with zip their own volume, articulation and instrument (written back as `\vol:90` … by `notes->mus`). Tempo, key and the rest come from the context where they play.

- `notes->mus` covers MIDI 24–119 (octaves 1–8) and throws outside that range.

- An infinite tree (`cycle`, `shuffle` without a `take` or a finite rhythm) is fine live, but `t/play!` and printing need a finite result.

- All random algos share one RNG (`musics.algo.numeric.rng/default-rng`). `(musics.algo.numeric.rng/seed! 2026)` makes a recipe reproducible, but reseeds every live voice too.

- A `defalgo` lives in the namespace that defines it: re-run it (keep it in a source file) in a fresh session. A tctx is live state, not saved by `persist-session`.

- Keys are checked per tree: two separate trees that happen to share a param name share it only if they share a tctx.
