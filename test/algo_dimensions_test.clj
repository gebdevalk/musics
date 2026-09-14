(ns algo-dimensions-test
  "algo.dimensions -- see that ns's own docstring for the full design
   (a 9-dimension taxonomy over algo/, seeded with a representative,
   evidence-based sample across toolkit/indisp/metric/rhythmic/
   melodic). Covers dimension-space integrity, profile validation,
   the registry, filtering by dimension value, and compatible?'s own
   producer/consumer matching against real, already-registered pairs."
  (:require [clojure.test :refer [deftest is]]
            [algo.dimensions :as dim]))

;; ============================================================
;; dimension-space -- the declared shape of the whole space
;; ============================================================

(deftest dimension-space-has-nine-dimensions-each-with-a-doc-and-values
  (is (= 9 (count (dim/dimension-names))))
  (doseq [[_ {:keys [doc values]}] dim/dimension-space]
    (is (string? doc))
    (is (set? values))
    (is (seq values))))

(deftest valid-value?-true-for-a-declared-value-false-otherwise
  (is (true? (dim/valid-value? :input :coll)))
  (is (false? (dim/valid-value? :input :not-a-real-value)))
  (is (false? (dim/valid-value? :not-a-real-dimension :coll))
      "an unknown dimension is false too, not an error at this level"))

;; ============================================================
;; validate-profile! -- fails loudly, at registration time
;; ============================================================

(deftest validate-profile!-accepts-a-well-formed-profile
  (is (= {:input :coll :output :coll} (dim/validate-profile! {:input :coll :output :coll}))))

(deftest validate-profile!-throws-for-an-unknown-dimension
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown dimension"
        (dim/validate-profile! {:not-a-real-dimension :coll}))))

(deftest validate-profile!-throws-for-an-invalid-value
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid value"
        (dim/validate-profile! {:input :not-a-real-value}))))

;; ============================================================
;; register-profile!/profile/profiles/profiles-of
;; ============================================================

