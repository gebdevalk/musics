(ns ^:repl musics.mu.live-test
  "A play word given a recipe plays live (musics.mu.live): on a silent
   engine, what a voice plays is read as events through its live name."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.engine :as engine]
            [musics.events :as ev]
            [musics.mu.eval :as e]
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
  (run "| '( [c d e] ) :riff >")
  (is (= :riff (:algo (engine/voice-at :riff))) "the voice plays through its own name")
  (is (= [60 62 64] (pitches "[c d e]" :riff))))

(deftest a-recipe-on-music-changes-it
  (run "[c d e] '( 5 + ) :up >")
  (is (= :up (:algo (engine/voice-at :up))))
  (is (= [65 67 69] (pitches "[c d e]" :up))))

(deftest a-mark-under-the-recipe-leaves-the-music-below
  (is (= 1 (count (run "[a b] | '( [c] ) :g >"))) "[a b] stays on the stack")
  (is (= [60] (pitches "[r4]" :g)) "the recipe ran on nothing"))

(deftest settings-are-heard-and-checked
  (run "| '( ^{ ?k:3 ?n:8 ?duration-pulse:1/8 } duration/euclid [c e g] pitches zip [~] ) :riff >")
  (is (= [3/8 3/8 1/4] (durations "[c8 c c c] [~]" :riff 3)) "the recipe's own settings")
  ;; the voice keeps its position (one cursor per voice path), so a
  ;; change goes on mid-pattern: the values tell the patterns apart
  (testing "set comes first, before the recipe's ^{ }"
    (run ":riff ^{ ?k:5 } set")
    (is (= #{1/4 1/8} (set (durations "[c8 c c c c c c] [~]" :riff 6)))))
  (testing "settings lists what the recipe reads"
    (is (str/includes? (with-out-str (run ":riff settings")) ":k")))
  (testing "a value out of range is refused"
    (is (thrown? clojure.lang.ExceptionInfo (run ":riff ^{ ?k:9999 } set"))))
  (testing "unset leaves it to the recipe's ^{ } again"
    (run ":riff '?k unset")
    (is (= #{3/8 1/4} (set (durations "[c8 c c c c c c] [~]" :riff 6))))))

(deftest retree-keeps-the-name
  (run "| '( [c d e] ) :riff >")
  (run "'( [f g a] ) :riff retree")
  (is (= [65 67 69] (pitches "[c d e]" :riff))))

(deftest a-voice-without-a-recipe-has-no-settings
  (run "[c d] :plain >")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plays no recipe" (run ":plain ^{ ?k:5 } set"))))

(deftest a-recipe-that-fails-is-refused-when-played
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs" (run "[c d] [~] '( pitch/walk zip zip ) :bad >")))
  (is (nil? (engine/voice-at :bad)) "nothing plays"))

(deftest a-recipe-must-leave-music
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"leaves music" (run "| '( 7 ) :n >"))))

(deftest a-composed-tree-is-a-recipe
  (let [tree (lib/zip (lib/pulses->durations lib/euclid) (lib/cycle> lib/scale))]
    (live/play! nil tree nil false :tr nil)
    (is (= :tr (:algo (engine/voice-at :tr))))
    (run ":tr ^{ ?k:5 } set")
    (is (= 5 (:k (t/params (live/tctx-of "set" :tr)))) "its settings know the tree's params")))

(deftest recipe-values
  (is (live/recipe? (peek (run "'( 5 + )"))))
  (is (not (live/recipe? (peek (run "[c d]"))))))

(deftest a-change-reruns-only-the-words-it-reaches
  (run "| '( ^{ ?len:8 } pitch/walk [c8] [~] durations swap zip volume/walk zip ) :swell >")
  (let [run!   (:run (musics.wall/registered :swell))
        tctx   (live/tctx-of "set" :swell)
        played #(take 8 (run! tctx nil))
        before (played)]
    (run ":swell ^{ ?volume-lo:70 ?volume-hi:90 } set")
    (let [after (played)]
      (is (= (map :pitches before) (map :pitches after)) "the walk heard as pitch keeps its random values")
      (is (not= (map #(get-in % [:overrides :volume]) before) (map #(get-in % [:overrides :volume]) after))
          "the volumes, which the change reaches, run again")
      (is (every? #(<= 70 (get-in % [:overrides :volume]) 90) after)))))
