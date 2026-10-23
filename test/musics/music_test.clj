(ns ^:repl musics.music-test
  "musics.music's functions are mu's words: the proof that a word can be
   a plain Clojure function."
  (:require [clojure.test :refer [deftest is testing]]
            [musics.music :as music]
            [musics.mu.eval :as e]
            [musics.mu.library]
            [musics.mu.print :as p]))

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
