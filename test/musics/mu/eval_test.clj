(ns ^:repl musics.mu.eval-test
  "mu's evaluator: the stack, literals with the input, words and their
   namespaces (late binding, ambiguity), definitions with locals,
   quotations, and errors that leave the stack as it was."
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

(deftest bar-lines
  (is (= ["C4/4" "|" "D4/4"] (shown (run "c | d"))) "at the top level | is a mark")
  (is (= ["C4/4" "||" "D4/4"] (shown (run "c || d"))) "|| … |||| too, a mark of that strength (>>'s boundary)")
  (is (= ["[C4/4 | D4]"] (shown (run "[c | d]"))) "inside brackets | is a bar line"))

(deftest stack-words
  (is (= [2 1] (:ok (run "1 2 swap"))))
  (is (= [1 2 1] (:ok (run "1 2 over"))))
  (is (= [2 3 1] (:ok (run "1 2 3 rot"))))
  (is (= [2] (:ok (run "1 2 nip"))))
  (is (= [2 1 2] (:ok (run "1 2 tuck"))))
  (is (= [10 20 30 10] (:ok (run "10 20 30 2 pick"))))
  (is (= [1/4 3/16 1/6] (:ok (run "/4 /8. /4*2/3"))) "a duration alone is its ratio")
  (is (= "'[1/4 1/8 3/16]" (p/show (first (:ok (run "( /4 /8 /8. )"))))))
  (is (re-find #"no note value" (:error (run "/3"))))
  (is (= ["^{ !M:3/4 }" "^{ !T:8=200 }" "^{ !K:D.major }" "^{ !vol:mf }"]
         (map (comp p/show first :ok run) ["M:3/4" "T:8=200" "K:D.major" "vol:mf"])) "a setting alone is a context")
  (is (= [10 20 30] (:ok (run "10 20 30 0 roll"))) "0 roll leaves the stack")
  (is (= [10 30 20] (:ok (run "10 20 30 1 roll"))) "1 roll is swap")
  (is (= [20 30 10] (:ok (run "10 20 30 2 roll"))) "2 roll is rot")
  (is (re-find #"roll: needs an index" (:error (run "10 20 3 roll"))))
  (is (= [1 1 2] (:ok (run "1 1 depth")))))

(deftest literals-are-pushed-with-the-input
  (is (= ["[C4/4 D4]" "[C4/4 D4]"] (shown (run "[c d] dup"))))
  (testing "the same text twice is two literals, each read in its place"
    (is (= ["C4/4" "E4/4" "G4/4" "C5/4"] (shown (run "c4 e g c"))))))

(deftest errors-leave-the-stack
  (let [r (run "1 2 drop drop drop")]
    (is (re-find #"drop: needs 1 item" (:error r)))
    (is (= [] (:stack @e/state)) "the input's own words consumed nothing it keeps"))
  (testing "what lay on the stack before the failing input stays"
    (run "7 8")
    (is (re-find #"unknown word: frobnicate" (:error (e/eval-input "9 frobnicate"))))
    (is (= [7 8] (:stack @e/state)) "9 was not kept: the input is all or nothing")
    (is (re-find #"can't read" (:error (e/eval-input "[c d ]]"))))
    (is (= [7 8] (:stack @e/state)) "a parse error too"))
  (is (re-find #"unknown word: frobnicate" (:error (run "1 frobnicate"))))
  (is (re-find #"instruction needs a container" (:error (run "!mf"))))
  (is (re-find #"can't read" (:error (run "[c d ]]"))))
  (testing "a word inside a literal is named, with the way to gather"
    (is (re-find #"^\.s is a word \(line 1, column 15\).*\n.*\( \[a b\] \[\?\] \[c d\] \[\?\] \)"
                 (:error (run "[[ a b c d e] .s [e f g a b]]"))))
    (is (re-find #"^dup is a word \(line 2, column 3\)" (:error (run "[c d\n  dup e]"))))))

;; a stack effect names what a word takes and gives; x' is x changed, as mu's own
;; help writes it, so a word that only copies x says x, and one that changes it x'
(deftest a-stack-effect-may-prime-a-name
  (is (= [3 3] (:ok (run ": twice ( $x -- x x ) $x $x ; 3 twice"))) "a copy leaves x as it is")
  (is (= [4] (:ok (run ": bump ( $n -- n' ) $n 1+ ; 3 bump"))) "a changed n is n'"))

;; only an input marked $ is bound; the others stay on the stack, so the
;; stack effect reads as it does in Forth
(deftest only-marked-inputs-are-locals
  (is (= [5] (:ok (run ": double ( n -- n' ) 1+ 1+ ; 3 double"))) "no $: the stack as it is")
  (is (= [5] (:ok (run ": double2 ( $n -- n' ) $n 1+ 1+ ; 3 double2"))) "$n: taken off, read as $n")
  (is (re-find #"dup: needs 1 item" (:error (run ": double3 ( $n -- n' ) dup 1+ ; 3 double3"))) "a bound input is not on the stack")
  (is (= [1 2 1] (:ok (run ": over2 ( $aa ys -- aa ys aa ) $aa swap $aa ; 1 2 over2"))) "mixed: aa bound, ys kept in place")
  (is (= [7 1 2 3] (:ok (run "7 1 2 3 : keep ( ns -- ns ) ; keep"))) "words below are left alone"))

;; the outputs are kept: how many, and a name without a prime is that input
(deftest a-stack-effect-is-kept
  (is (re-find #"leaves 2 values -- its stack effect says 1" (:error (run ": twin ( ns -- ns' ) dup 1+ ; 3 twin"))))
  (is (re-find #"leaves 1 value -- its stack effect says 0" (:error (run ": gives ( -- ) 1 ; gives"))))
  (is (re-find #"ns is the same on both sides.*write ns'" (:error (run ": swapped ( ns -- ns ) drop 5 ; 3 swapped"))))
  (is (= [5] (:ok (run ": swapped2 ( ns -- ns' ) drop 5 ; 3 swapped2"))) "a prime may change it")
  (is (= 1 (count (:ok (run ": gather ( ns... -- l ) ( ) ; 1 2 3 gather")))) "... is any number, not counted"))

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
  (is (= [1] (:ok (run ": ab 1 ; ab"))) "a name that is no leaf is fine"))

(deftest locals
  (is (= [1 2 2 1] (:ok (run ": mirror ( $a $b -- a b b a ) $a $b $b $a ; 1 2 mirror"))))
  (is (re-find #"\$q is not a local" (:error (run ": bad $q ; bad")))))

(deftest quotations
  (is (= [5 5] (:ok (run "5 '( dup ) call"))))
  (testing "a quotation made in a word keeps that word's locals"
    (is (= [7] (:ok (run ": maker ( $x -- q ) '( $x ) ; 7 maker call"))))))

(deftest namespaces
  (run "\"song\" in")
  (e/define! "a" "w" {:fn #(conj % :a)})
  (e/define! "b" "w" {:fn #(conj % :b)})
  (swap! e/state update :use conj "a" "b")
  (is (re-find #"w is ambiguous, in a and b -- write a/w or b/w" (:error (run "w"))))
  (is (= [:a :b] (:ok (run "a/w b/w"))) "qualified, either can be used"))

(deftest a-word-may-start-with-?
  (is (= [3 3] (:ok (run ": ?three 3 ; ?three ?three")))))
