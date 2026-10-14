(ns musics.algo.pitch
  "Every source of raw material delivered as pitches: a constructor per
   specialised algo (musics.algo.tree.half), named after its source --
   (pitch/<source>) is the registry's :pitch/<source>. Nothing written here."
  (:refer-clojure :only [])
  (:require [musics.algo.tree.lib]
            [musics.algo.tree.half :refer [defhalf]]))

(defhalf :pitch)
