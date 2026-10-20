(ns ^:repl musics.mu.complete-test
  (:require [clojure.test :refer [deftest is are]]
            [musics.mu.complete :as c]))

(defn- values [word] (mapv :value (c/candidates word)))

(deftest instruments-after-an-instrument-key
  (are [word vs] (= vs (values word))
    "!i:viol"          ["!i:violin" "!i:viola"]
    "!i:Viol"          ["!i:violin" "!i:viola"]
    "^{!i:pizz"        ["^{!i:pizzicatoStrings"]
    "c4\\i:gl"         ["c4\\i:glockenspiel"]
    "!instrument:trum" ["!instrument:trumpet"]
    "!prog:tub"        ["!prog:tubularBells" "!prog:tuba"]
    "!i:40,vio"        ["!i:40,violin" "!i:40,viola"]
    "!i:acb"           ["!i:acbs"])
  (is (= 128 (count (values "!i:"))) "nothing typed: every instrument")
  (is (= "40 violin vln" (:descr (first (c/candidates "!i:violin")))) "program, name and short name"))

(deftest drums-after-a-drums-backslash-and-kits
  (are [word vs] (= vs (values word))
    "x8\\cr"      ["x8\\cr1" "x8\\cr2"]
    "x\\clo"      ["x\\closedHiHat"]
    "x8\\kick,cl" ["x8\\kick,clap" "x8\\kick,closedHiHat" "x8\\kick,clv"]
    "!kit:el"     ["!kit:electronic"])
  (is (= "42 Closed Hi-Hat" (:descr (first (c/candidates "x\\clo"))))))

(deftest nothing-where-no-name-is-written
  (are [word] (empty? (c/candidates word))
    "c4" "!vol:mf" "c4\\f" "[blues:" "dup" "!i:zz"))
