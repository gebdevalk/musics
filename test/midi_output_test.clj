(ns midi-output-test
  "Tests for output.midi.midi-output — resolution, rendering, timing, channels.
   No MIDI hardware needed. Run: lein test midi-output-test"
  (:require [clojure.test :refer [deftest is testing]]
            [core.domain.music-domain :as d]
            [common.elements.music-elements :as el]
            [output.midi.midi-output :as mo]))

(defn test-leaf
  ([pitches] (test-leaf pitches 1/4 nil nil))
  ([pitches dur] (test-leaf pitches dur nil nil))
  ([pitches dur dynamic] (test-leaf pitches dur dynamic nil))
  ([pitches dur dynamic articulation]
   (d/leaf "test" (d/context-root {"tempo" 120 "volume" 50.0}) dur pitches
           articulation dynamic [] false)))

(def ^:private test-tempo (el/tempo 4 120))

;; MidiNote records
(deftest midi-note-basic
  (let [n (mo/->MidiNote 0 0.5 0.4 [60 64] 100 0 false {10 64})]
    (is (= 0 (:channel n)))
    (is (= 0.5 (:duration-notated n)))
    (is (= 0.4 (:duration-played n)))
    (is (= [60 64] (:pitches n)))
    (is (= 100 (:velocity n)))
    (is (false? (:tied n)))
    (is (= 64 (get (:cc-values n) 10)))))

;; Leaf rendering — duration
(deftest render-leaf-duration
  (let [leaf (test-leaf [60] 1/4)
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (< 0.49 (:duration-notated note) 0.51) "quarter at 120bpm = 0.5s")))

(deftest render-leaf-pitches
  (let [leaf (test-leaf [60 64 67] 1/2)
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (= [60 64 67] (:pitches note)))))

;; Velocity: from context volume
(deftest render-leaf-velocity-context
  (let [leaf (test-leaf [60])
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (= 64 (:velocity note)) "0.8 volume * 127 = 102")))

;; Velocity: dynamic addition
(deftest render-leaf-velocity-dynamic
  (let [leaf (test-leaf [60] 1/4 10)  ;; dynamic = 10
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (= 76 (:velocity note)) "base 0.5 + 10/100 = 0.6 * 127 = 76")))

;; Velocity: zero dynamic = unchanged
(deftest render-leaf-velocity-zero-dyn
  (let [leaf (test-leaf [60] 1/4 0)
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (= 64 (:velocity note)))))

;; Articulation: map form (from parser)
(deftest render-leaf-art-map
  (let [leaf (test-leaf [60] 1/4 0 {:duration 0.4 :dynamic 0})
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (< 0.19 (:duration-played note) 0.21) "0.5 * 0.4 = 0.2")))

;; Articulation: raw float
(deftest render-leaf-art-float
  (let [leaf (test-leaf [60] 1/4 0 0.75)
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (< 0.37 (:duration-played note) 0.38) "0.5 * 0.75 = 0.375")))

;; Ties
(deftest render-leaf-tie
  (let [leaf (d/leaf "tied" (d/context-root {"tempo" 120 "volume" 50.0}) 1/4 [60] nil nil [] true)
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (true? (:tied note)))))

;; Rest
(deftest render-rest-duration
  (let [rest (d/rest* "r4" (d/context-root {"tempo" 120}) 1/4)]
    (is (< 0.49 (mo/render-rest rest test-tempo) 0.51))))

;; Drum
(deftest render-drum-basic
  (let [drum (d/drum "kick" (d/context-root {"tempo" 120 "volume" 50.0}) 1/4 36)
        note (mo/render-drum drum 0.0 test-tempo)]
    (is (= 36 (:program note)))
    (is (< 0.49 (:duration-notated note) 0.51))
    (is (= 64 (:velocity note)))))

;; Timing: constant tempo
(deftest musical-seconds-constant
  (let [ctx (d/context-root {"tempo" 120})]
    (is (< 0.48 (mo/musical->seconds ctx 1/4) 0.52))
    (is (< 1.97 (mo/musical->seconds ctx 1) 2.03))
    (is (< 0.98 (mo/musical->seconds ctx 1/2) 1.02))))

(deftest musical-seconds-zero
  (let [ctx (d/context-root {"tempo" 120})]
    (is (= 0.0 (mo/musical->seconds ctx 0)))))

(deftest musical-seconds-half-tempo
  (let [ctx (d/context-root {"tempo" 60})]
    (is (< 0.98 (mo/musical->seconds ctx 1/4) 1.02) "quarter at 60bpm = 1.0s")))

;; Compute onset/noteoff
(deftest compute-onset-basic
  (let [ctx (d/context-root {"tempo" 120})]
    (is (< 0.49 (mo/compute-onset 0.0 ctx 1/4 0.0) 0.51))))

(deftest compute-noteoff-basic
  (is (< 0.69 (mo/compute-noteoff 0.0 0.5 0.2) 0.71)))

(deftest compute-micro-zero-jitter
  (let [ctx (d/context-root {"tempo" 120})]
    (is (= 0.0 (mo/compute-micro ctx 0.0)))))

;; Channel management
(deftest channel-create
  (let [ch (mo/make-channel 0)]
    (is (= 0 (:number ch)))
    (is (= 0 (:offset ch)))
    (is (empty? (:notes ch)))))

(deftest channel-note-on-off
  (let [ch (mo/make-channel 1)
        ch (mo/channel-note-on ch [60])]
    (is (mo/channel-sounding? ch [60]))
    (is (not (mo/channel-sounding? ch [64])))
    (let [ch (mo/channel-note-off ch [60])]
      (is (not (mo/channel-sounding? ch [60]))))))

(deftest channel-pool-count
  (let [pool (mo/build-channel-pool)]
    (is (= 15 (count pool)) "16 channels minus drum channel 9")
    (is (every? #(= 0 (:offset %)) pool))))

;; Edge cases
(deftest render-leaf-zero-duration
  (let [leaf (d/leaf "grace" (d/context-root {"tempo" 120}) 0 [60])
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (= 0.0 (:duration-notated note)))))

(deftest render-leaf-chord
  (let [leaf (test-leaf [60 64 67 72] 1/2)
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (= 4 (count (:pitches note))))))

(deftest render-leaf-nil-art
  (let [leaf (test-leaf [60] 1/4 0 nil)
        note (mo/render-leaf leaf 0.0 test-tempo 0)]
    (is (< 0.49 (:duration-played note) 0.51) "dur-played = dur-notated")))
