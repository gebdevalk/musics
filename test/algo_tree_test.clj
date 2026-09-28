(ns ^:algo algo-tree-test
  "algo.tree: introspected algos, the registry and its aliases, checked
   node construction, param keys, the tctx atom, and live playback."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [test-support :refer [with-fresh-registries]]
            [algo.tree :as t :refer [defalgo]]
            [algo.tree.registry :as reg]
            [algo.tree.lib :refer [euclid scale cycled shuffled head gate transpose
                                           indisp tilt power density pick notes pair-notes color-talea]]
            [algo.tree.live :as live]
            [algo.indisp.indispensability :as indisp]
            [algo.random.core :as seed]
            [core.async-engine :as engine]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.repo :as repo]
            [core.wall :as wall]
            [gui.lib.state :as gs]))

(use-fixtures :each (fn [f] (with-fresh-registries (f))))

(defalgo up "Shift pitches."
  {:algo {:in [:pitches] :out :pitches
          :params {:by {:type :int :min -48 :max 48 :default 12 :doc "shift"}}}}
  [pitches by] (map #(some-> % (+ by)) pitches))

(defalgo union "Two grids, onset wherever either has one."
  {:algo {:in [:grid :grid] :out :grid}}
  [a b] (mapv max a b))

(defalgo need "A required param."
  {:algo {:in [] :out :pitches
          :params {:x {:type :int :min 0 :max ##Inf :default ##NaN}}}}
  [x] [x])

(defalgo clash "Another :k, with a different spec."
  {:algo {:in [:grid] :out :grid :params {:k {:type :int :min 0 :max 4 :default 1}}}}
  [g k] (when k g))

(defalgo boom "Throws."
  {:algo {:in [:any] :out :any}}
  [_] (throw (ex-info "kaput" {})))

;; incomplete specs, for register! to refuse
(defn- no-range {:algo {:in [] :params {:y {:type :int :default 1}}}} [y] y)
(defn- no-default {:algo {:in [] :params {:y {:type :vector}}}} [y] y)
(defn- bare [y] y)

(def riff (notes (gate euclid (cycled scale))))

;; ---------------------------------------------------------------------------
;; Introspection and the registry
;; ---------------------------------------------------------------------------

(deftest introspection-reads-arglists-doc-and-metadata
  (let [e (reg/algo :euclid)]
    (is (= 'algo.rhythmic.rhythm/euclidean-rhythm (:full e)))
    (is (= [:k :n :rotation] (map :name (:params e))))
    (is (= 0 (:default (last (:params e)))) "rotation's default comes from its own :or")
    (is (= :grid (:out e)))
    (is (str/starts-with? (:doc e) "Distribute k beats"))))

(deftest a-multi-arity-fn-names-the-arity-it-wraps
  (is (= [:periods] (map :name (:params (reg/algo :color-talea))))))

(deftest register!-demands-a-complete-spec
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"param :y needs :min"
                        (reg/register! #'no-range)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"param :y needs :default"
                        (reg/register! #'no-default)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no :algo metadata"
                        (reg/register! #'bare))))

(deftest short-and-full-names-alias-each-other
  (is (= 'algo.indisp.indispensability/indispensability (t/full-name :indisp)))
  (is (= :indisp (t/short-name 'algo.indisp.indispensability/indispensability)))
  (is (= (t/algo :indisp) (t/algo 'algo.indisp.indispensability/indispensability)))
  (is (contains? (t/algos) :euclid)))

(deftest defalgo-keeps-the-raw-fn-callable
  (is (= [72 nil] (up* [60 nil] 12)))
  (is (= :up (:short (reg/algo :up)))))

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(deftest trees-print-and-show-as-their-expression
  (is (= "#node (notes (gate (euclid) (cycled (scale))))" (pr-str riff)))
  (is (= "#algo (euclid :k :n :rotation)" (pr-str euclid)))
  (is (= '(gate (euclid :as :bass) [60 62]) (t/show (gate (euclid :as :bass) [60 62])))))

(deftest construction-checks-child-count-and-types
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"euclid takes 0 children, got 1" (euclid scale)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gate: child 1 should be :grid, \(tilt \(indisp\)\) gives :weights"
                        (gate (tilt indisp) scale)))
  (is (some? (gate (density (tilt indisp)) scale)) ":grid from density fits")
  (is (some? (gate [1 0 1] :ps)) "a literal and a param read fit any slot")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plain fn as a child" (cycled inc))))

(deftest a-same-typed-algo-passes-its-childs-type-on
  (is (= :pitches (t/out-type (cycled scale))))
  (is (thrown? clojure.lang.ExceptionInfo (gate (cycled scale) scale))))

;; ---------------------------------------------------------------------------
;; Keys
;; ---------------------------------------------------------------------------

(deftest keys-are-bare-unless-different-algos-share-a-name
  (is (= [:k :n :rotation :root :intervals :dur] (map :key (t/param-keys riff))))
  (is (= [:subdivisions :adherence] (map :key (t/param-keys (power (tilt indisp)))))
      "identical specs share one key")
  (is (= [:euclid.k :n :rotation :clash.k] (map :key (t/param-keys (clash euclid))))
      "different specs under one name each get :<short>.<name>"))

(deftest a-named-instance-gets-its-own-keys
  (is (= [:bass/k :bass/n :bass/rotation :k :n :rotation]
         (map :key (t/param-keys (union (euclid :as :bass) euclid)))))
  (is (= [1 0 1 1 1 1 1 0] (t/run (union (euclid :as :bass) euclid) {:bass/k 2 :k 5}))
      "E(2,8) from :bass/k, E(5,8) from :k, merged"))

(deftest a-keyword-child-is-a-required-param
  (is (= [{:key :ps :type :any :algo :read}]
         (map #(select-keys % [:key :type :algo]) (filter #(= :read (:algo %)) (t/param-keys (up :ps))))))
  (is (= [62] (t/run (up :ps) {:ps [60] :by 2}))))

;; ---------------------------------------------------------------------------
;; The tctx
;; ---------------------------------------------------------------------------

(deftest a-tctx-holds-settings-not-the-tree
  (let [ctx (t/tctx riff)]
    (is (= #{:params :specs} (set (keys @ctx))))
    (is (= {:k 3 :n 8 :rotation 0 :root 60 :intervals [0 2 4 7 9] :dur 1/8} (:params @ctx)))
    (is (= [[60] nil nil [62] nil nil [64] nil] (map :pitches (t/run riff ctx))))))

(deftest one-tree-runs-against-several-tctxs
  (let [a (t/tctx riff) b (t/tctx riff {:k 2})]
    (is (not= (t/run riff a) (t/run riff b)))
    (is (= 2 (count (remove nil? (map :pitches (t/run riff b))))))))

(deftest the-validator-guards-every-change
  (let [ctx (t/tctx riff)]
    (is (= 5 (:k (t/set-param! ctx :k 5))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":k 99 should be at most 32 \(euclid, onsets\)"
                          (swap! ctx assoc-in [:params :k] 99)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be an integer" (t/set-param! ctx :k 1.5)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":nope is not a param" (t/set-param! ctx :nope 1)))
    (is (= 5 (get-in @ctx [:params :k])) "a rejected change leaves the value as it was")
    (is (thrown? clojure.lang.ExceptionInfo (t/tctx riff {:k -1})) "overrides are checked too")))

(deftest open-ranges-and-required-params
  (let [ctx (t/tctx (need))]
    (is (t/nan? (get-in @ctx [:params :x])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :x -- not set" (t/run (need) ctx)))
    (t/set-param! ctx :x 1000000)
    (is (= [1000000] (t/run (need) ctx)) "a ##Inf max accepts any size")))

(deftest fit!-prepares-a-tctx-for-another-tree
  (let [ctx (t/tctx riff {:k 5})
        other (notes (transpose (head (cycled scale))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no :len :semitones -- \(fit! ctx tree\)"
                          (t/run other ctx)))
    (t/fit! ctx other)
    (is (= 5 (get-in @ctx [:params :k])) "existing values stay")
    (is (= 16 (get-in @ctx [:params :len])) "new keys start at their defaults")
    (is (= 16 (count (t/run other ctx))))))

(deftest describe-prints-one-row-per-param
  (let [out (with-out-str (t/describe (t/tctx (need))))]
    (is (str/includes? out "required"))
    (is (str/includes? out ":x"))))

;; ---------------------------------------------------------------------------
;; Running
;; ---------------------------------------------------------------------------

(deftest a-plain-map-fills-in-defaults
  (is (= [1 0 0 1 0 0 1 0] (t/run euclid {})))
  (is (= [1 0 1 1 0 1 1 0] (t/run euclid {:k 5}))))

(deftest a-throwing-algo-names-its-node
  (let [e (try (t/run (boom scale) {}) (catch clojure.lang.ExceptionInfo e e))]
    (is (str/includes? (.getMessage e) "(boom (scale)) threw: kaput"))))

(deftest trace-returns-every-node-children-first
  (let [tr (t/trace (density (tilt indisp)) {})]
    (is (= ['(indisp) '(tilt (indisp)) '(density (tilt (indisp)))] (map :node tr)))
    (is (= (indisp/indispensability [2 2 3]) (:data (first tr)))))
  (is (= (conj (vec (repeat 16 60)) '...)
         (:data (first (t/trace (cycled [60]) {}))))
      "an infinite lazy seq is previewed, not walked"))

(deftest the-lib-matches-the-plain-functions
  (let [ranks (indisp/indispensability [2 2 3])]
    (is (= (indisp/density-grid (indisp/tilt-probabilities ranks 0.8) 0.5)
           (t/run (density (tilt indisp)) {:adherence 0.8 :density 0.5})))
    (is (= (indisp/power-law-probabilities ranks -0.8)
           (t/run (power indisp) {:adherence -0.8}))))
  (is (= [60 nil nil 64 nil nil 67 nil] (t/run (gate euclid (cycled scale)) {:intervals [0 4 7]})))
  (is (= [62 [62 66] nil] (t/run (transpose [60 [60 64] nil]) {:semitones 2})))
  (is (= 6 (count (t/run (pair-notes (color-talea [60 62 64] [1/4 1/8])) {}))))
  (is (< -1 (t/run (pick (tilt indisp)) {}) 12))
  (seed/seed! 42)
  (is (every? #(= #{1 2 3 4} (set %)) (partition 4 (take 12 (t/run (shuffled [1 2 3 4]) {}))))))

(deftest notes-are-leaves-and-rests
  (let [parts (t/run riff {:dur 1/16})]
    (is (= [:LEAF :REST :REST :LEAF] (map :type (take 4 parts))))
    (is (every? #(= 1/16 (:duration %)) parts))))

;; ---------------------------------------------------------------------------
;; Live
;; ---------------------------------------------------------------------------

(defn- fast-engine []
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :children []
                            :context (c/context-root {"Tempo" 6000 "volume" 80})})
  (engine/engine nil (repo/registry) :ROOT))

(defn- wait-until [pred]
  (loop [n 150] (when (and (pos? n) (not (pred))) (Thread/sleep 20) (recur (dec n))))
  (pred))

(defn- current-pitches [name]
  (:pitches (:current (first (vals @(:cursors (wall/registered name)))))))

(deftest live-follows-the-tctx-and-retree!-keeps-it
  (binding [engine/*engine* (fast-engine)]
    (try
      (let [ctx  (t/tctx (notes (cycled scale)) {:intervals [0 4 7]})
            path (t/live! :riff (notes (cycled scale)) ctx)]
        (is (wait-until #(some? (current-pitches :riff))))
        (t/set-param! ctx :root 72)
        (is (wait-until #(<= 72 (first (current-pitches :riff)))) "a ctx change is heard")
        (t/retree! :riff (notes (transpose (cycled scale))))
        (is (= 0 (get-in @ctx [:params :semitones])) "retree! fitted the ctx")
        (t/set-param! ctx :semitones 12)
        (is (wait-until #(<= 84 (first (current-pitches :riff)))) "the new tree follows the same ctx")
        (t/stop! :riff)
        (is (wait-until #(nil? (engine/voice-at path)))))
      (finally (engine/stop!)))))

(deftest live-rejects-a-tctx-that-doesnt-cover-the-tree
  (binding [engine/*engine* (fast-engine)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no :len"
                          (t/live! :x (notes (head (cycled scale))) (t/tctx (notes (cycled scale))))))
    (is (empty? @(:voices engine/*engine*)))))

(deftest a-transform-tree-rewrites-a-voices-own-notes
  (let [ctx (t/tctx (transpose :nodes) {:semitones 7})]
    (is (= :up7 (t/live! :up7 (transpose :nodes) ctx)) "a transform only binds the name")
    (let [f   (wall/algo :up7)
          out (f [(d/leaf :n1 nil 1/4 [60]) (d/rest* :r nil 1/4)] nil {:path [:TAA]})]
      (is (= [[67] nil] (map :pitches out)))
      (is (= out (f out nil {:path [:TAA]})) "already-transformed notes pass through"))))

(deftest the-wall-window-offers-a-control-per-live-param
  (let [ctx (t/tctx riff)]
    (#'live/bind! :riff (t/as-node riff) ctx)
    (#'gs/refresh-wall!)
    (let [{:keys [name tree params]} (first (get-in @gs/*state [:wall :trees]))]
      (is (= :riff name))
      (is (= "(notes (gate (euclid) (cycled (scale))))" tree))
      (is (= [:k :n :rotation :root :intervals :dur] (map :key params)))
      (is (= 3 (:value (first params)))))
    (gs/set-tree-param! :riff :k 5)
    (is (= 5 (get-in @ctx [:params :k])) "a control writes through set-param!")
    (gs/set-tree-param! :riff :intervals "[0 3 7]")
    (is (= [0 3 7] (get-in @ctx [:params :intervals])) "a text field's EDN is read")
    (gs/set-tree-param! :riff :k 99)
    (is (= 5 (get-in @ctx [:params :k])) "a rejected value isn't applied")
    (is (re-find #"at most 32" (get-in @gs/*state [:wall :message])) "and the window says why")))
