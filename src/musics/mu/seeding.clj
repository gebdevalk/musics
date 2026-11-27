(ns musics.mu.seeding
  "The seed of a random tctx key. A computed key that draws (random/rand-int in a quotation given to T!)
   draws from an RNG of its own, a function of the seed, the key and the beat it is computed at, so
   what it draws does not depend on which voice is read first, and a seed repeats it.

   The seed is a tctx key: :seed for the piece, :bass/seed for the bass, :drums/seed for a group, read
   from the voice's own through the groups to the shared one, for a key written in that voice's scope
   (a key written at the shared level takes :seed, so every voice draws the same at a beat); a number,
   or :none for a draw from the clock that nothing repeats. With none set the session's seed is used:
   the shared RNG's, drawn from the clock when mu starts, which `N rng/seed` sets."
  (:require [musics.algo.numeric.counter :as counter]
            [musics.algo.numeric.rng :as rng]
            [musics.domain.context :as c]
            [musics.registries :as reg]))

(defn seed? [x] (or (integer? x) (= :none x)))

(defn session-seed
  "The seed of the session: the shared RNG's."
  []
  (:seed @rng/default-rng))

(defn entry
  "The seed the tctx holds for key k (a keyword, :bass/density or :density), the nearest: the voice's
   own :bass/seed, then its groups', then the shared :seed. A number or :none, or nil when none is set."
  [k]
  (let [m     @(:envelopes-atom reg/tctx)
        cands (concat (map #(keyword % "seed") (when (namespace k) (c/voice-paths (namespace k)))) [:seed])]
    (some (fn [cand] (let [v (get m cand)] (when (seed? v) v))) cands)))

(defn effective
  "The seed in force for key k: what the tctx holds, else the session's. A number, or :none."
  [k]
  (or (entry k) (session-seed)))

(defn rng-for
  "A fresh RNG atom for computing key k at time, or nil for a key that is not seeded (a seed itself).
   The same seed, key and time give the same draws, in the order they are made; :none a stream
   from the clock."
  [k time]
  (when (and (keyword? k) (not= "seed" (name k)))
    (let [s (effective k)]
      (doto (atom nil)
        (rng/seed! (if (= :none s)
                     (System/nanoTime)
                     (counter/subseed (counter/subseed s (hash k)) (hash time))))))))
