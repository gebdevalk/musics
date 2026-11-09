;; Rotate a vector by i positions.

(ns musics.algo.common.rotate)

(defn rotate
  "Rotate pattern left by i positions (negative/oversized i wraps via
   mod, same as a cyclic pattern always would).

   (rotate [1 2 3 4] 1)  ;=> [2 3 4 1]
   (rotate [1 2 3 4] -1) ;=> [4 1 2 3]"
  [pattern i]
  (let [n (count pattern)
        i (mod i n)]
    (vec (concat (drop i pattern) (take i pattern)))))

(comment
  (rotate [1 2 3 4] 1)
  (rotate [1 2 3 4] -1)
  )
