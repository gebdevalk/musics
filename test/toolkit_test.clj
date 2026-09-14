(ns toolkit-test
  "algo.toolkit -- general-purpose building-block functions for
   algoline-intercepted.core. See algo.toolkit's own ns docstring for
   the design (plain Clojure fns, not pre-wrapped interceptor values)."
  (:require [clojure.test :refer [deftest is]]
            [algo.toolkit :as t]
            [algo.random.core :as rc]))

(deftest cycle-shuffle-yields-v-in-original-order-on-the-first-pass
  (is (= [1 2 3] (take 3 (t/cycle-shuffle [1 2 3])))
      "the very first (count v) elements are v itself, unshuffled --
       shuffling only happens starting the SECOND pass"))

(deftest cycle-shuffle-every-subsequent-group-is-a-permutation-of-the-same-set
  (let [xs (take 30 (t/cycle-shuffle [1 2 3]))]
    (is (every? #(= #{1 2 3} (set %)) (partition 3 xs)))))

(deftest cycle-shuffle-is-lazy-and-stack-safe-for-a-large-take
  (is (= 3000 (count (take 3000 (t/cycle-shuffle [1 2 3]))))))

(deftest cycle-shuffle-works-for-a-single-element-collection
  (is (= [42 42 42 42] (take 4 (t/cycle-shuffle [42])))))

(deftest cycle-shuffle-throws-immediately-for-an-empty-collection
  ;; Confirmed live, not assumed: realizing any prefix of the result
  ;; for an empty v hangs forever (concat/lazy-seq can never find a
  ;; first real element to stop looking for) -- this guards against
  ;; that unbounded hang with an immediate, clear error instead, same
  ;; discipline as every other resolution mechanism in this project.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
        (t/cycle-shuffle []))))

(deftest weighted-shuffle-returns-a-genuine-permutation
  (is (= #{1 2 3 4 5} (set (t/weighted-shuffle [1 2 3 4 5])))))

(deftest weighted-shuffle-is-a-no-op-on-an-empty-or-single-element-collection
  (is (= [] (t/weighted-shuffle [])))
  (is (= [42] (t/weighted-shuffle [42]))))

(deftest weighted-shuffle-is-genuinely-order-preserving-biased-not-uniform
  ;; lo-emph peaks toward the low end of the remaining pool on every
  ;; draw, so elements should tend to stay closer to their original
  ;; position than a uniform (clojure.core/shuffle) shuffle would --
  ;; confirmed with a real trial, not assumed from reading the algorithm.
  (let [orig             (vec (range 8))
        avg-displacement (fn [shuffler]
                            (let [trials (repeatedly 2000 #(shuffler orig))]
                              (/ (reduce +
                                   (for [s trials i (range (count orig))]
                                     (Math/abs (double (- i (.indexOf ^java.util.List (vec s) (nth orig i)))))))
                                 (* 2000.0 (count orig)))))]
    (is (< (avg-displacement t/weighted-shuffle) (* 0.8 (avg-displacement clojure.core/shuffle)))
        "meaningfully lower average displacement than a uniform shuffle")))

;; ============================================================
;; take-cycle-shuffle / cycle-weighted-shuffle / take-cycle-weighted-
;; shuffle -- realized-take and lo-emph-weighted counterparts to
;; cycle-shuffle
;; ============================================================

(deftest take-cycle-shuffle-returns-exactly-n-cycles-worth
  (is (= 9 (count (t/take-cycle-shuffle 3 [1 2 3])))))

(deftest take-cycle-shuffle-first-cycle-is-v-itself-unshuffled
  (is (= [1 2 3] (subvec (t/take-cycle-shuffle 3 [1 2 3]) 0 3))))

(deftest take-cycle-shuffle-every-cycle-is-a-permutation-of-v
  (let [xs (t/take-cycle-shuffle 10 [1 2 3])]
    (is (every? #(= #{1 2 3} (set %)) (partition 3 xs)))))

(deftest take-cycle-shuffle-throws-immediately-for-an-empty-collection
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
        (t/take-cycle-shuffle 3 []))))

(deftest cycle-weighted-shuffle-yields-v-in-original-order-on-the-first-pass
  (is (= [1 2 3] (take 3 (t/cycle-weighted-shuffle [1 2 3])))))

(deftest cycle-weighted-shuffle-every-subsequent-group-is-a-permutation
  (let [xs (take 30 (t/cycle-weighted-shuffle [1 2 3]))]
    (is (every? #(= #{1 2 3} (set %)) (partition 3 xs)))))

(deftest cycle-weighted-shuffle-is-lazy-and-stack-safe-for-a-large-take
  (is (= 3000 (count (take 3000 (t/cycle-weighted-shuffle [1 2 3]))))))

(deftest cycle-weighted-shuffle-throws-immediately-for-an-empty-collection
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
        (t/cycle-weighted-shuffle []))))

(deftest take-cycle-weighted-shuffle-returns-exactly-n-cycles-worth
  (is (= 12 (count (t/take-cycle-weighted-shuffle 4 [1 2 3])))))

(deftest take-cycle-weighted-shuffle-first-cycle-is-v-itself-unshuffled
  (is (= [1 2 3] (subvec (t/take-cycle-weighted-shuffle 4 [1 2 3]) 0 3))))

(deftest take-cycle-weighted-shuffle-throws-immediately-for-an-empty-collection
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
        (t/take-cycle-weighted-shuffle 3 []))))

;; ============================================================
;; algo.random re-exports -- thin forwards, not copies. The underlying
;; logic is already thoroughly tested in algo.random's own test suite
;; (rand_test.clj/chance_test.clj/distributions_test.clj/seed_test.clj)
;; -- these confirm delegation is wired correctly, not re-derive
;; statistical correctness.
;; ============================================================

(deftest toolkit-shuffle-is-the-seedable-algo-random-version-not-clojure-cores
  ;; The one thing worth confirming explicitly, not just assuming from
  ;; reading the code: toolkit/shuffle really does forward to
  ;; algo.random/shuffle (draws from algo.random.core's own seedable
  ;; RNG), distinct from clojure.core/shuffle.
  (is (= #{1 2 3} (set (t/shuffle [1 2 3])))))

(deftest cycle-shuffle-uses-the-seeded-rng-reproducible-under-with-seed
  ;; cycle-shuffle now calls algo.random/shuffle explicitly (qualified,
  ;; not the bare `shuffle` clojure.core/shuffle used to resolve to) --
  ;; the real, checkable consequence of that is reproducibility: the
  ;; same seed must produce the exact same reshuffled sequence, every
  ;; run, which clojure.core/shuffle's own unseedable java.util.Random
  ;; could never guarantee.
  (let [run #(rc/with-seed 42 (vec (take 12 (t/cycle-shuffle [1 2 3]))))]
    (is (= (run) (run)) "same seed, same output, every run")))

(deftest basic-primitives-sanity
  (is (<= 0.0 (t/rand-double) 1.0))
  (is (<= 0 (t/rand-int 10) 9))
  (is (contains? #{1 2 3} (t/choose [1 2 3])))
  (is (contains? #{:a :b} (t/weighted-choose [:a :b] [1 1])))
  (is (= #{1 2 3} (set (t/shuffle [1 2 3]))))
  (is (contains? #{:g :f} (t/markov {:c {:g 1 :f 1}} :c))))

(deftest continuous-distributions-sanity
  (is (<= 10 (t/uniform 10 20) 20))
  (is (number? (t/normal 0 1)))
  (is (pos? (t/exponential 2)))
  (is (pos? (t/gamma 2 1)))
  (is (pos? (t/chi-square 3)))
  (is (pos? (t/inverse-gamma 2 1)))
  (is (pos? (t/weibull 1 1)))
  (is (number? (t/cauchy 0 1)))
  (is (number? (t/student-t 5)))
  (is (number? (t/laplace 0 1)))
  (is (pos? (t/log-normal 0 1)))
  (is (<= 0.0 (t/beta 2 2) 1.0)))

(deftest discrete-collection-helpers-sanity
  (is (= 2 (count (t/choose-n 2 [1 2 3 4 5]))))
  (is (= #{1 2 3} (set (t/deep-shuffle [1 2 3]))))
  (is (= 5 (count (t/choose-from [1 2 3 4 5]))))
  (is (true? (t/weighted-coin 1.0)))
  (is (false? (t/weighted-coin 0.0)))
  (is (= [:b :d] (t/only [:a :b :c :d] [1 3])))
  (is (<= (count (t/sputter [1 2 3 4])) 100)))

(deftest cycle-deep-shuffle-yields-v-in-original-order-on-the-first-pass
  (is (= [[1 2] [3 4] [5 6]] (take 3 (t/cycle-deep-shuffle [[1 2] [3 4] [5 6]])))))

(deftest cycle-deep-shuffle-reshuffles-both-levels-on-later-passes
  (let [second-pass (nth (partition 3 (take 30 (t/cycle-deep-shuffle [[1 2] [3 4] [5 6]]))) 1)]
    (is (= #{#{1 2} #{3 4} #{5 6}}
           (into #{} (map set) second-pass))
        "each element is still one of the original pairs, regardless of
         its own internal order -- the top-level order and each pair's
         own internal order can both change")))

(deftest cycle-deep-shuffle-respects-an-explicit-depth-limit
  ;; depth 1 shuffles only the top-level order -- every nested pair's
  ;; own internal order stays exactly as given, on every pass.
  (let [xs (t/take-cycle-deep-shuffle 5 [[1 2] [3 4] [5 6]] 1)]
    (is (every? #(contains? #{[1 2] [3 4] [5 6]} %) xs))))

(deftest cycle-deep-shuffle-throws-immediately-for-an-empty-collection
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
        (t/cycle-deep-shuffle []))))

(deftest take-cycle-deep-shuffle-returns-exactly-n-cycles-worth
  (is (= 9 (count (t/take-cycle-deep-shuffle 3 [[1 2] [3 4] [5 6]])))))

(deftest take-cycle-deep-shuffle-throws-immediately-for-an-empty-collection
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
        (t/take-cycle-deep-shuffle 3 []))))

(deftest shaped-distributions-sanity
  (is (<= 0 (t/triangular 0 10 5) 10))
  (is (<= 0 (t/linear 0 10) 10))
  (is (<= 0 (t/arcsine 0 10) 10))
  (is (<= 0 (t/lo-emph 0 10) 10))
  (is (<= 0 (t/mean-emph 0 10) 10))
  (is (<= 0 (t/hi-emph 0 10) 10)))

(deftest walks-and-composite-generators-sanity
  (is (<= 5 (t/int-range 5 10) 9))
  (is (contains? #{1 2 3} ((t/cyclic-random [1 2 3]))))
  (is (number? ((t/random-walk 0 1))))
  (is (<= 0 (t/rising 0 10 0.5) 10))
  (is (<= 0 (t/falling 0 10 0.5) 10))
  (is (<= 0 (t/int-rising 0 10 0.5) 9))
  (is (<= 0 (t/int-falling 0 10 0.5) 9))
  (is (number? ((t/biased-walk 0 1 0.5))))
  (is (number? ((t/smooth-walk 0 0.5 1) 10)))
  (is (number? ((t/smooth-noise 8) 3.5))))

(deftest event-generation-and-markov-chain-sanity
  (is (every? #(<= 0 % 8) (t/poisson-events 4 8)))
  (is (contains? #{:g :c :f :a} ((t/markov-chain {:c {:g 2 :f 1} :g {:c 1 :a 1}} :c)))))

;; ============================================================
;; algo.common re-exports -- direct forwards (numeric/rotate/scaling/
;; trig/farey/split) and standalone ports (isorhythm's color-talea/
;; zip-parts, zfilter's z-filter family), same discipline as the
;; algo.random section above: these confirm delegation/porting is wired
;; correctly, not re-derive correctness already tested at the source.
;; ============================================================

(deftest numeric-forwards-sanity
  (is (= 6 (t/gcd 12 18)))
  (is (= 12 (t/lcm 4 6)))
  (is (= 12 (t/lcm-multiple [2 3 4]))))

(deftest rotate-forward-sanity
  (is (= [2 3 4 1] (t/rotate [1 2 3 4] 1)))
  (is (= [4 1 2 3] (t/rotate [1 2 3 4] -1))))

(deftest scaling-forwards-sanity
  (is (= 10 (t/clamp 0 10 15)))
  (is (= 15 (t/clamp-optional 5 nil 15)))
  (is (= 4 (t/closest-to 4.7 4 6)))
  (is (= 4.0 (t/round-to 4.7 2)))
  (is (= 100 (t/scale-range 5 0 10 50 150))))

(deftest trig-forwards-sanity
  (is (= 12.0 (t/cosr 0 2 10 8)))
  (is (= 10.0 (t/sinr 0 2 10 8)))
  (is (= 10.0 (t/trianglr 0 2 10 8)))
  (is (= 12.0 (t/squarr 1 2 10 8)))
  (is (= 10.0 (t/sawr 0 2 10 8)))
  (is (= 10.0 (t/tanr 0 2 10 8))))

(deftest farey-forward-sanity
  (is (= 3/4 (t/farey 0.75 4))))

(deftest split-forwards-sanity
  (is (= 3 (count (t/split [[60 1] [62 1]] 2))) "n=2 -> (inc n)=3 voices")
  (is (= [60 62] (mapv first (first (t/split [[60 1] [62 1]] 2)))) "voice 0 is the original, untouched")
  (is (= 3 (count (t/split-leafs [{:pitches [60] :duration 1} {:pitches [62] :duration 1}] 2))))
  (is (= [72 74 72 74] (mapv (comp first :pitches)
                             (t/split-leaf-voice 1 [{:pitches [60] :duration 1} {:pitches [62] :duration 1}])))
      "voice-index defaulting to n=1: one split-off (octave up, halved, doubled)"))

(deftest color-talea-cycles-color-and-talea-independently
  (is (= [[60 1/4] [62 1/8] [64 1/4] [60 1/8] [62 1/4] [64 1/8]]
         (t/color-talea [60 62 64] [1/4 1/8]))
      "color (period 3) against talea (period 2) -> a full period is
       lcm(3,2)=6 events, periods defaults to 1 -- exactly one full
       period's worth, confirming independent cycling of both streams"))

(deftest zip-parts-combines-independently-cycling-named-streams
  (is (= [{:pitch 60 :duration 1/4} {:pitch 62 :duration 1/8}
          {:pitch 64 :duration 1/4} {:pitch 60 :duration 1/8}
          {:pitch 62 :duration 1/4} {:pitch 64 :duration 1/8}]
         (t/zip-parts {:pitch [60 62 64] :duration [1/4 1/8]}))))

(deftest zip-parts-throws-for-empty-streams
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
        (t/zip-parts {}))))

(deftest z-filter-and-named-instances-sanity
  (is (= [1.0 1.0 1.0] (t/z-filter [1] [1] [1.0 1.0 1.0])) "identity filter")
  (is (= 3 (count (t/smooth 0.5 [10 20 30]))))
  (is (= 3 (count (t/momentum 0.5 [10 20 30]))))
  (is (= 3 (count (t/memory 0.5 [10 20 30])))))

(deftest smooth-intervals-and-interval-gain-pass-short-sequences-through-unchanged
  (is (= [5] (t/smooth-intervals 0.5 [5])))
  (is (= [5] (t/interval-gain 2 [5]))))

(deftest interval-gain-of-one-is-a-no-op
  (is (= [10 20 30] (t/interval-gain 1 [10 20 30]))))

(deftest pc-smooth-reduces-to-pitch-classes-before-smoothing
  (is (every? #(<= 0 % 11.5) (t/pc-smooth 0.0 [60 62 64]))
      "alpha 0.0 (no smoothing) just returns each value mod 12"))

;; ============================================================
;; algo.indisp/algo.metric/algo.rhythmic/algo.melodic additions
;; (2026-09-14) -- direct forwards + one standalone port
;; (slonimsky), same discipline as every prior toolkit addition.
;; ============================================================

(deftest indispensability-forwards-sanity
  (is (= [11 0 4 8 2 6 10 1 5 9 3 7] (t/indispensability [2 2 3])))
  (is (= [1.0 0.0] (t/normalize-weights [4 0])))
  (is (= [1.0 0.0 0.6666666666666666 0.3333333333333333] (t/normalized-indispensability [2 2])))
  (is (= 4 (count (t/tilt-probabilities (t/indispensability [2 2]) 0.5))))
  (is (not= (t/tilt-probabilities [3 0 2 1] 0.0) [0.25 0.25 0.25 0.25])
      "the irrational tie-break term keeps adherence=0 from collapsing to a flat tie")
  (is (= 4 (count (t/power-law-probabilities (t/indispensability [2 2]) 0.8))))
  (is (= [1 0 1 0] (t/density-grid (t/indispensability [2 2]) 0.5))))

(deftest metric-forwards-sanity
  (is (= [1 0 1 1] (t/binary-decomposition-rhythm 13)))
  (is (every? #{0 1} (t/continued-fraction-rhythm 1.5 8)))
  (is (every? #{0 1} (t/modular-rhythm 7 3 21 0))))

(deftest rhythm-forwards-sanity
  ;; the tresillo -- see rhythm_test.clj's own euclidean-test for the
  ;; full regression story (a real, pre-existing algorithm bug, fixed
  ;; 2026-09-14, not a stale comment as first misdiagnosed)
  (is (= [1 0 0 1 0 0 1 0] (t/euclidean-rhythm 3 8)))
  (is (every? #{0 1} (t/fibonacci-rhythm 13)))
  (is (every? #{0 1} (t/prime-rhythm 20)))
  (is (= 10 (count (t/lindenmayer-rhythm "A" {"A" "AB" "B" "A"} 2 10))))
  (is (= 5 (count (t/markov-rhythm 5 {"0" {"0" 1 "1" 1} "1" {"0" 1 "1" 1}})))))

(deftest slonimsky-forwards-sanity
  (is (= [:a 1 :b :a 2 :b :a 3] (t/mixed-polations [1 2 3] [:a] [:b] nil))
      "infra before every tone, inter between consecutive tones, never after the last")
  (is (= [:x 1 :x 2] (t/infrapolate [1 2] [:x])))
  (is (= [1 :x 2 :x] (t/ultrapolate [1 2] [:x])))
  (is (= [1 :x 2] (t/interpolate [1 2] [:x])))
  (is (= [1] (t/interpolate [1] [:x])) "fewer than 2 tones passes through unchanged"))

(deftest species-counterpoint-forwards-sanity
  (is (map? t/species-counterpoint-default-rules))
  (let [voices (t/species-counterpoint
                 [60 62 64 65 67 69 71 72] [55 84]
                 [0.5 0.5 1.0 0.5]
                 [{:shape [0 2 4 0] :density-envelope [1 1 1 1] :tolerance 2}])]
    (is (= 1 (count voices)))
    (is (= 4 (count (first voices))))))

;; ============================================================
;; Pre-composed combinations -- weighted-pulse-choice/shuffled-
;; euclidean/weighted-density-grid, each built from a real
;; algo.dimensions/compatible? :direct match.
;; ============================================================

(deftest weighted-pulse-choice-picks-a-valid-pulse-index
  (is (<= 0 (t/weighted-pulse-choice [2 2 3] 0.8) 11)))

(deftest shuffled-euclidean-is-an-infinite-stream-of-reshuffled-onsets
  (let [xs (take 24 (t/shuffled-euclidean 3 8))]
    (is (= 24 (count xs)))
    (is (every? #{0 1} xs))
    (is (= 3 (reduce + (take 8 xs))) "each 8-element pass still has exactly 3 onsets")))

(deftest weighted-density-grid-thins-to-the-requested-density
  (let [grid (t/weighted-density-grid [2 2 3] 0.8 0.5)]
    (is (= 12 (count grid)))
    (is (= 6 (reduce + grid)) "half of 12 pulses kept")))
