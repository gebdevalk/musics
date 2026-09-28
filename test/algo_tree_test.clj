(ns ^:algo algo-tree-test
  "Tests for algo.tree (tcxt-threading composition), algo.tree.lib
   and algo.tree.live.
   Run: lein test algo-tree-test"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [test-support :refer [with-fresh-registries]]
            [algo.tree :as tr :refer [defalgos]]
            [algo.tree.lib :as lib]
            [algo.tree.live :as live]
            [algo.indisp.indispensability :as indisp]
            [algo.random.core :as seed]
            [core.async-engine :as engine]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [core.repo :as repo]))

(use-fixtures :each (fn [f] (with-fresh-registries (f))))

;; The originating sketch's own smoke tree. b2 and b3 read no common
;; param, so every key stays bare.
(defalgos
  b2 (fn [_ lo hi] (range lo hi))
  b3 (fn [_ scale] (map #(* scale %) (range)))
  A1 (fn [seqs] (apply map vector seqs))
  A2 (fn [[xs]] (vec (sort xs)))
  A3 (fn [[xs]] (vec (take 8 xs))))

(def tree (A1 (b2) (A2 (b2) (A3 (b3)))))

(defalgos
  lowA (fn [_ lo] [:a lo])
  lowB (fn [_ lo] [:b lo])
  pair (fn [ds] ds)
  boom (fn [[_xs]] (throw (ex-info "kaput" {})))
  opt  (fn [_ ^{:default 7 :min 0 :max 10 :doc "how many"} amount] amount))

;; ---------------------------------------------------------------------------
;; Running
;; ---------------------------------------------------------------------------

(deftest smoke-tree-zips-children-data
  (let [params {:lo 0 :hi 5 :scale 2}
        out    (tree params)]
    (is (= [[0 0] [1 1] [2 2] [3 3] [4 4]] (:data out))
        "A1 zips b2's range with A2's result; A2 destructures [[xs]], so it
         sorts only its FIRST child's data (b2) -- A3's data is computed but unread")
    (is (= params (dissoc out :data)) "params ride through untouched")
    (is (= (:data out) (tr/run tree params)))))

(deftest algos-registry-matches-direct-calls
  (is (= #{:b2 :b3 :A1 :A2 :A3 :lowA :lowB :pair :boom :opt :up} (set (keys algos)))
      "every defalgos form in this ns merges into one registry")
  (is (= (tr/run (A1 (b2) (b2)) {:lo 0 :hi 3})
         (tr/run ((algos :A1) ((algos :b2)) ((algos :b2))) {:lo 0 :hi 3}))))

(deftest two-instances-share-params-unless-with-overrides
  (is (= [[0 0] [1 1]] (tr/run (A1 (b2) (b2)) {:lo 0 :hi 2})))
  (is (= [[0 10] [1 11]] (tr/run (A1 (b2) (tr/with {:lo 10 :hi 12} (b2))) {:lo 0 :hi 2})))
  (let [out ((A1 (tr/with {:lo 5} (b2))) {:lo 0 :hi 7})]
    (is (= 0 (:lo out)) "the outer params come back unchanged after a with")))

(deftest infinite-leaf-feeding-a-forcing-parent-terminates
  (is (= [0 3 6 9 12 15 18 21] (tr/run (A3 (b3)) {:scale 3}))))

(deftest children-can-be-uncalled-algos-keywords-or-literals
  (is (= (tr/run (A1 (b2) (b2)) {:lo 0 :hi 3}) (tr/run (A1 b2 b2) {:lo 0 :hi 3}))
      "an uncalled algo as a child means calling it with no children")
  (is (= [[1 :x] [2 :y]] (tr/run (A1 [1 2] :tags) {:tags [:x :y]}))
      "a literal is its own data; a keyword reads that param")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plain fn as a child"
                        (A1 inc))))

;; ---------------------------------------------------------------------------
;; Params: naming, defaults, missing keys
;; ---------------------------------------------------------------------------

(deftest only-colliding-params-get-a-prefix
  (is (= {"b2" [:b.lo :hi] "c1" [:c.lo] "x" [:y]}
         (tr/param-keys {"b2" ["lo" "hi"] "c1" ["lo"] "x" ["y"]}))))

(deftest prefix-grows-until-unique
  (is (= {"b2" [:b2.lo] "b3" [:b3.lo] "c" [:c.lo]}
         (tr/param-keys {"b2" ["lo"] "b3" ["lo"] "c" ["lo"]})))
  (is (= {"b" [:b.lo] "b2" [:b2.lo]}
         (tr/param-keys {"b" ["lo"] "b2" ["lo"]}))))

(deftest shared-params-keep-their-bare-key
  (is (= {"tilt" [:adherence] "power" [:adherence] "x" [:x.adherence]}
         (tr/param-keys {"tilt" ["adherence"] "power" ["adherence"] "x" ["adherence"]}
                       #{["tilt" "adherence"] ["power" "adherence"]})))
  (is (= [{:key :adherence :min -1.0 :max 1.0 :algo 'tilt}] (tr/params lib/tilt)))
  (is (= [:adherence] (map :key (tr/params lib/power)))))

(deftest colliding-params-read-their-own-keys
  (is (= [:lowA.lo :lowB.lo] (map :key (tr/params (pair lowA lowB)))))
  (is (= [[:a 1] [:b 2]] (tr/run (pair (lowA) (lowB)) {:lowA.lo 1 :lowB.lo 2}))))

(deftest arg-metadata-becomes-the-param-spec
  (is (= [{:key :amount :default 7 :min 0 :max 10 :doc "how many" :algo 'opt}] (tr/params (opt))))
  (is (= 7 (tr/run (opt) {})) "a missing param with a default uses it")
  (is (= 3 (tr/run (opt) {:amount 3}))))

(deftest params-lists-every-key-a-tree-reads-once
  (is (= [:lo :hi :scale] (map :key (tr/params tree))))
  (is (= ['b2 'b2 'b3] (map :algo (tr/params tree))))
  (is (= [:hi :tags] (map :key (tr/params (A1 (tr/with {:lo 1} (b2)) :tags))))
      "a key a with supplies isn't needed from outside; a keyword child is"))

(deftest missing-keys-are-named-up-front
  (is (= [:hi :scale] (tr/missing tree {:lo 0})))
  (let [e (try (tr/run tree {:lo 0}) (catch clojure.lang.ExceptionInfo e e))]
    (is (= [:hi :scale] (:missing (ex-data e))))
    (is (str/includes? (.getMessage e) "(A1 (b2) (A2 (b2) (A3 (b3))))")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\(b2 \.\.\.\) needs :hi"
                        (tree {:lo 0}))
      "calling a tree directly, skipping run's check, still names node and key"))

(deftest a-throwing-algo-names-its-node
  (let [e (try (tr/run (boom (b2)) {:lo 0 :hi 2}) (catch clojure.lang.ExceptionInfo e e))]
    (is (str/includes? (.getMessage e) "(boom (b2)) threw: kaput"))
    (is (= '(boom (b2)) (:node (ex-data e))))))

(defn- expansion-error [form]
  (try (binding [*ns* (the-ns 'algo-tree-test)] (macroexpand form)) nil
       (catch Exception e (ex-data (or (.getCause e) e)))))

(deftest bad-specs-fail-at-macroexpansion
  (is (= 'x (:id (expansion-error '(algo.tree/defalgos x (fn [_ {:keys [a]}] a)))))
      "a destructured param has no single name to key on")
  (is (= 'x (:id (expansion-error '(algo.tree/defalgos x (fn ([_] 1) ([_ a] a))))))
      "multi-arity fns are rejected")
  (is (= 'x (:id (expansion-error '(algo.tree/defalgos x 42))))))

(deftest a-later-form-sharing-a-bare-key-warns
  (let [err (java.io.StringWriter.)]
    (binding [*err* err *ns* (the-ns 'algo-tree-test)]
      (macroexpand '(algo.tree/defalgos late (fn [_ scale] scale))))
    (is (str/includes? (str err) "late reads :scale which [:b3]"))))

;; ---------------------------------------------------------------------------
;; Trees as data
;; ---------------------------------------------------------------------------

(deftest show-gives-back-the-expression
  (is (= '(A1 (b2) (A2 (b2) (A3 (b3)))) (tr/show tree)))
  (is (= '(A1 (with {:lo 3} (b2)) [1 2] :tags) (tr/show (A1 (tr/with {:lo 3} b2) [1 2] :tags))))
  (is (= 'b2 (tr/show b2))))

(deftest nodes-and-algos-print-readably
  (is (= "#node (A1 (b2) (A2 (b2) (A3 (b3))))" (pr-str tree)))
  (is (= "#algo (b2 :lo :hi)" (pr-str b2)))
  (is (= "{:x #node (with {:lo 3} (b2))}" (pr-str {:x (tr/with {:lo 3} b2)}))))

(deftest trace-returns-every-node-in-computed-order
  (let [t (tr/trace (A1 (b2) (A3 (b3))) {:lo 0 :hi 3 :scale 1})]
    (is (= ['(b2) '(b3) '(A3 (b3)) '(A1 (b2) (A3 (b3)))] (map :node t)))
    (is (= [0 1 2] (:data (first t))) "a finite value is shown whole")
    (is (= (conj (vec (range 16)) '...) (:data (second t)))
        "an infinite lazy seq is previewed, not walked forever")
    (is (= [[0 0] [1 1] [2 2]] (:data (last t))))))

;; ---------------------------------------------------------------------------
;; lib
;; ---------------------------------------------------------------------------

(deftest explicit-key-spec-lifts-an-existing-fn
  (is (= [:k :n] (map :key (tr/params lib/euclid))))
  (is (= [1 0 0 1 0 0 1 0] (tr/run lib/euclid {:k 3 :n 8}))))

(deftest gate-puts-pitches-on-onsets
  (is (= [60 nil nil 62 nil nil 64 nil]
         (tr/run (lib/gate lib/euclid (lib/cycled lib/scale))
                {:k 3 :n 8 :root 60 :intervals [0 2 4]}))))

(deftest notes-builds-leaves-and-rests-lazily
  (let [parts (tr/run (lib/notes (lib/gate lib/euclid (lib/cycled lib/scale)))
                     {:k 3 :n 8 :root 60 :intervals [0 2 4] :dur 1/16})]
    (is (= 8 (count parts)))
    (is (= [:LEAF :REST :REST :LEAF] (map :type (take 4 parts))))
    (is (= [[60] [62] [64]] (keep :pitches parts)))
    (is (every? #(= 1/16 (:duration %)) parts)))
  (is (= 1/8 (:duration (first (tr/run (lib/notes [60]) {})))) ":dur defaults to 1/8")
  (is (= 5 (count (take 5 (tr/run (lib/notes (lib/cycled [60 62])) {}))))
      "an infinite source stays lazy through notes"))

(deftest notes-makes-chords-and-rests
  (is (= [[60] [60 64 67] nil] (map :pitches (tr/run (lib/notes :ps) {:ps [60 [60 64 67] nil]})))))

(deftest color-talea-into-pair-notes
  (let [parts (tr/run (lib/pair-notes (lib/color-talea [60 62 64] [1/4 1/8])) {})]
    (is (= 6 (count parts)) "lcm(3, 2) events, :periods defaulting to 1")
    (is (= [1/4 1/8 1/4 1/8 1/4 1/8] (map :duration parts)))))

(deftest indispensability-family-matches-the-direct-calls
  (let [ranks (indisp/indispensability [2 2 3])]
    (is (= ranks (tr/run (lib/indisp [2 2 3]) {})))
    (is (= (indisp/density-grid (indisp/tilt-probabilities ranks 0.8) 0.5)
           (tr/run (lib/density (lib/tilt (lib/indisp [2 2 3]))) {:adherence 0.8 :density 0.5})))
    (is (= (indisp/power-law-probabilities ranks 0.8)
           (tr/run (lib/power (lib/indisp [2 2 3])) {:adherence 0.8}))
        "power fills tilt's slot with the SAME :adherence key")
    (is (< -1 (tr/run (lib/pick (lib/tilt (lib/indisp [2 2 3]))) {:adherence 0.8}) 12))))

(deftest transpose-shifts-pitches-chords-and-notes-and-leaves-rests
  (is (= [62 [62 66] nil] (tr/run (lib/transpose [60 [60 64] nil]) {:semitones 2})))
  (is (= [[67] nil] (map :pitches (tr/run (lib/transpose :ns) {:semitones 7
                                                               :ns [(d/leaf :a nil 1/4 [60]) (d/rest* :r nil 1/4)]})))))

(deftest shuffled-plays-every-item-once-per-pass
  (seed/seed! 42)
  (let [xs (take 12 (tr/run (lib/shuffled [1 2 3 4]) {}))]
    (is (every? #(= #{1 2 3 4} (set %)) (partition 4 xs)))))

(defn- engine-with-fast-root []
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT
                            :context (c/context-root {"Tempo" 6000 "volume" 80})
                            :children []})
  (engine/engine nil (repo/registry) :ROOT))

(defn- wait-until [pred]
  (loop [n 150] (when (and (pos? n) (not (pred))) (Thread/sleep 20) (recur (dec n))))
  (pred))

(deftest notes-output-plays-as-a-plain-form
  (binding [engine/*engine* (engine-with-fast-root)]
    (let [voice (engine/voice-at (engine/play (tr/run (lib/notes (lib/gate lib/euclid (lib/cycled lib/scale)))
                                                     {:k 3 :n 8 :root 60 :intervals [0 2 4]})))]
      (is (wait-until #(= 1 @(:structural voice)))
          "the voice walked all 8 eighth notes/rests, 1 whole note of structural time"))))

;; ---------------------------------------------------------------------------
;; live
;; ---------------------------------------------------------------------------

(defn- cursors [name] @(:cursors (wall/registered name)))
(defn- positions [name] (map :pos (vals (cursors name))))
(defn- current-pitches [name] (:pitches (:current (first (vals (cursors name))))))

(deftest live-plays-changes-and-stops
  (binding [engine/*engine* (engine-with-fast-root)]
    (try
      (let [path (live/play! :riff (lib/notes (lib/cycled lib/scale)) {:root 60 :intervals [0 4 7]})]
        (is (wait-until #(some (fn [p] (> p 4)) (positions :riff))) "the voice keeps pulling notes")
        (is (= '{:tree (notes (cycled (scale))) :params {:root 60 :intervals [0 4 7]}} (live/spec :riff)))
        (live/param! :riff :root 72)
        (is (wait-until #(<= 72 (first (current-pitches :riff)))) "the new :root is heard")
        (live/retree! :riff (lib/notes [50]))
        (is (wait-until #(= [50] (current-pitches :riff)))
            "a swapped-in finite tree loops, continuing at the same position")
        (live/param! :riff :intervals nil)
        (live/retree! :riff (lib/notes (lib/cycled lib/scale)))
        (Thread/sleep 100)
        (is (= [50] (current-pitches :riff))
            "a tree that fails to run (nil :intervals) keeps the previous material")
        (live/stop! :riff)
        (is (wait-until #(nil? (engine/voice-at path))) "stop! ends the voice")
        (is (some? (live/spec :riff)) "the name stays installed"))
      (finally (engine/stop!)))))

(deftest two-voices-on-one-name-keep-their-own-position
  (binding [engine/*engine* (engine-with-fast-root)]
    (try
      (live/install! :count (lib/notes (range 1000)) {})
      (live/play! :count)
      (Thread/sleep 60)
      (live/play! :count)
      (is (wait-until #(= 2 (count (cursors :count)))))
      (let [[a b] (sort (positions :count))]
        (is (< a b) "the later voice starts from 0, not where the first one is"))
      (finally (engine/stop!)))))

(defalgos up (fn [[ns] ^{:default 12} by]
               (map #(cond-> % (:pitches %) (update :pitches (partial mapv (fn [p] (+ p by))))) ns)))

(deftest transform-mode-rewrites-the-voices-own-notes
  (binding [engine/*engine* (engine-with-fast-root)]
    (try
      (live/install! :up (up :nodes) {:by 7})
      (let [f   (wall/algo :up)
            out (f [(d/leaf :n1 nil 1/4 [60]) (d/rest* :r nil 1/4)] nil {:path [:TAA]})]
        (is (= [[67] nil] (map :pitches out)) "notes transposed, the rest left alone")
        (is (= out (f out nil {:path [:TAA]})) "already-transformed notes pass through untouched"))
      (finally (engine/stop!)))))

(deftest live-rejects-a-tree-missing-params-before-starting
  (binding [engine/*engine* (engine-with-fast-root)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :root :intervals"
                          (live/play! :bad (lib/notes (lib/cycled lib/scale)) {})))
    (is (nil? (live/spec :bad)) "nothing was installed")
    (is (empty? @(:voices engine/*engine*)) "nothing was started")))
