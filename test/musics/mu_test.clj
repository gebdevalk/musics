(ns ^:repl musics.mu-test
  "The mu REPL loop: the prompt shows the stack's depth, an unfinished
   input continues, errors are reported and clear the stack, bye and
   the end of input leave, the stack lasts the session."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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
    (is (str/includes? out "0> 3\n1> 3\n2> [C4/4 D4 E4 F4]\n"))))

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

(deftest only-words-print
  (let [[out _] (session "{ [a b] [c d] } a 1 2")]
    (is (= "" out) "a literal is pushed silently, as a number is in Forth"))
  (let [[out _] (session "[motif: c d] R!")]
    (is (= "" out) "R! stores silently, as Forth's ! does"))
  (let [[out _] (session "[c d] R! .")]
    (is (re-matches #":s\d+\n" out) "its id is on the stack, for . to print")))

(deftest the-terminal-passes-musics-text-through
  (let [history (.toPath (java.io.File/createTempFile "mu-history" ""))
        read    (fn [line]
                  (let [t (org.jline.terminal.impl.DumbTerminal.
                           (java.io.ByteArrayInputStream. (.getBytes (str line "\n")))
                           (java.io.ByteArrayOutputStream.))]
                    (.readLine ^org.jline.reader.LineReader (#'mu/line-reader t history) "mu<0> ")))]
    (try
      (doseq [line ["[x8\\36 x\\38-> x\\38\\ghost]" "[!mf c4\\f] !! [!vol:mf< c]" "'[60 64] '( dup ) 'name \"s\""]]
        (is (= line (read line)) "backslashes, ! and ' arrive as typed"))
      (finally (java.nio.file.Files/deleteIfExists history)))))

(deftest mu-loads-what-commits-the-root
  ;; lein mu reads against :ROOT from its first input: musics.mu.store
  ;; commits it when it loads, so musics.mu must load it (a fixture's
  ;; :ROOT would hide its absence)
  (let [ns-form (read-string (slurp (io/resource "musics/mu.clj")))]
    (is (some #{'musics.mu.store} (flatten (seq ns-form))))))
