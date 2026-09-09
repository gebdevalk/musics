(ns ^:repl adviser-musics-test
  "Confirms musics.clj's own thin wrappers (parse/commit!/play/stop!/
   assign-algo!/register-factory!/build!/...) really do append to
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

(deftest uh?-prints-a-real-suggestion-and-returns-nil-not-the-vector
  ;; nil, not the suggestions vector, is deliberate -- returning the
  ;; vector too meant a REPL echoed the same text a SECOND time (once
  ;; printed here, then again as the call's own raw return value),
  ;; confirmed live in a real session. core.adviser/what-next is the
  ;; place to get the data instead.
  (m/reset)
  (let [printed (with-out-str (is (nil? (m/uh?))))]
    (is (re-find #"Nothing committed yet" printed))))

(deftest advise-with-no-arg-prints-the-same-thing-uh?-does
  (m/reset)
  (is (= (with-out-str (m/uh?)) (with-out-str (m/advise)))))

(deftest advise-with-an-intent-biases-without-storing-anything
  (m/reset)
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
  (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
  (repo/play-latest!)
  ;; *engine* is a global, non-rebound var at the musics.clj level (by
  ;; design -- production connect!/play need it to persist across
  ;; unrelated calls); give THIS test its own fresh one so a prior
  ;; test's own leftover :algo-assignments in this same file can't make
  ;; algo-registered-but-nothing-assigned? false before this even runs.
  (binding [engine/*engine* (engine/engine nil repo/play-tx :ROOT)]
    (m/build-algo! ::advise-test-algo (fn [nodes _ _] nodes))
    (let [printed (with-out-str (is (nil? (m/advise :configure))))]
      (is (re-find #"Algorithm\(s\) registered" printed)
          "biased AS IF :configure were the current intent"))))

(deftest advise-accepts-a-1-based-position-in-place-of-the-keyword
  (m/reset)
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
  (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
  (binding [engine/*engine* (engine/engine nil repo/play-tx :ROOT)]
    (m/build-algo! ::advise-test-algo2 (fn [nodes _ _] nodes))
    (is (= (with-out-str (m/advise :configure)) (with-out-str (m/advise 4)))
        ":configure is intents' own 4th entry")))

(deftest advise-rejects-an-unrecognized-intent
  (m/reset)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a recognized intent"
        (m/advise :composting))))

(deftest advise-rejects-an-out-of-range-position
  (m/reset)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a recognized intent"
        (m/advise 0)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a recognized intent"
        (m/advise 99))))

(deftest advise!-reads-a-typed-number-from-stdin
  (m/reset)
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
  (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
  (binding [engine/*engine* (engine/engine nil repo/play-tx :ROOT)]
    (m/build-algo! ::advise!-test-algo (fn [nodes _ _] nodes))
    (let [result (with-in-str "4" (with-out-str (m/advise!)))]
      (is (re-find #"Algorithm\(s\) registered" result)
          "typed \"4\" resolved to :configure, same as (advise :configure)"))))

(deftest advise!-reads-a-typed-keyword-name-with-or-without-the-colon
  (m/reset)
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
  (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
  (binding [engine/*engine* (engine/engine nil repo/play-tx :ROOT)]
    (m/build-algo! ::advise!-test-algo2 (fn [nodes _ _] nodes))
    (let [out1 (with-in-str "configure" (with-out-str (m/advise!)))
          out2 (with-in-str ":configure" (with-out-str (m/advise!)))]
      (is (re-find #"Algorithm\(s\) registered" out1))
      (is (re-find #"Algorithm\(s\) registered" out2)))))

(deftest advise!-blank-input-means-no-bias
  (m/reset)
  (let [out (with-in-str "" (with-out-str (m/advise!)))]
    (is (re-find #"Nothing committed yet" out))))

(deftest advise!-surfaces-the-clear-error-for-a-typo
  (m/reset)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a recognized intent"
        (with-in-str "notaphase" (with-out-str (m/advise!))))))

(deftest wipe-adviser!-works-through-musics
  (m/reset)
  (m/parse "[verse: c4]")
  (m/wipe-adviser!)
  (is (empty? (adviser/recent-activity))))

(deftest build-algo!-and-assign-algo!-log-activity
  (m/reset)
  (let [{:keys [sid ids]} (m/parse "[verse: c4 d4]")]
    (m/commit! sid)
    (m/play-latest!)
    (m/build-algo! ::adviser-musics-test-algo (fn [nodes _ _] nodes))
    (let [id (m/play :verse)]
      (m/assign-algo! id ::adviser-musics-test-algo)
      (m/stop!)
      (let [actions (set (mapv :action (adviser/recent-activity)))]
        (is (contains? actions :build-algo!))
        (is (contains? actions :play))
        (is (contains? actions :assign-algo!))
        (is (contains? actions :stop!))))))
