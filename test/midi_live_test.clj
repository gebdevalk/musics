(ns ^:engine midi-live-test
  "Real assertions on output.midi.midi-live's note-on/note-off clamping --
   a stub Receiver captures every sent ShortMessage (no real MIDI hardware
   needed), so this runs the same in CI as at a real terminal."
  (:require [clojure.test :refer [deftest is]]
            [output.midi.midi-live :as live])
  (:import [javax.sound.midi Receiver ShortMessage]))

(defn- stub-receiver
  "A Receiver that just conj's every sent ShortMessage onto sent-atom
   instead of touching real hardware."
  [sent-atom]
  (reify Receiver
    (send [_ msg _timestamp] (swap! sent-atom conj msg))
    (close [_] nil)))

(defn- captured-out [f]
  (let [w (java.io.StringWriter.)]
    (binding [*out* w] (f))
    (str w)))

(deftest note-on-passes-in-range-values-through-unclamped
  (let [sent (atom [])
        rcv  (stub-receiver sent)]
    (live/note-on rcv 0 60 100)
    (let [[^ShortMessage msg] @sent]
      (is (= 60 (.getData1 msg)))
      (is (= 100 (.getData2 msg))))))

(deftest note-on-clamps-an-above-range-pitch-and-warns
  (let [sent (atom [])
        rcv  (stub-receiver sent)
        out  (captured-out #(live/note-on rcv 0 140 100))]
    (let [[^ShortMessage msg] @sent]
      (is (= 127 (.getData1 msg)) "clamped to the legal ceiling, not thrown on")
      (is (= 100 (.getData2 msg)) "velocity untouched, only pitch was out of range"))
    (is (re-find #"pitch 140 above MIDI's 0-127 range, clamped to 127" out))))

(deftest note-on-clamps-a-below-range-pitch-and-warns
  (let [sent (atom [])
        rcv  (stub-receiver sent)
        out  (captured-out #(live/note-on rcv 0 -5 100))]
    (let [[^ShortMessage msg] @sent]
      (is (= 0 (.getData1 msg))))
    (is (re-find #"pitch -5 below MIDI's 0-127 range, clamped to 0" out))))

(deftest note-on-clamps-out-of-range-velocity-too
  (let [sent (atom [])
        rcv  (stub-receiver sent)
        out  (captured-out #(live/note-on rcv 0 60 200))]
    (let [[^ShortMessage msg] @sent]
      (is (= 60 (.getData1 msg)))
      (is (= 127 (.getData2 msg))))
    (is (re-find #"velocity 200 above MIDI's 0-127 range, clamped to 127" out))))

(deftest note-off-clamps-pitch-the-same-way
  (let [sent (atom [])
        rcv  (stub-receiver sent)
        out  (captured-out #(live/note-off rcv 0 142))]
    (let [[^ShortMessage msg] @sent]
      (is (= 127 (.getData1 msg))))
    (is (re-find #"pitch 142 above MIDI's 0-127 range, clamped to 127" out))))
