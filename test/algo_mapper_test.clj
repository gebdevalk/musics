(ns ^:algo algo-mapper-test
  "Tests for algo.mapper (tcxt-threading composition) and algo.mapper.lib.
   Run: lein test algo-mapper-test"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [test-support :refer [with-fresh-registries]]
            [algo.mapper :as m :refer [defalgos]]
            [algo.mapper.lib :as lib]
            [core.async-engine :as engine]
            [core.domain.context :as c]
            [core.repo :as repo]))

(use-fixtures :each (fn [f] (with-fresh-registries (f))))

;; The sketch's own smoke tree. b2 and b3 read no common param, so every
;; key stays bare.
(defalgos
  b2 (fn [_ lo hi] (range lo hi))
  b3 (fn [_ scale] (map #(* scale %) (range)))
  A1 (fn [seqs] (apply map vector seqs))
  A2 (fn [[xs]] (vec (sort xs)))
  A3 (fn [[xs]] (vec (take 8 xs))))

(def tree (A1 (b2) (A2 (b2) (A3 (b3)))))

(deftest smoke-tree-zips-children-data
  (let [params {:lo 0 :hi 5 :scale 2}
        out    (tree params)]
    (is (= [[0 0] [1 1] [2 2] [3 3] [4 4]] (:data out))
        "A1 zips b2's range with A2's result; A2 destructures [[xs]], so it
         sorts only its FIRST child's data (b2) -- A3's data is computed but unread")
    (is (= params (dissoc out :data)) "params ride through untouched")
    (is (= (:data out) (m/run tree params)))))

(deftest mappers-registry-matches-direct-calls
  (is (= #{:b2 :b3 :A1 :A2 :A3 :lowA :lowB :pair :lit :durs :color :talea}
         (set (keys mappers)))
      "every defalgos form in this ns merges into one registry")
  (is (= (m/run (A1 (b2) (b2)) {:lo 0 :hi 3})
         (m/run ((mappers :A1) ((mappers :b2)) ((mappers :b2))) {:lo 0 :hi 3}))))

(deftest two-instances-share-params
  (is (= [[0 0] [1 1]] (m/run (A1 (b2) (b2)) {:lo 0 :hi 2}))))

(deftest infinite-leaf-feeding-a-forcing-parent-terminates
  (is (= [0 3 6 9 12 15 18 21] (m/run (A3 (b3)) {:scale 3}))))

(deftest params-are-on-mapper-metadata
  (is (= [:lo :hi] (:params (meta b2))))
  (is (= [] (:params (meta A1)))))

(deftest only-colliding-params-get-a-prefix
  (is (= {"b2" [:b.lo :hi] "c1" [:c.lo] "x" [:y]}
         (m/param-keys {"b2" ["lo" "hi"] "c1" ["lo"] "x" ["y"]}))))

(deftest prefix-grows-until-unique
  (is (= {"b2" [:b2.lo] "b3" [:b3.lo] "c" [:c.lo]}
         (m/param-keys {"b2" ["lo"] "b3" ["lo"] "c" ["lo"]})))
  (is (= {"b" [:b.lo] "b2" [:b2.lo]}
         (m/param-keys {"b" ["lo"] "b2" ["lo"]}))))

(defalgos
  lowA (fn [_ lo] [:a lo])
  lowB (fn [_ lo] [:b lo])
  pair (fn [ds] ds))

(defalgos
  lit   (fn [_ ps] ps)
  durs  (fn [_ ds] ds)
  color (fn [_ c] c)
  talea (fn [_ t] t))

(deftest colliding-params-read-their-own-keys
  (is (= [:lowA.lo :lowB.lo] (mapcat (comp :params meta) [lowA lowB])))
  (is (= [[:a 1] [:b 2]] (m/run (pair (lowA) (lowB)) {:lowA.lo 1 :lowB.lo 2}))))

(defn- expansion-error [form]
  (try (macroexpand form) nil
       (catch Exception e (ex-data (or (.getCause e) e)))))

(deftest bad-specs-fail-at-macroexpansion
  (is (= 'x (:id (expansion-error '(algo.mapper/defalgos x (fn [_ {:keys [a]}] a)))))
      "a destructured param has no single name to key on")
  (is (= 'x (:id (expansion-error '(algo.mapper/defalgos x (fn ([_] 1) ([_ a] a))))))
      "multi-arity fns are rejected")
  (is (= 'x (:id (expansion-error '(algo.mapper/defalgos x 42))))))

;; ---------------------------------------------------------------------------
;; lib
;; ---------------------------------------------------------------------------

(deftest explicit-key-spec-lifts-an-existing-fn
  (is (= [:k :n] (:params (meta lib/euclid))))
  (is (= [1 0 0 1 0 0 1 0] (m/run (lib/euclid) {:k 3 :n 8}))))

(deftest gate-puts-pitches-on-onsets
  (is (= [60 nil nil 62 nil nil 64 nil]
         (m/run (lib/gate (lib/euclid) (lib/cycled (lib/scale)))
                {:k 3 :n 8 :root 60 :intervals [0 2 4]}))))

(deftest notes-builds-leaves-and-rests
  (let [parts (m/run (lib/notes (lib/gate (lib/euclid) (lib/cycled (lib/scale))))
                     {:k 3 :n 8 :root 60 :intervals [0 2 4] :dur 1/16})]
    (is (= 8 (count parts)))
    (is (= [:LEAF :REST :REST :LEAF] (map :type (take 4 parts))))
    (is (= [[60] [62] [64]] (keep :pitches parts)))
    (is (every? #(= 1/16 (:duration %)) parts))))

(deftest notes-takes-a-durations-child-and-chords
  (let [parts (m/run (lib/notes (lit) (durs)) {:ps [60 [60 64 67] nil] :ds [1/4 1/2 1/8]})]
    (is (= [[60] [60 64 67] nil] (map :pitches parts)))
    (is (= [1/4 1/2 1/8] (map :duration parts)))))

(deftest color-talea-into-pair-notes
  (let [parts (m/run (lib/pair-notes (lib/color-talea (color) (talea)))
                     {:c [60 62 64] :t [1/4 1/8] :periods 1})]
    (is (= 6 (count parts)) "lcm(3, 2) events")
    (is (= [1/4 1/8 1/4 1/8 1/4 1/8] (map :duration parts)))))

(deftest notes-output-plays-as-a-plain-vector
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT
                            :context (c/context-root {"Tempo" 6000 "volume" 80})
                            :children []})
  (let [eng   (engine/engine nil (repo/registry) :ROOT)
        parts (m/run (lib/notes (lib/gate (lib/euclid) (lib/cycled (lib/scale))))
                     {:k 3 :n 8 :root 60 :intervals [0 2 4]})]
    (binding [engine/*engine* eng]
      (let [voice (engine/voice-at (engine/play parts))
            done? #(= 1 @(:structural voice))]
        (loop [n 100] (when (and (pos? n) (not (done?))) (Thread/sleep 20) (recur (dec n))))
        (is (done?) "the voice walked all 8 eighth notes/rests, 1 whole note of structural time")))))
