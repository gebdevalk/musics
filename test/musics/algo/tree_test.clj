(ns ^:algo musics.algo.tree-test
  "musics.algo.tree: introspected algos, the registry and its aliases, checked
   node construction, param keys, the tctx atom, and live playback."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [musics.test-support :refer [with-fresh-registries]]
            [musics.algo.tree :as t :refer [defalgo]]
            [musics.algo.tree.registry :as reg]
            [musics.algo.tree.lib :refer [lorenz euclid scale cycle> shuffle> take> transpose zip pulses->durations
                                           indisp tilt power weights->pulses pick degrees->pitches]]
            [musics.algo.tree.live :as live]
            [musics.algo.indispensability :as indisp]
            [musics.algo.random.core :as seed]
            [musics.engine :as engine]
            [musics.common.music-elements :as el]
            [musics.domain.context :as c]
            [musics.domain :as d]
            [musics.repo :as repo]
            [musics.wall :as wall]
            [musics.gui.state :as gs]))

(use-fixtures :each (fn [f] (with-fresh-registries (f))))

(defalgo up "Shift pitches."
  {:algo {:in [:pitch] :out :pitch
          :params {:by {:type :int :min -48 :max 48 :default 12 :doc "shift"}}}}
  [pitches by] (map #(some-> % (+ by)) pitches))

(defalgo union "Two grids, onset wherever either has one."
  {:algo {:in [:pulse :pulse] :out :pulse}}
  [a b] (mapv max a b))

(defalgo need "A required param."
  {:algo {:in [] :out :pitch
          :params {:x {:type :int :min 0 :max ##Inf :default ##NaN}}}}
  [x] [x])

(defalgo clash "Another :k, with a different spec."
  {:algo {:in [:pulse] :out :pulse :params {:k {:type :int :min 0 :max 4 :default 1}}}}
  [g k] (when k g))

(defalgo boom "Throws."
  {:algo {:in [:any] :out :any}}
  [_] (throw (ex-info "kaput" {})))

;; incomplete specs, for register! to refuse
(defn- no-range {:algo {:in [] :params {:y {:type :int :default 1}}}} [y] y)
(defn- no-default {:algo {:in [] :params {:y {:type :vector}}}} [y] y)
(defn- bare [y] y)

(def riff (zip (pulses->durations euclid) (cycle> scale)))
(def melody (zip (cycle> [1/8]) (cycle> scale)))

;; ---------------------------------------------------------------------------
;; Introspection and the registry
;; ---------------------------------------------------------------------------

(deftest introspection-reads-arglists-doc-and-metadata
  (let [e (reg/algo :euclid)]
    (is (= 'musics.algo.rhythmic.rhythm/euclidean-rhythm (:full e)))
    (is (= [:k :n :rotation] (map :name (:params e))))
    (is (= 0 (:default (last (:params e)))) "rotation's default comes from its own :or")
    (is (= :pulse (:out e)))
    (is (str/starts-with? (:doc e) "Distribute k beats"))))

(deftest a-multi-arity-fn-names-the-arity-it-wraps
  (is (= [:syllables] (map :name (:params (reg/algo :konnakol))))))

(deftest register!-demands-a-complete-spec
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"param :y needs :min"
                        (reg/register! #'no-range)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"param :y needs :default"
                        (reg/register! #'no-default)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no :algo metadata"
                        (reg/register! #'bare))))

(deftest short-and-full-names-alias-each-other
  (is (= 'musics.algo.indispensability/indispensability (t/full-name :indisp)))
  (is (= :indisp (t/short-name 'musics.algo.indispensability/indispensability)))
  (is (= (t/algo :indisp) (t/algo 'musics.algo.indispensability/indispensability)))
  (is (contains? (t/algos) :euclid)))

(deftest defalgo-keeps-the-raw-fn-callable
  (is (= [72 nil] (up* [60 nil] 12)))
  (is (= :up (:short (reg/algo :up)))))

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(deftest trees-print-and-show-as-their-expression
  (is (= "#node (zip (pulses->durations (euclid)) (cycle> (scale)))" (pr-str riff)))
  (is (= "#algo (euclid :k :n :rotation)" (pr-str euclid)))
  (is (= '(zip (pulses->durations (euclid :as :bass)) [60 62])
         (t/show (zip (pulses->durations (euclid :as :bass)) [60 62])))))

(deftest construction-checks-child-count-and-types
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"euclid takes 0 children, got 1" (euclid scale)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pulses->durations: child 1 should be :pulse, \(scale\) gives :pitch"
                        (pulses->durations scale)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"zip: child 2, \(pick \(indisp\)\), gives :index, not a part of a note"
                        (zip [1/4] (pick indisp))))
  (is (some? (zip (pulses->durations (weights->pulses (tilt indisp))) scale)) "durations from pulses fit")
  (is (some? (zip [1/4 1/8] :ps)) "a literal and a param read fit any slot")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plain fn as a child" (cycle> inc))))

(deftest a-same-typed-algo-passes-its-childs-type-on
  (is (= :pitch (t/out-type (cycle> scale))))
  (is (thrown? clojure.lang.ExceptionInfo (zip (cycle> scale) scale))))

;; ---------------------------------------------------------------------------
;; Keys
;; ---------------------------------------------------------------------------

(deftest keys-are-bare-unless-different-algos-share-a-name
  (is (= [:k :n :rotation :pulse :root :intervals] (map :key (t/param-keys riff))))
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
  (let [tctx (t/tctx riff)]
    (is (= #{:params-atom} (set (keys tctx))) "its own shape, not a music context")
    (is (= {} @(:params-atom tctx)) "it sets nothing yet")
    (is (= {:k 3 :n 8 :rotation 0 :pulse 1/16 :root 60 :intervals [0 2 4 7 9]} (t/params tctx)) "the defaults show through")
    (is (= [[60] [62] [64]] (map :pitches (t/run riff tctx))))
    (t/setp! tctx :k 5)
    (is (= {"k" 5} @(:params-atom tctx)) "a setting is the name an @{ } writes")
    (is (= {:k 5} (t/values tctx)))))

(deftest settings-come-from-sources-nearest-first
  (let [tctx (t/tctx riff)]
    (t/setp! tctx :k 2)
    (is (= 2 (count (t/run riff [nil tctx]))) "the tctx")
    (is (= 5 (count (t/run riff [{"k" 5} tctx]))) "a nearer source wins: a word's own @{ } over the voice's")
    (t/unsetp! tctx :k)
    (is (= 3 (count (t/run riff [nil tctx]))) "unset again, the default"))
  (testing "the most specific name wins within a source; written values are coerced"
    (is (= 4 (count (t/run riff {"k" 5 "euclid.k" 4}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be an integer" (t/run (euclid) {"k" :x})))
    (is (= [62 64] (take 2 (t/run (degrees->pitches [0 1]) {"key" :D.major}))) "a bare name read as a string"))
  (testing "a named instance reads its own name first"
    (let [two (zip (pulses->durations (euclid :as :a)) (cycle> scale))]
      (is (= 2 (count (t/run two {"a.k" 2 "k" 7}))))))
  (testing "the music gives only what names a music key"
    (let [music (c/context)]
      (c/ctx-append music :Key 0 (el/parse-key "D.major") :fixed)
      (is (= [62 64] (take 2 (t/run (degrees->pitches [0 1]) nil [music]))) "the bridges' key reads !key:")
      (is (= 3 (count (t/run riff nil [music]))) "everything else takes its default"))))

(deftest one-tree-runs-against-several-tctxs
  (let [a (t/tctx riff) b (t/tctx riff {:k 2})]
    (is (not= (t/run riff a) (t/run riff b)))
    (is (= 2 (count (t/run riff b))))))

(deftest the-validator-guards-every-change
  (let [tctx (t/tctx riff)]
    (is (= 5 (:k (t/setp! tctx :k 5))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":k 99 should be at most 32 \(euclid, onsets\)"
                          (swap! (:params-atom tctx) assoc "k" 99)) "even a plain swap!")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be an integer" (t/setp! tctx :k 1.5)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":nope is not a param" (t/setp! tctx :nope 1)))
    (is (= 5 (:k (t/params tctx))) "a rejected change leaves the value as it was")
    (is (thrown? clojure.lang.ExceptionInfo (t/tctx riff {:k -1})) "overrides are checked too")))

(deftest open-ranges-and-required-params
  (let [tctx (t/tctx (need))]
    (is (t/nan? (:x (t/params tctx))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :x -- not set" (t/run (need) tctx)))
    (t/setp! tctx :x 1000000)
    (is (= [1000000] (t/run (need) tctx)) "a ##Inf max accepts any size")))

(deftest fit!-prepares-a-tctx-for-another-tree
  (let [tctx (t/tctx riff {:k 5})
        other (transpose (take> (zip (cycle> [1/8]) (cycle> scale))))]
    (is (= 16 (count (t/run other tctx))) "keys a tctx doesn't know take their defaults")
    (t/fit! tctx other)
    (is (= 5 (:k (t/params tctx))) "existing values stay")
    (is (= 16 (:len (t/params tctx))) "new keys start at their defaults")
    (is (contains? (t/specs tctx) :semitones) "and can be set now")
    (is (= 16 (count (t/run other tctx))))))

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
  (let [tr (t/trace (weights->pulses (tilt indisp)) {})]
    (is (= ['(indisp) '(tilt (indisp)) '(weights->pulses (tilt (indisp)))] (map :node tr)))
    (is (= (indisp/indispensability [2 2 3]) (:data (first tr)))))
  (is (= (conj (vec (repeat 16 60)) '...)
         (:data (first (t/trace (cycle> [60]) {}))))
      "an infinite lazy seq is previewed, not walked"))

(deftest the-lib-matches-the-plain-functions
  (let [ranks (indisp/indispensability [2 2 3])]
    (is (= (indisp/density-grid (indisp/tilt-probabilities ranks 0.8) 0.5)
           (t/run (weights->pulses (tilt indisp)) {:adherence 0.8 :density 0.5})))
    (is (= (indisp/power-law-probabilities ranks -0.8)
           (t/run (power indisp) {:adherence -0.8}))))
  (is (= [[60] [64] [67]] (map :pitches (t/run riff {:intervals [0 4 7]}))))
  (is (= [62 [62 66] nil] (t/run (transpose [60 [60 64] nil]) {:semitones 2})))
  (is (= [[60 1/4] [62 1/8] [64 1/4] [60 1/8] [62 1/4] [64 1/8]]
         (map (juxt (comp first :pitches) :duration) (t/run (take> (zip (cycle> [1/4 1/8]) (cycle> [60 62 64]))) {:len 6})))
      "an isorhythm: two cycles of different length, zipped")
  (is (< -1 (t/run (pick (tilt indisp)) {}) 12))
  (seed/seed! 42)
  (is (every? #(= #{1 2 3 4} (set %)) (partition 4 (take 12 (t/run (shuffle> [1 2 3 4]) {}))))))

(deftest zip-makes-leaves-each-onset-lasting-until-the-next
  (is (= [[:LEAF 3/16] [:LEAF 3/16] [:LEAF 1/8]] (map (juxt :type :duration) (t/run riff {}))))
  (is (= [[:REST 1/8] [:LEAF 3/16] [:LEAF 1/16]]
         (map (juxt :type :duration) (t/run (zip (pulses->durations [0 0 1 0 0 1]) (cycle> scale)) {})))
      "0s before the first onset are one rest"))

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
      (let [tctx  (t/tctx melody {:intervals [0 4 7]})
            path (t/live! :riff melody tctx)]
        (is (wait-until #(some? (current-pitches :riff))))
        (t/setp! tctx :root 72)
        (is (wait-until #(<= 72 (first (current-pitches :riff)))) "a tctx change is heard")
        (t/retree! :riff (transpose melody))
        (is (= 0 (:semitones (t/params tctx))) "retree! fitted the tctx")
        (t/setp! tctx :semitones 12)
        (is (wait-until #(<= 84 (first (current-pitches :riff)))) "the new tree follows the same tctx")
        (t/stop! :riff)
        (is (wait-until #(nil? (engine/voice-at path)))))
      (finally (engine/stop!)))))

(deftest live-refuses-a-tree-that-gives-no-leaves
  (binding [engine/*engine* (fast-engine)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a live tree ends in leaves"
                          (t/live! :y (cycle> scale) (t/tctx (cycle> scale)))))
    (is (empty? @(:voices engine/*engine*)))))

(deftest a-transform-tree-rewrites-a-voices-own-notes
  (let [tctx (t/tctx (transpose :nodes) {:semitones 7})]
    (is (= :up7 (t/live! :up7 (transpose :nodes) tctx)) "a transform only binds the name")
    (let [f   (wall/algo :up7)
          out (f [(d/leaf :n1 nil 1/4 [60]) (d/rest* :r nil 1/4)] nil {:path [:TA]})]
      (is (= [[67] nil] (map :pitches out)))
      (is (= out (f out nil {:path [:TA]})) "already-transformed notes pass through"))))

(deftest a-live-tree-reads-the-music-it-plays-in
  (let [music (doto (c/context) (c/ctx-append :Key 0 (el/parse-key "D.major") :fixed))
        degs  (zip (cycle> [1/4]) (degrees->pitches (cycle> [0 1 2])))]
    (t/live! :up7 (transpose :nodes) (t/tctx (transpose :nodes) {:semitones 7}))
    (is (= [[67]] (map :pitches ((wall/algo :up7) [(d/leaf :n1 nil 1/4 [60])] [[music 0]] {:path [:TA]})))
        "what the tctx sets (a voice's chain holds [ctx offset] links)")
    (#'live/bind! :deg (t/as-node degs) (t/tctx degs))
    (is (= [62] (:pitches (first ((wall/algo :deg) [(d/leaf :n1 nil 1/4 [60])] [music] {:path [:TB]}))))
        "a key param plays in the music's key")))

(deftest the-wall-window-offers-a-control-per-live-param
  (let [tctx (t/tctx riff)]
    (#'live/bind! :riff (t/as-node riff) tctx)
    (#'gs/refresh-wall!)
    (let [{:keys [name tree params]} (first (get-in @gs/*state [:wall :trees]))]
      (is (= :riff name))
      (is (= "(zip (pulses->durations (euclid)) (cycle> (scale)))" tree))
      (is (= [:k :n :rotation :pulse :root :intervals] (map :key params)))
      (is (= 3 (:value (first params)))))
    (gs/set-tree-param! :riff :k 5)
    (is (= 5 (:k (t/params tctx))) "a control writes through setp!")
    (gs/set-tree-param! :riff :intervals "[0 3 7]")
    (is (= [0 3 7] (:intervals (t/params tctx))) "a text field's EDN is read")
    (gs/set-tree-param! :riff :k 99)
    (is (= 5 (:k (t/params tctx))) "a rejected value isn't applied")
    (is (re-find #"at most 32" (get-in @gs/*state [:wall :message])) "and the window says why")))

(deftest setp!-is-assoc-for-a-tctx
  (let [tctx (t/tctx (euclid))]
    (is (= 5 (:k (t/setp! tctx :k 5))))
    (is (= [2 16] ((juxt :k :n) (t/setp! tctx :k 2 :n 16))) "several key/value pairs, like assoc")
    (is (= [3 8] ((juxt :k :n) (t/setp! tctx {:k 3 :n 8}))) "or a map")
    (is (thrown? Exception (t/setp! tctx :k 4 :n 99)))
    (is (= [3 8] ((juxt :k :n) (t/params tctx))) "one swap!: a bad value leaves every value as it was")))

(deftest a-param-can-follow-the-music
  (let [music (c/context)]
    (c/ctx-append music :Key 0 (el/parse-key "D.major") :fixed)
    (is (= [62 64 66] (t/run (degrees->pitches [0 1 2]) nil [music])) "degrees->pitches plays in the music's key")
    (is (= [60 62 64] (t/run (degrees->pitches [0 1 2]) {"key" "C.major"} [music])) "the settings' key comes first")
    (is (= [60 62 64] (t/run (degrees->pitches [0 1 2]) {} [])) "no context: the default")))

(deftest raw-material-gets-its-bridges
  (is (= '(zip (pulses->durations (euclid)) (scale)) (t/show (zip euclid scale)))
      "pulses where durations go: the smallest chain of bridges goes around them")
  (is (= '(zip (pulses->durations (weights->pulses (tilt (indisp)))) (scale)) (t/show (zip (tilt indisp) scale)))
      "two bridges when one doesn't reach")
  (is (= '(zip (pulses->durations (euclid)) (points->pitches (lorenz))) (t/show (zip (pulses->durations euclid) lorenz)))
      "a join bridges to the part neither child gives yet")
  (is (= '(degrees->pitches (cycle> [0 2])) (t/show (degrees->pitches (cycle> [0 2])))) "what fits stays as it is")
  (is (= 3 (count (t/run (zip euclid scale) {:k 3 :n 8})))))
