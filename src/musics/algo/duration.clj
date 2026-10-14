(ns musics.algo.duration
  "Every source of raw material delivered as durations: a constructor per
   specialised algo (musics.algo.tree.half), named after its source --
   (duration/<source>) is the registry's :duration/<source>. Nothing written here."
  (:refer-clojure :only [])
  (:require [musics.algo.tree.lib]
            [musics.algo.tree.half :refer [defhalf]]))

(defhalf :duration)
