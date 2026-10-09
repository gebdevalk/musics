(ns ^:parsing musics.mu.grammar-test
  "mu's grammar: leaves win, musical literals are musics text, mu's own
   items (words, names, locals, quotations, definitions), and telling an
   unfinished input from a wrong one."
  (:require [clojure.test :refer [deftest is testing]]
            [musics.mu.grammar :as g]))

(defn- items [text]
  (let [r (g/parse text)]
    (is (not (g/failure? r)) (str "parses: " (pr-str text)))
    (vec (rest r))))

(defn- tags [text] (mapv first (items text)))

(deftest leaves-win
  (testing "a bare pitch, rest or drum letter is a leaf"
    (is (= [:Note] (tags "a")))
    (is (= [:Note :Note :Note] (tags "c4 d e")))
    (is (= [:Note] (tags "C5/8")))
    (is (= [:Note] (tags "cn")) "C natural, not a word")
    (is (= [:Chord] (tags "<a c e>4")))
    (is (= [:Rest] (tags "r4")))
    (is (= [:MultiRest] (tags "R")))
    (is (= [:MultiRest] (tags "R*4")))
    (is (= [:MultiRest] (tags "R1*4")))
    (is (= [[:Word "R!"] [:Word "R@"]] (items "R! R@")) "R! and R@ are words, not rests")
    (is (= [:MultiRest :Word] (mapv first (items "R R!"))) "a rest, then the store word")
    (is (= [:Drum] (tags "x8\\36")))
    (is (= [:Drum] (tags "x")) "x is a drum, so a word named x needs \\x"))
  (testing "what is no leaf is a word"
    (doseq [w ["dup" "pop" "pick" "par" "rot" "be" "pulses->durations" "note?" "play+" "cr" "decr"]]
      (is (= [[:Word [:WordName w]]] (items w)) w))
    (is (= [[:Word [:WordName "tool/cycle"]]] (items "tool/cycle")) "ns/word")
    (doseq [w [">>" ".+" ".-" ".*" "./" ".<" ".>" ".<=" ".>=" ".=" "**" "*" "+" "-" "/" "." ".s" "<>" "<" ">" "<=" ">=" "=" "R!" "R@" "!" "@"]]
      (is (= [[:Word w]] (items w)) w)))
  (testing "\\ forces a word"
    (is (= [[:ForcedWord [:WordName "a"]]] (items "\\a")))))

(deftest literals-are-musics-text
  (is (= [:Sequence] (tags "[c4 d e]")))
  (is (= [:Parallel] (tags "{[c d] [e f]}")))
  (is (= [:Context] (tags "^{ !ff !tempo:120 }")))
  (is (= [:Data] (tags "'[60 64 67]")))
  (is (= :transpose (ffirst (rest (first (items "[\\transpose c d ( c d e ) f]")))))
      "the text command inside a literal")
  (is (= :Dynamic (-> (items "[c4\\f d]") first second last first))
      "a glued dynamic inside a literal"))

(deftest mu-items
  (is (= [[:SignedInt "7"] [:SignedRatio "1/4"] [:SignedFloat "0.5"] [:SignedInt "-12"] [:StringLit "t"]]
         (items "7 1/4 0.5 -12 \"t\"")))
  (is (= [[:IdName "motif"] [:Word "R!"]] (items ":motif R!")) "a repo name, then the store word")
  (is (= [[:Local "a"]] (items "$a")))
  (is (= [[:Quotation [:Word [:WordName "dup"]] [:SignedInt "12"] [:Word "+"]]]
         (items "'( dup 12 + )")))
  (is (= [[:VariableDecl [:WordName "shift"]]] (items "variable shift")))
  (is (= [[:LooseInstruction [:BangConst [:Name "mf"]]]] (items "!mf"))
      "parsed, so it can be refused with a clear message"))

(deftest definitions
  (is (= [[:Definition [:WordName "octaved"]
           [:Word [:WordName "dup"]] [:SignedInt "12"] [:Word "+"] [:SignedInt "2"] [:Word [:WordName "par"]]]]
         (items ": octaved dup 12 + 2 par ;")))
  (is (= [:Definition [:WordName "canon"] [:StackEffect [:EffectIn "a" "s"] [:EffectOut "p"]]
          [:Local "a"] [:Local "a"] [:Local "s"] [:Word "+"] [:SignedInt "2"] [:Word [:WordName "par"]]]
         (first (items ": canon ( a s -- p ) $a $a $s + 2 par ;")))
      "a stack effect names the locals, leaf letters included"))

(deftest unfinished-or-wrong
  (doseq [t ["[c d" "'( dup" ": x dup" "{[c d]" "^{ !ff" "\"open"]]
    (is (g/incomplete? t) (str "unfinished: " (pr-str t))))
  (doseq [t ["[c d ]]" "c4 ) d" "dup ;"]]
    (is (and (g/failure? (g/parse t)) (not (g/incomplete? t))) (str "wrong: " (pr-str t))))
  (is (not (g/incomplete? "[c d e]")) "complete"))
