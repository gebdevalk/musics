(ns ^:engine musics.midi.channels-test
  "The MIDI channel pool (musics.midi.channels): a voice alone on its
   channel keeps it through a change of program or pan, sending only what
   changed; voices of one timbre share a channel; a full pool gives none.
   And render, which takes its channels from the same pool."
  (:require [clojure.test :refer [deftest is testing]]
            [musics.midi.channels :as ch]
            [musics.midi.file :as midi-file]
            [musics.mu]
            [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.test-support :refer [with-fresh-session]])
  (:import (javax.sound.midi ShortMessage)))

(defn- takes
  "[pool [[channel sends] …]]: each [path key] taken in turn."
  [pool steps]
  (reduce (fn [[p out] [path key]]
            (let [[p c sends] (ch/take-channel p path key)] [p (conj out [c sends])]))
          [pool []] steps))

(deftest a-fresh-channel-gets-program-and-cc
  (let [[_ [[c sends]]] (takes ch/empty-pool [[[:TA 0] [40 {10 0}]]])]
    (is (= 0 c))
    (is (= {:program 40 :cc {10 0}} sends))))

(deftest alone-on-its-channel-a-voice-keeps-it
  (testing "a pan change: only the CC"
    (let [[_ out] (takes ch/empty-pool [[[:TA 0] [0 {10 0}]] [[:TA 0] [0 {10 64}]] [[:TA 0] [0 {10 127}]]])]
      (is (= [0 0 0] (map first out)) "one channel for the whole ramp")
      (is (= [{:cc {10 64}} {:cc {10 127}}] (map second (rest out))))))
  (testing "a program change: only the program"
    (let [[_ out] (takes ch/empty-pool [[[:TA 0] [0 {10 64}]] [[:TA 0] [40 {10 64}]]])]
      (is (= [0 0] (map first out)))
      (is (= {:program 40} (second (second out))))))
  (testing "nothing changed: nothing sent"
    (let [[_ out] (takes ch/empty-pool [[[:TA 0] [0 {10 64}]] [[:TA 0] [0 {10 64}]]])]
      (is (nil? (second (second out)))))))

(deftest voices-of-one-timbre-share-a-channel
  (let [[pool out] (takes ch/empty-pool [[[:TA 0] [0 {10 64}]] [[:TB 0] [0 {10 64}]]])]
    (is (= [0 0] (map first out)))
    (is (nil? (second (second out))) "the second finds it set")
    (testing "one of them changes: it moves, the other keeps the channel as it is"
      (let [[pool [[c sends]]] (takes pool [[[:TB 0] [0 {10 127}]]])]
        (is (= 1 c))
        (is (= {:program 0 :cc {10 127}} sends))
        (is (= [0 {10 64}] (get-in pool [:claims 0 :key])))))
    (testing "a change onto a timbre another channel carries: joining it"
      (let [[pool _] (takes pool [[[:TC 0] [40 {10 0}]]])
            [_ [[c sends]]] (takes pool [[[:TA 0] [40 {10 0}]]])]
        (is (= 1 c))
        (is (nil? sends))))))

(deftest a-full-pool-gives-no-channel-and-takes-none
  (let [[full _] (takes ch/empty-pool (for [i (range 15)] [[i 0] [i {10 64}]]))
        [after [[c sends]]] (takes full [[[:TZ 0] [99 {10 64}]]])]
    (is (nil? c))
    (is (nil? sends))
    (is (= (:claims full) (:claims after)) "no channel taken from another voice")
    (testing "once a voice ends, the next note gets its channel"
      (let [[_ [[c _]]] (takes (ch/release-voice after 0) [[[:TZ 0] [99 {10 64}]]])]
        (is (some? c))))))

(defn- rendered
  "[notes ccs] render writes for x (mu text): each note-on as [tick pitch
   channel pan], pan the last CC 10 sent on its channel before it, and
   each CC 10 as [tick channel value]."
  [x]
  (let [s   (midi-file/events->sequence (mt/events (peek (e/run-text [] x))))
        evs (sort-by (fn [[t c]] [t (if (= c ShortMessage/NOTE_ON) 1 0)])
                     (for [tr (.getTracks s) i (range (.size tr))
                           :let [ev (.get tr i) m (.getMessage ev)] :when (instance? ShortMessage m)]
                       [(.getTick ev) (.getCommand ^ShortMessage m) (.getChannel ^ShortMessage m)
                        (.getData1 ^ShortMessage m) (.getData2 ^ShortMessage m)]))
        cc? (fn [[_ c _ d1]] (and (= c ShortMessage/CONTROL_CHANGE) (= d1 10)))]
    [(loop [es evs pan {} out []]
       (if-let [[t c chan d1 d2 :as ev] (first es)]
         (cond (cc? ev) (recur (rest es) (assoc pan chan d2) out)
               (= c ShortMessage/NOTE_ON) (recur (rest es) pan (conj out [t d1 chan (pan chan)]))
               :else (recur (rest es) pan out))
         out))
     (for [[t _ chan _ v :as ev] evs :when (cc? ev)] [t chan v])]))

(deftest render-pans-every-note-of-a-ramp-longer-than-the-pool
  (with-fresh-session
    (let [[notes ccs] (rendered "[!pan:-1.0< c16 d e f g a b c d e f g a b c d e f g a !pan:1.0 b] [!pan:0.0 C3/1] {2}")
          held  (first (filter #(= 48 (second %)) notes))
          ramp  (remove #(= 48 (second %)) notes)]
      (is (= 21 (count ramp)) "every note")
      (is (= (map #(long (Math/round (* 127.0 (/ % 20.0)))) (range 21)) (map #(nth % 3) ramp))
          "each at its own place, from left to right")
      (is (>= 2 (count (distinct (map #(nth % 2) ramp)))) "on one channel, or two -- sharing the held note's at its pan")
      (is (= [[0 (nth held 2) 64]] (filter #(= (nth held 2) (second %)) ccs))
          "the held note's channel set once, its pan untouched while it sounds"))))
