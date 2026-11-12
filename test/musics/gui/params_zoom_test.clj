(ns musics.gui.params-zoom-test
  (:require [clojure.test :refer [deftest is testing]]
            [musics.gui.components :as ui]))

(deftest zoom-halves-around-the-value-and-wraps-out
  (let [spec {:type :double :min 0.0 :max 100.0}]
    (testing "each step halves the smaller distance to a bound, centred on the value"
      (is (= {:min 25.0 :max 75.0} (ui/zoom-bounds spec nil 50.0)))
      (is (= {:min 37.5 :max 62.5} (ui/zoom-bounds spec {:min 25.0 :max 75.0} 50.0))))
    (testing "near a bound the nearer side sets the width"
      (is (= {:min 5.0 :max 15.0} (ui/zoom-bounds spec nil 10.0))))
    (testing "too narrow: back to the whole range"
      (is (= {:min 0.0 :max 100.0} (ui/zoom-bounds spec {:min 49.5 :max 50.5} 50.0))))
    (testing "a value outside the zoomed range: the whole range"
      (is (= {:min 0.0 :max 100.0} (ui/zoom-bounds spec {:min 40.0 :max 60.0} 90.0)))))
  (testing "an integer parameter keeps whole bounds and wraps under four steps"
    (let [spec {:type :int :min 0 :max 32}]
      (is (= {:min 8.0 :max 24.0} (ui/zoom-bounds spec nil 16)))
      (is (= {:min 0.0 :max 32.0} (ui/zoom-bounds spec {:min 15.0 :max 18.0} 16))))))

(deftest zoom-out-widens-symmetrically-around-the-value-and-includes-zero
  (let [none {:type :double}]
    (testing "no range yet: value - |value| .. value + |value|"
      (is (= {:min 0.0 :max 16.0} (ui/zoom-out-bounds none nil 8)))
      (is (= {:min 0.0 :max 1.0} (ui/zoom-out-bounds none nil 0.5)))
      (is (= {:min -1.0 :max 0.0} (ui/zoom-out-bounds none nil -0.5)))
      (is (= {:min -1.0 :max 1.0} (ui/zoom-out-bounds none nil 0))))
    (testing "every further step doubles the distance from the value, panning can go negative"
      (is (= {:min -8.0 :max 24.0} (ui/zoom-out-bounds none {:min 0.0 :max 16.0} 8)))
      (is (= {:min -24.0 :max 40.0} (ui/zoom-out-bounds none {:min -8.0 :max 24.0} 8))))
    (testing "an integer keeps whole bounds"
      (is (= {:min 0.0 :max 15.0} (ui/zoom-out-bounds {:type :int} nil 7.5)))))
  (testing "with a range it widens the same way but never past it, and stays on the whole range"
    (let [spec {:type :double :min 0.0 :max 100.0}]
      (is (= {:min 0.0 :max 100.0} (ui/zoom-out-bounds spec nil 50.0)) "already the whole range")
      (is (= {:min 25.0 :max 75.0} (ui/zoom-out-bounds spec {:min 37.5 :max 62.5} 50.0)) "doubled")
      (is (= {:min 0.0 :max 100.0} (ui/zoom-out-bounds spec {:min 25.0 :max 75.0} 50.0)) "the whole range once it would pass it")
      (is (= {:min 0.0 :max 20.0} (ui/zoom-out-bounds spec {:min 5.0 :max 15.0} 10.0)) "doubled, and 10 - 10 is the bound")))
  (testing "nothing to centre on"
    (is (nil? (ui/zoom-out-bounds {:type :double} nil nil)))
    (is (= {:min 0.0 :max 10.0} (ui/zoom-out-bounds {:type :double :min 0.0 :max 10.0} {:min 2.0 :max 4.0} nil)))))
