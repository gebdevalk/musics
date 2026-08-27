#_:clj-kondo/ignore
(ns user
  "REPL entry point -- exists purely so `lein repl` (:init-ns user, see
   project.clj) starts with every musics command unqualified: (parse ...),
   (ids), (play :verse), etc., instead of needing the m/ prefix.
   Only loaded in dev (see the :dev profile's :source-paths).

   algo.random.core (the RNG engine -- seed!/with-seed/default-rng plus
   the basic primitives rand-double/rand-int/choose/weighted-choose/
   shuffle) and algo.rnd (everything built on top of those: continuous
   distributions, discrete/collection helpers, shaped distributions,
   walks/composite generators) are both required here, alongside the
   two chaotic-map namespaces (logistic/lorenz, deterministic given
   their own explicit state, not RNG-based) -- all aliased short, :as,
   not :refer :all, since musics.clj's own thread exists precisely to
   reach these by qualified name (e.g. (thread rnd/deep-shuffle
   :verse)); :refer :all-ing them in as well would risk silently
   shadowing what musics.clj already shadows from core (rand, shuffle,
   ...).

   input.forth is aliased too, :as (same reasoning -- it defines things
   like tokenize/push!/pop-val! that could plausibly collide with
   something else here) -- (forth/repl!) drops into a nested Forth
   REPL from this Clojure one, mirroring (mu!) for musics text; BYE (or
   Ctrl-D) inside it returns to this prompt."
  (:require [musics :refer :all]
            [algo.random.core :as core]
            [algo.rnd :as rnd]
            [algo.random.logistic :as logistic]
            [algo.random.lorenz :as lorenz]
            [input.forth :as forth]))
