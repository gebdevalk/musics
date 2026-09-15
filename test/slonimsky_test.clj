(ns ^:algo slonimsky-test
  (:require [clojure.test :refer [deftest is]]
            [algo.melodic.slonimsky :as sl]
            [core.wall :as wall]))

(deftest infrapolate-inserts-before-each-tone
  (is (= [9 1 9 2 9 3] (sl/infrapolate [1 2 3] [9]))))

(deftest ultrapolate-inserts-after-every-tone-including-the-last
  (is (= [1 9 2 9 3 9] (sl/ultrapolate [1 2 3] [9]))))

(deftest interpolate-inserts-between-consecutive-tones-only
  (is (= [1 9 2 9 3] (sl/interpolate [1 2 3] [9]))
      "no trailing insertion after the last tone -- nothing left to interpolate toward"))

(deftest interpolate-passes-through-fewer-than-2-tones-unchanged
  (is (= [1] (sl/interpolate [1] [9])))
  (is (= [] (sl/interpolate [] [9]))))

(deftest mixed-polations-combines-all-three-layers-in-slonimskys-own-order
  ;; per tone: infra, principal, ultra (unless last & not ultra-after-last),
  ;; inter (unless last)
  (is (= [8 1 7 9 8 2 7 9 8 3]
         (sl/mixed-polations [1 2 3] [8] [9] [7]))))

(deftest mixed-polations-ultra-after-last-forces-ultra-on-the-final-tone-too
  (is (= [1 5 2 5] (sl/mixed-polations [1 2] nil nil [5] true))))

(deftest mixed-polations-with-no-insertions-is-the-identity
  (is (= [1 2 3] (sl/mixed-polations [1 2 3]))))

(deftest mixed-polations-any-layer-can-be-independently-disabled
  (is (= [1 9 2 9 3] (sl/mixed-polations [1 2 3] nil [9] nil))
      "only inter given -- same as interpolate")
  (is (= [9 1 9 2 9 3] (sl/mixed-polations [1 2 3] [9] nil nil))
      "only infra given -- same as infrapolate"))

(deftest mixed-polations-algo-builds-and-stores-a-wall-fn-shaped-closure
  ;; (nodes ctx-chain voice) -> nodes', ctx-chain/voice ignored -- same
  ;; contract weighted-shuffle-algo's own returned fn has. The factory
  ;; itself returns name (core.wall/build-algo!'s own contract), and
  ;; stores the wall-fn under it -- retrieve via wall/algo to call it.
  (sl/mixed-polations-algo ::combined {:infra [8] :inter [9] :ultra [7]})
  (let [wall-fn (wall/algo ::combined)]
    (is (= [8 1 7 9 8 2 7 9 8 3]
           (wall-fn [1 2 3] :whatever-ctx-chain :whatever-voice)))))

(deftest mixed-polations-algo-with-no-insertions-is-the-identity
  (sl/mixed-polations-algo ::identity-case {})
  (let [wall-fn (wall/algo ::identity-case)]
    (is (= [1 2 3] (wall-fn [1 2 3] nil nil)))))

(deftest mixed-polations-algo-ultra-after-last-threads-through
  (sl/mixed-polations-algo ::ultra-case {:ultra [5] :ultra-after-last? true})
  (let [wall-fn (wall/algo ::ultra-case)]
    (is (= [1 5 2 5] (wall-fn [1 2] nil nil)))))
