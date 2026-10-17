(ns ^:repl musics.mu.tools-test
  "mu's first tools: they keep the form (a line gives a line), endless
   streams are refused where all of a stream is needed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.domain.context :as c]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.print :as p]
            [musics.mu.tools]
            [musics.mu.value :as v]
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
    (is (= "repeat 3 [C4/4 D4]" (top "[c d] 3 [*]")) "no :ROOT stored yet")))

(deftest qualified-symbol-words
  (swap! e/state assoc :use ["kernel" "music"])
  (is (= "repeat 2 [C4/4 D4]" (top "[c d] 2 tool/[*]")))
  (is (re-find #"unknown word: \[\*\]" (top "[c d] 2 [*]")) "tool isn't in use here"))

(deftest tool-is-in-use-by-default
  (is (= ["kernel" "tool" "music" "play" "rate" "mine"] (:use (e/fresh-state)))))

(deftest bar-lines-are-elements
  (is (= "[C4/4 D4 | E4]" (top "| [c d | e] '( ) each seq")) "seq keeps a bar of a line's elements")
  (is (= "[F4/4 E4 | D4 C4]" (top "[c d | e f] retrograde")) "a tool keeps it in a line")
  (is (= "repeat 2 [3:1(C4/4 D4) |]" (top "[c d |] 1/3 * 2 [*]")) "and through * and [*]"))

(deftest times-and-repeat
  (is (= "repeat 3 [A3/4 B3]" (top "[a b] 3 times")) "3 times is the text's repeat 3 [a b]")
  (is (= "repeat 3 [A3/4 B3]" (top "[a b] 3 [*]")))
  (is (= "repeat 2 [C4/4]" (top "c 2 times")) "a note is repeated as a line")
  (is (= "repeat 3 [A3/4 B3]" (top "[a b] 3 repeat")) "repeat counts the plays, as the text and LilyPond do")
  (is (= (top "[a b] 3 times") (top "[a b] 3 repeat")) "the same as times")
  (is (re-find #"repeat: needs 1 or more times" (top "[a b] 0 repeat")))
  (is (= "[: A3/4 B3 :]" (top "[a b] repeats")) "repeats: in repeat signs, played twice")
  (is (= "repeat 3 [A3/4 B3] volta [C4]" (top "[a b] 3 times [c] volta"))
      "volta takes the endings above the repeat")
  (is (= "repeat 3 [A3/4 B3 C4 D4] volta [[C4/2 B3 A3] [A3/4]]"
         (top "[a b c d] 3 times [c2 b a] [a4] volta"))
      "several endings, grouped as LilyPond groups them")
  (is (= "[: C4/4 D4 :] volta [[E4] [F4]]" (top "[: c d :] [e] [f] volta")) "repeat signs take them too")
  (testing "it reads back as itself"
    (doseq [t ["[a b] 3 times [c] volta" "[a b c d] 3 times [c2 b a] [a4] volta" "[: c d :] [e] [f] volta"]]
      (let [shown (top t)] (is (= shown (top shown)) t))))
  (is (= "repeat 3 [B3/4 C#4] volta [[D4] [F#4]]" (top "[a b] 3 times [c] [e] volta 2 +"))
      "+ reaches into its body and endings")
  (is (= "[E4/4 repeat 2 [B3 A3] C4]" (top "[c repeat 2 [a b] e] retrograde"))
      "an element of a line, its body backwards")
  (is (re-find #"volta: needs a repeat below" (top "[c] volta")))
  (is (re-find #"volta: has nothing above the repeat" (top "[c] 2 times volta")))
  (is (re-find #"volta: the repeat has its endings already" (top "[c] 2 times [d] volta [e] volta")))
  (is (re-find #"needs 1 or more times" (top "[a b] 0 times"))))

(deftest vary-writes-the-passes-out
  (is (= "[[B3/4 A3] [C4] [B3 A3] [C4]]" (top "[a b] 2 times [c] volta '( retrograde ) vary"))
      "every pass the quotation of the body as written, each followed by its ending")
  (is (= "[[B3/4 C#4] [B3 C#4] [B3 C#4]]" (top "[a b] 3 times '( 2 + ) vary")) "no endings")
  (is (= "[[3:1(A3/4 B3)] [C4] [3:1(A3 B3)] [C4]]" (top "[a b] 2 times [c] volta '( 1/3 * ) vary"))
      "* too: each pass scaled, the endings not")
  (let [shown (top "[a b] 2 times [c] volta '( 1/3 * ) vary")]
    (is (= shown (top shown)) "reads back the same"))
  (let [shown (top "[a4 b c d] 2 times [c2 b a] [a1] volta '( shuffle ) vary")]
    (is (re-find #"\[C4/2 B3 A3\] .* \[A3/1\]\]$" shown) "the first ending, then the last")
    (is (= shown (top shown)) "reads back the same"))
  (is (= "[[C4/4 D4] [C5 D5] [C4/2 D4]]" (top "[c d] 3 times '( ) '( 12 + ) '( 2 * ) vary"))
      "one quotation a pass, in order")
  (is (re-find #"2 quotations for 3 passes" (top "[c d] 3 times '( ) '( 12 + ) vary")))
  (is (re-find #"takes quotations" (top "[c d] 2 times [e] vary")))
  (is (re-find #"must leave one value a pass" (top "[a b] 2 times '( dup ) vary"))))

(deftest repeat-stores-as-parse-stores-it
  (swap! e/state assoc :stack [])
  (let [id (first (:ok (e/eval-input "[a b] 2 times R!")))]
    (is (re-matches #"s\d+" (name id)) "in a line of its own, as parse wraps a bare repeat")
    (is (= "[repeat 2 [A3/4 B3]]" (top (str id " R@"))))))

(deftest form-tools-keep-the-form
  (is (= "[C4/4 D4]" (top "[c d e f] 2 take")))
  (is (= "[E4/4 F4]" (top "[c d e f] 2 drop")))
  (is (= "[F4/4]" (top "[c d e f] 1 take-last")))
  (is (= "[F4/4 E4 D4 C4]" (top "[c d e f] retrograde")) "retrograde")
  (is (= "[D4/4 E4 C4]" (top "[c d e] 1 rotate")))
  (is (= "[C4/4 D4 C4 D4]" (top "[c d] 2 [*] 4 take")) "a repeat's elements are what it plays")
  (is (= "'[1 2 1 2]" (top "'[1 2] 2 [*]")) "data is written out")
  (is (= "[C4/4 D4 E4]" (top "[c d] [e] then")) "succession")
  (is (= "[C4/4 E4 D4 F4]" (top "[c d] [e f] interleave")))
  (is (= "[[C4/4 D4] [E4]]" (top "[c d e] 2 partition")))
  (is (= "[C4/4] [D4/4 E4]" (top "[c d e] 1 split-at")))
  (is (= "[!mf C4/4 D4]" (top "[!mf c d e] 2 take")) "leading instructions are kept")
  (is (= 4 (count (re-seq #"[A-G]" (top "[c d e f] shuffle")))) "shuffled, the same four"))

(deftest streams-and-endless
  (is (= "'[0 1 2]" (top "0 3 range")))
  (is (= "'[60 62 64]" (top "60 '[0 2 4] scale")))
  (is (= "'[1 3 5 7]" (top "1 '( 2 add ) iterate 4 take")))
  (is (= "repeat ~ [C4/4 D4]" (top "[c d] cycle")) "cycle on music is the loop, [~]")
  (is (= "[C4/4 D4 C4 D4 C4]" (top "[c d] cycle 5 take")) "take from a loop as from a stream")
  (is (= "'[1 2 1 2 1]" (top "'[1 2] cycle 5 take")) "on data, an endless stream")
  (testing "an endless stream prints its start"
    (is (str/ends-with? (top "'[1 2] cycle") "…]")))
  (testing "where all of a stream is needed, an endless one is refused"
    (is (re-find #"endless -- take some first" (top "'[1 2] cycle retrograde")))
    (is (re-find #"endless" (top "'[1 2] cycle count")))))

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
  (is (= "[!pp C4/4 D4]" (top "[c d] ^{ !pp } with"))))

(deftest with-puts-the-settings-in-front
  (is (= "[!ff D4/4 E4 C4]" (top "[c d e] ^{ !ff } with 1 rotate")) "the line stays a line of notes")
  (is (= "[!tempo:60 !ff !tempo:90 C4/4 D4 E4]" (top "[!tempo:90 c d e] ^{ !tempo:60 !ff } with")))
  (swap! e/state assoc :stack [])
  (let [x   (first (:ok (e/eval-input "[!tempo:90 c d e] ^{ !tempo:60 !ff } with")))
        ctx (:context (v/node x))]
    (is (= 90 (c/ctx-value-chain [ctx] :Tempo 0)) "the line's own setting, after, still wins")
    (is (= 80.0 (double (c/ctx-value-chain [ctx] :volume 0))) "and the inserted !ff fills what it leaves open"))
  (is (= "[!ff <C4 E4 G4>4]" (top "<c e g>4 ^{ !ff } with")) "a note becomes a line")
  (is (= "[mel: !ff C4/4 D4]" (top "[mel: c d] ^{ !ff } with")) "a named line keeps its name")
  (is (= "[!ff {[C4/4 D4] [E4 F4]}]" (top "{[c d] [e f]} ^{ !ff } with")) "a { } is wrapped")
  (is (= "[!ff repeat 2 [C4/4 D4]]" (top "[c d] 2 times ^{ !ff } with")) "a repeat is wrapped"))

(deftest meaning-tools
  (is (= "[D4/4 E4]" (top "[c d] c d transpose")) "by the interval from C to D")
  (is (= "[C4/2 D4]" (top "[c d] 2 *"))))

(deftest every-sign-has-a-twin
  (doseq [[sign word input] [["[R]" "retrograde" "[c d e g]"] ["[I]" "inversion" "[c d e g]"]
                             ["[RI]" "retrograde-inversion" "[c d e g]"] ["[~]" "cycle" "[c d]"]
                             ["[:]" "repeats" "[c d]"] ["[!]" "unfold" "[c d] [:]"] ["[||]" "barred" "[c2 d2 e2]"]
                             ["[[]]" "measures" "[c2 d2 e2]"] ["[*]" "times" "[c d] 3"] ["[/]" "parts" "[c d e f] 2"]
                             ["<>" "chord" "c e g"] ["{+}" "beside" "{[c] [d]} [e]"] ["[+]" "then" "[c] [d]"]
                             ["+" "up" "[c d] 7"] ["-" "down" "[c d] 12"] ["*" "stretch" "[c d] 2"] ["/" "shrink" "[c d e] 3"]
                             ["[%]" "rotate" "[c d e] 1"] ["+" "up" "[c d] 7"] ["-" "down" "[c d] 12"]
                             ["*" "stretch" "[c d] 2"] ["/" "shrink" "[c d e] 3"] ["[M]" "mirror" "[c d e g]"]]]
    (is (= (top (str input " " sign)) (top (str input " " word))) word))
  (is (= (top "[c] [d] [e] [3]") (top "[c] [d] [e] 3 line")))
  (is (= (top "[c d e f] [H]") (top "[c d e f] head")))
  (is (= (top "[c d] :st R! R@") (top "[c d] :st store fetch")) "store, fetch")
  (is (= (top "[c d e f] 2 [T]") (top "[c d e f] 2 tail"))))

(deftest the-retrograde-plays-backwards
  (is (= "[E4/8 D4 C4~ C4/4]" (top "[c4~ c8 d e] [R]")) "a tie turns round: on the note now before its partner")
  (is (= "[F4/4\\volume:40.0 E4\\volume:50.0 D4\\volume:70 C4]" (top "[c !f d !vol< e f !pp] [R]"))
      "what the instructions change is written into the notes, so it sounds backwards")
  (is (= "[!mf D4/4 C4]" (top "[!mf c d] [R]")) "a setting from the start stays one")
  (is (re-find #"jumps can't be played backwards" (top "[c d $ e DC] [R]")))
  (is (= "[F4/4 [E4 D4] C4]" (top "[c [d e] f] [R]")) "a nested line too")
  (is (= "[A4/4 {[E4 D4] [G4 F4]} C4]" (top "[c {[d e] [f g]} a] [R]")) "a parallel's parts each")
  (is (= "repeat 2 [D4/4 C4]" (top "[c d] 2 times [R]")) "a repeat stays a repeat")
  (is (= "repeat ~ [D4/4 C4]" (top "[c d] [~] [R]")) "a loop too")
  (is (re-find #"volta endings can't be played backwards" (top "[c repeat 2 [d e] volta [f]] [R]"))))

(deftest a-natural-the-key-would-alter-prints-with-n
  (is (= "[!key:D.major Cn4/4 D4]" (top "[!key:D.major cn d]")) "so it reads back natural")
  (is (= "'[61 60 58 56]" (top "[!key:D.major c d e f] [I] pitches")) "the inversion keeps the natural it gives"))

(deftest shuffle-as-it-plays
  (is (= "shuffle [C4/4 D4 E4]" (top "[c d e] [?]")) "[?] on music: shuffle [ ], ordered as it plays")
  (is (= "repeat ~ [shuffle [C4/4 D4 E4]]" (top "[c d e] [?] [~]")) "so a loop shuffles every pass")
  (is (= "shuffle [C4/4 D4 E4]" (top "shuffle [c d e]")) "and it reads back")
  (is (= 3 (count (re-seq #"\d" (top "'[1 2 3] [?]")))) "data is shuffled now"))

(deftest choose-as-it-plays
  (is (= "choose [[C4/4 A#3 G#3 F3] [G4 E4 D4 C4]]" (top "[c d e g] '( [I] ) '( [R] ) [|]"))
      "[|]: the quotations make the alternatives, one played each time")
  (is (= "choose [[C4/4 D4] [E4 F4]]" (top "[[c d] [e f]] [|]")) "without them, x's elements")
  (is (= (top "[[c d] [e f]] [|]") (top "[[c d] [e f]] choosing")))
  (is (= "choose [[C4/4 D4] [E4 F4]]" (top "CHS [[c d] [e f]]")) "the text's choose, short CHS, reads back")
  (is (re-find #"nothing to choose from" (top "'[] [|]")))
  (is (= "choose 3,1 [[C4/4 D4] [E4 F4]]" (top "[[c d] [e f]] '[3 1] [|]")) "weights as data on top, one each")
  (is (re-find #"1 weights for 2 alternatives" (top "[[c d] [e f]] '[3] [|]")))
  (is (= "choose 1.5,1 [[C4/4] [D4]]" (top "choose 1.5,1 [[c] [d]]")) "the text's weights read back")
  (is (= "repeat ~ [alternate [[C4/4 A#3 G#3 F3] [G4 E4 D4 C4]]]" (top "[c d e g] '([I]) '([R]) [\\] [~]"))
      "[\\]: the alternatives in turn, one each pass")
  (is (= (top "[[c d] [e f]] [\\]") (top "[[c d] [e f]] alternating")))
  (is (= "alternate [[C4/4 D4] [E4 F4]]" (top "ALT [[c d] [e f]]")) "the text's alternate, short ALT"))

(deftest the-texts-words
  (is (= "[F4/4 G4]" (top "[c d] 5 transpose")) "transpose by semitones, as transpose 5 ( … )")
  (is (= "[D4/4 E4]" (top "[c d] c d transpose")) "or by the interval between two notes")
  (is (= "rotate 1 [C4/4 D4 E4]" (top "[c d e] 1 rotating")) "rotating: the text's rotate n [ … ]")
  (is (= "rotate 1 [C4/4 D4 E4]" (top "rotate 1 [c d e]")) "which reads back")
  (is (= (top "[c d e] [?]") (top "[c d e] shuffling")) "shuffling is [?]")
  (is (= "[F4/4 E4 D4 C4]" (top "[retrograde ( c d e f )]")) "the text's row forms inside a literal")
  (is (= (top "[c d] 5 transpose") (top "[c d] 5 TRP")) "TRP is transpose")
  (is (= (top "[c d e] 1 rotate") (top "[c d e] 1 ROT")) "ROT is rotate")
  (is (= "shuffle [C4/4 D4]" (top "SFL [c d]")) "SFL [ … ] reads as shuffle [ … ]")
  (is (= "[F4/4 G4]" (top "[TRP 5 ( c d )]")) "TRP in the text"))

(deftest head-and-tail
  (is (= "[C4/4]" (top "[c d e f] [H]")) "without a count, the first")
  (is (= "[D4/4 E4 F4]" (top "[c d e f] [T]")) "and the rest")
  (is (= "[C4/4 D4]" (top "[c d e f] 2 [H]")))
  (is (= "[E4/4 F4]" (top "[c d e f] 2 [T]")) "the same n splits the line in two")
  (is (= "'[1]" (top "'[1 2 3] [H]")) "numbers: the data is on top, no count")
  (is (= "'[3]" (top "'[1 2 3] 2 [T]")))
  (is (= (top "[c] [d] {2}") (top "[c] [d] 2 together"))))

(deftest row-forms
  (is (= "[G4/4 E4 D4 C4]" (top "[c4 d e g] [R]")) "the retrograde")
  (is (= "[r1]" (top "[ R ]")) "[ R ] is a rest")
  (is (= "[F#4/4 E4 D4 B3]" (top "[c4 d e g] [M]")) "[M]: around the mean pitch (63)")
  (is (= "[F#4/4 E4 D4 B3]" (top "[c4 d e g] mirror")))
  (is (= "[C4/4 A#3 G#3 F3]" (top "[c4 d e g] inversion")) "inversion is [I]")
  (is (= "[C4/4 A#3 G#3 F3]" (top "[c4 d e g] [I]")) "[I]: around the first note, which stays")
  (is (= "[F4/4 D#4 C#4 A#3]" (top "[c4 d e g] [I] 5 +")) "I5")
  (is (= "[F3/4 G#3 A#3 C4]" (top "[c4 d e g] [RI]")) "inverted around the first note, then backwards")
  (is (= "[C4/4 <G#3 F3> r A#3]" (top "[c4 <e g> r d] [I]")) "chords mirror, rests stay"))

(deftest bracketed-symbols-act-on-the-whole
  (is (= "[[C4/4 D4] [E4 F4]]" (top "[c d e f] 2 [/]")) "[/] splits into n parts")
  (is (= "[[C4/4 D4] [E4]]" (top "[c d e] 2 [/]")) "the longer part first")
  (is (re-find #"can't split 2 elements into 3 parts" (top "[c d] 3 [/]")))
  (is (= "[C4/4 D4 E4 F4]" (top "[c d] [e f] [+]")) "[+] joins two lines")
  (is (= "[D4/4 E4 F4 C4]" (top "[c d e f] 1 [%]")) "[%] rotates")
  (is (= #{"C4" "D4" "E4" "F4"} (set (re-seq #"[A-G]\d" (top "[c d e f] [?]")))) "[?] reorders, keeping every note"))

(deftest loop-and-together
  (is (= "repeat ~ [C4/4 D4]" (top "[c d] [~]")) "[~] is an endless repeat")
  (let [shown (top "[c d] [~]")] (is (= shown (top shown)) "it reads back as itself"))
  (is (re-find #"no endings" (top "[c d] [~] [e] volta")))
  (is (re-find #"can't be written out" (top "[c d] [~] '( 2 + ) vary")))
  (is (= "{[C4/4 D4] [E4 F4]}" (top "[c d] [e f] {2}")) "{2} puts the top two side by side")
  (is (= "{[C4/4] [E4] [G4]}" (top "[c] [e] [g] {3}")) "{3} the top three")
  (is (= "[C4/4]" (top "[c] [e] [g] {2} pop")) "only the top n")
  (is (re-find #"\{4\}: needs 4" (top "[c] [e] {4}")))
  (is (re-find #"\{1\}: takes 2 or more" (top "[c] {1}")))
  (is (= "[[C4/4 D4] [E4 F4]]" (top "[c d] [e f] [2]")) "[2] the top two, one after another")
  (is (= "[[C4/4] [D4] [E4]]" (top "[c] [d] [e] [3]")))
  (is (re-find #"\[1\]: takes 2 or more" (top "[c] [1]")))
  (is (= "{[C4/4] [D4] [E4] [F4]}" (top "{[c] [d]} {[e] [f]} {+}")) "{+} joins the parts of two parallels")
  (is (= "{[C4/4 D4] [E4] [F4]}" (top "[c d] {[e] [f]} {+}")) "a line is one part")
  (is (= "{!ff [C4/4] [D4] [E4] [F4]}" (top "{!ff [c] [d]} {[e] [f]} {+}")) "the first keeps its settings"))

(deftest repeat-signs-written-out-and-barred
  (is (= "[: C4/4 D4 :]" (top "[c d] [:]")) "[:] puts the whole in repeat signs")
  (is (= "[[C4/4 D4] [E4] [C4 D4] [F4]]" (top "[c4 d] 2 times [e] [f] volta [!]")) "[!] writes the passes out")
  (is (= "[C4/4 D4 E4 D4 E4 F4]" (top "[c4 $ d e DS f] [!]")) "jumps followed")
  (is (= "[C4/4 D4 | C4 D4 | E4]" (top "[c4 d | ./. | e] [!]")) "./. written out")
  (is (re-find #"endless repeat can't be written out" (top "[c d] [~] [!]")))
  (is (= "[C4/4 D4 E4 F4 | G4 A4 B4 C5]" (top "[c4 d e f g a b c] [||]")) "[||] bars by the meter, 4/4 by default")
  (is (= "[!Meter:3/4 C4/4 D4 E4 | F4 G4 A4]" (top "[!Meter:3/4 c4 d e f g a] [||]")) "its own meter")
  (is (= "[C4/2. D4/4~ | D4]" (top "[c2. d2] [||]")) "a note across the bar line cut and tied")
  (is (= "[x2.\\38 x4~ | x]" (top "[x2.\\38 x2] [||]")) "a drum too: struck once")
  (is (= "[C4/2. D4/4~ | D4/2.]" (top "[c2. d1] [||]")) "the rest of it carries on")
  (is (= "[r2. r4 | r2]" (top "[r2. r2.] [||]")) "a rest is cut, not tied")
  (is (= "[C4/8 D4 E4 F4 G4 A4 B4 C5 | D5 E5 F5 G5]" (top "[c8 d e f | g a b c d e f g] [||]")) "its own bar lines replaced"))

(deftest sounds-goes-through-drums-and-instruments
  (is (= "[x8\\35 x\\36 x\\37]" (top "x8 35 38 range sounds")) "a drum's key")
  (is (= "[x8\\35 x\\36 x\\37]" (top "x8 35 38 .. sounds")) ".. is range")
  (is (= "[C4/4\\instrument:0 C4\\instrument:40]" (top "c4 '[0 40] sounds")) "a note's instrument")
  (is (= "[<C4 E4 G4>2\\instrument:3 <C4 E4 G4>\\instrument:4]" (top "<c e g>2 3 5 range sounds")) "a chord's")
  (is (= "repeat ~ [x4\\36 x\\38]" (top "x4 '[36 38] sounds [~]")) "a line like any other")
  (is (re-find #"sounds: needs a note, chord or drum" (top "r4 '[1 2] sounds")))
  (is (re-find #"sounds: needs whole numbers 0-127" (top "c4 '[128] sounds")))
  (is (re-find #"endless" (top "c4 '[1] cycle sounds"))))

(deftest a-key-is-a-value
  (is (= "D.major" (top "D.major")) "a key literal prints back as written")
  (is (= "B&.minor" (top "b&.minor")) "# sharp, & flat; the tonic's case doesn't matter")
  (is (= "D4/4" (top "D")) "without a scale, D is the note")
  (is (re-find #"D.blah names no key" (top "D.blah")))
  (is (= "[D4/4 E4 F#4 G4 A4 B4 C#5]" (top "D.major scale")) "scale: one octave from the tonic")
  (is (= "'[60 64 67]" (top "60 '[0 4 7] scale")) "scale on numbers as before")
  (is (= "[D4/4 F#4 A4 D5]" (top "'[0 2 4 7] D.major degrees")) "degrees: scale steps from the tonic")
  (is (= "[A3/4 B3 C#4 D4]" (top "-3 1 .. D.major degrees")) "below the tonic")
  (is (= "D4/4" (top "D.major tonic")))
  (is (= "E.major" (top "D.major 2 +")) "+ moves a key")
  (is (= "G.major" (top "B&.major 3 -")))
  (is (= "A.minor" (top "[!key:A.minor a b c] key")) "a line's own key")
  (is (= "C.major" (top "[c d e] key")) "else the defaults'")
  (is (= "C.major" (top "c4 key")) "the key a note was written in")
  (is (= "[!key:G.major C4/4 D4 E4 F#4 G4]" (top "[c d e f g] G.major with")) "with plays a line in a key"))

(deftest every-key-a-tonic-can-name
  (doseq [k ["C#.major" "C&.major" "G#.minor" "D#.minor" "A#.minor"]]
    (is (= k (top k)) k))
  (is (= "[C#4/4 D#4 E#4 F#4 G#4 A#4 B#4]" (top "C#.major scale")) "E# and B#")
  (is (= "G#.minor" (top "A.minor 11 +")) "a key moved takes the spelling with fewer accidentals")
  (is (= "D&.major" (top "C.major 1 +")))
  (is (= "A&.dorian" (top "B&.dorian 2 -")) "a tie keeps the circle's spelling"))

(deftest readers-and-access
  (is (= "70" (top "c4\\f volume")))
  (is (= "nil" (top "c4 volume")) "nil without its own")
  (is (= ":accent" (top "c4-> articulation")))
  (is (= ":ghost" (top "x8\\38\\ghost articulation")) "a drum's, by its dynamic")
  (is (= "40" (top "c4\\i:40 instrument")))
  (is (= "38" (top "x8\\38 instrument")) "a drum's key")
  (is (= "60" (top "c4 pitch")))
  (is (= "'[60 64 67]" (top "<c e g>4 pitch")) "a chord's pitches, lowest first")
  (is (= "nil" (top "r4 pitch")))
  (is (= "3/16" (top "c8. duration")))
  (is (= "90" (top "c4\\vol:90 volume")))
  (is (= "true true true true" (top "c4 note? <c e>4 chord? r4 rest? x drum?")))
  (is (re-find #"pitch: reads one note; for every note of a line, use pitches" (top "[c d] pitch")))
  (is (re-find #"note\?: reads one note; for every note of a line, use '\( note\? \) map" (top "[c d] note?")))
  (is (re-find #"pitch: reads a note" (top "5 pitch")))
  (is (= "C4/4 E4/4 D4/4 3" (top "[c d e] first [c d e] last [c d e] 1 nth [c d e] count")))
  (is (re-find #"nth: 5 is past the end" (top "[c d e] 5 nth")))
  (is (= "C4/4" (top "[c d] cycle first")) "first of an endless stream")
  (is (re-find #"endless" (top "[c d] cycle count"))))

(deftest pitches-print-as-written-or-by-key
  (is (= "[B&3/4 C4 E&4 F#4]" (top "[b& c e& f#]")) "as written")
  (is (= "[!key:F.major B&3/4 C4]" (top "[!key:F.major b c]")) "as the key implies")
  (is (= "[B&4/4 C5 D&5 E&5 F5 G&5 A&5]" (top "B&.minor scale")))
  (is (= "[C&4/4 B#3]" (top "[C&4 B#3]")) "the octave is the letter's")
  (is (= "<B&3 D4 F4>4" (top "<b& d f>4")) "a chord's pitches as written")
  (is (= "<G&4 B&4 D&5>2" (top "<e& g b&>2 3 +")) "moved, it keeps to flats")
  (is (= "[C#4/4 D#4 F4]" (top "[c d e] 1 +")) "else sharps"))

(deftest selection-by-a-quotation
  (is (= "[C4/4 E4]" (top "[c e g c'] '( pitch 65 lt ) filter")))
  (is (= "[G4/4 C6]" (top "[c e g c'] '( pitch 65 lt ) remove")) "the later notes move earlier")
  (is (= "[C4/4 E4 |]" (top "[c e | g c'] '( pitch 65 lt ) filter")) "a bar line stays")
  (is (= "[C4/4 E4]" (top "[c e g c'] '( pitch 66 lt ) take-while")))
  (is (= "[G4/4 C6]" (top "[c e g c'] '( pitch 66 lt ) drop-while")))
  (is (= "'[3 4]" (top "'[1 2 3 4] '( 2 gt ) filter")) "numbers too")
  (is (re-find #"one value" (top "[c e] '( dup ) filter")))
  (is (re-find #"quotation" (top "[c e] 3 filter"))))

(deftest mute-keeps-the-rhythm
  (is (= "[C4/4 E4 r r]" (top "[c e g c'] '( pitch 65 gt ) mute")))
  (is (= "[C4/8 r4 r8]" (top "[c8 e4 r8] '( pitch 62 gt ) mute")) "a rest stays a rest, untested")
  (is (= "[[C4/4 r] r]" (top "[[c e] g] '( pitch 62 gt ) mute")) "at any depth"))

(deftest distinct-dedupe-and-chance
  (is (= "[C4/4 E4]" (top "[c c e c e] distinct")))
  (is (= "[C4/4 E4 C4]" (top "[c c e e c] dedupe")))
  (is (= "'[1 2 3]" (top "'[1 2 2 3 1] distinct")))
  (is (= "D4/4" (top "[c d e] '[0 1 0] select")))
  (is (re-find #"as many weights" (top "[c d e] '[1 0] select")))
  (is (= 2 (count (str/split (subs (top "[c d e] 2 choose-n") 1) #" "))))
  (is (= "'[1 1 1]" (top "'[1 1 1] choose-from"))))

(deftest map-each-element
  (is (= "[C5/4 E5 G5]" (top "[c e g] '( 12 + ) map")) "notes give the line back")
  (is (= "'[60 64 67]" (top "[c e g] '( pitch ) map")) "anything else a stream")
  (is (= "'[10 20]" (top "'[1 2] '( 10 mul ) map"))))

(deftest a-line-taken-apart-and-zipped-again
  (is (= "'[1/8 1/4 1/8 1/8]" (top "[c8 <e g>4 r8 d] durations")))
  (is (= "'[60 '[64 67] nil 62]" (top "[c8 <e g>4 r8 d] pitches")))
  (is (= "'[nil 90 nil]" (top "[c d\\vol:90 e] volumes")))
  (is (= "'[nil :accent]" (top "[c d->] articulations")))
  (is (= "'[40 38]" (top "[c\\i:40 x\\38] instruments")))
  (is (= "[C4/8 <E4 G4>4 r8 D4]" (top "[c8 <e g>4 r8 d] dup durations swap pitches zip")) "x durations x pitches zip is x")
  (is (= "'[60]" (top "c pitches")) "one note: a stream of one"))

(deftest a-chunk-out-changed-and-back
  (is (= "[D4/4 E4]" (top "[c d e f g] 1 3 slice")) "start up to, not including, end")
  (is (= "[C4/4 A4 B4 C5 F4 G4]" (top "[c d e f g] 1 3 [a b c] splice")) "the chunk's length may change")
  (is (= "[C4/4 F4 E4 D4 G4]" (top "[c d e f g] 1 4 '( retrograde ) within")))
  (is (= "[C4/4 D5 E5 F4 G4]" (top "[c d e f g] 1 3 '( 12 + ) within")))
  (is (= "[C4/4 D4 r r G4]" (top "[c d e f g] 2 4 '( '( pitch 0 gt ) mute ) within")) "a quotation inside")
  (is (= "[D4/4 C4 E4]" (top "[c d e] 0 2 '( retrograde ) within")) "from the start")
  (is (re-find #"0 <= start < end" (top "[c d e] 1 1 '( retrograde ) within")) "a chunk holds something")
  (is (= "[C4/4 A4 D4 E4]" (top "[c d e] 1 1 [a] splice")) "start = end inserts")
  (is (= "'[1 30 20 4]" (top "'[1 2 3 4] 1 3 '( '( 10 mul ) map retrograde ) within")) "numbers too")
  (is (re-find #"0 <= start < end <= 3" (top "[c d e] 2 5 slice")))
  (is (re-find #"one value" (top "[c d e] 0 2 '( dup ) within"))))

(deftest measures-split-a-line-by-its-meter
  (is (= "[[C4/2 D4] [E4]]" (top "[c2 d2 e2] [[]]")))
  (is (= "[!Meter:3/4 [C4/2 D4/4~] [D4 E4/2]]" (top "[!Meter:3/4 c2 d2 e2] [[]]")) "a note across a bar line cut and tied")
  (is (= "[[C4/4 D4 E4 F4] [G4 A4 B4 C5]]" (top "[c4 d e f | g a b C5] [[]]")) "its own bar lines replaced")
  (is (= "[!pp [C4/2 D4] !ff [E4 F4]]" (top "[!pp c2 d2 !ff e2 f2] [[]]")) "a setting before a measure goes in front of it")
  (is (= "[[C4/2 D4/4 !ff E4] !ff [F4/1] [G4/2]]" (top "[c2 d4 !ff e4 f1 g2] [[]]")) "one inside a measure carries on after it")
  (is (= "[[x4\\36 x x x] [x x]]" (top "[x4\\36 x x x x x] [[]]")) "drums too"))

(deftest a-setting-at-a-bar-line-opens-the-next-bar
  (is (= "[!pp C4/2 D4 | !ff E4 F4]" (top "[!pp c2 d2 !ff e2 f2] [||]"))))
