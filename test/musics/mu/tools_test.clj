(ns ^:repl musics.mu.tools-test
  "mu's first tools: they keep the form (a line gives a line), endless
   streams are refused where all of a stream is needed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.print :as p]
            [musics.mu.tools]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh
  "A fresh registry with a :ROOT and a mu state with the kernel, tool and
   music words, all in use; mu's state is restored afterwards."
  [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "tool" "music"])))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- top [input]
  (swap! e/state assoc :stack [])
  (let [r (e/eval-input input)]
    (if (:error r) (:error r) (str/join " " (map p/show (:ok r))))))

(deftest a-tool-in-a-fresh-session
  (with-fresh-registries
    (is (= "[[C4/4 D4/4] [C4/4 D4/4] [C4/4 D4/4]]" (top "[c d] 3 **")) "no :ROOT stored yet")))

(deftest qualified-symbol-words
  (swap! e/state assoc :use ["kernel" "music"])
  (is (= "[[C4/4 D4/4] [C4/4 D4/4]]" (top "[c d] 2 tool/**")))
  (is (re-find #"unknown word: \*\*" (top "[c d] 2 **")) "tool isn't in use here"))

(deftest tool-is-in-use-by-default
  (is (= ["kernel" "tool" "music" "mine"] (:use (e/fresh-state)))))

(deftest bar-lines-are-elements
  (is (= "[C4/4 D4/4 | E4/4]" (top "c d | e 4 seq")) "seq keeps a bare bar")
  (is (= "[F4/4 E4/4 | D4/4 C4/4]" (top "[c d | e f] reverse")) "a tool keeps it in a line")
  (is (= "[[C4/4*1/3 D4/4*1/3 |] [C4/4*1/3 D4/4*1/3 |]]" (top "[c d |] 1/3 * 2 **")) "and through * and **"))

(deftest repeat-is-the-texts-repeat
  (is (= "repeat volta 3 [A3/4 B3/4]" (top "[a b] 3 repeat")) "repeat volta 3 [a b], postfix")
  (is (= "repeat volta 3 [A3/4 B3/4] alternative [C4/4]" (top "[a b] 3 repeat [c] alternative")))
  (is (= "repeat volta 2 [C4/4]" (top "c 2 repeat")) "a note is repeated as a line")
  (testing "it reads back as itself"
    (let [shown (top "[a b] 3 repeat [c] alternative")]
      (is (= shown (top shown)))))
  (is (= "repeat volta 3 [B3/4 C#4/4] alternative [D4/4]" (top "[a b] 3 repeat [c] alternative 2 +"))
      "+ reaches into its body and ending")
  (is (= "[[E4/4 repeat volta 2 [A3/4 B3/4] alternative [D4/4] C4/4]]" (str "[" (top "[c repeat volta 2 [a b] alternative [d] e] reverse") "]"))
      "an element of a line, kept whole")
  (is (= "[[A3/4 B3/4] [A3/4 B3/4]] [[A3/4 B3/4] [A3/4 B3/4]]" (str (top "[a b] 2 ** dup")))
      "** gives copies, repeat a repetition")
  (is (re-find #"only a volta repeat" (top "[repeat unfold 2 [a]] '( [c] alternative ) each")))
  (is (re-find #"has one already" (top "[a b] 2 repeat [c] alternative [d] alternative")))
  (is (re-find #"needs a repeat under the ending" (top "c [d] alternative")))
  (is (re-find #"at least 1 pass" (top "[a b] 0 repeat"))))

(deftest repeat-stores-as-parse-stores-it
  (swap! e/state assoc :stack [])
  (let [id (first (:ok (e/eval-input "[a b] 2 repeat R!")))]
    (is (re-matches #"s\d+" (name id)) "in a line of its own, as parse wraps a bare repeat")
    (is (= "[repeat volta 2 [A3/4 B3/4]]" (top (str id " R@"))))))

(deftest form-tools-keep-the-form
  (is (= "[C4/4 D4/4]" (top "[c d e f] 2 take")))
  (is (= "[E4/4 F4/4]" (top "[c d e f] 2 drop")))
  (is (= "[F4/4]" (top "[c d e f] 1 take-last")))
  (is (= "[F4/4 E4/4 D4/4 C4/4]" (top "[c d e f] reverse")) "retrograde")
  (is (= "[D4/4 E4/4 C4/4]" (top "[c d e] 1 rotate")))
  (is (= "[[C4/4 D4/4] [C4/4 D4/4]]" (top "[c d] 2 **")) "copies, each its own element")
  (is (= "[C4/4 C4/4 C4/4]" (top "c 3 **")) "a note's copies are notes")
  (is (= "'[1 2 1 2]" (top "'[1 2] 2 **")) "data stays flat")
  (is (= "[C4/4 D4/4 E4/4]" (top "[c d] [e] then")) "succession")
  (is (= "[C4/4 E4/4 D4/4 F4/4]" (top "[c d] [e f] interleave")))
  (is (= "[[C4/4 D4/4] [E4/4]]" (top "[c d e] 2 partition")))
  (is (= "[C4/4] [D4/4 E4/4]" (top "[c d e] 1 split-at")))
  (is (= "[!mf C4/4 D4/4]" (top "[!mf c d e] 2 take")) "leading instructions are kept")
  (is (= 4 (count (re-seq #"/4" (top "[c d e f] shuffle")))) "shuffled, the same four"))

(deftest streams-and-endless
  (is (= "'[0 1 2]" (top "0 3 range")))
  (is (= "'[60 62 64]" (top "60 '[0 2 4] scale")))
  (is (= "'[1 3 5 7]" (top "1 '( 2 .+ ) iterate 4 take")))
  (is (= "[C4/4 D4/4 C4/4 D4/4 C4/4]" (top "[c d] cycle 5 take")) "a stream of leaves prints as a line")
  (testing "an endless stream prints its start"
    (is (str/ends-with? (top "[c d] cycle") "…]")))
  (testing "where all of a stream is needed, an endless one is refused"
    (is (re-find #"endless -- take some first" (top "[c d] cycle reverse")))
    (is (re-find #"endless" (top "[c d] cycle play")))))

(deftest assemble
  (is (= "[C4/4 D4/4 E4/4]" (top "c4 d e 3 seq")))
  (is (= "{[C4/4 D4/4] [E4/4]}" (top "[c d] e4 2 par")))
  (is (= "<A3 C4 E4>4" (top "a c e <>")) "a chord from the leaves on top")
  (is (= "[G3/4] <C4 E4>4" (top "[g] c e <>")) "stopping at the first non-leaf")
  (is (= "[C4/4 D4/8 E4/4]" (top "'[/4 /8 /4] '[60 62 64] zip")))
  (is (= "[!pp [C4/4 D4/4]]" (top "[c d] ^{ !pp } with"))))

(deftest meaning-tools
  (is (= "[D4/4 E4/4]" (top "[c d] c d transpose")) "by the interval from C to D")
  (is (= "[C4/2 D4/2]" (top "[c d] 2 stretch"))))
