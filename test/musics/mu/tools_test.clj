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
    (is (= "[[C4/4 D4] [C4 D4] [C4 D4]]" (top "[c d] 3 **")) "no :ROOT stored yet")))

(deftest qualified-symbol-words
  (swap! e/state assoc :use ["kernel" "music"])
  (is (= "[[C4/4 D4] [C4 D4]]" (top "[c d] 2 tool/**")))
  (is (re-find #"unknown word: \*\*" (top "[c d] 2 **")) "tool isn't in use here"))

(deftest tool-is-in-use-by-default
  (is (= ["kernel" "tool" "music" "mine"] (:use (e/fresh-state)))))

(deftest bar-lines-are-elements
  (is (= "[C4/4 D4 | E4]" (top "| [c d | e] '( ) each seq")) "seq keeps a bar of a line's elements")
  (is (= "[F4/4 E4 | D4 C4]" (top "[c d | e f] reverse")) "a tool keeps it in a line")
  (is (= "[[3/1(C4/4 D4) |] [3/1(C4 D4) |]]" (top "[c d |] 1/3 * 2 **")) "and through * and **"))

(deftest repeat-is-the-texts-repeat
  (is (= "repeat volta 3 [A3/4 B3]" (top "[a b] 3 repeat")) "repeat volta 3 [a b], postfix")
  (is (= "repeat volta 2 [C4/4]" (top "c 2 repeat")) "a note is repeated as a line")
  (is (= "repeat volta 3 [A3/4 B3] alternative [C4]" (top "| [c] alternative [a b] 3 repeat"))
      "endings first, gathered to the mark, then the repeat takes them")
  (is (= "repeat volta 3 [A3/4 B3 C4 D4] alternative [[C4/2 B3 A3] [A3/4]]"
         (top "| [c2 b a] [a4] alternative [a b c d] 3 repeat"))
      "several endings, grouped as LilyPond groups them")
  (is (= "| [C4/4] [E4] alternative" (top "| [c] [e] alternative")) "endings print as written")
  (testing "it reads back as itself"
    (doseq [t ["| [c] alternative [a b] 3 repeat" "| [c2 b a] [a4] alternative [a b c d] 3 repeat" "| [c] [e] alternative"]]
      (let [shown (top t)] (is (= shown (top shown)) t))))
  (is (= "repeat volta 3 [B4/4 C#5] alternative [[D4] [F#4]]" (top "| [c] [e] alternative [a b] 3 repeat 2 +"))
      "+ reaches into its body and endings")
  (is (= "[[E4/4 repeat volta 2 [A3 B3] alternative [D4] C4]]" (str "[" (top "[c repeat volta 2 [a b] alternative [d] e] reverse") "]"))
      "an element of a line, kept whole")
  (is (= "[[A3/4 B3] [A3 B3]] [[A3/4 B3] [A3 B3]]" (str (top "[a b] 2 ** dup")))
      "** gives copies, repeat a repetition")
  (is (re-find #"alternative: needs a \| mark" (top "[c] alternative")))
  (is (re-find #"at least 1 pass" (top "[a b] 0 repeat"))))

(deftest repeat-with-a-quotation-writes-its-passes-out
  (is (= "[[A3/4 B3] [C4] [B3 A3] [C4]]" (top "| [c] alternative [a b] '( reverse ) 2 repeat"))
      "as written, then the quotation of the pass before, each followed by its ending")
  (is (= "[[A3/4 B3] [B3 C#4] [C#4 D#4]]" (top "[a b] '( 2 + ) 3 repeat")) "the changes add up; no endings")
  (let [shown (top "| [c2 b a] [a1] alternative [a4 b c d] '( shuffle ) 2 repeat")]
    (is (re-find #"\[C4/2 B3 A3\] .* \[A3/1\]\]$" shown) "the first ending, then the last")
    (is (= shown (top shown)) "reads back the same"))
  (is (re-find #"must leave one value a pass" (top "[a b] '( dup ) 2 repeat"))))

(deftest repeat-stores-as-parse-stores-it
  (swap! e/state assoc :stack [])
  (let [id (first (:ok (e/eval-input "[a b] 2 repeat R!")))]
    (is (re-matches #"s\d+" (name id)) "in a line of its own, as parse wraps a bare repeat")
    (is (= "[repeat volta 2 [A3/4 B3]]" (top (str id " R@"))))))

(deftest form-tools-keep-the-form
  (is (= "[C4/4 D4]" (top "[c d e f] 2 take")))
  (is (= "[E4/4 F4]" (top "[c d e f] 2 drop")))
  (is (= "[F4/4]" (top "[c d e f] 1 take-last")))
  (is (= "[F4/4 E4 D4 C4]" (top "[c d e f] reverse")) "retrograde")
  (is (= "[D4/4 E4 C4]" (top "[c d e] 1 rotate")))
  (is (= "[[C4/4 D4] [C4 D4]]" (top "[c d] 2 **")) "copies, each its own element")
  (is (= "[C4/4 C4 C4]" (top "c 3 **")) "a note's copies are notes")
  (is (= "'[1 2 1 2]" (top "'[1 2] 2 **")) "data stays flat")
  (is (= "[C4/4 D4 E4]" (top "[c d] [e] then")) "succession")
  (is (= "[C4/4 E4 D4 F4]" (top "[c d] [e f] interleave")))
  (is (= "[[C4/4 D4] [E4]]" (top "[c d e] 2 partition")))
  (is (= "[C4/4] [D4/4 E4]" (top "[c d e] 1 split-at")))
  (is (= "[!mf C4/4 D4]" (top "[!mf c d e] 2 take")) "leading instructions are kept")
  (is (= 4 (count (re-seq #"[A-G]" (top "[c d e f] shuffle")))) "shuffled, the same four"))

(deftest streams-and-endless
  (is (= "'[0 1 2]" (top "0 3 range")))
  (is (= "'[60 62 64]" (top "60 '[0 2 4] scale")))
  (is (= "'[1 3 5 7]" (top "1 '( 2 .+ ) iterate 4 take")))
  (is (= "[C4/4 D4 C4 D4 C4]" (top "[c d] cycle 5 take")) "a stream of leaves prints as a line")
  (testing "an endless stream prints its start"
    (is (str/ends-with? (top "[c d] cycle") "…]")))
  (testing "where all of a stream is needed, an endless one is refused"
    (is (re-find #"endless -- take some first" (top "[c d] cycle reverse")))
    (is (re-find #"endless" (top "[c d] cycle play")))))

(deftest assemble
  (is (= "[C4/4 D4 E4]" (top "| c4 d e seq")))
  (is (= "{[C4/4 D4] [E4]}" (top "| [c d] e4 par")))
  (is (= "[G3/4] [C4/4 D4]" (top "[g] | c d seq")) "only what lies above the mark")
  (is (= "[C4/4 [D4 E4]]" (top "| c | d e seq seq")) "nested marks, nearest first")
  (is (re-find #"seq: needs a \| mark" (top "c d seq")))
  (is (re-find #"has nothing above its \| mark" (top "c | seq"))))

(deftest a-mark-prints-as-a-bar
  (is (= "1 | 2" (top "1 | 2")))
  (is (= "<A3 C4 E4>4" (top "a c e <>")) "a chord from the leaves on top")
  (is (= "[G3/4] <C4 E4>4" (top "[g] c e <>")) "stopping at the first non-leaf")
  (is (= "[C4/4 D4/8 E4/4]" (top "'[/4 /8 /4] '[60 62 64] zip")))
  (is (= "[!pp [C4/4 D4]]" (top "[c d] ^{ !pp } with"))))

(deftest meaning-tools
  (is (= "[D4/4 E4]" (top "[c d] c d transpose")) "by the interval from C to D")
  (is (= "[C4/2 D4]" (top "[c d] 2 stretch"))))
