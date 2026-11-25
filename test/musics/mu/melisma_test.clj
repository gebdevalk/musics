(ns ^:repl musics.mu.melisma-test
  "mus/melisma.mus: a cantus firmus and melismatic counter voices in a small ambitus, in
   ternary groups -- voices that are functions of the onset, their choices functions of a seed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [musics.compose :as compose]
            [musics.domain.context :as c]
            [musics.events :as ev]
            [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.inputs :as inputs]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.live :as live]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
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
               (doseq [v [:cantus :voice1 :voice2]] (inputs/clear! v))
               (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- run [text] (swap! e/state assoc :stack []) (e/eval-input text))

(defn- at
  "text run as a voice runs it at onset on, the tctx's keywords answered; what it leaves, printed."
  [on text]
  (let [g (c/getter [live/tctx] on)
        r (e/with-lookup (fn [k] (if (= k :onset) on (g k))) #(run text))]
    (or (some-> (:ok r) (->> (mapv p/show))) (:error r))))

(defn- load!
  "The piece, loaded, and the settings these tests count on pinned (the piece's own may be changed)."
  []
  (let [r (with-out-str (run (slurp (io/file "mus/melisma.mus"))))]
    (is (not (re-find #"can't read|error" r)) r)
    (run "'[57 53 52] :timbres T!  '[-12 12 12] :shifts T!  '[68 62 58] :levels T!  '[1.0 0.9 0.9] :articulations T!  5 :seed T!  2 :rounds T!")))

(defn- leaf [on v] (first (at on (str on " " v " voice"))))

(defn- pitch-of
  "The MIDI pitch of a printed note ([D4/8\\...]), nil for a rest."
  [s]
  (when-let [[_ n acc o] (re-find #"^\[(?:\d+:\d+\[)?([A-G])([#&]?)(\d)" s)]
    (+ (* 12 (inc (Integer/parseInt o))) ({"C" 0 "D" 2 "E" 4 "F" 5 "G" 7 "A" 9 "B" 11} n) ({"#" 1 "&" -1 "" 0} acc))))

(defn- groups
  "The leaves voice v plays over the groups g0 .. g1, as [onset leaf-text], walking the voice as the
   engine does: each leaf asked at the onset the one before it leaves."
  [v g0 g1]
  (loop [on (* g0 3/8) out []]
    (if (>= on (* g1 3/8))
      out
      (let [s (leaf on v)
            d (if (re-find #"/4\\|\[r4\]" s) 1/4 1/8)]
        (recur (+ on d) (conj out [on s d]))))))

(deftest the-cantus-firmus-is-one-long-note-for-three-groups
  (load!)
  (is (= [57 59 60 62 60 59] (mapv #(Integer/parseInt (first (at (* % 9/8) ":cantus T@@"))) (range 6))) "the line, a degree each note")
  (is (re-find #"^\[8:9\[A2/1\\instrument:57\\panning:0.0\\volume:68\]\]" (leaf 0 0)) "nine eighths, the trombone an octave down")
  (is (re-find #"^\[8:9\[B2/1\\instrument:57" (leaf 9/8 0)) "and then the next"))

(deftest the-voices-enter-one-after-another
  (load!)
  (is (= ["[r8]" "[r8]"] [(leaf 0 1) (leaf 0 2)]) "the counter voices wait, the cantus alone")
  (is (every? nil? (map (comp pitch-of #(leaf (+ (* 1 9/8) %) 1)) (range 0 9/8 1/8))) "voice 1 is silent through note 1 of the cantus")
  (is (some? (pitch-of (leaf (* 2 9/8) 1))) "and enters with note 2")
  (is (every? nil? (map (comp pitch-of #(leaf (+ (* 5 9/8) %) 2)) (range 0 9/8 1/8))) "voice 2 is still silent at note 5")
  (is (some some? (map (comp pitch-of #(leaf (+ (* 6 9/8) %) 2)) (range 0 9/8 1/8))) "and enters at note 6"))

(deftest the-counter-voice-sings-in-ternary-groups-in-the-ambitus
  (load!)
  (let [gs   (groups 1 6 106)                       ; 100 groups, once voice 1 has entered
        ps   (map #(- % 12) (keep (comp pitch-of second) gs))   ; voice 1 sings an octave up
        durs (frequencies (map #(nth % 2) gs))]
    (is (every? #(<= 55 % 64) ps) "g below the a up to the e, nothing else (an octave up)")
    (is (every? #{55 57 59 60 62 64} ps) "white notes: g a b c d e")
    (is (pos? (count (filter #{55} ps))) "now and then the g below")
    (is (< (count (filter #{55} ps)) (* 0.25 (count ps))) "but only now and then")
    (is (= #{1/8 1/4} (set (keys durs))) "a note of one pulse or two")
    (testing "each group is filled exactly: 111, 21 or 12"
      (let [by-group (group-by #(quot (* 8 (first %)) 3) gs)]
        (is (every? #(= 3/8 (reduce + (map (fn [x] (nth x 2)) (val %)))) by-group))
        (is (= #{[1/8 1/8 1/8] [1/4 1/8] [1/8 1/4]}
               (set (map #(mapv (fn [x] (nth x 2)) (val %)) by-group))) "all three ways turn up")))))

(deftest the-choice-is-the-seeds
  (load!)
  (let [chant (fn [] (mapv second (groups 1 6 30)))
        a (chant)]
    (is (= a (chant)) "the same seed, the same chant")
    (run "6 :seed T!")
    (is (not= a (chant)) "another seed, another chant")))

(deftest it-ends-with-a-cadence
  (load!)
  (let [end 333/8]                                   ; the cantus firmus' last note, 35, held to note 37
    (is (= 35 (Integer/parseInt (first (at 0 ":last T@@")))) "two rounds of the 18-note line")
    (is (= 45 (pitch-of (leaf 315/8 0))) "the cantus firmus ends on the a, an octave down")
    (is (= ["[]"] (at end "333/8 0 voice")) "and the voices finish when its two notes are over")
    (is (= ["[]"] (at end "333/8 1 voice")))
    (is (= ["[]"] (at end "333/8 2 voice")))
    (testing "the counter voices slow, approach, and settle on the a and the e"
      (is (= [71 74] (mapv #(pitch-of (leaf 81/2 %)) [1 2])) "approach: b and d, an octave up")
      (is (= [69 76] (mapv #(pitch-of (leaf 327/8 %)) [1 2])) "final: a and e, an octave up")
      (is (re-find #"^\[A4/2\." (leaf 327/8 1)) "held two groups, a dotted half")
      (is (re-find #"^\[E5/2\." (leaf 327/8 2))))
    (testing "before that, melisma"
      (is (every? #(re-find #"/(8|4)" %) (map #(leaf (+ 315/8 (* 1/8 %)) 1) [0 1 2 3 4 5]))))))

(deftest the-stream-of-each-voice-is-finite
  (load!)
  (doseq [[v id] [[0 :cantus] [1 :voice1] [2 :voice2]]]
    (inputs/set-inputs! id [["v" v]])
    (let [f      (#'live/onset-source "voice" id ["onset" "v"])
          src    (compose/source f (live/tctx-node!))
          events (vec (take 2000 (ev/events (repo/registry) src)))
          notes  (filter #(= :note (:kind %)) events)]
      (is (< (count events) 2000) (str id " ends"))
      (is (every? #(<= (:onset %) 333/8) events) (str id " ends when the long a is over"))
      (is (every? #(< (:onset %) 333/8) notes) (str id " sounds nothing after that"))
      (when (= 0 v)
        (is (= 36 (count notes)) "the cantus firmus: two rounds of 18 notes")
        (is (= 315/8 (:onset (last notes))) "the last, the long a, begins at note 35")))))

(deftest the-cantus-firmus-is-legato-through-its-context
  (load!)
  (let [played (fn [art]
                 (let [r   (run (str "88 " art " voice-ctx"))
                       ctx (:context (v/node (peek (:ok r))))
                       _   (inputs/set-inputs! :cantus [["v" 0]])
                       f   (#'live/onset-source "voice" :cantus ["onset" "v"])
                       evs (take 4 (ev/events (repo/registry) (compose/source f (live/tctx-node!) ctx)))
                       n   (first (filter #(= :note (:kind %)) evs))]
                   (/ (:dur-played n) (:dur-secs n))))]
    (is (< 0.99 (played 1.0) 1.01) "articulation 1.0: the note sounds its whole length")
    (is (< 0.89 (played 0.9) 0.91) "0.9: a little short of it")
    (is (= ["^{ !Tempo:88 !articulation:1.0 }"] (mapv p/show (:ok (run "88 1.0 voice-ctx")))))))

(deftest a-voice-may-have-two-timbres-at-once
  (load!)
  (run "'[ '[60 61] 57 59 ] :timbres T!")
  (is (re-find #"\\instrument:60,61\\" (leaf 0 0)) "the cantus firmus: horn and brass section together")
  (is (re-find #"instrument:57\\" (leaf 315/8 1)) "the others as before, a single program")
  (inputs/set-inputs! :cantus [["v" 0]])
  (let [f    (#'live/onset-source "voice" :cantus ["onset" "v"])
        note (first (filter #(= :note (:kind %)) (take 4 (ev/events (repo/registry) (compose/source f (live/tctx-node!))))))]
    (is (= [60 61] (:program note)) "and the engine sounds the note on both programs")))

(deftest it-starts-three-voices
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (load!)
    (binding [engine/*engine* eng]
      (with-redefs [engine/receiver (atom :silent)]
        (let [r (run "start")]
          (is (nil? (:error r)) (:error r))
          (is (every? some? (map engine/voice-at [:cantus :voice1 :voice2])))
          (run "stop"))))))
