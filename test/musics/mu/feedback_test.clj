(ns ^:repl musics.mu.feedback-test
  "mu's feedback words: rating the sound last played or the audition's
   item, combos, and reading the ratings back -- without playing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.engine :as engine]
            [musics.feedback :as fb]
            [musics.input.reader.builder :as builder]
            [musics.midi.gervill :as gervill]
            [musics.mu.eval :as e]
            [musics.mu.feedback :as mfb]
            [musics.mu.kernel]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.tools]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]])
  (:import (java.util.concurrent LinkedBlockingQueue)))

(def ^:private said (atom []))

(defn- fresh
  "A fresh registry and mu state, ratings in a scratch file, what is said
   above the prompt collected, no audition and nothing played."
  [t]
  (let [saved  @e/state
        file   @fb/file
        above  @e/print-above
        f      (java.io.File/createTempFile "musics.mu.feedback-test" ".edn")]
    (.delete f)
    (reset! fb/file (.getPath f))
    (reset! said [])
    (reset! e/print-above #(swap! said conj %))
    (reset! mt/last-played nil)
    (reset! mfb/audition nil)
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "tool" "music" "play" "rate"])))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally
        (reset! e/state saved) (reset! fb/file file) (reset! e/print-above above)
        (reset! mt/last-played nil) (reset! mfb/audition nil)
        (io/delete-file f true)))))

(use-fixtures :each fresh)

(defn- top [input]
  (swap! e/state assoc :stack [])
  (let [r (e/eval-input input)]
    (if (:error r) (:error r) (str/join " " (map p/show (:ok r))))))

(defn- value [input]
  (swap! e/state assoc :stack [])
  (peek (:ok (e/eval-input input))))

