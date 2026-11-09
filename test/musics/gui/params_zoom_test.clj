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
