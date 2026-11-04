(ns musics.algo.numeric.lsystem
  "L-system rewriting, shared by the algos that map its characters to values
   (lsys-rhythm to pulses, lsys-melody to pitches)."
  (:require [clojure.string :as str]))

(defn expand
  "axiom rewritten `iterations` times: each character is replaced by the string
   rules gives for it (a map of one-character strings), or stays when it has no rule.
     (expand \"A\" {\"A\" \"AB\" \"B\" \"A\"} 4) ;=> \"ABAABABA\""
  [axiom rules iterations]
  (nth (iterate (fn [s] (str/join (map #(get rules (str %) (str %)) s))) axiom)
       iterations))
