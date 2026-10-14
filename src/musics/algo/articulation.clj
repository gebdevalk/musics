(ns musics.algo.articulation
  "Every source of raw material delivered as articulations: a constructor per
   specialised algo (musics.algo.tree.half), named after its source --
   (articulation/<source>) is the registry's :articulation/<source>. Nothing written here."
  (:refer-clojure :only [])
  (:require [musics.algo.tree.lib]
            [musics.algo.tree.half :refer [defhalf]]))

(defhalf :articulation)
