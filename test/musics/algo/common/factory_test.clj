(ns ^:algo musics.algo.common.factory-test
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.common.factory :as f]))

(deftest create-fn-fixes-the-settings-and-leaves-the-index
  (is (= [10 13 16] (map (f/create-fn (fn [idx a b] (+ a (* b idx))) 10 3) (range 3))))
  (is (= 5 ((f/create-fn (fn [idx] (+ idx 5))) 0)) "no settings")
  (is (= [0 1 2] (take 3 (map (f/create-fn identity) (range)))) "lazy over an endless range"))

(deftest create-fn-asks-f-once-at-index-0
  (let [calls (atom [])]
    (f/create-fn (fn [idx & _settings] (swap! calls conj idx) :x) 1 2)
    (is (= [0] @calls)))
  (is (thrown? AssertionError (f/create-fn (fn [_idx d] {:pre [(pos? d)]} d) -1)) "settings it refuses fail at creation"))
