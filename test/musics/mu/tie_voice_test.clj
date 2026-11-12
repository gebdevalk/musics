(ns ^:repl musics.mu.tie-voice-test
  "A voice that is a function of the beat and makes skeletons: pul>tied for one pulse,
   zip fills it with a pitch. The word is one pulse at a time; the engine holds a
   note open through the tied rests after it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [musics.domain.context :as c]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.inputs :as inputs]
            [musics.engine :as engine]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.live :as live]
            [musics.mu.tools]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "play" "music" "tool" "arith" "compare"])))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (reset! (:envelopes-atom live/tctx) {})
        (t))
      (finally (reset! (:envelopes-atom live/tctx) {})
               (inputs/clear! :t)
               (reset! e/state saved)))))

(use-fixtures :each fresh)

(def ^:private words
  "'bridge 'b reqalgo
   '[1 0 0 1 0 0 1 0] :pattern T!
   '[60 62 64 65 67 69 71 72] :melody T!
   : one ( $x -- list ) '[1] $x * ;
   : pulse-at ( $beat -- i ) $beat 16 * int ;
   : on ( $i -- n ) :pattern T@ $i :pattern T@ length mod @ ;
   : pitch-at ( $i -- n ) :melody T@ $i :melody T@ length mod @ ;
   : tied ( $i -- leaf ) | ( $i on $i 1 + on ) 1/16 b/pul>tied 1 take  $i pitch-at one  zip ;
   : tvoice ( $beat -- leaf ) $beat pulse-at tied ;")

(deftest a-beat-voice-makes-a-skeleton-a-pulse-at-a-time
  (let [r (e/eval-input words)]
    (is (nil? (:error r)) (str (:error r))))
  (inputs/set-inputs! :t {})
  (let [f      (#'live/beat-source "tvoice" :t ["beat"])
        leaf   (fn [i] (f (/ i 16) (c/getter [live/tctx] (/ i 16))))
        leaves (map leaf (range 8))]
    (is (= [:PITCHED :REST :REST :PITCHED :REST :REST :PITCHED :REST] (map :type leaves)) "an onset a note, a 0 a rest")
    (is (= [true true false true true false true false] (map #(boolean (:tied %)) leaves))
        "each tied when the pulse after it is 0; pulse 7 is followed by the next pass's onset")
    (is (every? #(= 1/16 (:duration %)) leaves))
    (is (= [60 nil nil 65 nil nil 71 nil] (map (comp first :pitches) leaves)) "a pitch for each position, a rest's unused")))

(deftest the-engine-strikes-a-tied-run-once-and-releases-it-with-the-last
  (e/eval-input words)
  (let [sent (atom [])]
    (with-redefs [engine/receiver (atom :silent)
                  engine/send-midi-on!  (fn [fs ev _] (when (= fs ::fs) (doseq [p (:pitches ev)] (swap! sent conj [:on p]))))
                  engine/send-midi-off! (fn [fs ev] (when (= fs ::fs) (doseq [p (:pitches ev)] (swap! sent conj [:off p]))))]
      (binding [engine/*engine* (engine/engine ::fs (repo/registry) :ROOT)]
        (let [r (e/eval-input "| :duration '[1] :Tempo '[1] 240 * >ctx  'tvoice swap :t ->")]
          (is (nil? (:error r)) (str (:error r))))
        (Thread/sleep 1300)
        (engine/stop!)))
    (is (= [[:on 60] [:off 60] [:on 65] [:off 65] [:on 71] [:off 71] [:on 60] [:off 60]] (take 8 @sent))
        "each note struck once, held through its tied rests, released at the end of the last: three notes a pass, then the pass goes round")))
