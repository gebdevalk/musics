(ns ^:repl musics.assist-test
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.test-support :refer [with-fresh-session]]
            [musics.assist :as a]
            [musics.registries :as reg]
            [musics.mu.store :as store]))

(use-fixtures :each (fn [f] (with-fresh-session (f))))

(deftest every-action-is-logged-by-its-var
  (doseq [{:keys [action var logs]} a/actions]
    (testing action
      (is (requiring-resolve var) (str var " doesn't exist"))
      (is (str/includes? (or (repl/source-fn var) "") (str "log! " (or logs action)))
          (str var " doesn't log " (or logs action))))))

(deftest plans-are-shortest-and-in-order
  (is (= [:live-word] (a/plan #{} :playing)) "the shortest way to hearing something")
  (is (= [:live-word] (a/plan #{} :live)))
  (is (= [] (a/plan #{:committed} :committed)))
  (is (nil? (a/plan #{} :nonsense))))

(deftest why-not-names-the-missing-needs
  (is (= [:committed] (a/why-not #{} :play)))
  (is (= [] (a/why-not #{} :live-word)))
  (is (= [] (a/why-not #{:committed} :play)))
  (is (= [:live-word] (a/providers :live))))

(deftest now-leaves-out-the-last-action-and-puts-what-follows-first
  (is (= [:parse :live-word :connect] (a/now #{} nil)))
  (is (= [:play :render :live-word :connect] (a/now #{:committed} :parse)))
  (is (= [:play-add :pause! :stop! :render :live-word] (a/now #{:committed :connected :playing} :play))))

(deftest history-comes-from-the-log
  (is (= #{} (a/facts)))
  (reg/log! :live-word)
  (is (= #{:played} (a/history)) "an action taken shows what it gave that no state shows")
  (is (= [:live-word] (a/plan :live))))

(deftest assist-at-the-repl
  (testing "a fresh session points at parse"
    (let [out (with-out-str (a/explain))]
      (is (str/includes? out "Holds: nothing yet"))
      (is (str/includes? out "(parse"))))
  (testing "a goal"
    (is (str/includes? (with-out-str (a/explain :live)) "5 8 'riff :bass ->")))
  (testing "an action that can't be taken yet"
    (is (str/includes? (with-out-str (a/explain :render)) "render needs :committed")))
  (testing "after parse"
    (with-out-str (store/read! "[verse: c4 d e]"))
    (is (= #{:committed} (a/facts)))
    (is (str/includes? (with-out-str (a/explain :committed)) "holds already"))))
