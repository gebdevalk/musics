(ns ^:algo musics.algo.harmonic.walking-bass-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.test-support :refer [with-fresh-session]]
            [musics.core :as m]
            [musics.repo :as repo]
            [musics.events :as ev]
            [musics.algo.random.core :as rc]
            [musics.algo.harmonic.progression :as hp]
            [musics.algo.harmonic.walking-bass :as wb]))

(use-fixtures :each (fn [f] (with-fresh-session (f))))

(defn- parse! [text] (with-out-str (m/parse text)))

(def practice
  "The jazz blues in F, with its closing bar."
  "[blues: !M:4/4 F3/1:7 | B&3:7 | F3:7 | C4/2:m7 F3:7 | B&3/1:7 | B3:d7 | F3:7 | D4:7 | G3:m7 | C4:7 | F3/2:7 D4:7 | G3:m7 C4:7 | F3/1:7 ]")

(defn- walked [seed opts]
  (parse! practice)
  (rc/seed! seed)
  (wb/walk (wb/chords-of :blues) opts))

(defn- onsets
  "[beat pitch] of each note, beats from 0."
  [notes]
  (map vector (reductions + 0 (map :duration notes)) (map :pitch notes)))

(defn- root-at
  "The bass note at beat b (a bar's start, whole notes)."
  [notes b]
  (some (fn [[t p]] (when (= t b) p)) (onsets notes)))

(deftest the-root-on-every-chord
  (doseq [seed [42 7 1234 99]]
    (let [notes (walked seed {})]
      (is (= [5 10 5 0 5 10 11 5 2 7 0 5 2 7 0 5]
             (map #(mod (root-at notes %) 12) [0 1 2 3 7/2 4 5 6 7 8 9 10 21/2 11 23/2 12]))
          (str "seed " seed ": F B& F C F B& B F D G C F D G C, then F")))))

(deftest a-quarter-a-beat-then-the-end-held
  (let [notes (walked 42 {})]
    (is (= 13 (reduce + (map :duration notes))) "twelve bars and the closing one")
    (is (every? #(= 1/4 (:duration %)) (butlast notes)))
    (is (= {:pitch (root-at notes 12) :duration 1} (last notes)) "the end: F, a whole bar")))

(deftest the-line-walks-and-stays-in-range
  (doseq [seed [42 7 1234 99]]
    (let [ps (map :pitch (walked seed {:choruses 4}))]
      (is (every? #(<= 28 % 55) ps) (str "seed " seed ": E1 to G3"))
      (is (every? #(<= 1 % 7) (map (comp abs -) (rest ps) ps)) (str "seed " seed ": no note twice, no leap past a fifth")))))

(deftest the-beat-before-a-chord-leads-into-it
  (let [notes (vec (walked 42 {}))
        times (vec (onsets notes))]
    (doseq [b [1 2 3 4 5 6 7 8 9 10 11 12]]
      (let [i      (some #(when (= b (first (times %))) %) (range (count times)))
            [_ to] (times i)
            [_ by] (times (dec i))]
        (is (or (<= (abs (- to by)) 2) (= 7 (mod (- by to) 12))) (str "bar " (inc b) ": a step or the fifth into it"))))))

(deftest choruses-repeat-the-form-and-end-once
  (let [notes (walked 42 {:choruses 3})]
    (is (= 37 (reduce + (map :duration notes))) "three times twelve bars, then the end")
    (is (= 1 (count (filter #(= 1 (:duration %)) notes))) "one held note, the last")))

(deftest without-an-end-the-line-loops
  (let [notes (walked 42 {:end false})]
    (is (= 13 (reduce + (map :duration notes))) "the closing bar walked like any other")
    (is (every? #(= 1/4 (:duration %)) notes))))

(deftest a-seed-repeats-the-line
  (is (= (walked 5 {}) (walked 5 {})))
  (is (not= (walked 5 {}) (walked 6 {}))))

(deftest the-text-plays-with-the-chords
  (parse! practice)
  (rc/seed! 42)
  (let [text (wb/->mus :blues)]
    (is (re-find #"^\[bass: !i:acousticBass F2/4 " text))
    (is (not (re-find #"#" text)) "chords written with flats: a bass spelled with flats")
    (parse! text))
  (let [evs   (ev/events (repo/registry) #{:blues :bass})
        notes (filter #(= :note (:kind %)) evs)]
    (is (= 12 (apply max (map :beat notes))) "both reach the closing bar, beat 12")
    (is (= 32 (:program (first (filter #(= 1 (count (:pitches %))) notes)))) "an acoustic bass")))

(deftest a-progression-gets-its-bass
  (parse! (hp/->mus :basic {:tonic "B&" :name :bb}))
  (rc/seed! 42)
  (testing "B& blues: the bass starts and ends on B&"
    (let [notes (wb/walk (wb/chords-of :bb))]
      (is (= 10 (mod (:pitch (first notes)) 12)))
      (is (= 10 (mod (:pitch (last notes)) 12))))))

(deftest a-repeat-gets-a-bass-in-its-form
  (parse! (hp/->mus :jazz {:tonic "F" :name :rv :repeat 2}))
  (doseq [seed [42 7 1234]]
    (rc/seed! seed)
    (let [text (wb/->mus :rv {:name :rvbass})]
      (is (re-find #"^\[rvbass: !i:acousticBass repeat 2 \[ .* \] volta \[ \[ .* \] \[ .* F2/1 \] \] \]$" text)
          "the chords' form, ending on a held F")
      (parse! text)
      (let [ns    (filter #(= :note (:kind %)) (ev/events (repo/registry) #{:rv :rvbass}))
            bass  (filter #(= 1 (count (:pitches %))) ns)
            at    (fn [xs b] (some #(when (= b (:beat %)) %) xs))
            ps    (map (comp first :pitches) bass)]
        (is (= 93 (count bass)) "23 bars of quarters and the held end")
        (is (every? #(= (mod (apply min (:pitches (at ns %))) 12) (mod (first (:pitches (at bass %))) 12))
                    (range 24))
            (str "seed " seed ": the root under every bar, both passes and both endings"))
        (is (every? #(<= 1 % 7) (map (comp abs -) (rest ps) ps))
            (str "seed " seed ": from body to ending and back, no leap past a fifth"))))))

(deftest a-repeat-without-endings-loops-its-body
  (parse! "[lp: !M:4/4 repeat 3 [ F3/1:7 | B&3:7 ] ]")
  (rc/seed! 42)
  (let [{:keys [count body endings] :as form} (wb/form-of :lp)
        [b es] (wb/walk-repeat form {})]
    (is (= [3 2 []] [count (clojure.core/count body) endings]))
    (is (empty? es))
    (is (= 8 (clojure.core/count b)) "two bars of quarters, the last leading back to F")
    (is (#{0 3 4 6 7} (mod (:pitch (last b)) 12)) "E or G&, E& or G, or C: a half step, a step or the fifth into F")))
