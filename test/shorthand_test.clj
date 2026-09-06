(ns ^:repl shorthand-test
  "Confirms reg-*/unreg-*/conf-* are genuine, thin aliases for their
   verbose register-*/unregister-*/configure-* originals -- same effect,
   not a parallel/divergent implementation."
  (:require [clojure.test :refer [deftest is]]
            [musics :as m]
            [core.wall :as wall]))

(deftest reg-algo!-is-register-algo!
  (m/reset)
  (m/reg-algo! ::shorthand-test-algo (fn [nodes _ _] nodes))
  (is (some? (wall/algo-fn ::shorthand-test-algo)))
  (m/unreg-algo! ::shorthand-test-algo)
  (is (nil? (wall/algo-fn ::shorthand-test-algo))))

(deftest conf-algo!-is-configure-algo!
  (m/reset)
  (m/reg-algo! ::shorthand-test-factory (fn [n] (fn [nodes _ctx _voice] (map #(assoc % :n n) nodes))) nil :factory)
  (m/conf-algo! ::shorthand-test-factory 7)
  (is (= [{:n 7}] ((wall/algo-fn ::shorthand-test-factory) [{}] [] nil))
      "conf-algo! resolved the factory with 7 and re-registered it under the same name"))

(deftest reg-preset!-and-conf-preset!-are-the-verbose-ones
  (m/reset)
  (m/reg-algo! ::shorthand-test-factory2 (fn [a b] (fn [nodes _ _] (map #(assoc % :stamp [a b]) nodes))) nil :factory)
  (m/conf-preset! ::shorthand-bright ::shorthand-test-factory2 1 2)
  (is (some? (wall/preset-fn ::shorthand-bright)))
  (m/unreg-preset! ::shorthand-bright)
  (is (nil? (wall/preset-fn ::shorthand-bright))))

(deftest reg-action!-and-unreg-action!-are-the-verbose-ones
  (m/reset)
  (let [fired (atom false)]
    (m/reg-action! ::shorthand-action (fn [] (reset! fired true)))
    (m/trigger! ::shorthand-action)
    (is @fired)
    (m/unreg-action! ::shorthand-action)))
