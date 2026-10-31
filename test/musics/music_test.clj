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

(deftest time-gives-access-to-a-line
  (testing "length, in whole notes as written"
    (is (= 1 (mu "[c4 d4 e2] length")))
    (is (= 3/8 (mu "[c8 d e] length")))
    (is (= 1/2 (mu "{[c4 d4] [e2]} length")) "a parallel is its longest part")
    (is (= 3/2 (mu "[c4 d4] 3 [*] length")) "a repeat written out")
    (is (= 1 (mu "[c2 d2 e2 f2] 1 bar length"))))
  (testing "at: the note sounding t whole notes in"
    (is (= "C4/4" (run "[c4 d4 e2] 0 at")))
    (is (= "D4/4" (run "[c4 d4 e2] 1/4 at")))
    (is (= "D4/4" (run "[c4 d4 e2] 3/8 at")) "a note sounds until the next starts")
    (is (= "E4/2" (run "[c4 d4 e2] 0.5 at")) "a float is read as the ratio it names")
    (is (= "E4/2" (run "[[c4 d4] [e2]] 3/4 at")) "into nested lines")
    (is (= "r4" (run "[c4 r4 e2] 1/4 at")) "a rest too")
    (is (thrown-with-msg? Exception #"past the end" (e/run-text [] "[c4 d4 e2] 1 at")))
    (is (thrown-with-msg? Exception #"time from 0" (e/run-text [] "[c4 d4 e2] -1 at")))
    (is (thrown-with-msg? Exception #"not parallel" (e/run-text [] "{[c4 d4] [e2]} 0 at"))))
  (testing "bar and bars, counted from 0 as nth counts"
    (is (= "[C4/2 D4]" (run "[c2 d2 e2 f2] 0 bar")))
    (is (= "[E4/2 F4]" (run "[c2 d2 e2 f2] 1 bar")))
    (is (= "[!Meter:3/4 D4/4 E4/2]" (run "[!Meter:3/4 c2 d2 e2 f2] 1 bar")) "by the line's own meter, a note across cut and tied")
    (is (= "[E4/2 F4 C4 D4]" (run "[c2 d2 e2 f2 c2 d2] 1 3 bars")) "bars 1 and 2, one line")
    (is (= "[!mf C4/2 D4 !pp E4 F4]" (run "[!mf c2 d2 !pp e2 f2] 0 2 bars")) "settings stay where they begin")
    (is (= "[!mf !pp E4/2 F4]" (run "[!mf c2 d2 !pp e2 f2] 1 bar")) "and those in effect before it come first")
    (is (thrown-with-msg? Exception #"0 <= start <= end <= 2" (e/run-text [] "[c2 d2 e2 f2] 2 bar")))
    (is (thrown-with-msg? Exception #"0 <= start <= end <= 2" (e/run-text [] "[c2 d2 e2 f2] 1 3 bars")))
    (is (thrown-with-msg? Exception #"at least one bar" (e/run-text [] "[c2 d2 e2 f2] 1 1 bars")))))

(deftest time-words-refuse-an-endless-loop
  (is (thrown-with-msg? Exception #"endless" (e/run-text [] "[c d] [~] length")) "a loop has no length -- it must not run forever")
  (is (thrown-with-msg? Exception #"endless" (e/run-text [] "[c d] [~] 1 at"))))

(deftest delaying-a-line-and-the-parts-of-a-parallel
  (testing "delay: a rest of any rational length in front"
    (is (= "[r4 C4 D4]" (run "[c d] 1/4 delay")) "a duration is written where it changes")
    (is (= "[r4. C4/4 D4]" (run "[c d] 3/8 delay")) "a dotted value")
    (is (= "[3:1(r1) C4/4 D4]" (run "[c d] 1/3 delay")) "a length that is no note value, as a tuplet")
    (is (= "[!mf r4 C4 D4]" (run "[!mf c d] 1/4 delay")) "the line's own settings stay first")
    (is (= "[r4 C4]" (run "c 1/4 delay")) "a note becomes a line")
    (is (= 3/4 (mu "[c d] 1/4 delay length")))
    (is (= 5/6 (mu "[c d] 1/3 delay length")) "a third of a whole note, exactly")
    (is (= "C4/4" (run "{[c d] [c d]} 1/4 delays 1 @ 1/4 at")) "the second part sounds from 1/4")
    (is (= "[r4 C4 D4]" (run "[c d] 0.25 delay")) "a float is read as the ratio it names")
    (is (thrown-with-msg? Exception #"above 0" (e/run-text [] "[c d] 0 delay")))
    (is (thrown-with-msg? Exception #"use delays" (e/run-text [] "{[c d] [e f]} 1/4 delay")))
    (is (thrown-with-msg? Exception #"needs a line" (e/run-text [] "7 1/4 delay"))))
  (testing "delays: the parts of a { }"
    (is (= "{[C4/4 D4] [r C4 D4] [r2 C4/4 D4]}" (run "{[c d] [c d] [c d]} 1/4 delays")) "part k by k times the step")
    (is (= "{[C4/4 D4] [3:1(r1) C4/4 D4]}" (run "{[c d] [c d]} '[0/1 1/3] delays")) "a list: each its own, 0 leaves one alone")
    (is (= "{[C4/4 D4] [C4 D4]}" (run "{[c d] [c d]} 0 delays")) "a step of 0 changes nothing")
    (is (= 1 (mu "{[c d] [c d] [c d]} 1/4 delays length")) "a parallel ends with its longest part: 1/2 + 2 * 1/4")
    (is (thrown-with-msg? Exception #"one delay for each of the 2 parts, got 3" (e/run-text [] "{[c d] [c d]} '[0/1 1/4 1/2] delays")))
    (is (thrown-with-msg? Exception #"needs a parallel" (e/run-text [] "[c d] 1/4 delays")))
    (is (thrown-with-msg? Exception #"from 0" (e/run-text [] "{[c d] [c d]} -1/4 delays")))))

(deftest a-ramp-is-read-as-a-plain-number-and-a-meter-as-itself
  (is (= "60" (run "[!vol:30< c4 d4 e4 f4 !vol:90] 2 @ !volume")) "an interpolated 60, not 60N")
  (is (= "75" (run "[!vol:30< c4 d4 e4 f4 !vol:90] 3 @ !volume")))
  (is (= "60" (run "[!vol:30< c4 d4 e4 f4 !vol:90] 1/2 at !volume")) "volume at a time: at, then !volume")
  (is (= "3/4" (run "[!M:3/4 c d] !Meter")) "a meter prints as it is written")
  (is (= "7/8(2+2+3)" (run "[!Meter:\"7/8(2+2+3)\" c d] !Meter"))))
