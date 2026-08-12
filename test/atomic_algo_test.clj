(ns ^:domain atomic-algo-test
  "Execution-level coverage for @[ ] (AtomicAlgo) -- registry lookup,
   positional Data/Primitive/nested-algo args, splicing real Leaf
   children, and the Unknown-algo error path. grammar_parse_test.clj
   only covers that this syntax *parses*; nothing before this file
   exercised what walk-atomic-algo/run-algo actually do with it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [input.grammar-parser :as gp]
            [input.algo-registry :as algo-registry]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- walk [text]
  (gp/parse-domain-string text))

;; atomic-algo-registry is a shared defonce atom -- any test that
;; registers a throwaway algo has to leave it exactly as it found it,
;; or a later test (in this file, another file, or a REPL session that
;; loaded this namespace) could see a stale registration. Snapshot/
;; restore around every test rather than trusting each test to clean up
;; after itself correctly.
(defn- restore-registry-fixture [f]
  (let [before @algo-registry/atomic-algo-registry]
    (try (f) (finally (reset! algo-registry/atomic-algo-registry before)))))

(use-fixtures :each restore-registry-fixture)

;; ============================================================
;; colorTalea (the built-in registration) -- real execution
;; ============================================================

(deftest atomic-algo-splices-real-leaves-from-a-registered-fn
  (let [{:keys [tree]} (walk "{ct: @[ colorTalea [C4 D4 E4 F4 G4 A4 B4] [/4. /8 /16 /4] ] }")
        leaves (:children (get tree :ct))]
    (is (= 28 (count leaves)) "one full isorhythmic period, lcm(7,4)")
    (is (= [60 62 64 65 67 69 71] (map (comp first :pitches) (take 7 leaves)))
        "color cycles mod 7, absolute pitches C4..B4")
    (is (= [3/8 1/8 1/16 1/4] (map :duration (take 4 leaves)))
        "talea cycles mod 4 -- 4. 8 16 4")
    (is (every? #(= :LEAF (:type %)) leaves)
        "real Leaf records, not raw {:type :pitch/:duration ...} atoms")))

(deftest atomic-algo-inside-repeat-unfold-computes-exactly-one-period
  ;; The algorithm itself never sees "5" -- \repeat unfold is what asks
  ;; for repetition; run-algo/walk-atomic-algo always compute exactly
  ;; one period for the args given.
  (let [{:keys [tree]} (walk "{ct: \\repeat unfold 5 { @[ colorTalea [C4 D4 E4 F4 G4 A4 B4] [/4. /8 /16 /4] ] } }")
        iter (first (:children (get tree :ct)))]
    (is (= :REPEAT (:type iter)))
    (is (= {:count 5 :repeat-type :unfold} (:params iter)))
    (is (= 28 (count (:children (:source iter))))
        "the Iterator's :source holds one period -- repetition is :count 5, not 140 leaves")))

;; ============================================================
;; Positional args -- Data and bare Primitive, mixed and ordered
;; ============================================================

(deftest atomic-algo-mixes-primitive-and-data-args-positionally
  (let [transpose-cycle (fn [semitones pitches durs]
                           (mapv (fn [i p] [(+ p semitones) (nth durs (mod i (count durs)))])
                                 (range (count pitches)) pitches))]
    (algo-registry/register-algo! "transposeCycle" transpose-cycle)
    (let [{:keys [tree]} (walk "{tc: @[ transposeCycle 2 [C4 D4 E4] [/4 /8] ] }")
          leaves (:children (get tree :tc))]
      (is (= [62 64 66] (map (comp first :pitches) leaves))
          "each pitch shifted by the leading scalar arg (2 semitones)")
      (is (= [1/4 1/8 1/4] (map :duration leaves))
          "durations cycle from the Data arg, independent of the scalar"))))

;; ============================================================
;; Recursion -- an Arg can itself be another AtomicAlgo call
;; ============================================================

(deftest atomic-algo-args-are-recursive-with-no-flattening
  (let [pitch-gen (fn [base n] (mapv #(+ base %) (range n)))
        dur-gen   (fn [durs] (vec durs))
        zip-pd    (fn [pitches durs]
                    (mapv (fn [i p] [p (nth durs (mod i (count durs)))])
                          (range (count pitches)) pitches))]
    (algo-registry/register-algo! "pitchGen" pitch-gen)
    (algo-registry/register-algo! "durGen" dur-gen)
    (algo-registry/register-algo! "zip" zip-pd)
    (let [{:keys [tree]} (walk "{z: @[ zip @[ pitchGen 60 4 ] @[ durGen [/4 /8] ] ] }")
          leaves (:children (get tree :z))]
      (is (= [60 61 62 63] (map (comp first :pitches) leaves))
          "pitchGen's plain flat pitch seq (not [pitch duration] pairs) passed straight through")
      (is (= [1/4 1/8 1/4 1/8] (map :duration leaves))
          "durGen's plain flat duration seq passed straight through, cycling mod 2"))))

(deftest atomic-algo-recursion-goes-arbitrarily-deep
  (let [pitch-gen (fn [base n] (mapv #(+ base %) (range n)))
        double-it (fn [xs] (mapv #(* 2 %) xs))
        dur-gen   (fn [durs] (vec durs))
        zip-pd    (fn [pitches durs]
                    (mapv (fn [i p] [p (nth durs (mod i (count durs)))])
                          (range (count pitches)) pitches))]
    (algo-registry/register-algo! "pitchGen" pitch-gen)
    (algo-registry/register-algo! "double" double-it)
    (algo-registry/register-algo! "durGen" dur-gen)
    (algo-registry/register-algo! "zip" zip-pd)
    (let [{:keys [tree]} (walk "{z: @[ zip @[ double @[ pitchGen 60 3 ] ] @[ durGen [/4] ] ] }")
          leaves (:children (get tree :z))]
      (is (= [120 122 124] (map (comp first :pitches) leaves))
          "3 levels deep: zip(double(pitchGen(60 3)), durGen([/4]))"))))

;; ============================================================
;; Unknown algo -- fails clean, doesn't silently no-op
;; ============================================================

(deftest atomic-algo-unknown-name-throws-with-a-clear-message
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown algo: nope"
        (walk "{x: @[ nope [C4] ] }"))))

;; ============================================================
;; register-algo!/unregister-algo!/algos -- the registry's own API
;; ============================================================

(deftest register-algo-then-unregister-algo-round-trips
  (let [echo (fn [x] [[x 1/4]])]
    (is (not (contains? @algo-registry/atomic-algo-registry "echoTest"))
        "not registered before the test touches it")
    (algo-registry/register-algo! "echoTest" echo "echoes its single arg back")
    (is (contains? @algo-registry/atomic-algo-registry "echoTest"))
    (is (= "echoes its single arg back" (:doc (get @algo-registry/atomic-algo-registry "echoTest"))))
    (let [{:keys [tree]} (walk "{e: @[ echoTest 60 ] }")]
      (is (= [60] (map (comp first :pitches) (:children (get tree :e))))))
    (algo-registry/unregister-algo! "echoTest")
    (is (not (contains? @algo-registry/atomic-algo-registry "echoTest")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown algo: echoTest"
          (walk "{e2: @[ echoTest 60 ] }")))))

;; ============================================================
;; AlgoName -- a hyphen is allowed, unlike most identifiers here
;; ============================================================

(deftest algo-name-token-permits-a-hyphen
  ;; Top-level AtomicAlgo (unlike a nested Arg -- see the recursion tests
  ;; above) must return real [pitch duration] pairs, since walk-atomic-algo
  ;; converts the result straight into Leaf children.
  (let [my-algo (fn [base n] (mapv (fn [i] [(+ base i) 1/4]) (range n)))]
    (algo-registry/register-algo! "my-algo" my-algo)
    (let [{:keys [tree]} (walk "{p: @[ my-algo 60 3 ] }")]
      (is (= [60 61 62] (map (comp first :pitches) (:children (get tree :p))))
          "a registered hyphenated name resolves the same as any other"))))
