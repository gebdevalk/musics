(ns ^:repl adviser-musics-test
  "Confirms musics.clj's own thin wrappers (parse/commit!/play/stop!/
   assign-algo!/register-algo!/configure-preset!/...) really do append to
   core.adviser's activity log -- not just core.adviser's own lower-level
   API, which adviser-test already covers directly."
  (:require [clojure.test :refer [deftest is]]
            [musics :as m]
            [core.adviser :as adviser]
            [core.repo :as repo]
            [core.async-engine :as engine]
            [core.domain.context :as c]))

(deftest parse-and-commit!-log-activity
  (m/reset)
  (let [{:keys [sid ids]} (m/parse "[verse: c4 d4 e4]")]
    (m/commit! sid)
    (let [actions (mapv :action (adviser/recent-activity))]
      (is (= [:parse :commit!] actions)))))

(deftest uh?-surfaces-a-real-suggestion-through-the-musics-wrapper
  (m/reset)
  (let [suggestions (m/uh?)]
    (is (re-find #"Nothing committed yet" (first suggestions)))))

(deftest advice-with-no-arg-behaves-like-uh?
  (m/reset)
  (is (= (m/uh?) (m/advice))))

(deftest advice-with-an-intent-biases-without-storing-anything
  (m/reset)
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
  (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
  ;; *engine* is a global, non-rebound var at the musics.clj level (by
  ;; design -- production connect!/play need it to persist across
  ;; unrelated calls); give THIS test its own fresh one so a prior
  ;; test's own leftover :algo-assignments in this same file can't make
  ;; algo-registered-but-nothing-assigned? false before this even runs.
  (binding [engine/*engine* (engine/engine nil repo/play-tx :ROOT)]
    (m/register-algo! ::advice-test-algo (fn [nodes _ _] nodes))
    (let [suggestions (m/advice :configuring)]
      (is (re-find #"Algorithm\(s\) registered" (first suggestions))
          "biased AS IF :configuring were the current intent"))))

(deftest advice-rejects-an-unrecognized-intent
  (m/reset)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a recognized intent"
        (m/advice :composting))))

(deftest wipe-adviser!-works-through-musics
  (m/reset)
  (m/parse "[verse: c4]")
  (m/wipe-adviser!)
  (is (empty? (adviser/recent-activity))))

(deftest register-algo!-and-assign-algo!-log-activity
  (m/reset)
  (let [{:keys [sid ids]} (m/parse "[verse: c4 d4]")]
    (m/commit! sid)
    (m/play-latest!)
    (m/register-algo! ::adviser-musics-test-algo (fn [nodes _ _] nodes))
    (let [id (m/play :verse)]
      (m/assign-algo! id ::adviser-musics-test-algo)
      (m/stop!)
      (let [actions (set (mapv :action (adviser/recent-activity)))]
        (is (contains? actions :register-algo!))
        (is (contains? actions :play))
        (is (contains? actions :assign-algo!))
        (is (contains? actions :stop!))))))
