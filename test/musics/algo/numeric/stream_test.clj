(ns musics.algo.numeric.stream-test
  "The samplers as lazy seqs: pure, the same arguments the same stream."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.numeric.chaos.henon :as henon]
            [musics.algo.numeric.chaos.logistic :as logistic]
            [musics.algo.numeric.chaos.lorenz :as lorenz]
            [musics.algo.numeric.markov :as markov]
            [musics.algo.numeric.walk :as walk]))

(deftest the-chaos-maps-are-lazy-seqs
  (let [lg (take 5 (logistic/logistic-seq 3.8 0.5))]
    (is (= (* 3.8 0.5 0.5) (first lg)) "the step after x0")
    (is (= lg (take 5 (logistic/logistic-seq 3.8 0.5))) "the same stream every time")
    (is (= (first lg) ((:value (logistic/logistic 3.8 0.5)))) "and the closure's own first step"))
  (let [hn (henon/henon-seq 1.4 0.3 0.1 0.1)]
    (is (= [1.09 0.03] (mapv #(/ (Math/round (* % 100.0)) 100.0) (first hn))) "1 - 1.4*0.01 + 0.1 = 1.086, 0.3*0.1")
    (is (= (take 3 hn) (take 3 (henon/henon-seq 1.4 0.3 0.1 0.1)))))
  (let [lz (lorenz/lorenz-seq 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0 0.01)
        cl ((:value (lorenz/lorenz 10.0 28.0 (/ 8.0 3.0) 1.0 1.0 1.0)))]
    (is (= cl (first lz)) "the same first step as the closure")
    (is (= 3 (count (first lz))))
    (is (= 2000 (count (take 2000 lz))) "endless")))

(deftest a-markov-chain-and-a-walk-are-seeded-streams
  (let [table {0 {4 2, 3 1}, 4 {0 1}, 3 {0 1}}
        ch    (take 12 (markov/chain-seq 7 table 0))]
    (is (= ch (take 12 (markov/chain-seq 7 table 0))) "the same seed, the same chain")
    (is (not= ch (take 12 (markov/chain-seq 8 table 0))) "another seed")
    (is (every? #{0 3 4} ch))
    (is (every? #{0} (take-nth 2 (rest ch))) "every state leads back to 0")))

(deftest a-walk-seq-moves-by-at-most-its-bound
  (let [w (take 200 (walk/walk-seq 7 60.0 2.0))]
    (is (= w (take 200 (walk/walk-seq 7 60.0 2.0))))
    (is (every? true? (map #(<= (abs (- %1 %2)) 2.0) w (cons 60.0 w))) "no step above the bound")
    (is (not= w (take 200 (walk/walk-seq 8 60.0 2.0)))))
  (let [w (take 500 (walk/walk-seq 3 60.0 5.0 :clip-lo 55.0 :clip-hi 65.0))]
    (is (every? #(<= 55.0 % 65.0) w) "clipped")))
