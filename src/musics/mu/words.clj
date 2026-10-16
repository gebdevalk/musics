(ns musics.mu.words
  "mu's words, callable from Clojure: (retrograde x), (R x), (times x 3),
   (mu \"[c d e]\"). Interned from mu's registry by musics.mu.bridge;
   clojure.core is not referred, as many names are mu's (take, first,
   map, filter …)."
  (:refer-clojure :only [])
  (:require [musics.mu.bridge :as bridge]))

(bridge/install! clojure.core/*ns*)
