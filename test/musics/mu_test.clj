(ns ^:repl musics.mu-test
  "The mu REPL loop: the prompt shows the stack's depth, an unfinished
   input continues, errors are reported and clear the stack, bye and
   the end of input leave, the stack lasts the session."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.mu :as mu]
            [musics.mu.eval :as e]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (:ns saved)))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- session
  "Run the loop over lines; [prompts shown, what was printed]."
  [& lines]
  (let [prompts (atom []) more (atom lines)]
    [(with-out-str
       (mu/run-loop (fn [p] (swap! prompts conj p) (let [l (first @more)] (swap! more rest) l))))
     @prompts]))

(deftest the-prompt-shows-the-depth
  (let [[_ prompts] (session "1 2" "pop")]
    (is (= ["mu<0> " "mu<2> " "mu<1> "] prompts))))

(deftest an-unfinished-input-continues
  (let [[_ prompts] (session "[c4 d" "e f]" ".s")]
    (is (= ["mu<0> " "  ... " "mu<1> " "mu<1> "] prompts) "... right-aligned under mu<n>"))
  (is (= [1] (map count [(:stack @e/state)])))
  (let [[out _] (session ": twice" "  dup ;" "3 twice .s")]
    (is (str/includes? out "<3> [C4/4 D4/4 E4/4 F4/4] 3 3"))))

(deftest errors-are-reported
  (let [[out prompts] (session "1 2 pop pop pop" "7")]
    (is (str/includes? out "error: pop: needs 1 item"))
    (is (= "mu<0> " (second prompts)) "the stack is cleared")))

(deftest bye-and-the-end-leave
  (let [[_ prompts] (session "1" "bye" "2")]
    (is (= 2 (count prompts)) "bye stops reading"))
  (is (= [1] (:stack @e/state)) "the stack lasts the session")
  (let [[_ prompts] (session)]
    (is (= 1 (count prompts)) "the end of input leaves")))
