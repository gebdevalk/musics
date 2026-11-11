(ns musics.algo.common.factory
  "Factories: an algorithm that gives a value for an index, as a function of the
   index alone.")

(defn create-fn
  "A function of idx alone from f, which takes the index first and then the
   settings: (create-fn f a b) is (fn [idx] (f idx a b)). f is asked once, at
   index 0, so settings it refuses fail here, not at the first note that uses
   the function. f should do its work for any idx (a lazy seq under map, an
   endless range) and not build a list to find one value."
  [f & settings]
  (let [g (fn [idx] (apply f idx settings))]
    (g 0)
    g))
