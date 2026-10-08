(ns ^:algo musics.algo.catalog-test
  "Every generative fn in musics/algo/{melodic,metric,random,rhythmic} and the
   bridges (musics/algo/bridge.clj) is a tree
   algo (it carries :algo metadata), or is listed below with the reason
   it isn't -- and every algo runs with its own defaults."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.algo.random.core :as rc]
            [musics.algo.melodic.melody :as melody]
            [musics.domain :as d]))

(def not-algos
  "Public vars that aren't tree algos, and why."
  {'musics.algo.melodic.counterpoint/default-rules          "data: counterpoint's :rules default"
   'musics.algo.melodic.counterpoint/generate               "[pitch dur] pairs; the counterpoint algo makes leaves of them"
   'musics.algo.melodic.melody/a-minor                      "data: a scale"
   'musics.algo.melodic.melody/c-major                      "data: a scale"
   'musics.algo.melodic.melody/c-pentatonic                 "data: a scale"
   'musics.algo.melodic.melody/cadence-constraint           "builds a constraint fn, a :constraints value"
   'musics.algo.melodic.melody/direction-limit-constraint   "builds a constraint fn, a :constraints value"
   'musics.algo.melodic.melody/max-leap-constraint          "builds a constraint fn, a :constraints value"
   'musics.algo.melodic.melody/no-repeat-constraint         "a constraint fn, a :constraints value"
   'musics.algo.random/choose                               "one draw; choose-from/choose-n are the sequence forms"
   'musics.algo.random/markov                               "one step; markov-chain (chain) walks it"
   'musics.algo.random/rand-double                          "primitive; uniform covers it"
   'musics.algo.random/rand-int                             "primitive; int-range covers it"
   'musics.algo.random/shuffle                              "lib's shuffle> covers it"
   'musics.algo.random/smooth-noise                         "returns a curve of t; lib's noise samples it"
   'musics.algo.random/weighted-choose                      "one draw; lib's pick covers it"
   'musics.algo.random/weighted-coin                        "one boolean; stochastic/threshold make grids"
   'musics.algo.rhythmic.necklace/vuza-canon                "always [] -- both single-beat patterns start at 0 and overlap"
   'musics.algo.rhythmic.transform/oblique-strategies       "data: oblique's :strategy choices"
   'musics.algo.rhythmic.world/common-talas                 "data: tala's :tala-name choices"
   'musics.algo.bridge/degree->pitch                        "one value; degrees->pitches maps it"
   'musics.algo.bridge/number->duration                     "one value; numbers->durations maps it"
   'musics.algo.bridge/number->pitch                        "one value; numbers->pitches maps it"
   'musics.algo.rhythmic.drums/kit                          "data: drums' kit pieces, in layer order"
   'musics.algo.rhythmic.drums/styles                       "data: drums' :style choices"
   'musics.algo.rhythmic.world/named-bell-patterns          "data: bell's :pattern-name choices"
   'musics.algo.rhythmic.world/tala-pattern                 "matra maps; tala is their accents"
   'musics.algo.rhythmic.world/djembe-pattern               "stroke maps in seconds; djembe makes the leaves"
   'musics.algo.rhythmic.necklace/rhythmic-tiling           "[grid tiled?]; tiling is the grid"
   'musics.algo.rhythmic.micro/pocket-groove                "timed maps in seconds; pocket is the delay per onset"
   'musics.algo.rhythmic.micro/humanize-rhythm              "the :humanization context key does this per note"
   'musics.algo.rhythmic.necklace/rhythm-necklace           "every rotation; necklace picks one"
   'musics.algo.rhythmic.necklace/rhythm-bracelet           "every rotation and reversal; bracelet picks one"
   'musics.algo.rhythmic.necklace/all-binary-necklaces      "every class; necklaces picks one"
   'musics.algo.rhythmic.stochastic/crossover-genomes       "both children; crossover picks one"
   'musics.algo.rhythmic.poly/polymeter                     "2/1 marks; the polymeter algo gives them as pulses"
   'musics.algo.random/generative-patch                     "a fixed demo of timed events with pitch bend, no params"})

(def engine-nss
  "Not generators at all: the RNG engine."
  #{'musics.algo.random.core})

(defn- source-nss []
  (->> (concat [(io/file "src/musics/algo/random.clj") (io/file "src/musics/algo/bridge.clj")
                (io/file "src/musics/algo/metric.clj")]
               (mapcat #(file-seq (io/file "src/musics/algo" %)) ["melodic" "random" "rhythmic"]))
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
        (str v " is annotated but not exposed by musics.algo.tree.lib"))))

(def samples
  "A child value per :in type."
  {:pulse [1 0 1 1 0 1 0 0] :pitch [60 62 64 67 69] :number [0.1 0.5 0.3 0.9 0.2]
   :onset [0.0 0.5 0.75 1.5 2.0] :point [[1 2 3] [2 3 4]] :layer [[1 0 1] [0 1 1]]
   :part [[(d/leaf nil nil 1/4 [60]) (d/leaf nil nil 1/4 [64])] [(d/leaf nil nil 1/2 [48])]]
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
   :layer   #(every? (fn [l] (every? #{0 1} l)) %)
   :part    #(every? (fn [p] (every? :type p)) %)
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
          :when (and (str/starts-with? (namespace full) "musics.algo.")      ; not other tests' algos
                     (not (str/ends-with? (namespace full) "-test")))
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
  (let [lib-names (set (keys (ns-publics 'musics.algo.tree.lib)))
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
  (is (= [60 62 64 67 69] (t/run (lib/degrees->pitches [0 1 2 3 4]) {:key "C.pentatonic-major"})))
  (is (= [60 72 81] (t/run (lib/degrees->pitches (lib/scale> [0 0.5 1])) {:key "C.pentatonic-major" :to-hi 9.0}))
      "numbers onto two octaves of a scale: scale> then degrees->pitches")
  (is (= [0 1 1] (t/run (lib/threshold [1 5 9]) {:level 0.4})))
  (is (= [1/4 1/8] (t/run (lib/onsets->durations [0 1 1.5]) {})) "a time unit is a quarter")
  (is (= [0 1 1] (t/run (lib/layer [[1 0] [0 1 1]]) {:index 1})))
  (is (= [2 5] (t/run (lib/axis [[1 2 3] [4 5 6]]) {:axis 1})))
  (is (= 16 (count (t/run (lib/noise) {})))))

(deftest a-plain-map-is-checked-like-a-tctx
  (is (thrown-with-msg? Exception #"should be one of" (t/run (lib/bell) {:pattern-name "polka"})))
  (is (thrown-with-msg? Exception #"at most 32" (t/run (lib/euclid) {:k 99}))))
