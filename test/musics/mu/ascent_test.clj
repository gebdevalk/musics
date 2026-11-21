(ns ^:repl musics.mu.ascent-test
  "mus/ascent.mus: four phases of an ambitus that grows each time its highest note, the target,
   is hit, notes shortening as it grows, a dimension more in each phase."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.material :as mt]
            [musics.mu.print]
            [musics.mu.tools]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "play" "music" "tool" "arith" "compare"])))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- run [text] (swap! e/state assoc :stack []) (e/eval-input text))

(defn- value
  "The value the text leaves on top of the stack, as mu holds it."
  [text]
  (let [r (run text)] (if (:error r) (throw (ex-info (:error r) {:text text})) (peek (:ok r)))))

(defn- draws [text] (vec (value text)))
(defn- leaves [text] (vec (mt/played-leaves (value text))))

(defn- shown [text]
  (let [r (run text)] (or (some->> (:ok r) (mapv musics.mu.print/show)) (:error r))))

(defn- load!
  "The piece, loaded, with the settings the numbers below count on pinned (the piece's own may change)."
  []
  (let [r (with-out-str (run (slurp (io/file "mus/ascent.mus"))))]
    (is (not (re-find #"can't read|error" r)) r)
    (run "'[1/2 1/2 1/2 1/2] :unit T!  '[55 55 55 55] :base T!  '[12 12 12 12] :top T!  '[4 4 4 4] :delay T!  '[70 70 70 70] :loudness T!")
    (run "'[14 73 68] :fixedtimbres T!  '[14 11 12 13 73 75 79 68 70 71 42 52 53] :ladder T!")))

(deftest a-level-is-the-attempts-at-a-target
  (load!)
  (run "5 rng/seed")
  (doseq [n (range 13)]
    (let [d (draws (str n " attempts"))]
      (is (= n (last d)) (str "the level of " n " ends when the target is hit"))
      (is (every? #(<= 0 % n) d) "every draw is of the ambitus 0..n")
      (when (pos? n) (is (= n (first d)) "the note just allowed is played first"))
      (is (not-any? #{n} (butlast (if (pos? n) (rest d) d))) "and the target is not hit before")))
  (is (= [0] (draws "0 attempts")) "the lowest note alone: its first attempt hits it")
  (testing "low notes are the likeliest"
    (let [f (frequencies (draws "'( 0 13 r/int-lo-emph ) 3000 pull"))]
      (is (> (f 0) (f 6) (f 11)) "a triangular draw, weighted to the low end"))))

(deftest the-seed-is-the-run
  (load!)
  (run "5 rng/seed")
  (let [a (draws "12 attempts")]
    (run "5 rng/seed")
    (is (= a (draws "12 attempts")) "the same seed, the same run")
    (run "6 rng/seed")
    (is (not= a (draws "12 attempts")) "another seed, another")))

(deftest a-level-has-the-dimensions-its-phase-has
  (load!)
  (run "5 rng/seed")
  (testing "phase 1: pitch only, the rest fixed"
    (let [ls (leaves "5 1 '[0 3 5 5] level-of")]
      (is (= [[55] [58] [60] [60]] (map :pitches ls)) "the draws above the lowest note")
      (is (= #{1/12} (set (map :duration ls))) "the unit over the 6 values of the ambitus 0..5")
      (is (= #{70} (set (map #(get-in % [:overrides :volume]) ls))))
      (is (= #{14} (set (map #(get-in % [:overrides :instrument]) ls))) "tubular bells")))
  (testing "phase 2: volume drawn from the ambitus, 25 + 75 * value / 12 at most"
    (let [ls (leaves "3 2 '[0 1 2 3 3 3 3 3 3 3] level-of")
          vs (map #(get-in % [:overrides :volume]) ls)]
      (is (every? #(<= 25 % (Math/round (double (+ 25 (* 75 3/12))))) vs) "values 0..3 only: 44 at most")
      (is (= #{73} (set (map #(get-in % [:overrides :instrument]) ls))) "flute, fixed")))
  (testing "phase 4: timbre drawn from the first rungs of the ladder"
    (let [ls (leaves "2 4 '[0 1 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2] level-of")
          is' (set (map #(get-in % [:overrides :instrument]) ls))]
      (is (every? #{14 11 12} is') "tubular bells, vibraphone, marimba")
      (is (> (count is') 1)))))

(deftest a-phase-of-the-whole-ambitus
  (load!)
  (run "5 rng/seed")
  (let [ph (leaves "1 phase-seq")]
    (is (= 55 (first (map (comp first :pitches) ph))) "from the lowest note, G3 ...")
    (is (= 67 (last (map (comp first :pitches) ph))) "... to the octave above it, hit")
    (is (= 1/2 (first (map :duration ph))))
    (is (= 1/26 (last (butlast (map :duration ph)))) "13 values: a thirteenth of the unit")
    (is (= 1/2 (last (map :duration ph))) "the last note, the top hit, as long as the first")
    (is (apply >= (butlast (map :duration ph))))))

(deftest the-timbres-may-be-layered
  (load!)
  (run "5 rng/seed")
  (run "'[ '[70 32] 73 '[43 57] ] :fixedtimbres T!  '[ '[47 12] '[12 0] '[0 19] '[19 32] '[32 70] ] :ladder T!")
  (let [ins #(get-in % [:overrides :instrument])]
    (testing "a fixed timbre may be a stack of programs, sounding together"
      (is (= #{[70 32]} (set (map ins (leaves "3 1 '[0 1 2 3] level-of")))) "phase 1: bassoon and acoustic bass")
      (is (= #{73} (set (map ins (leaves "3 2 '[0 1 2 3] level-of")))) "phase 2: a flute alone")
      (is (= #{[43 57]} (set (map ins (leaves "3 3 '[0 1 2 3] level-of")))) "phase 3: contrabass and trombone"))
    (testing "and so may a rung of the ladder"
      (let [is' (set (map ins (leaves "4 4 '[0 1 2 3 4 4 4 4 4 4 4 4 4 4 4 4 4 4] level-of")))]
        (is (every? #{[47 12] [12 0] [0 19] [19 32] [32 70]} is'))
        (is (> (count is') 1))))
    (testing "the engine sounds a note on each"
      (is (= [70 32] (ins (first (leaves "3 1 '[0] level-of"))))))))

(deftest a-setting-may-be-a-list-with-a-value-for-each-phase
  (load!)
  (run "'[3 3 3 3] :top T!  '[1 2 1 1] :voices T!")
  (run "'[1/2 1 2 1/4] :unit T!  '[55 48 43 60] :base T!  '[80 70 70 70] :loudness T!  '[2 4 4 4] :top T!")
  (let [first-note (fn [ph] (first (leaves (str ph " phase-seq"))))]
    (testing "the fixed duration of each phase"
      (is (= [1/2 1 2 1/4] (mapv #(:duration (first-note %)) [1 2 3 4]))))
    (testing "the lowest note of each phase"
      (is (= [55 48 43 60] (mapv #(first (:pitches (first-note %))) [1 2 3 4]))))
    (testing "the ambitus of each phase: its last note is as long as the first: the unit"
      (is (= [1/2 1 2 1/4] (mapv #(:duration (last (leaves (str % " phase-seq")))) [1 2 3 4]))))
    (testing "the volume a phase has before it is a dimension"
      (is (= 80 (get-in (first-note 1) [:overrides :volume]))))
    (testing "the delay of a canon in each phase, in that phase's units"
      (run "'[4 4 4 4] :delay T!  '[2 2 3 1] :voices T!")
      (is (= ["5/2" "9/2"] [(first (shown "[c d] 2 1 canon duration")) (first (shown "[c d] 2 2 canon duration"))])
          "4 units of 1/2, then 4 of 1: a rest of 2 whole notes, then of 4, behind the seq of 1/2"))))

(deftest voices-make-a-canon
  (load!)
  (testing "a seq played by n voices, each entering :delay units after the one before"
    (is (= ["{[C4/4 D4 E4]}"] (shown "[c d e] 1 1 canon")) "one voice is the seq as it is")
    (let [three (first (shown "[c d e] 3 1 canon"))]
      (is (re-find #"^\{\[C4/4 D4 E4\] \[r1\*2/1 C4/4 D4 E4\] \[r1\*4/1 C4/4 D4 E4\]\}$" three) "4 units of 1/2 = 2 whole notes, then 4")
      (is (= ["19/4"] (shown "[c d e] 3 1 canon duration")) "the last voice ends 4 whole notes after the first"))
    (run "'[2 2 2 2] :delay T!")
    (is (= ["11/4"] (shown "[c d e] 3 1 canon duration")) "a delay of 2 units, a whole note: the last voice 2 whole notes behind")))

(deftest phase-2-plays-two-voices
  (load!)
  (run "'[3 3 3 3] :top T!  '[1 2 1 1] :voices T!")
  (let [dur (fn [text] (run "5 rng/seed") (first (shown text)))]
    (testing "the voices of a phase are the same phase, entering later"
      (is (= ["1" "2" "1" "1"] (mapv #(str (nth (vec (draws ":voices T@@")) %)) (range 4))) "one voice in phase 1, two in 2")
      (let [alone (edn/read-string (dur "2 phase-seq duration"))
            canon (edn/read-string (dur "2 phase-voices duration"))]
        (is (= (+ alone 2) canon) "the second voice enters 4 units of 1/2 later: the phase takes 2 whole notes longer"))
      (is (= (dur "1 phase-seq duration") (dur "1 phase-voices duration")) "a phase of one voice is the phase")
      (run "5 rng/seed")
      (is (re-find #"^\{\[.*\] \[r1\*2/1 " (first (shown "2 phase-voices"))) "two voices, the second behind a rest"))))

(deftest the-four-phases
  (load!)
  (run "'[4 4 4 4] :top T!   5 rng/seed")                                       ; five notes: quick
  (let [phases (mapv #(leaves (str % " phase-seq")) [1 2 3 4])
        durs   #(map :duration %)
        pitch  #(map (comp first :pitches) %)
        by     (fn [f ph] (set (map f ph)))
        vol    #(get-in % [:overrides :volume])
        ins    #(get-in % [:overrides :instrument])]
    (testing "each phase climbs from the lowest note to the top of the ambitus"
      (doseq [ph phases]
        (is (= 55 (first (pitch ph))) "it starts on the lowest note alone")
        (is (= 59 (last (pitch ph))) "and ends hitting the top")
        (is (every? #(<= 55 % 59) (pitch ph)))))
    (testing "from long to short"
      (doseq [ph phases]
        (is (= 1/2 (first (durs ph))) "the fixed duration at first")
        (is (= 1/10 (last (butlast (durs ph)))) "a fifth of it with 5 values")
        (is (= 1/2 (last (durs ph))) "the last note as long as the first")
        (is (apply >= (butlast (durs ph))) "never longer again")
        (is (every? (set (map #(/ 1/2 %) (range 1 6))) (durs ph)))))
    (testing "a dimension more in each phase"
      (is (= #{70} (by vol (phases 0))) "phase 1: one volume ...")
      (is (= #{14} (by ins (phases 0))) "... and one timbre")
      (is (> (count (by vol (phases 1))) 3) "phase 2: volumes")
      (is (every? #(<= 25 % 100) (by vol (phases 1))))
      (is (= #{73} (by ins (phases 1))) "the timbre is fixed")
      (is (> (count (by :articulation (phases 2))) 1) "phase 3: articulations")
      (is (= #{68} (by ins (phases 2))))
      (is (> (count (by ins (phases 3))) 3) "phase 4: timbres")
      (is (every? #{14 11 12 13 73 75 79 68 70 71 42 52 53} (by ins (phases 3)))))
    (testing "the ascent is the phases one after another"
      (run "5 rng/seed")
      (let [all (leaves "'[1 2] phases-seq")]                                       ; two of them, to be quick
        (is (= 55 (first (pitch all))))
        (is (= 6 (count (filter #(= 1/2 (:duration %)) all))) "each phase begins long again and ends as long: phase 1 once, phase 2 in both its voices")
        (is (= 1/2 (:duration (last all))))))))

(deftest it-starts-and-plays
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (load!)
    (run "'[3 3 3 3] :top T!")                                               ; a small ambitus: quick
    (binding [engine/*engine* eng]
      (with-redefs [engine/receiver (atom :silent)]
        (doseq [word ["'[1] start" "'[3] start" "'[2 1 4] start"]]
          (let [r (run word)]
            (is (nil? (:error r)) (:error r))
            (is (engine/busy?) (str word ": a voice plays"))
            (run "stop")))))))
