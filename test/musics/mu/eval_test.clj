(ns ^:repl musics.mu.eval-test
  "mu's evaluator: the stack, literals with the input, words and their
   namespaces (late binding, ambiguity), definitions with locals,
   quotations, and errors that clear the stack."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.print :as p]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh
  "A fresh registry and a mu state with only the kernel words; mu's state
   is restored afterwards."
  [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc-in (e/fresh-state) [:ns "kernel"] (get-in saved [:ns "kernel"])))
        (t))
      (finally (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- run
  "Evaluate inputs in turn on an empty stack; the last result."
  [& inputs]
  (swap! e/state assoc :stack [])
  (last (mapv e/eval-input inputs)))
(defn- shown [r] (mapv p/show (:ok r)))

(deftest numbers-strings-names
  (is (= [7 1/4 0.5 -12 "t" :motif] (:ok (run "7 1/4 0.5 -12 \"t\" :motif")))))

(deftest stack-words
  (is (= [2 1] (:ok (run "1 2 swap"))))
  (is (= [1 2 1] (:ok (run "1 2 over"))))
  (is (= [2 3 1] (:ok (run "1 2 3 rot"))))
  (is (= [2] (:ok (run "1 2 nip"))))
  (is (= [2 1 2] (:ok (run "1 2 tuck"))))
  (is (= [10 20 30 10] (:ok (run "10 20 30 2 pick"))))
  (is (= [1 1 2] (:ok (run "1 1 depth")))))

(deftest literals-are-pushed-with-the-input
  (is (= ["[C4/4 D4/4]" "[C4/4 D4/4]"] (shown (run "[c d] dup"))))
  (testing "the same text twice is two literals, each read in its place"
    (is (= ["C4/4" "E4/4" "G4/4" "C5/4"] (shown (run "c4 e g c"))))))

(deftest errors-clear-the-stack
  (let [r (run "1 2 pop pop pop")]
    (is (re-find #"pop: needs 1 item" (:error r)))
    (is (= [] (:stack @e/state)) "cleared"))
  (is (re-find #"unknown word: frobnicate" (:error (run "1 frobnicate"))))
  (is (re-find #"instruction needs a container" (:error (run "!mf"))))
  (is (re-find #"can't read" (:error (run "[c d ]]")))))

(deftest unfinished-input
  (is (:incomplete (run "[c d")))
  (is (:incomplete (run ": twice dup"))))

(deftest definitions-and-late-binding
  (is (= [3 3] (:ok (run ": twice dup ; 3 twice"))))
  (testing "a redefinition reaches the words that use it"
    (run ": inner 1 ;" ": outer inner inner ;")
    (is (= [1 1] (:ok (run "outer"))))
    (run ": inner 2 ;")
    (is (= [2 2] (:ok (run "outer"))))))

(deftest a-note-can't-be-redefined
  (doseq [n ["a" "c4" "cn" "x" "R" "r8"]]
    (is (re-find #"is a note .* can't be a word" (str (:error (run (str ": " n " 1 ;"))))) n))
  (is (re-find #"can't be a word" (str (:error (run "variable a")))))
  (is (= [1] (:ok (run ": ab 1 ; ab"))) "a name that is no leaf is fine"))

(deftest locals
  (is (= [1 2 2 1] (:ok (run ": mirror ( a b -- b a ) $a $b $b $a ; 1 2 mirror"))))
  (is (re-find #"\$q is not a local" (:error (run ": bad $q ; bad")))))

(deftest quotations
  (is (= [5 5] (:ok (run "5 '( dup ) call"))))
  (testing "a quotation made in a word keeps that word's locals"
    (is (= [7] (:ok (run ": maker ( x -- q ) '( $x ) ; 7 maker call"))))))

(deftest namespaces
  (run "\"song\" in")
  (e/define! "a" "w" {:fn #(conj % :a)})
  (e/define! "b" "w" {:fn #(conj % :b)})
  (swap! e/state update :use conj "a" "b")
  (is (re-find #"w is ambiguous, in a and b -- write a/w or b/w" (:error (run "w"))))
  (is (= [:a :b] (:ok (run "a/w b/w"))) "qualified, either can be used"))
