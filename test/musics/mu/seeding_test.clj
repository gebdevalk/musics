(ns ^:repl musics.mu.seeding-test
  "The seed of a random tctx key (musics.mu.seeding): a key that draws is a function of the seed, the
   key and the beat, so a seed repeats it whichever voice is read first."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.algo.numeric.rng :as rng]
            [musics.domain.context :as c]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.live :as live]
            [musics.mu.print :as p]
            [musics.mu.seeding :as seeding]
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
        (with-out-str (e/eval-input "\"numeric.random\" reqalgo \"numeric.rng\" reqalgo"))
        (t))
      (finally (reset! (:envelopes-atom live/tctx) {})
               (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- run [in] (swap! e/state assoc :stack []) (e/eval-input in))

(defn- top-of [in] (str/trim (p/show (peek (:ok (run in))))))

(def ^:private draw "'( 0 1000000 random/rand-int ) ")

(defn- at [chain k t] (c/ctx-value-chain chain k t))

(deftest a-seeded-key-repeats-at-a-beat-and-changes-with-the-seed
  (run "7 :seed T!")
  (run (str draw ":r T!"))
  (let [beats [0 1/4 1/2 3/4]
        vs    (mapv #(at [live/tctx] :r %) beats)]
    (is (= vs (mapv #(at [live/tctx] :r %) beats)) "the same beat, the same draw, however often it is asked")
    (is (apply distinct? vs) "another beat, another draw")
    (run "8 :seed T!")
    (is (not= vs (mapv #(at [live/tctx] :r %) beats)) "another seed")
    (run "7 :seed T!")
    (is (= vs (mapv #(at [live/tctx] :r %) beats)) "and the first one again")))

(deftest a-key-written-for-a-voice-draws-for-that-voice-and-a-shared-key-for-all
  (run "5 :seed T!")
  (run (str draw ":s T!"))
  (run (str draw ":bass/r T!"))
  (run (str draw ":lead/r T!"))
  (let [bass (live/tctx-chain :bass) lead (live/tctx-chain :lead)]
    (testing "written at the shared level, every voice draws the same at a beat"
      (is (= (map #(at bass :s %) [0 1/4 1/2]) (map #(at lead :s %) [0 1/4 1/2]))))
    (testing "written in a voice's scope, each voice has its own draw"
      (is (not= (map #(at bass :r %) [0 1/4 1/2]) (map #(at lead :r %) [0 1/4 1/2]))))
    (testing "what a voice draws does not depend on which voice is read first"
      (let [alone-bass (mapv #(at bass :r %) [0 1/4 1/2 3/4])
            alone-lead (mapv #(at lead :r %) [0 1/4 1/2 3/4])
            inter      (vec (for [t [0 1/4 1/2 3/4] chain [bass lead]] (at chain :r t)))]
        (is (= (interleave alone-bass alone-lead) inter))))))

(deftest a-voice-or-a-group-may-have-a-seed-of-its-own
  (run "5 :seed T!")
  (run (str draw ":s T!"))
  (run (str draw ":bass/r T!"))
  (run (str draw ":lead/r T!"))
  (let [bass (live/tctx-chain :bass) lead (live/tctx-chain :lead)
        shared  (mapv #(at bass :s %) [0 1/4])
        bass-r  (mapv #(at bass :r %) [0 1/4])
        lead-r  (mapv #(at lead :r %) [0 1/4])]
    (run "9 :bass/seed T!")
    (is (not= bass-r (mapv #(at bass :r %) [0 1/4])) "the bass's key draws from the bass's seed")
    (is (= lead-r (mapv #(at lead :r %) [0 1/4])) "the lead's is as it was")
    (is (= shared (mapv #(at bass :s %) [0 1/4])) "a key written at the shared level takes the shared seed, for the bass too"))
  (testing "which seed is in force for a key: its voice's, its group's, the shared one, else the session's"
    (run "3 :drums/seed T!")
    (is (= 9 (seeding/entry :bass/r)))
    (is (= 3 (seeding/entry (keyword "drums.kick" "r"))) "the group's")
    (run "4 :drums.kick/seed T!")
    (is (= 4 (seeding/entry (keyword "drums.kick" "r"))) "the voice's own, before the group's")
    (is (= 3 (seeding/entry (keyword "drums.snare" "r"))))
    (is (= 5 (seeding/entry :r)) "a shared key: the shared seed")
    (is (= 5 (seeding/entry :other/r)) "a voice with none")))

(deftest none-draws-from-the-clock-and-nothing-repeats-it
  (run (str draw ":r T!"))
  (run ":none :seed T!")
  (let [vs (repeatedly 5 #(at [live/tctx] :r 0))]
    (is (> (count (distinct vs)) 1) "the same beat, different draws"))
  (run "7 :seed T!")
  (run ":none :bass/seed T!")
  (run (str draw ":bass/r T!"))
  (let [bass (live/tctx-chain :bass)]
    (is (> (count (distinct (repeatedly 5 #(at bass :r 0)))) 1) "none for one voice while the piece is seeded")))

(deftest with-no-seed-set-the-session-seed-is-used
  (run (str draw ":r T!"))
  (run "7 rng/seed")
  (let [vs (mapv #(at [live/tctx] :r %) [0 1/4 1/2])]
    (is (= "7" (top-of ":seed T@")) "the seed in force is shown, though none is written")
    (is (= vs (mapv #(at [live/tctx] :r %) [0 1/4 1/2])) "repeats within the session")
    (run "8 rng/seed")
    (is (= "8" (top-of ":seed T@")))
    (is (not= vs (mapv #(at [live/tctx] :r %) [0 1/4 1/2])) "N rng/seed changes it")
    (run "7 rng/seed")
    (is (= vs (mapv #(at [live/tctx] :r %) [0 1/4 1/2])) "and 7 again gives the first draws"))
  (testing "a seed written is the seed in force, :seed T_ gives the session's back"
    (run "42 :seed T!")
    (is (= "42" (top-of ":seed T@")))
    (run ":seed T_")
    (is (= "7" (top-of ":seed T@")))))

(deftest a-seed-is-a-whole-number-or-none
  (is (re-find #"is a seed: a whole number, or :none -- got 1\.5" (:error (run "1.5 :seed T!"))))
  (is (re-find #"is a seed" (:error (run "'( 1 ) :seed T!"))) "not a computed key")
  (is (re-find #"is a seed" (:error (run "\"x\" :bass/seed T!"))))
  (is (nil? (:error (run ":none :bass/seed T!"))))
  (is (re-find #"is a seed" (:error (run "@{ :seed 1.5 } :bass T!"))) "also in a block")
  (is (nil? (:error (run "@{ :seed 4 :r 1 } :bass T!"))))
  (is (= 4 (at [live/tctx] :bass/seed 0))))

(deftest the-random-words-outside-a-key-draw-from-the-shared-stream-as-before
  (is (nil? rng/*rng*))
  (run "7 rng/seed")
  (let [a (top-of "0 1000000 random/rand-int")]
    (run "7 rng/seed")
    (is (= a (top-of "0 1000000 random/rand-int")) "N rng/seed repeats them")))
