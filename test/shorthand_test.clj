(ns ^:repl shorthand-test
  "Confirms reg-*/unreg-*/bld! are genuine, thin aliases for their
   verbose register-*/unregister-*/build! originals -- same effect,
   not a parallel/divergent implementation."
  (:require [clojure.test :refer [deftest is]]
            [musics :as m]
            [core.wall :as wall]))

(deftest reg-factory!-is-register-factory!
  (m/reset)
  (m/reg-factory! ::shorthand-test-factory (fn [name] (m/build-algo! name (fn [nodes _ _] nodes))))
  (is (some? (wall/factory ::shorthand-test-factory)))
  (m/unreg-factory! ::shorthand-test-factory)
  (is (nil? (wall/factory ::shorthand-test-factory))))

(deftest bld!-is-build!
  (m/reset)
  (m/reg-factory! ::shorthand-test-factory2
    (fn [name n] (m/build-algo! name (fn [nodes _ctx _voice] (map #(assoc % :n n) nodes)))))
  (m/bld! ::shorthand-built ::shorthand-test-factory2 7)
  (is (= [{:n 7}] ((wall/algo ::shorthand-built) [{}] [] nil))
      "bld! built the same result build! would have, under the same name"))

(deftest unreg-algo!-is-unregister-algo!
  (m/reset)
  (m/build-algo! ::shorthand-cooked (fn [nodes _ _] nodes))
  (is (some? (wall/algo ::shorthand-cooked)))
  (m/unreg-algo! ::shorthand-cooked)
  (is (nil? (wall/algo ::shorthand-cooked))))

(deftest reg-action!-and-unreg-action!-are-the-verbose-ones
  (m/reset)
  (let [fired (atom false)]
    (m/reg-action! ::shorthand-action (fn [] (reset! fired true)))
    (m/trigger! ::shorthand-action)
    (is @fired)
    (m/unreg-action! ::shorthand-action)))