(deftest nothing-to-rate-until-a-sound-is-played
  (top "like")
  (is (re-find #"nothing to rate" (last @said)))
  (is (= [] (fb/ratings)))
  (reset! mt/last-played (value "[c d]"))
  (top "like")
  (is (= [] (fb/ratings)) "a line isn't a sound"))

(deftest rating-the-sound-last-played
  (reset! mt/last-played (value "c4\\i:40,73"))
  (top "like")
  (top "dislike")
  (is (= [["C4/4\\instrument:40,73" 1 :repl] ["C4/4\\instrument:40,73" -1 :repl]]
         (map (juxt :what :score :where) (fb/ratings))))
  (is (= "like: C4/4\\instrument:40,73  violin + flute" (first @said)))
  (top "meh")
  (is (= 0 (:score (last (fb/ratings))))))

(deftest rating-the-auditions-item-moves-on
  (let [q (LinkedBlockingQueue.)]
    (reset! mfb/audition {:items [{:value (value "x8\\36")} {:value (value "x8\\36,39")}] :i 1 :queue q})
    (top "like")
    (is (= ["x8\\36,39" 1 :audition] ((juxt :what :score :where) (last (fb/ratings)))))
    (is (= :next (.poll q)) "a rating moves on")
    (top "prev")
    (is (= :back (.poll q)))))

(deftest combos-layer-instruments-and-drums
  (is (= "[C4/4\\instrument:40,41 C4\\instrument:40,42 C4\\instrument:41,42]"
         (top "c4 '[40 41 42] 2 combos")))
  (is (= "[x8\\36 x\\38]" (top "x8 '[36 38] 1 combos")))
  (is (= "[x8\\36,38,42]" (top "x8 '[36 38 42] 3 combos")))
  (is (re-find #"combos: needs a count 1 to 2" (top "c4 '[40 41] 3 combos")))
  (is (re-find #"combos: needs a note" (top "[c d] '[40 41] 1 combos"))))

(deftest liked-and-disliked-give-the-sounds-back
  (doseq [[what score] [["C4/4\\instrument:40" 1] ["x8\\36" -1] ["C4/4\\instrument:41" 1]]]
    (fb/rate! {:what what} score))
  (is (= "[C4/4\\instrument:40 C4\\instrument:41]" (top "liked")))
  (is (= "[x8\\36]" (top "disliked")))
  (is (str/includes? (with-out-str (top "taste")) "x\\36 Bass Drum 1")))

(deftest auditioning-needs-sounds
  (is (re-find #"audition: needs a line of notes" (top "'[1 2] audition"))))

(defn- wait-until [pred ms]
  (loop [n (quot ms 10)] (cond (pred) true (zero? n) false :else (do (Thread/sleep 10) (recur (dec n))))))

(deftest an-audition-plays-each-sound-in-turn
  (let [played (atom [])]
    (with-redefs [mt/play!      (fn [x _ & _] (swap! played conj (p/show x)))
                  engine/busy?  (constantly false)
                  mfb/gap-ms    (atom 30)]
      (testing "auto: one after another by itself"
        (top "x8 '[36 38 42] 1 combos audition")
        (is (wait-until #(nil? @mfb/audition) 2000))
        (is (= ["x8\\36" "x8\\38" "x8\\42"] @played))
        (is (= "audition: done" (last @said))))
      (testing "step: each waits; a rating moves on, again plays it once more"
        (reset! played [])
        (reset! mfb/auto? false)
        (try
          (top "x8 '[36 38] 1 combos audition")
          (is (wait-until #(= 1 (count @played)) 1000))
          (top "again")
          (is (wait-until #(= 2 (count @played)) 1000))
          (top "like")
          (is (wait-until #(= 3 (count @played)) 1000))
          (is (= ["x8\\36" "x8\\36" "x8\\38"] @played))
          (is (= ["x8\\36" :audition] ((juxt :what :where) (last (fb/ratings)))))
          (top "stop")
          (is (wait-until #(nil? @mfb/audition) 1000) "stop stops it")
          (finally (reset! mfb/auto? true)))))))

(deftest trying-the-soundfonts-plays-the-music-through-every-instrument-of-each
  (let [played  (atom [])
        holds   {"/sf/a.sf2" [{:bank 0 :program 40 :name "Violin" :percussion? false}
                              {:bank 0 :program 0 :name "Piano " :percussion? false}
                              {:bank 0 :program 0 :name "Kit" :percussion? true}
                              {:bank 8 :program 1 :name "Other bank" :percussion? false}]
                 "/sf/b.sf2" [{:bank 0 :program 48 :name "Strings" :percussion? false}]}
        run     (fn [input]
                  (reset! played [])
                  (with-out-str (top input))
                  (wait-until #(nil? @mfb/audition) 2000))]
    (with-redefs [engine/soundfonts   (constantly ["/sf/a.sf2" "/sf/b.sf2"])
                  engine/soundfont!   (fn [path] (swap! played conj path) "bank")
                  gervill/instruments holds
                  mt/play!            (fn [x ctx & _] (swap! played conj (str (p/show x) " " (p/show ctx))))
                  engine/busy?        (constantly false)
                  mfb/gap-ms          (atom 30)]
      (is (run "[c d] try-soundfonts"))
      (is (= ["/sf/a.sf2" "[C4/4 D4] ^{ !i:0 }" "[C4/4 D4] ^{ !i:40 }"
              "/sf/b.sf2" "[C4/4 D4] ^{ !i:48 }"]
             @played)
          "each soundfont loaded once, its bank-0 instruments in program order, no kit")
      (is (some #{"2/3  0> a.sf2  !i:40 Violin"} @said) "shown by the number soundfont takes it by")
      (testing "a text picks the instruments, by their own name or their General MIDI one"
        (is (run "[c d] \"STRING\" try-soundfonts"))
        (is (= ["/sf/b.sf2" "[C4/4 D4] ^{ !i:48 }"] @played))
        (is (some #{"1/1  1> b.sf2  !i:48 Strings"} @said) "keeping its soundfont's number")
        (is (run "[c d] \"acousticGrand\" try-soundfonts"))
        (is (= ["/sf/a.sf2" "[C4/4 D4] ^{ !i:0 }"] @played) "program 0's General MIDI name")
        (is (re-find #"no instrument of a soundfont has \"b.sf\"" (top "[c] \"b.sf\" try-soundfonts"))
            "not the soundfont's file"))))
  (is (re-find #"try-soundfonts: needs music" (top "3 try-soundfonts"))))
