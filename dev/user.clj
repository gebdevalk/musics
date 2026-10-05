#_:clj-kondo/ignore
(ns user
  "REPL entry point -- exists purely so `lein repl` (:init-ns user, see
   project.clj) starts with every musics command unqualified: (parse ...),
   (ids), (play :verse), etc., instead of needing the m/ prefix.
   Only loaded in dev (see the :dev profile's :source-paths).

   musics.algo.random.core (the pure RNG engine -- seed!/with-seed/default-rng,
   plus the public rnd-*/step! pure fns) and musics.algo.random (everything a
   caller actually reaches for: the basic primitives rand-double/
   rand-int/choose/weighted-choose/shuffle/markov, continuous
   distributions, discrete/collection helpers, shaped distributions,
   walks/composite generators) are both required here, alongside the
   two chaotic-map namespaces (logistic/lorenz, deterministic given
   their own explicit state, not RNG-based) -- all aliased short, :as,
   not :refer :all, since musics.core's own thread exists precisely to
   reach these by qualified name (e.g. (thread rnd/deep-shuffle
   :verse)); :refer :all-ing them in as well would risk silently
   shadowing what musics.core already shadows from core (rand, shuffle,
   ...).

   musics.algo.logic.tree is aliased lt (find-algos/how/feeds/why-not/
   examples/surprise -- questions to the algo registry, in core.logic).

   musics.algo.tree is aliased t (tctx/run/describe/live!/...), and every
   musics.algo.tree.lib constructor (euclid, scale, transpose, stretch, notes,
   ...) is referred in -- musics.core deliberately has no scale/
   transpose of its own, so nothing shadows."
  (:require [musics.core :refer :all]
            [musics.algo.random.core :as core]
            [musics.algo.random :as rnd]
            [musics.algo.random.logistic :as logistic]
            [musics.algo.random.lorenz :as lorenz]
            [musics.algo.tree :as t]
            [musics.algo.logic.tree :as lt]
            [musics.algo.tree.lib :refer :all]))
