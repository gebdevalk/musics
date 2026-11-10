(ns ^:algo musics.algo.numeric.trig-test
  (:require [clojure.test :refer [deftest is testing]]
            [musics.algo.numeric.trig :as trig]))

(deftest cosr-matches-known-values
  (is (= [12.0 10.0 8.0 10.0 12.0]
         (mapv #(trig/cosr % 2 10 8) [0 2 4 6 8]))))

(deftest sinr-matches-known-values
  (is (= [10.0 12.0 10.0 8.0 10.0]
         (mapv #(trig/sinr % 2 10 8) [0 2 4 6 8]))))

(deftest tanr-is-centered-at-zero-crossings
  ;; idx 0/4/8 land on multiples of period/2, where tan itself is 0,
  ;; so tanr should return exactly base regardless of amp
  (is (= [10.0 10.0 10.0]
         (mapv #(trig/tanr % 2 10 8) [0 4 8]))))

(deftest cosr-completes-one-cycle-per-period
  (is (= (trig/cosr 0 3 5 4) (trig/cosr 4 3 5 4))))

(deftest trianglr-matches-known-values
  (is (= [10.0 12.0 10.0 8.0 10.0]
         (mapv #(trig/trianglr % 2 10 8) [0 2 4 6 8]))))

(deftest trianglr-completes-one-cycle-per-period
  ;; arcsin(sin(...))'s own floating-point error can be larger than
  ;; cos/sin's alone -- confirmed live (period=4/amp=3 landed 0 and 4
  ;; a full 1e-15 apart, not exactly equal) -- so this checks within a
  ;; tolerance, not exact equality, unlike cosr's own version above
  (is (< (Math/abs (- (trig/trianglr 0 3 5 4) (trig/trianglr 4 3 5 4))) 1e-9)))

(deftest squarr-plateaus-at-amp-extremes-off-the-zero-crossings
  (is (= [12.0 12.0 8.0 8.0]
         (mapv #(trig/squarr % 2 10 8) [1 3 5 7]))))

(deftest squarr-at-idx-zero-lands-exactly-on-base
  ;; sign(sin(0.0)) is exactly 0.0, unlike the OTHER zero-crossings
  ;; (idx=4/8), which are at the mercy of floating-point noise instead
  (is (= 10.0 (trig/squarr 0 2 10 8))))

(deftest sawr-ramps-linearly-away-from-base-at-idx-zero
  (is (= [10.0 11.0 9.0 10.0]
         (mapv #(trig/sawr % 2 10 8) [0 2 6 8]))))

(deftest sawr-completes-one-cycle-per-period
  (is (= (trig/sawr 1 3 5 4) (trig/sawr 5 3 5 4))))

(deftest waves-between-lo-and-hi
  (is (= [75.0 90.0 75.0 60.0] (mapv #(trig/sine % 60 90 8) [0 2 4 6])))
  (is (= [90.0 75.0 60.0 75.0] (mapv #(trig/cosine % 60 90 8) [0 2 4 6])))
  (is (< (Math/abs (- 90.0 (trig/triangle 2 60 90 8))) 1e-9))
  (is (< (Math/abs (- 60.0 (trig/triangle 6 60 90 8))) 1e-9))
  (is (= [90.0 90.0 60.0 60.0] (mapv #(trig/square % 60 90 8) [1 3 5 7])))
  (is (= [75.0 82.5 67.5] (mapv #(trig/saw % 60 90 8) [0 2 6]))))

(deftest the-shorter-calls-leave-out-what-is-one-or-zero
  (is (= 1.0 (trig/sine 0.25)) "idx alone: a period of one, an amplitude of one")
  (is (= 1.0 (trig/sine 1 4)) "a period")
  (is (= 3.0 (trig/sine 2 3 8)) "an amplitude, then the period")
  (is (= -3.0 (trig/sine 6 3 8)) "between -amp and amp")
  (is (= 90.0 (trig/sine 2 60 90 8)) "lo, hi, period")
  (is (= (trig/sine 2 60 90 0 8) (trig/sine 2 60 90 8)) "a phase of 0 left out")
  (is (= 1.0 (trig/cosine 0)))
  (is (= 1.0 (trig/square 0.25)))
  (is (= 0.5 (trig/saw 0.25)) "a ramp from -1 to 1: a quarter of the way up from the middle"))

(deftest phase-moves-the-wave-by-a-part-of-a-cycle
  (is (= 90.0 (trig/sine 0 60 90 0.25 8)) "a quarter of a cycle ahead: the sine begins at hi")
  (is (= 60.0 (trig/cosine 0 60 90 0.5 8)))
  (is (< (Math/abs (- (trig/sine 3 60 90 0.25 8) (trig/sine 5 60 90 8))) 1e-9) "phase 0.25 of 8 is 2 idxs"))

(deftest waves-stay-between-lo-and-hi
  (doseq [f [trig/sine trig/cosine trig/triangle] i (range 0 16) ph [0 0.3 0.7]]
    (let [v (f i 60 90 ph 8)] (is (<= 59.999 v 90.001)))))

(deftest wave-fns-take-the-settings-once
  (is (= [75.0 90.0 75.0 60.0] (mapv (trig/sine-fn 60 90 0 8) [0 2 4 6])))
  (is (= 1.0 ((trig/sine-fn) 0.25)))
  (is (= 3.0 ((trig/sine-fn 3 8) 2)))
  (is (= 90.0 ((trig/sine-fn 60 90 8) 2)))
  (doseq [[f g] [[trig/sine trig/sine-fn] [trig/cosine trig/cosine-fn] [trig/triangle trig/triangle-fn]
                 [trig/square trig/square-fn] [trig/saw trig/saw-fn]]]
    (is (= (mapv #(f % 60 90 0.25 8) (range 9)) (mapv (g 60 90 0.25 8) (range 9))))
    (is (= (mapv #(f % 2 8) (range 9)) (mapv (g 2 8) (range 9))))
    (is (= (f 0.25) ((g) 0.25))))
  (is (thrown? Exception (apply trig/sine-fn [1 2 3 4 5]))))

(deftest a-spiral-shifts-each-sine-by-a-part-of-a-cycle
  (testing "the first sine is the sine itself"
    (is (= (mapv #(trig/sine % 60 90 8) (range 8)) (mapv #(trig/spiral 0 % 4 60 90 8) (range 8)))))
  (testing "the nth is nth/sines of a cycle ahead: 1 of 4 on a period of 8 is 2 idxs"
    (is (= (trig/sine 4 60 90 8) (trig/spiral 1 2 4 60 90 8)))
    (is (= (trig/sine 4 60 90 8) (trig/spiral 2 0 4 60 90 8)  (trig/spiral 2 8 4 60 90 8))))
  (testing "nth wraps round"
    (is (= (trig/spiral 1 3 4) (trig/spiral 5 3 4))))
  (testing "the sines at one idx are spread over the range"
    (is (= [0 1 0 -1] (mapv #(Math/round (trig/spiral % 0 4)) (range 4)))
        "four sines at idx 0, a quarter cycle apart"))
  (testing "the fn takes the settings once"
    (let [f (trig/spiral-fn 4 60 90 8)]
      (is (= [90.0 75.0 90.0] [(f 0 2) (f 0 0) (f 1 0)]))
      (is (= (trig/spiral 3 5 4 60 90 8) (f 3 5))))))

