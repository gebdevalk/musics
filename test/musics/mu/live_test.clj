(ns ^:repl musics.mu.live-test
  "A play word given a recipe plays live (musics.mu.live): on a silent
   engine, what a voice plays is read as events through its live name."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.algo.tree.tools :as tools]
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

(defn- durations
  "The beats from each note to the next, of the first n+1 notes."
  [x nm n]
  (let [bs (map :beat (take (inc n) (notes x nm)))]
    (map - (rest bs) bs)))

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

(deftest settings-are-heard-and-checked
  (run "| '( @^{ onsets:3 pulses:8 duration-pulse:1/8 } duration/euclid [c e g] pitches zip [~] ) :riff ->")
  (is (= [3/8 3/8 1/4] (durations "[c8 c c c] [~]" :riff 3)) "the recipe's own settings")
  ;; the voice keeps its position (one cursor per voice path), so a
  ;; change goes on mid-pattern: the values tell the patterns apart
  (testing "set comes first, before the recipe's head"
    (run ":riff @^{ onsets:5 } set")
    (is (= #{1/4 1/8} (set (durations "[c8 c c c c c c] [~]" :riff 6)))))
  (testing "settings lists what the recipe reads"
    (is (str/includes? (with-out-str (run ":riff settings")) ":onsets")))
  (testing "a value out of range is refused"
    (is (thrown? clojure.lang.ExceptionInfo (run ":riff @^{ onsets:9999 } set"))))
  (testing "unset leaves it to the recipe's head again"
    (run ":riff 'onsets unset")
    (is (= #{3/8 1/4} (set (durations "[c8 c c c c c c] [~]" :riff 6))))))

(deftest retree-keeps-the-name
  (run "| '( [c d e] ) :riff ->")
  (run "'( [f g a] ) :riff retree")
  (is (= [65 67 69] (pitches "[c d e]" :riff))))

(deftest a-voice-without-a-recipe-has-no-settings
  (run "[c d] :plain ->")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plays no recipe" (run ":plain @^{ onsets:5 } set"))))

(deftest a-recipe-that-fails-is-refused-when-played
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs" (run "[c d] [~] '( pitch/walk zip zip ) :bad ->")))
  (is (nil? (engine/voice-at :bad)) "nothing plays"))

(deftest a-recipe-must-leave-music
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"leaves music" (run "| '( 7 ) :n ->"))))

(deftest a-composed-tree-is-a-recipe
  (let [tree (lib/zip (lib/pulses->durations lib/euclid) (tools/cycle lib/scale))]
    (live/play! nil tree nil false :tr nil)
    (is (= :tr (:algo (engine/voice-at :tr))))
    (run ":tr @^{ onsets:5 } set")
    (is (= 5 (:onsets (t/params (live/tctx-of "set" :tr)))) "its settings know the tree's params")))

(deftest recipe-values
  (is (live/recipe? (peek (run "'( 5 + )"))))
  (is (not (live/recipe? (peek (run "[c d]"))))))

(deftest a-change-reruns-only-the-words-it-reaches
  (run "| '( @^{ len:8 } pitch/walk [c8] [~] durations swap zip volume/walk zip ) :swell ->")
  (let [run!   (:run (musics.wall/registered :swell))
        tctx   (live/tctx-of "set" :swell)
        played #(take 8 (run! tctx nil))
        before (played)]
    (run ":swell @^{ volume-lo:70 volume-hi:90 } set")
    (let [after (played)]
      (is (= (map :pitches before) (map :pitches after)) "the walk heard as pitch keeps its random values")
      (is (not= (map #(get-in % [:overrides :volume]) before) (map #(get-in % [:overrides :volume]) after))
          "the volumes, which the change reaches, run again")
      (is (every? #(<= 70 (get-in % [:overrides :volume]) 90) after)))))

(deftest a-recipes-head-is-the-voices-settings
  (run "| '( @^{ onsets:3 pulses:8 duration-pulse:1/8 } duration/euclid [c e g] pitches zip [~] ) :head ->")
  (let [tctx (live/tctx-of "settings" :head)]
    (is (= [3 8 1/8] ((juxt :onsets :pulses :duration-pulse) (t/params tctx))) "settings show the head as written")
    (run ":head @^{ onsets:5 } set")
    (is (= 5 (:onsets (t/params tctx))))
    (run ":head 'onsets unset")
    (is (= #{3/8 1/4} (set (durations "[c8 c c c c c c] [~]" :head 6))) "unset: the head's onsets:3 again")))

(deftest a-key-param-plays-in-the-music-a-voice-plays-in
  (run "[!key:D.major c d e] '( @^{ start:0.0 step-bound:0.0 len:3 } pitch/walk-degrees zip ) :deg ->")
  (is (= 62 (first (pitches "[!key:D.major c d e]" :deg))) "!key: of the line reaches the bridge's key: D major's tonic"))

(deftest a-tree-recipe-plays-live-and-set-changes-it
  (run "| @( '[1/8] [~] scale [~] zip ) :sc ->")
  (is (= :sc (:algo (engine/voice-at :sc))))
  (is (= [60 62 64] (pitches "[c d e]" :sc)) "each note the next the recipe gives, lazily")
  (run ":sc @^{ root:62 } set")
  (is (= 62 (:root (t/params (live/tctx-of "set" :sc)))) "its settings are the voice's")
  (is (re-find #"takes nothing" (try (run "[c d] @( '[1/8] [~] scale [~] zip ) :x ->") (catch Exception ex (ex-message ex))))
      "a generator plays on nothing"))

(deftest a-tree-recipe-plays-on-the-music-under-it
  (run "[c d e] @( '[0.1 0.9] [~] numbers->volumes zip ) :vol ->")
  (is (= :vol (:algo (engine/voice-at :vol))))
  (is (= [60 62 64] (pitches "[c d e]" :vol)) "the music's own notes")
  (is (= 2 (count (distinct (map :velocity (notes "[c d e]" :vol))))) "with the recipe's volumes on them"))

(deftest a-named-recipe-plays-as-its-own-voice
  (run "| @( sc: '[1/8] [~] scale [~] zip ) ->")
  (is (= :sc (:algo (engine/voice-at :sc))))
  (is (= [60 62 64] (pitches "[c d e]" :sc))))

(deftest set-stays-with-the-voice
  (run "@( sc: '[1/8] [~] scale [~] zip ) R!" "| :sc R@ :sc ->" ":sc @^{ root:62 } set")
  (is (= [62 64 66] (pitches "[c d e]" :sc)) "the voice plays its setting")
  (is (not= 62 (:root (t/params (:tctx (peek (run ":sc R@")))))) "what is stored keeps its own"))

(deftest a-voice-follows-the-recipe-stored-under-its-id
  (run "@( sc: '[1/8] [~] scale [~] zip ) R!" "| :sc R@ :sc ->" ":sc @^{ root:62 } set")
  (run "@( sc: ^{ root:67 } '[1/8] [~] scale [~] zip ) R!")
  (is (= [67 69 71] (pitches "[c d e]" :sc)) "the new recipe, its settings, not what set gave the old one"))

(deftest a-word-with-dollar-inputs-plays-live
  (run ": pat ( $count -- l ) [c d e f g a b] $count take ;")
  (testing "start it with its inputs under its name: 3 'pat :w ->"
    (run "3 'pat :w ->")
    (is (= [60 62 64 60 62] (take 5 (pitches "[c c c c c c]" :w))) "each note the next of what the word gives, a short line again")
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
  (run ": pat ( $onsets $label -- l ) [c d e f g a b] $onsets take ;")
  (run "3 \"x\" 'pat :w ->")
  (testing "settings lists the inputs; one named like an algo's param has its range"
    (let [o (out-of ":w settings")]
      (is (re-find #":onsets +3 +0\.\.32" o) o)
      (is (re-find #":label +\"x\" +string" o) "an input no algo reads has the type of its value")))
  (testing "set takes a value as before, and a value the spec refuses is an error that changes nothing"
    (run ":w @^{ onsets:2 } set")
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
  (run ": thin ( $similarity $label -- l ) [c d e f g a b] 4 take ;")
  (run "0.5 \"x\" 'thin :w ->")
  (let [specs (inputs/specs :w)]
    (is (= :emi (:algo (:similarity specs))) "one algo reads similarity: the control is under it")
    (is (= :thin (:algo (:label specs))) "no algo reads label: it is under the word"))
  (run ": two ( $onsets -- l ) [c d e f g a b] 4 take ;")
  (run "3 'two :v ->")
  (is (= :two (:algo (:onsets (inputs/specs :v))))
      "several algos read onsets: it stays under the word, not under a guess"))
