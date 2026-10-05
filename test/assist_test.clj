(ns ^:repl assist-test
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [test-support :refer [with-fresh-session]]
            [musics.assist :as a]
            [musics.registries :as reg]
            [musics.core :as m]))

(use-fixtures :each (fn [f] (with-fresh-session (f))))

(deftest every-action-is-logged-by-its-var
  (doseq [{:keys [action var logs]} a/actions]
    (testing action
      (is (requiring-resolve var) (str var " doesn't exist"))
      (is (str/includes? (or (repl/source-fn var) "") (str "log! " (or logs action)))
          (str var " doesn't log " (or logs action))))))

(deftest plans-are-shortest-and-in-order
  (is (= [:parse :play] (a/plan #{} :playing)))
  (is (= [:build-tree :live!] (a/plan #{} :live)))
  (is (= [:tctx :live!] (a/plan #{:tree} :live)) "a tree made already isn't made again")
  (is (= [:build-tree :live! :play-algo] (a/plan #{:committed} :play-algo)) "an action comes last")
  (is (= [] (a/plan #{:committed} :committed)))
  (is (nil? (a/plan #{} :nonsense))))

(deftest why-not-names-the-missing-needs
  (is (= [:committed] (a/why-not #{} :play)))
  (is (= [:tctx] (a/why-not #{:tree} :live!)))
  (is (= [] (a/why-not #{:committed} :play)))
  (is (= [:build-tree :tctx :gui] (a/providers :tctx))))

(deftest now-leaves-out-the-last-action-and-puts-what-follows-first
  (is (= [:parse :build-tree :connect] (a/now #{} nil)))
  (is (= [:play :render :build-tree :connect] (a/now #{:committed} :parse)))
  (is (= [:play-add :pause! :stop! :render :build-tree] (a/now #{:committed :connected :playing} :play))))

(deftest history-comes-from-the-log
  (is (= #{} (a/facts)))
  (reg/log! :tctx)
  (is (= #{:tree :tctx} (a/history)) "an action taken shows its needs held")
  (is (= [:live!] (a/plan :live))))

(deftest assist-at-the-repl
  (testing "a fresh session points at parse"
    (let [out (with-out-str (m/assist))]
      (is (str/includes? out "Holds: nothing yet"))
      (is (str/includes? out "(parse"))))
  (testing "a goal"
    (is (str/includes? (with-out-str (m/assist :live)) "(t/live!")))
  (testing "an action that can't be taken yet"
    (is (str/includes? (with-out-str (m/assist :render)) "render needs :committed")))
  (testing "after parse"
    (with-out-str (m/parse "[verse: c4 d e]"))
    (is (= #{:committed} (a/facts)))
    (is (str/includes? (with-out-str (m/assist :committed)) "holds already"))))
