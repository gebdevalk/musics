(ns musics.algo.instrument
  "Every source of raw material delivered as instruments: a constructor
   per specialised algo (musics.algo.tree.half), named after its source --
   (instrument/<source>) is the registry's :instrument/<source>. Nothing
   written here."
  (:refer-clojure :only [])
  (:require [musics.algo.tree.lib]
            [musics.algo.tree.half :refer [defhalf]]))

(defhalf :instrument)