(deftest register-profile!-then-profile-round-trips
  (binding [dim/*profiles* (atom {})]
    (dim/register-profile! 'test/fn {:input :coll :output :scalar})
    (is (= {:input :coll :output :scalar} (dim/profile 'test/fn)))))

(deftest register-profile!-rejects-an-invalid-profile-before-storing-anything
  (binding [dim/*profiles* (atom {})]
    (is (thrown? clojure.lang.ExceptionInfo
          (dim/register-profile! 'test/bad {:input :nope})))
    (is (nil? (dim/profile 'test/bad)))))

(deftest unregister-profile!-forgets-only-its-own-sym
  (binding [dim/*profiles* (atom {})]
    (dim/register-profile! 'test/a {:input :coll})
    (dim/register-profile! 'test/b {:input :scalar})
    (dim/unregister-profile! 'test/a)
    (is (nil? (dim/profile 'test/a)))
    (is (some? (dim/profile 'test/b)))))

(deftest profiles-of-finds-only-matching-syms
  (binding [dim/*profiles* (atom {})]
    (dim/register-profile! 'test/gen1 {:statefulness :stateful-closure})
    (dim/register-profile! 'test/gen2 {:statefulness :stateful-closure})
    (dim/register-profile! 'test/pure {:statefulness :stateless})
    (is (= #{'test/gen1 'test/gen2} (set (dim/profiles-of :statefulness :stateful-closure))))))

;; ============================================================
;; seed data -- the real, evidence-based profiles this ns ships with
;; ============================================================

(deftest seeded-profiles-cover-every-directory-surveyed
  (is (some? (dim/profile 'algo.toolkit/weighted-shuffle)))
  (is (some? (dim/profile 'algo.indisp.indispensability/indispensability)))
  (is (some? (dim/profile 'algo.metric.metric/modular-rhythm)))
  (is (some? (dim/profile 'algo.rhythmic.rhythm/euclidean-rhythm)))
  (is (some? (dim/profile 'algo.melodic.melody/markov-train))))

(deftest cyclic-random-is-classified-as-a-stateful-closure-producer
  (is (= {:statefulness :stateful-closure :role :producer}
         (select-keys (dim/profile 'algo.toolkit/cyclic-random) [:statefulness :role]))))

;; ============================================================
;; shape-compatible / compatible? -- producer/consumer matching,
;; against real, already-registered pairs, confirming both real
;; algo-matrix.txt findings (direct/glue) and the domain-type guard
;; ============================================================

(deftest shape-compatible-coll-to-coll-is-direct
  (is (= :direct (dim/shape-compatible :coll :coll))))

(deftest shape-compatible-fn0-to-coll-needs-glue
  (is (= :glue (dim/shape-compatible :fn0 :coll))))

(deftest shape-compatible-bool-to-anything-is-nil
  (is (nil? (dim/shape-compatible :bool :coll)))
  (is (nil? (dim/shape-compatible :bool :scalar))))

(deftest compatible?-weighted-shuffle-into-only-is-direct
  ;; both :coll/:primitive -- a real algo-matrix.txt worked example
  ;; (choose-n of shuffle), same shape family as only.
  (is (= :direct (dim/compatible? 'algo.toolkit/weighted-shuffle 'algo.toolkit/only))))

(deftest compatible?-cyclic-random-into-weighted-shuffle-needs-glue
  ;; a stateful Fn0 generator can feed a Coll-consumer, but only via
  ;; the standard (repeatedly n producer) glue -- same real example
  ;; algo-matrix.txt verified live (shuffle of repeatedly cyclic-random).
  (is (= :glue (dim/compatible? 'algo.toolkit/cyclic-random 'algo.toolkit/weighted-shuffle))))

(deftest compatible?-poisson-events-into-only-is-direct
  ;; onset-timing IS a plain coll of times once produced -- real
  ;; algo-matrix.txt example (only of poisson-events).
  (is (= :direct (dim/compatible? 'algo.toolkit/poisson-events 'algo.toolkit/only))))

(deftest compatible?-markov-train-into-markov-generate-is-direct-via-model
  ;; the :model shape's whole reason for existing: markov-train's own
  ;; output is ONLY meaningful as markov-generate's own input.
  (is (= :direct (dim/compatible? 'algo.melodic.melody/markov-train 'algo.melodic.melody/markov-generate))))

(deftest compatible?-is-nil-when-domain-types-disagree-even-with-matching-shape
  ;; split-leafs' own output is :coll, same SHAPE as weighted-shuffle's
  ;; own input -- but split-leafs' :out-type is :leaf (real Leaf-shaped
  ;; maps) while weighted-shuffle's :in-type is :primitive (bare pitch
  ;; integers) -- NOT interchangeable data despite the shared shape.
  (is (nil? (dim/compatible? 'algo.toolkit/split-leafs 'algo.toolkit/weighted-shuffle))))

(deftest compatible?-is-nil-for-an-unclassified-sym
  (is (nil? (dim/compatible? 'not/a-real-sym 'algo.toolkit/only)))
  (is (nil? (dim/compatible? 'algo.toolkit/only 'not/a-real-sym))))

;; ============================================================
;; Full algo.toolkit classification (2026-09-14) -- every one of its
;; 77 public vars now has a profile, not just the 10-fn representative
;; sample the ns started with.
;; ============================================================

(deftest every-toolkit-public-var-is-classified
  (require 'algo.toolkit)
  (let [toolkit-syms (set (map #(symbol "algo.toolkit" (name %))
                                (keys (ns-publics (find-ns 'algo.toolkit)))))
        classified   (set (filter #(= "algo.toolkit" (namespace %)) (keys (dim/profiles))))]
    (is (= toolkit-syms classified)
        "every real toolkit var has a profile, and nothing classified
         under algo.toolkit/* is a typo'd/stale symbol")))

(deftest the-twelve-continuous-distributions-share-the-identical-profile
  (doseq [sym '[uniform normal exponential gamma chi-square inverse-gamma
                weibull cauchy student-t laplace log-normal beta]]
    (is (= {:input :scalar :output :scalar :in-type :primitive :out-type :primitive
            :role :producer :statefulness :stateless :determinism :random
            :granularity :per-event :dependency :standalone}
           (dim/profile (symbol "algo.toolkit" (name sym)))))))

(deftest stateful-closure-generators-are-found-together-regardless-of-own-output-shape
  (is (= #{'algo.toolkit/cyclic-random 'algo.toolkit/random-walk
           'algo.toolkit/biased-walk 'algo.toolkit/smooth-walk}
         (set (filter #{'algo.toolkit/cyclic-random 'algo.toolkit/random-walk
                         'algo.toolkit/biased-walk 'algo.toolkit/smooth-walk}
                       (dim/profiles-of :statefulness :stateful-closure))))
      "all four hide state in a closure; smooth-walk's own :output is :fn1
       (takes a target arg) rather than :fn0, but :statefulness still finds
       it alongside the 0-arg generators -- the two dimensions are
       independent, exactly as designed")
  (is (= :fn0 (:output (dim/profile 'algo.toolkit/random-walk))))
  (is (= :fn1 (:output (dim/profile 'algo.toolkit/smooth-walk)))))

(deftest deterministic-scalar-transformers-are-genuinely-not-random
  (is (= :deterministic (:determinism (dim/profile 'algo.toolkit/gcd))))
  (is (= :deterministic (:determinism (dim/profile 'algo.toolkit/farey))))
  (is (= :deterministic (:determinism (dim/profile 'algo.toolkit/cosr)))
      "cosr is a deterministic PRODUCER (generates a value from idx/amp/base/
       period alone, no upstream coll needed), distinguished from the random
       producers (uniform et al) purely by :determinism, not :role"))

(deftest zip-parts-input-is-classified-as-model-not-a-plain-coll
  (is (= :model (:input (dim/profile 'algo.toolkit/zip-parts)))
      "a NAMED map of independently-cycling streams, not a flat collection --
       genuinely different from e.g. rotate's own plain :coll input"))

(deftest producers-for-and-consumers-for-are-inverse-views-of-compatible?
  (binding [dim/*profiles* (atom {})]
    (dim/register-profile! 'test/producer {:output :coll :out-type :primitive})
    (dim/register-profile! 'test/consumer {:input :coll :in-type :primitive})
    (is (= [['test/producer :direct]] (dim/producers-for 'test/consumer)))
    (is (= [['test/consumer :direct]] (dim/consumers-for 'test/producer)))))
