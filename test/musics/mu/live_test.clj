(ns ^:repl musics.mu.live-test
  "A play word given a quotation, or a word with $ inputs, plays live
   (musics.mu.live): on a silent engine, what a voice plays is read as events
   through its live name."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.engine :as engine]
            [musics.events :as ev]
            [musics.mu.eval :as e]
            [musics.mu.inputs :as inputs]
            [musics.mu.live :as live]
            [musics.mu.store]
            [musics.mu.value :as v]
            [musics.repo :as repo]
            [musics.wall]
            [musics.test-support :refer [with-fresh-session]]))

(use-fixtures :each
  (fn [t]
    (let [saved @e/state]
      (try
        (with-fresh-session
          (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
            (with-redefs [engine/receiver (atom :silent)]
              (try (t) (finally (engine/stop!))))))
        (finally (reset! e/state saved))))))

(defn- run [& inputs]
  (swap! e/state assoc :stack [])
  (let [r (last (mapv e/eval-input inputs))]
    (when (:error r) (throw (ex-info (:error r) {})))
    (:ok r)))

(defn- notes
  "The note events music x (mu text) plays through live name nm."
  [x nm]
  (let [m (peek (e/run-text [] x))]
    (filter #(= :note (:kind %))
            (ev/events (merge (into {} @(repo/registry)) (v/own-nodes m)) (:id m) :algo nm))))

(defn- pitches [x nm] (map (comp first :pitches) (notes x nm)))

(deftest a-recipe-from-nothing-plays-what-it-gives
  (run "| '( [c d e] ) :riff ->")
  (is (= :riff (:algo (engine/voice-at :riff))) "the voice plays through its own name")
  (is (= [60 62 64] (pitches "[c d e]" :riff))))

(deftest a-recipe-on-music-changes-it
  (run "[c d e] '( 5 + ) :up ->")
  (is (= :up (:algo (engine/voice-at :up))))
  (is (= [65 67 69] (pitches "[c d e]" :up))))

(deftest a-mark-under-the-recipe-leaves-the-music-below
  (is (= 1 (count (run "[a b] | '( [c] ) :g ->"))) "[a b] stays on the stack")
  (is (= [60] (pitches "[r4]" :g)) "the recipe ran on nothing"))

(deftest retree-keeps-the-name
  (run "| '( [c d e] ) :riff ->")
  (run "'( [f g a] ) :riff retree")
  (is (= [65 67 69] (pitches "[c d e]" :riff))))


(deftest a-voice-that-plays-nothing-live-has-no-inputs
  (run "[c d] :plain ->")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plays nothing live" (run ":plain settings"))))

(deftest a-recipe-that-fails-is-refused-when-played
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs" (run "[c d] [~] '( zip zip ) :bad ->")))
  (is (nil? (engine/voice-at :bad)) "nothing plays"))

(deftest a-recipe-must-leave-music
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plays music" (run "| '( 7 ) :n ->"))))

(deftest recipe-values
  (is (live/recipe? (peek (run "'( 5 + )"))))
  (is (not (live/recipe? (peek (run "[c d]"))))))

(deftest a-word-with-dollar-inputs-plays-live
  (run ": pat ( $count -- l ) [c d e f g a b] $count take ;")
  (testing "start it with its inputs under its name: 3 'pat :w ->"
    (run "3 'pat :w ->")
    (is (= [60 62 64 60 62] (take 5 (pitches "[c c c c c c]" :w))) "each note the next of what the word gives, a short seq again")
    (is (= [3] (:stack (do (swap! e/state assoc :stack []) (e/eval-input ":w/count @") @e/state)))
        "the input is :w/count"))
  (testing "a store is heard: the word runs again with the new value"
    (run "2 :w/count !")
    (is (= [60 62 60 62] (take 4 (pitches "[c c c c c c]" :w)))))
  (testing "the inputs are named in the order of the stack effect"
    (run ": two ( $a $b -- l ) [c d e f g a b] $a take $b + ;")
    (run "5 2 'two :t ->")
    (is (= [5 2] [(first (:stack (do (swap! e/state assoc :stack []) (e/eval-input ":t/a @") @e/state)))
                  (first (:stack (do (swap! e/state assoc :stack []) (e/eval-input ":t/b @") @e/state)))]))
    (is (= [62 64 66 67 69] (take 5 (pitches "[c c c c c c]" :t))))))

(deftest a-live-word-needs-a-voice-name-and-only-dollar-inputs
  (run ": pat ( $count -- l ) [c d e f g a b] $count take ;")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name the voice that plays pat" (run "3 'pat ->")))
  (run ": mixed ( x $k -- l ) x $k take ;")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mixed has inputs without \$" (run "[c d] 1 'mixed :m ->")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pat takes 1 input under its name" (run "'pat :w ->"))))

(defn- out-of [in]
  (swap! e/state assoc :stack [])
  (let [o (with-out-str (let [r (e/eval-input in)] (when (:error r) (println "ERR:" (:error r)))))]
    (str/trim-newline o)))

(deftest a-word-voice-has-settings-and-a-restart
  (require 'musics.algo.rhythmic.pattern)   ; its parameters are where the ranges come from
  (run ": pat ( $onsets $label -- l ) [c d e f g a b] $onsets take ;")
  (run "3 \"x\" 'pat :w ->")
  (testing "settings lists the inputs; one named like an algo's param has its range"
    (let [o (out-of ":w settings")]
      (is (re-find #":onsets +3 +0\.\.32" o) o)
      (is (re-find #":label +\"x\" +string" o) "an input no algo reads has the type of its value")))
  (testing "set takes a value as before, and a value the spec refuses is an error that changes nothing"
    (run "2 :w/onsets !")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"onsets: 99 should be at most 32" (run "99 :w/onsets !")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"onsets: 2.5 should be an integer" (run "2.5 :w/onsets !")))
    (is (= 2 (inputs/value :w "onsets")) "still 2")
    (is (= 2 (inputs/value :w "onsets"))))
  (testing ":w -> starts it again with the inputs as they are now"
    (run "5 :w/onsets !")
    (run ":w ->")
    (is (= 5 (peek (run ":w/onsets @"))))
    (is (= [60 62 64 65 67 60] (take 6 (pitches "[c c c c c c c c]" :w))))))

(deftest inputs-of-a-voice-that-is-gone-are-not-reachable
  (run ": pat ( $onsets -- l ) [c d e f g a b] $onsets take ;")
  (run "3 'pat :w ->")
  (is (= 3 (peek (run ":w/onsets @"))))
  (engine/stop!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the voice :w is not playing" (run ":w/onsets @"))))

(deftest a-word-voice-input-belongs-to-the-algo-that-owns-the-param
  (require 'musics.algo.rhythmic.pattern 'musics.algo.rhythmic.feel 'musics.algo.rhythmic.sieve)
  (run ": thin ( $similarity $label -- l ) [c d e f g a b] 4 take ;")
  (run "0.5 \"x\" 'thin :w ->")
  (let [specs (inputs/specs :w)]
    (is (= :emi (:algo (:similarity specs))) "one algo reads similarity: the control is under it")
    (is (= :thin (:algo (:label specs))) "no algo reads label: it is under the word"))
  (run ": two ( $onsets -- l ) [c d e f g a b] 4 take ;")
  (run "3 'two :v ->")
  (is (= :two (:algo (:onsets (inputs/specs :v))))
      "several algos read onsets: it stays under the word, not under a guess"))

