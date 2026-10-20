(ns ^:repl musics.mu.words-test
  "mu's words called from Clojure (musics.mu.words)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
    (is (= "[!K:D.major F#4/4 E4 D4]" (show (w/mu "[c d e] [R] 2 +"))) "or a phrase")
    (is (= ["[C4/4 D4]" "[E4/4 G4]"] (show (w/mu "[c d e g] 2 split-at"))) "several results: a vector")))

(deftest what-clojure-has-is-clojures
  (is (every? nil? (map #(ns-resolve 'musics.mu.words (symbol %)) ["take" "filter" "first" "map" "count" "iterate"]))
      "mu's take, filter, first … are clojure.core's from Clojure")
  (let [x (w/mu "[c d e g]")]
    (is (= "[C4/4 D4]" (show (apply w/line (take 2 (w/elements x))))) "elements, then line")
    (is (= "[C4/4 D4]" (show (apply w/line (filter #(< (w/pitch %) 64) (w/elements x))))))
    (is (= "[C4/4 D4 E4 G4 C4]" (show (apply w/line (take 5 (w/elements (w/cycle x)))))) "a loop's elements, endlessly")
    (is (= "#mu \"C4/4\"" (pr-str (first (w/elements x)))) "an element prints as mu prints it")))

(deftest short-forms-are-the-letter-signs
  (let [x (w/mu "[c d e g]")]
    (doseq [[short long] [[w/R w/retrograde] [w/I w/inversion] [w/RI w/retrograde-inversion] [w/M w/mirror]
                          [w/H w/head] [w/T w/tail]]]
      (is (= (show (long x)) (show (short x)))))
    (is (= "[C4/4 D4]" (show (w/H x 2))) "an optional count stays optional")
    (is (= "[!K:G.major G4/4 A4 B4 D5]" (show (w/up x 7))) "+ as up")
    (is (= "[3:1(C4/4 D4 E4 G4)]" (show (w/shrink x 3))) "/ as shrink")
    (is (true? (w/= x (w/mu "[C4 D4 E4 G4]"))) "= is eq: music by its notes")
    (is (false? (clojure.core/= x (w/mu "[c d e g]"))) "where clojure.core's = sees two values")
    (is (false? (w/= x (w/R x))))))

(deftest any-word-by-its-mu-name
  (let [x (w/mu "[c d e g]")]
    (is (= "[!K:G.major G4/4 A4 B4 D5]" (show (w/word "+" x 7))) "a sign without a Clojure name")
    (is (= (show (w/word "[R]" x)) (show (w/R x))))
    (is (= "repeat ~ [C4/4 D4 E4 G4]" (show (w/word "[~]" x))))
    (is (= (show (w/word "+" x 7)) (show (w/up x 7))) "up is +")
    (is (= (show (w/word "-" x 12)) (show (w/down x 12))) "down is -")
    (is (= (show (w/word "*" x 2)) (show (w/stretch x 2))) "stretch is *")
    (is (= "[3:1(C4/4 D4 E4)]" (show (w/shrink (w/mu "[c d e]") 3))) "shrink is /")))

(deftest counting-words-count-their-arguments
  (let [c (w/mu "[c]") d (w/mu "[d]")]
    (is (= "[[C4/4] [D4]]" (show (w/line c d))) "line is [n]")
    (is (= "{[C4/4] [D4]}" (show (w/together c d))) "together is {n}")))

(deftest a-clojure-fn-stands-in-for-a-quotation
  (let [x (w/mu "[c d e g]")]
    (is (= "[C4/4 D4 r r]" (show (w/mute x #(> (w/pitch %) 63)))) "mute keeps the rhythm")
    (is (= "[C4/4 G4 E4 D4]" (show (w/within x 1 4 w/R))) "within a chunk")))

(deftest leaves-and-streams-print-as-mu-prints-them
  (let [x (w/mu "[c d]")]
    (is (= "#mu \"C4/4\"" (pr-str (w/mu "c"))))
    (is (= "#mu \"'[60 62]\"" (pr-str (w/pitches x))))
    (is (= "#mu \"'[60 62] [~]\"" (pr-str (w/cycle (w/pitches x)))) "a loop prints as one")))

(deftest errors-are-mus
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a whole number" (w/times (w/mu "[c]") :x))))
