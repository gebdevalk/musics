(ns ^:repl musics.mu.words-test
  "mu's words called from Clojure (musics.mu.words)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.mu.print :as p]
            [musics.mu.words :as w]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(use-fixtures :each
  (fn [t]
    (with-fresh-registries
      (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
      (t))))

(defn- show [x] (if (vector? x) (mapv p/show x) (p/show x)))

(deftest a-word-is-a-fn-of-what-it-takes
  (let [x (w/mu "[c d e g]")]
    (is (= "[C4/4 D4 E4 G4]" (show x)) "mu reads a literal")
    (is (= "[G4/4 E4 D4 C4]" (show (w/retrograde x))))
    (is (= "repeat 3 [C4/4 D4 E4 G4]" (show (w/times x 3))) "arguments in mu's order")
    (is (= "[F#4/4 E4 D4]" (show (w/mu "[c d e] [R] 2 +"))) "or a phrase")
    (is (= ["[C4/4 D4]" "[E4/4 G4]"] (show (w/split-at x 2))) "several results: a vector")))

(deftest short-forms-are-the-letter-signs
  (let [x (w/mu "[c d e g]")]
    (doseq [[short long] [[w/R w/retrograde] [w/I w/invert] [w/RI w/retrograde-inversion] [w/M w/mirror]
                          [w/H w/head] [w/T w/tail]]]
      (is (= (show (long x)) (show (short x)))))
    (is (= "[C4/4 D4]" (show (w/H x 2))) "an optional count stays optional")))

(deftest counting-words-count-their-arguments
  (let [c (w/mu "[c]") d (w/mu "[d]")]
    (is (= "[[C4/4] [D4]]" (show (w/line c d))) "line is [n]")
    (is (= "{[C4/4] [D4]}" (show (w/together c d))) "together is {n}")))

(deftest a-clojure-fn-stands-in-for-a-quotation
  (let [x (w/mu "[c d e g]")]
    (is (= "[C4/4 D4]" (show (w/filter x #(w/lt (w/pitch %) 64)))))
    (is (= "[D4/4 E4 F#4 A4]" (show (w/map x #(w/transpose % (w/mu "c") (w/mu "d"))))) "each note through it")))

(deftest leaves-and-streams-print-as-mu-prints-them
  (let [x (w/mu "[c d]")]
    (is (= "#mu \"C4/4\"" (pr-str (w/first x))))
    (is (= "#mu \"'[60 62]\"" (pr-str (w/pitches x))))
    (testing "an endless stream prints its start"
      (is (re-find #"…\]\"$" (pr-str (w/cycle (w/pitches x))))))))

(deftest errors-are-mus
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a whole number" (w/times (w/mu "[c]") :x))))
