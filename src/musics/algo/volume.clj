(ns musics.algo.volume
  "Every source of raw material delivered as volumes: a constructor per
   specialised algo (musics.algo.tree.half), named after its source --
   (volume/<source>) is the registry's :volume/<source>. Nothing written here."
  (:refer-clojure :only [])
  (:require [musics.algo.tree.lib]
            [musics.algo.tree.half :refer [defhalf]]))

(defhalf :volume)
