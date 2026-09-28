(ns ^:engine wall-test
  "core.wall: the name -> wall fn registry the engine reads per node."
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [musics.core :as m]
            [core.repo :as repo]
            [core.wall :as wall]
            [core.async-engine :as engine]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]))

(defn- stamp [a b] (fn [nodes _ctx _voice] (map #(assoc % :stamp [a b]) nodes)))

(deftest a-registered-algo-resolves-through-play-and-doesnt-fail-validation
  (with-fresh-registries
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
    (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context)
                               :children [(d/leaf :n1 (c/context) 1/4 [60])]})
    (wall/build-algo! ::bright (stamp 1 2))
    (let [eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        ;; validate-algo-name! runs BEFORE play's flush -- an unregistered
        ;; name would throw here instead of returning normally.
        (let [id (engine/play :verse :algo ::bright)]
          (is (= (wall/algo ::bright) (wall/algo (:algo (engine/voice-at eng [id])))))
          (engine/stop! eng))))))

(deftest re-registering-a-name-hot-swaps-it
  (with-fresh-registries
    (wall/build-algo! ::a (stamp 1 1))
    (let [before (wall/algo ::a)]
      (wall/build-algo! ::a (stamp 2 2))
      (is (not= before (wall/algo ::a)))
      (is (= [{:stamp [2 2]}] ((wall/algo ::a) [{}] [] nil))))))

(deftest unregister-algo!-forgets-a-name
  (with-fresh-registries
    (wall/build-algo! ::bright (stamp 1 2))
    (wall/unregister-algo! ::bright)
    (is (nil? (wall/algo ::bright)))))

(deftest registered-with-a-name-returns-just-that-entrys-full-map
  (with-fresh-registries
    (wall/build-algo! ::a identity "a's own doc")
    (is (= {:fn identity :doc "a's own doc"} (wall/registered ::a)))
    (is (nil? (wall/registered ::nope)) "unregistered -- nil, not an error")))

(deftest a-bare-Data-reference-passed-to-play-plays-silently-not-crash
  ;; a :DATA container has no Leaf/Rest/Drum/Bar children play-node
  ;; recognizes, so (play id) on one must neither throw nor hang.
  (with-fresh-registries
    (m/reset)
    (let [{:keys [ids]} (m/parse "'[ /4 /8 /8 /4 ]")
          eng (engine/engine nil (repo/registry) :ROOT)]
      (binding [engine/*engine* eng]
        (let [track (engine/play (first ids))]
          (Thread/sleep 100)
          (is (nil? (get @(:voices eng) [track]))
              "the voice already finished -- zero recognizable content, zero
               duration, nothing left running"))))))
