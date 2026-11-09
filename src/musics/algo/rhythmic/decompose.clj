(ns musics.algo.rhythmic.decompose)

(defn bisect
  "Decompose `duration` into powers of two, largest first, then expand each term into `depth` finer pieces by repeated halving, the last piece taking the remainder, so the output always sums to `duration`.
     (bisect 3/4 2) ;=> [1/4 1/4 1/8 1/8]
     ;; 3/4 = 1/2 + 1/4; 1/2 -> 1/4 1/4, 1/4 -> 1/8 1/8"
  {:algo {:in [] :out :duration
          :params {:duration {:type :ratio :min 1/64 :max 16 :default 1 :doc "total length"}
                   :depth    {:type :int :min 1 :max 8 :default 2 :doc "pieces per power of two"}}}}
  [duration depth]
  (let [base (loop [remaining duration
                    power 1
                    acc []]
               (cond
                 (zero? remaining) acc
                 (>= remaining power) (recur (- remaining power) (/ power 2) (conj acc power))
                 :else (recur remaining (/ power 2) acc)))
        expand (fn [x depth]
                 (loop [remaining x
                        power (/ x 2)
                        n depth
                        acc []]
                   (if (= n 1)
                     (conj acc remaining) ;; last piece: takes the rest exactly
                     (recur (- remaining power) (/ power 2) (dec n) (conj acc power)))))]
    (vec (mapcat #(expand % depth) base))))

(defn split
  "Split `duration` into `depth` pieces: each takes a `ratio`-sized bite of what remains and the last takes the rest, so the pieces sum to `duration` for any depth and ratio. ratio 1/2 halves evenly (Zeno), 2/3 is long-short, 1/3 short-long. depth <= 1 returns [duration].
   duration may be a number or a leaf map with :duration, which gives that many copies of the leaf, each with its own :duration (as transient-ops/times does).
     (split 1 4 1/2) ;=> [1/2 1/4 1/8 1/8]
     (split 1 3 2/3) ;=> [2/3 2/9 1/9]
     (split {:pitches [60] :duration 1} 3 2/3)
       ;=> [{:pitches [60] :duration 2/3} {:pitches [60] :duration 2/9} {:pitches [60] :duration 1/9}]"
  {:algo {:in [] :out :duration
          :params {:duration {:type :ratio :min 1/64 :max 16 :default 1 :doc "total length"}
                   :depth    {:type :int :min 1 :max 32 :default 4 :doc "pieces"}
                   :ratio    {:type :ratio :min 1/16 :max 15/16 :default 1/2 :doc "bite taken each step"}}}}
  [duration depth ratio]
  (if (:duration duration)
    (mapv #(assoc duration :duration %) (split (:duration duration) depth ratio))
    (loop [remaining duration
           n depth
           acc []]
      (if (<= n 1)
        (conj acc remaining)
        (let [piece (* remaining ratio)]
          (recur (- remaining piece) (dec n) (conj acc piece)))))))