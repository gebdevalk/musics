#_:clj-kondo/ignore
(ns user
  "REPL entry point -- exists purely so `lein repl` (:init-ns user, see
   project.clj) starts with every musics command unqualified: (parse ...),
   (ids), (play :verse), etc., instead of needing the m/ prefix.
   Only loaded in dev (see the :dev profile's :source-paths).

   The algo.random.* namespaces are required here too, aliased short --
   :as, not :refer :all, since musics.clj's own thread exists precisely
   to reach these by qualified name (e.g. (thread chance/deep-shuffle
   :verse)); :refer :all-ing them in as well would risk shadowing each
   other's same-named fns across chance/rand/seed (rand, shuffle, ...)
   silently, on top of what musics.clj already shadows from core.

   input.forth is aliased too, :as (same reasoning -- it defines things
   like tokenize/push!/pop-val! that could plausibly collide with
   something else here) -- (forth/repl!) drops into a nested Forth
   REPL from this Clojure one, mirroring (mu!) for musics text; BYE (or
   Ctrl-D) inside it returns to this prompt."
  (:require [musics :refer :all]
            [algo.random.seed :as seed]
            [algo.random.chance :as chance]
            [algo.random.distributions :as dist]
            [algo.random.rand :as gen]
            [algo.random.logistic :as logistic]
            [algo.random.lorenz :as lorenz]
            [input.forth :as forth]))
