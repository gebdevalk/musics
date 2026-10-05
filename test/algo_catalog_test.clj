(ns ^:algo algo-catalog-test
  "Every generative fn in algo/{melodic,metric,random,rhythmic} and the
   bridges (algo/bridge.clj) is a tree
   algo (it carries :algo metadata), or is listed below with the reason
   it isn't -- and every algo runs with its own defaults."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [algo.tree :as t]
            [algo.tree.lib :as lib]
            [algo.random.core :as rc]
            [algo.melodic.melody :as melody]
            [core.domain.flat-domain :as d]))

(def not-algos
  "Public vars that aren't tree algos, and why."
  {'algo.melodic.counterpoint/default-rules          "data: counterpoint's :rules default"
   'algo.melodic.melody/a-minor                      "data: a scale"
   'algo.melodic.melody/c-major                      "data: a scale"
   'algo.melodic.melody/c-pentatonic                 "data: a scale"
   'algo.melodic.melody/cadence-constraint           "builds a constraint fn, a :constraints value"
   'algo.melodic.melody/direction-limit-constraint   "builds a constraint fn, a :constraints value"
   'algo.melodic.melody/max-leap-constraint          "builds a constraint fn, a :constraints value"
   'algo.melodic.melody/no-repeat-constraint         "a constraint fn, a :constraints value"
   'algo.random/choose                               "one draw; choose-from/choose-n are the sequence forms"
   'algo.random/markov                               "one step; markov-chain (chain) walks it"
   'algo.random/rand-double                          "primitive; uniform covers it"
   'algo.random/rand-int                             "primitive; int-range covers it"
   'algo.random/shuffle                              "lib's shuffled covers it"
   'algo.random/smooth-noise                         "returns a curve of t; lib's noise samples it"
   'algo.random/weighted-choose                      "one draw; lib's pick covers it"
   'algo.random/weighted-coin                        "one boolean; stochastic/threshold make grids"
   'algo.rhythmic.necklace/vuza-canon                "always [] -- both single-beat patterns start at 0 and overlap"
   'algo.rhythmic.transform/oblique-strategies       "data: oblique's :strategy choices"
   'algo.rhythmic.world/common-talas                 "data: tala's :tala-name choices"
   'algo.bridge/degree->pitch                        "one value; degrees->pitches maps it"
   'algo.bridge/number->duration                     "one value; numbers->durations maps it"
   'algo.bridge/number->pitch                        "one value; numbers->pitches maps it"
   'algo.rhythmic.drums/kit                          "data: drums' kit pieces, in layer order"
   'algo.rhythmic.drums/styles                       "data: drums' :style choices"
   'algo.rhythmic.world/named-bell-patterns          "data: bell's :pattern-name choices"})

(def engine-nss
  "Not generators at all: the RNG engine."
  #{'algo.random.core})

(defn- source-nss []
  (->> (concat [(io/file "src/algo/random.clj") (io/file "src/algo/bridge.clj")]
               (mapcat #(file-seq (io/file "src/algo" %)) ["melodic" "metric" "random" "rhythmic"]))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (map #(-> (.getPath %) (subs 4) (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-") symbol))
       (remove engine-nss)
       sort))

(deftest every-public-fn-is-an-algo-or-says-why-not
  (let [publics (for [n (source-nss) :let [_ (require n)]
                      [s v] (ns-publics n)]
                  [(symbol (str n) (str s)) v])]
    (is (< 100 (count publics)))
    (doseq [[full v] publics]
      (is (or (:algo (meta v)) (not-algos full))
          (str full " has no :algo metadata and isn't listed in not-algos")))
    (doseq [full (keys not-algos)]
      (is (not (:algo (meta (resolve full)))) (str full " is listed in not-algos but is an algo")))))

(deftest every-algo-is-exposed-in-lib
  (doseq [n (source-nss) [_ v] (ns-publics n) :when (:algo (meta v))]
    (is (t/short-name (symbol (str (:ns (meta v))) (str (:name (meta v)))))
        (str v " is annotated but not exposed by algo.tree.lib"))))

(def samples
  "A child value per :in type."
  {:pulse [1 0 1 1 0 1 0 0] :pitch [60 62 64 67 69] :number [0.1 0.5 0.3 0.9 0.2]
   :onset [0.0 0.5 0.75 1.5 2.0] :point [[1 2 3] [2 3 4]] :part [[1 0 1] [0 1 1]]
   :weight [3 0 2 1] :duration [1/4 1/8 1/8] :pair [[60 1/4] [nil 1/8]]
   :leaf [(d/leaf nil nil 1/4 [60]) (d/rest* nil nil 1/8) (d/leaf nil nil 1/8 [64])]
   :stroke ["Ta" "-" "Ka" "Di"] :volume [50 70 30] :articulation [:accent nil :staccato]
   :instrument [0 "violin" 40]
   :model (melody/markov-train [60 62 64 62 60 67] 1) :any [60 62 64 65]})

(def shape?
  {:pulse   #(every? #{0 1} %)
   :weight  #(every? number? %)
   :pitch   #(every? (some-fn nil? number?) %)
   :number  #(every? number? %)
   :duration #(every? (some-fn (every-pred rational? pos?) d/rest?) %)
   :onset   #(every? number? %)
   :point   #(every? vector? %)
   :part    #(every? sequential? %)
   :stroke  #(every? string? %)
   :pair    #(every? vector? %)
   :leaf    #(every? :type %)
   :volume  #(every? number? %)
   :articulation #(every? (some-fn nil? keyword?) %)
   :instrument #(every? (some-fn number? string? keyword?) %)
   :index   integer?})

(deftest every-algo-runs-with-its-defaults
  (rc/seed! 2026)
  (doseq [[short {:keys [full in out]}] (t/algos)
          :when (str/starts-with? (namespace full) "algo.")   ; not other tests' algos
          :let [tree (apply (t/constructor (t/algo short)) (map samples in))
                required (into {} (for [{:keys [key default]} (t/param-keys tree) :when (t/nan? default)]
                                    [key (fn [p] (reduce + p))]))
                result (t/run tree required)
                check (shape? (if (= :same out) (first in) out))]]
    (testing short
      (is (some? result))
      (when check
        (is (check (if (and (seq? result) (not (counted? result))) (take 64 result) result))
            (str short " doesn't produce " out ": " (pr-str (if (coll? result) (take 8 result) result))))))))

(deftest lib-names-never-shadow-core-or-musics-core
  (require 'musics.core)
  (let [lib-names (set (keys (ns-publics 'algo.tree.lib)))
        taken (set/union (set (keys (ns-publics 'clojure.core)))
                         (set (keys (ns-publics 'musics.core))))]
    (is (empty? (set/intersection lib-names taken)))))

(deftest repeat-and-pull-draw-len-values-reproducibly
  (let [run-seeded (fn [tree m] (rc/seed! 7) (t/run tree m))]
    (testing ":repeat -- a sampler"
      (is (= 5 (count (t/run (lib/normal) {:len 5}))))
      (is (= (run-seeded (lib/normal) {}) (run-seeded (lib/normal) {}))))
    (testing ":pull -- a closure, called once, pulled :len times"
      (is (= 12 (count (t/run (lib/walk) {:len 12}))))
      (is (= (run-seeded (lib/cyclic (lib/scale)) {}) (run-seeded (lib/cyclic (lib/scale)) {}))))
    (testing ":pull :via -- a generator inside the returned map"
      (is (every? #(< 0 % 1) (t/run (lib/logistic) {}))))
    (testing ":pull :args -- params passed on every pull"
      (is (< 71 (last (t/run (lib/glide) {:inertia 0.0 :step 0.0 :target 72.0})) 73)))))

(deftest fn-and-map-params-live-in-the-tctx
  (let [tree (lib/genetic)
        tctx  (t/tctx tree)]
    (is (t/nan? (get-in @tctx [:params :fitness-fn])) "a fn param without a default is required")
    (is (thrown-with-msg? Exception #"needs :fitness-fn" (t/run tree tctx)))
    (is (thrown-with-msg? Exception #"should be a function" (t/setp! tctx :fitness-fn 3)))
    (t/setp! tctx {:fitness-fn #(- (reduce + %)) :generations 10})
    (rc/seed! 7)
    (is (> 4 (reduce + (t/run tree tctx))) "fittest = fewest onsets")
    (is (thrown-with-msg? Exception #"should be a map" (t/setp! (t/tctx (lib/lsys-rhythm)) :rules [1])))))

(deftest bridges
  (is (= [60 62 64 67 69] (t/run (lib/degrees [0 1 2 3 4] (lib/scale)) {})))
  (is (= [60 72 81] (t/run (lib/degrees [0 0.5 1] (lib/scale)) {:octaves 2})) "rescaled onto two octaves")
  (is (= [0 1 1] (t/run (lib/threshold [1 5 9]) {:level 0.4})))
  (is (= [1/4 1/8] (t/run (lib/gaps [0 1 1.5]) {})) "a time unit is a quarter")
  (is (= [0 1 1] (t/run (lib/layer [[1 0] [0 1 1]]) {:index 1})))
  (is (= [2 5] (t/run (lib/axis [[1 2 3] [4 5 6]]) {:axis 1})))
  (is (= 16 (count (t/run (lib/noise) {})))))

(deftest a-plain-map-is-checked-like-a-tctx
  (is (thrown-with-msg? Exception #"should be one of" (t/run (lib/bell) {:pattern-name "polka"})))
  (is (thrown-with-msg? Exception #"at most 32" (t/run (lib/euclid) {:k 99}))))
