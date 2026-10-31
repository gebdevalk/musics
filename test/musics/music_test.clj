(ns ^:repl musics.music-test
  "musics.music's functions are mu's words: the proof that a word can be
   a plain Clojure function."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.music :as music]
            [musics.mu.eval :as e]
            [musics.mu.library]
            [musics.mu.print :as p]
            [musics.test-support :refer [with-fresh-session]]))

;; transposing reads :ROOT's key, so each test starts from a fresh session
(use-fixtures :each (fn [t] (with-fresh-session (t))))

(defn- mu
  "Run mu text on a fresh stack: the one value it leaves, nil for none."
  [text]
  (let [out (e/run-text [] text)]
    (case (count out) 0 nil 1 (peek out) out)))

(defn- run [text] (p/show (mu text)))

(deftest a-word-is-its-function
  (is (= #'music/retrograde (:var (e/lookup @e/state "[R]"))))
  (is (= #'music/retrograde (:var (e/lookup @e/state "retrograde"))) "by its name and its sign")
  (is (= #'music/up (:var (e/lookup @e/state "+"))))
  (is (= #'music/find-instrument! (:var (e/lookup @e/state "find-instrument"))) "a ! left off")
  (is (= #'music/times (:var (e/lookup @e/state "tool/[*]"))) "in its :ns"))

(deftest called-from-clojure-or-from-mu-alike
  (let [x (mu "[c d e]")]
    (is (= "[E4/4 D4 C4]" (p/show (music/retrograde x)) (run "[c d e] [R]")))
    (is (= "repeat 3 [C4/4 D4]" (p/show (music/times (mu "[c d]") 3)) (run "[c d] 3 [*]")))
    (is (= "[!K:G.major G4/4 B4 D5]" (p/show (music/up (mu "[c e g]") 7)) (run "[c e g] 7 +")))))

(deftest an-optional-argument-by-its-test
  (testing "n is taken only when the top is a whole number"
    (is (= 8 (count (re-seq #"[A-G][&#]?\d" (run "[F3/1:7 | B&3:7] walking-bass")))) "an ostinato: two bars")
    (is (= 13 (count (re-seq #"[A-G][&#]?\d" (run "[F3/1:7 | B&3:7] 2 walking-bass")))) "twice, the end held"))
  (is (= "( chords [n] -- walking-bass )" (re-find #"^\(.*?\)" (:effect (e/lookup @e/state "walking-bass"))))
      "the effect mu shows, from the arglists"))

(deftest a-function-done-for-its-effect-leaves-nothing
  (is (= " 40  violin                 vln\n 41  viola                  vla\n"
         (with-out-str (is (nil? (mu "\"viol\" find-instrument")))))))

(deftest its-errors-are-mus-named-by-the-word
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^\+: is musical; on numbers write add" (mu "1 2 +")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^\+: transposes by a whole number" (mu "[c d] 1/2 +"))))

(deftest redefining-the-function-redefines-the-word
  (let [original @#'music/retrograde]
    (try
      (alter-var-root #'music/retrograde (constantly (fn [x] x)))
      (is (= "[C4/4 D4 E4]" (run "[c d e] [R]")) "mu runs the var as it is now")
      (finally (alter-var-root #'music/retrograde (constantly original))))))

(deftest a-bang-name-reads-what-a-note-plays-with
  (let [line "[!i:40 !mf !K:D.major c d\\vol:90\\i:73 r x8\\38]"]
    (testing "a note: its own value wins, else the context's where it sits"
      (is (= 60 (mu (str line " 0 nth !volume"))))
      (is (= 90 (mu (str line " 1 nth !volume"))))
      (is (= 40 (mu (str line " 0 nth !instrument"))))
      (is (= 73 (mu (str line " 1 nth !instrument"))))
      (is (= 60 (mu (str line " 2 nth !volume"))) "a rest too")
      (is (= 38 (mu (str line " 3 nth !instrument"))) "a drum's is its key of the kit"))
    (testing "the key's aliases read the same key"
      (is (= (mu (str line " 0 nth !volume")) (mu (str line " 0 nth !vol")) (mu (str line " 0 nth !v"))))
      (is (= "D.major" (run (str line " 0 nth !K")))))
    (testing "a line: its context at its start"
      (is (= 60 (mu (str line " !volume"))))
      (is (= "D.major" (run (str line " !K")))))
    (testing "articulation: the note's name, else the context's factor"
      (is (= :staccato (mu "[c4-. d] 0 nth !articulation")))
      (is (= 0.9 (mu "[c d] 0 nth !articulation"))))
    (testing "the word it stands for"
      (is (= 60 (mu (str line " :volume context-of")))))
    (testing "a mark or an unknown name is still refused, a number has no context"
      (is (thrown-with-msg? Exception #"instruction needs a container" (e/run-text [] "!mf")))
      (is (thrown-with-msg? Exception #"instruction needs a container" (e/run-text [] "[c d] 0 nth !nothing")))
      (is (thrown-with-msg? Exception #"needs a note or a line" (e/run-text [] "7 !volume"))))))
