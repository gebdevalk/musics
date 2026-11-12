(ns ^:repl musics.mu.hoketus2-test
  "mus/hoketus2.mus: a hoketus whose voices are functions of the beat -- the arc is derived
   from the beat as it is asked, and a voice is one leaf at a time."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [musics.domain.context :as c]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.inputs :as inputs]
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
               (doseq [v [:m :b :s :k :p]] (inputs/clear! v))
               (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- load-piece!
  "Loads the piece, then pins the settings these tests count on (8 pulses a pass, 4 passes an
   incarnation, soft 80, a lag of 3/8), so the numbers in the piece can be changed freely."
  []
  (let [r (with-out-str (e/eval-input (slurp (io/file "mus/hoketus2.mus"))))]
    (is (not (re-find #"can't read|error" r)) r))
  (doseq [[k v] {:steps 8 :reps 4 :spiral 24 :soft 80 :loud 90 :width 1.0 :lag 3/8}]
    (c/tctx! live/tctx k v)))

(defn- at [k beat] (c/ctx-value-chain [live/tctx] k beat))

(defn- voice [word id names inputs]
  (inputs/set-inputs! id inputs)
  (let [f (#'live/beat-source word id names)]
    (fn [beat] (f beat (c/getter [live/tctx] beat)))))

(defn- sounds? [leaf] (boolean (seq (:pitches leaf))))

(deftest the-arc-is-derived-from-the-beat
  (load-piece!)
  (testing "8 pulses a pass, 4 passes an incarnation: an incarnation is 2 whole notes"
    (is (= [0 0 1 1 2] (map #(at :incarnation %) [0 3/2 2 7/2 4])))
    (is (= [1 2 3 8 2] (map #(at :density %) [0 2 4 14 26])) "1 .. steps .. 1, and back")
    (is (= [0 7 0] (map #(at :climb %) [0 14 28])) "the arc's height, 0 at either end"))
  (testing "which pulses sound is the Euclidean pattern of the density"
    (is (= [1 0 0 0 0 0 0 0] (at :pulses 0)))
    (is (= [1 0 0 0 1 0 0 0] (at :pulses 2)))
    (is (= [1 1 1 1 1 1 1 1] (at :pulses 14))))
  (testing "a setting changed while it plays is read at the next ask"
    (c/tctx! live/tctx :steps 4)
    (is (= [1 1 1 1] (at :pulses 3)) "at 4 steps the climax is 4 notes, and it comes at beat 3")
    (is (= 4 (count (at :pulses 0))))))

(deftest a-melodic-voice-is-one-leaf-a-sixteenth-at-a-time
  (load-piece!)
  (let [marimba (voice "hoke" :m ["beat" "phase" "threshold" "instrument" "side" "late"]
                       [["phase" 0.0] ["threshold" 1] ["instrument" 12] ["side" -1] ["late" 0]])
        leaves  (map marimba (map #(/ % 16) (range 0 40)))]
    (testing "density 1: only the first pulse of a pass sounds, then density 2 adds the fifth"
      (is (= [true false false false false false false false true] (map sounds? (take 9 leaves))) "pulse 0 of passes 1 and 2")
      (is (= [64 64] (map (comp first :pitches) (filter sounds? (take 16 leaves))))))
    (testing "it carries its own instrument and pan, and a volume that climbs with the arc"
      (let [l (marimba 0)]
        (is (= 12 (get-in l [:overrides :instrument])))
        (is (= -1.0 (get-in l [:overrides :panning])))
        (is (<= (- (at :soft 0) 10) (get-in l [:overrides :volume]) (+ (at :soft 0) 10)) "around the soft volume at the start")
        (is (< (get-in (marimba 0) [:overrides :volume]) (get-in (marimba 14) [:overrides :volume])) "louder at the climax")))
    (testing "every leaf is a sixteenth, so the voices stay in step"
      (is (every? #(= 1/16 (:duration %)) leaves)))))

(deftest the-late-group-starts-with-a-rest-and-then-sounds-late
  (load-piece!)
  (let [b (voice "hoke" :b ["beat" "phase" "threshold" "instrument" "side" "late"]
                 [["phase" 0.0] ["threshold" 1] ["instrument" 12] ["side" 1] ["late" 1]])]
    (is (not (sounds? (b 0))))
    (let [lag (at :lag 0)]
      (is (= lag (:duration (b 0))) "a rest up to the lag")
      (is (sounds? (b lag)) "its first pulse, the lag after group A")
      (is (= 1.0 (get-in (b lag) [:overrides :panning])) "on the right"))))

(deftest a-voice-enters-when-the-density-reaches-its-threshold
  (load-piece!)
  (let [synth (voice "hoke" :s ["beat" "phase" "threshold" "instrument" "side" "late"]
                     [["phase" 0.5] ["threshold" 5] ["instrument" 62] ["side" -1] ["late" 0]])]
    (is (not-any? sounds? (map #(synth (/ % 16)) (range 0 128))) "silent while the density is 1 .. 4: the first 8 whole notes")
    (is (some sounds? (map #(synth (+ 8 (/ % 16))) (range 0 32))) "and sounding once it is 5")))

(deftest the-drums-and-the-chord-are-hits-of-the-density
  (load-piece!)
  (let [kick (voice "thump" :k ["beat" "threshold" "divisor" "rotation" "louder" "drum"]
                    [["threshold" 2] ["divisor" 2] ["rotation" 0] ["louder" 6] ["drum" [:kick]]])
        stab (voice "stab" :p ["beat" "threshold" "divisor" "rotation" "louder"]
                    [["threshold" 4] ["divisor" 2] ["rotation" 2] ["louder" -2]])]
    (is (= :DRUM (:type (kick 2))) "a drum hit at the first pulse once the density is 2")
    (is (= 36 (:program (kick 2))))
    (is (not= :DRUM (:type (kick 0))) "a rest while the density is 1")
    (is (= 4 (count (:pitches (some #(let [l (stab (+ 8 (/ % 16)))] (when (sounds? l) l)) (range 0 16)))))
        "the chord has its four notes")))
