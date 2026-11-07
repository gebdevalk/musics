(ns ^:repl musics.mu.kernel-test
  "mu's kernel words: printing, numbers, control, bindings, the repo,
   playback (on a silent engine), namespaces, help, files, defaults."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.events :as ev] [musics.mu.store :as store] [musics.domain.resolve :as r]
            [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.conductor :as conductor]
            [musics.mu.eval :as e]
            [musics.mu.inputs :as inputs]
            [musics.mu.kernel :as kernel]
            [musics.mu.library]
            [musics.mu.tools]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh
  "A fresh registry with a :ROOT and a mu state with only the words of
   the namespaces musics.mu.kernel defines in (kernel, play, music); mu's
   state is restored afterwards."
  [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "play" "music" "tool"])))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- run [& inputs]
  (swap! e/state assoc :stack [])
  (last (mapv e/eval-input inputs)))

(defn- out [& inputs] (with-out-str (apply run inputs)))

(deftest printing
  (is (= "C4/4\n" (out "c4 .")))
  (is (= "0> [C4/4 D4]\n1> 7\n----\n" (out "7 [c d] .s")) "top first, numbered as pick counts, ---- under the last")
  (is (= "" (out ".s")) "an empty stack shows nothing")
  (reset! kernel/edit-text nil)
  (is (= "0> 7\n----\n" (out "7 [c d] , .s")) ", prints nothing and takes the top off the stack")
  (is (= "[C4/4 D4]" @kernel/edit-text) ", leaves the top's text for the next prompt"))

(deftest spreading
  (is (= ["C4/4" "D4/4" "E4/4"] (map p/show (:ok (run "[c d e] spread")))) "a seq's notes, one an item")
  (is (= ["[C4/4 D4]" "[E4/4]"] (map p/show (:ok (run "{[c d] [e]} **")))) "** is spread: a parallel's parts")
  (is (= [1 2 3] (:ok (run "'[1 2 3] spread"))) "data's values")
  (is (= [7 1 2] (:ok (run "7 '[1 2] **"))) "what lies under stays")
  (is (= ["C4/4" "D4/4" "E4/4"] (map p/show (:ok (run "[c d | e] spread")))) "bar lines left out")
  (is (= ["C4/4" "D4/4"] (map p/show (:ok (run "[c $ d DS] spread")))) "jump marks left out")
  (is (re-find #"spread: .*endless" (:error (run "'[1 2] cycle spread"))) "an endless stream refused")
  (is (re-find #"^spread: needs a seq, a list or a stream" (:error (run "7 spread"))) "an error named by the word"))

(deftest numbers
  (is (= [3 1/4 6 1/2 2 -3 3 2 4 3 0.75 3/4]
         (:ok (run "1 2 +  1/2 1/4 -  2 3 *  1 2 /  9 7 mod  3 negate  -3 abs  2 3 min  4 3 max  7/2 int  3/4 float  0.75 rational"))))
  (is (= [3] (:ok (run "2.7 round"))))
  (is (re-find #"\+: needs two numbers" (:error (run "1 \"x\" +"))))
  (is (re-find #"/: division by zero" (:error (run "1 0 /"))))
  (is (= [true] (:ok (run "1 1.5 <")))))

(deftest comparisons-and-control
  (is (= [true false true true] (:ok (run "1 2 <  1 2 =  1 1 =  2 2 >="))))
  (is (= [true false] (:ok (run "1 2 <>  \"a\" \"a\" <>"))) "<> on any values")
  (is (= [true true false true] (:ok (run "[c d e] [c d e] =  c4 c4 =  [c d e] [c d f] =  [c d e] [c d f] <>")))
      "music is equal when it has the same notes")
  (is (= [true] (:ok (run "[c d e] [C4 D4 E4] ="))) "however they are written")
  (is (= [true] (:ok (run "[zz1: c d] [c d] ="))) "whatever they are called")
  (is (= [true false] (:ok (run "'[1 2] [~] '[1 2] [~] =  '[1 2] [~] '[1 3] [~] ="))) "loops by what they repeat")
  (is (= [:yes] (:ok (run "1 2 < '( :yes ) '( :no ) if"))))
  (is (= [:no] (:ok (run "2 1 < '( :yes ) '( :no ) if"))))
  (is (= [5] (:ok (run "5 1 2 > '( drop ) when"))) "when false: nothing")
  (is (= ["C4/4" "D4/4" "E4/4"] (mapv p/show (:ok (run "[c d e] '( ) each")))) "each: every element")
  (is (= [6] (:ok (run "'[1 2 3] 0 '( + ) reduce")))))

(deftest comparison-as-forth-writes-it
  (is (= [true true true true true true]
         (:ok (run "1 2 <  2 1 >  1 1 <=  2 2 >=  1 2 <>  3 3 ="))))
  (is (= [true true true] (:ok (run "1 1.0 =  1/2 0.5 =  /4 /8 >"))) "numbers by value, a duration is a ratio")
  (is (= [true] (:ok (run "'[1 2] '[1.0 2.0] ="))) "element by element, by value")
  (testing "the order signs take numbers only, and say how to play"
    (is (re-find #"^<: needs two numbers, got \"a\" and \"b\"" (:error (run "\"a\" \"b\" <"))))
    (is (re-find #"^>: needs 2 items on the stack, has 1 -- to play, write ->" (:error (run "[c d] >"))))
    (is (re-find #"^>: needs two numbers, got \[C4/4 D4\] and 5 -- to order by something, give a key: c d '\( pitch \) >; to play, write ->" (:error (run "[c d] 5 >"))))
    (is (re-find #"^<: needs 2 items on the stack, has 1 -- what a voice plays is <-" (:error (run ":bass <")))))
  (testing "the old play signs say what replaced them"
    (is (re-find #"^>>: isn't a word; write -|>" (:error (run "[c] >>"))))
    (is (re-find #"^<<: isn't a word; write <<-" (:error (run "<<"))))
    (is (re-find #"^>\+: isn't a word; write ->\+" (:error (run "[c] >+")))))
  (testing "the names are gone"
    (doseq [w ["lt" "gt" "le" "ge" "eq" "ne" "neg" "add" "sub" "mul" "div"]]
      (is (re-find #"unknown word" (:error (run (str "1 2 " w)))) w))))

(deftest flags
  (is (= [true false] (:ok (run "true false"))))
  (is (= [false true false true] (:ok (run "true true xor  true false xor  false false or  true true and"))))
  (is (= [true false] (:ok (run "1 2 < 3 4 < and  1 2 < 4 3 < and"))) "two conditions joined")
  (is (= [false true] (:ok (run "true not  false not"))))
  (testing "a flag is true or false and nothing else"
    (is (re-find #"^not: needs a flag \(true or false\), got 0" (:error (run "0 not"))))
    (is (re-find #"^and: needs a flag \(true or false\), got 1" (:error (run "true 1 and"))))
    (is (re-find #"^if: needs a flag" (:error (run "1 '( :a ) '( :b ) if"))))
    (is (re-find #"^when: needs a flag" (:error (run "5 '( 1 ) when"))))))

(deftest forth-stack-words
  (is (= [1 2 1 2] (:ok (run "1 2 2dup"))))
  (is (= [1] (:ok (run "1 2 3 2drop"))))
  (is (= [3 1 2] (:ok (run "1 2 3 -rot"))) "the top goes third; rot goes the other way")
  (is (= [2 3 1] (:ok (run "1 2 3 rot"))))
  (is (= [8 6] (:ok (run "7 1+  7 1-"))))
  (is (= ["'[61 63]"] (mapv p/show (:ok (run "'[60 62] 1+")))) "a list too")
  (is (= ["[!K:D&.major D&4/4]"] (mapv p/show (:ok (run "[c] 1+")))) "music a semitone")
  (is (re-find #"^1\+: needs 1 item" (:error (run "1+")))))

(deftest quoting-and-executing-words
  (is (= [5 5] (:ok (run "5 'dup execute"))))
  (is (= ["[!K:D.major D4/4 E4]"] (mapv p/show (:ok (run "[c d] 2 '+ execute")))) "a symbol word")
  (is (= [3 3] (:ok (run ": twice dup ;" "3 'twice execute"))) "a word of mine")
  (is (= [6] (:ok (run "'w '* :=" "2 3 \\w execute"))) "bound, and executed later")
  (is (= [5 5] (:ok (run "5 '( dup ) execute"))) "a quotation, as call")
  (is (re-find #"unknown word: nope" (:error (run "'nope execute"))))
  (is (re-find #"execute: needs a quoted word" (:error (run "7 execute")))))

(deftest bindings
  (is (= [7 7] (:ok (run "'shift 7 :=" "\\shift \\shift"))))
  (is (= ["'shift"] (mapv p/show (:ok (run "'shift")))) "a name, held")
  (testing "music bound to a name goes in a literal, as a text variable does"
    (run "'m [!mf c d e] :=")
    (is (= "[!mf C4/4 D4 E4 F4]" (p/show (first (:ok (run "[\\m f]"))))))
    (run "'k c4 :=")
    (is (= "[C4/4 C4 E4]" (p/show (first (:ok (run "[\\k \\k e]"))))))
    (run "'n {[c d] [e f]} :=")
    (is (= "[{[C4/4 D4] [E4 F4]} G3]" (p/show (first (:ok (run "[\\n g]"))))))
    (is (:ok (run "[\\n g] :both R!")))
    (is (= :PAR (:type (repo/current (first (:children (repo/current :both))))))
        "stored with what it binds"))
  (testing "a rebinding is seen from then on"
    (run "'m [g a] :=")
    (is (= "[G3/4 A3 B3]" (p/show (first (:ok (run "[\\m b]")))))))
  (is (re-find #"nothing is bound to zz" (:error (run "\\zz"))))
  (is (re-find #"nothing is bound to zz" (:error (run "[\\zz]"))))
  (is (re-find #"holds 7 -- only music" (:error (run "'q 7 :=" "[\\q]"))))
  (is (= "[C4/4 D4 E4]" (p/show (peek (:ok (run "'m [c d] := [\\m e]"))))) "bound, then used, in one input")
  (is (re-find #"bound by the := it comes before" (:error (run "[\\fresh e] 'fresh [c d] :="))))
  (is (re-find #":=: needs a name under the value" (:error (run "3 4 :=")))))

(deftest storing-and-fetching
  (testing "an explicit name"
    (is (= "" (out "[c4 d e] :motif R!")))
    (is (= "[motif: C4/4 D4 E4]" (p/show (first (:ok (run ":motif R@"))))) "fetched, with its id")
    (is (some #{:motif} (:children (repo/current :ROOT))) "a top-level part"))
  (testing "the literal's own id"
    (is (= "" (out "[hook: g a] R!"))))
  (testing "an explicit name wins over the literal's own"
    (is (= "" (out "[hook: g a] :theme R!")))
    (is (repo/current :theme)))
  (testing "an auto id"
    (is (re-matches #":s\d+" (str (first (:ok (run "[e f] R!")))))))
  (testing "a leaf is stored as a one-leaf seq"
    (is (= "" (out "c4 :one R!")))
    (is (= "[one: C4/4]" (p/show (first (:ok (run ":one R@")))))))
  (testing "R! leaves the id on the stack"
    (is (= [:motif] (:ok (run "[c4 d e] :motif R!"))))
    (is (= "[C4/4 D4]" (p/show (first (:ok (run "[c d] R! R@"))))) "so it can be fetched")
    (is (= [:p] (:ok (run "'id [x: c] :p R! :=" "\\id"))) "or bound"))
  (testing "nothing is stored without R!"
    (let [before (set (keys @(repo/registry)))]
      (run "[f g a] [motif2: c d] dup")
      (is (= before (set (keys @(repo/registry)))))))
  (is (re-find #"nothing is stored as :nope" (:error (run ":nope R@")))))

(deftest playing-stores-nothing
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (let [before (set (keys @(repo/registry)))]
        (is (:ok (run "[c4 d e] play")))
        (is (= 1 (count @(:voices engine/*engine*))) "a voice started")
        (is (:ok (run "[c4 d] ^{ !tempo:90 } play")) "with a context")
        (is (= before (set (keys @(repo/registry)))) "nothing stored")
        (is (:ok (run "[e f] play+")) "play+ joins")
        (is (= 2 (count @(:voices engine/*engine*))) "the earlier voice keeps playing")
        (is (:ok (run "[g] ->+")) "->+ is play+")
        (is (= 3 (count @(:voices engine/*engine*))))
        (is (:ok (run "[g] ->")) "-> is play")
        (is (= 1 (count @(:voices engine/*engine*))) "play stops everything first")
        (run "pause")
        (is (engine/paused?) "pause pauses what plays")
        (run "pause")
        (is (engine/playing?) "and resumes it")
        (engine/stop!)
        (is (= "nothing plays\n" (out "pause")) "with nothing playing it says so")))))

(deftest require-loads-clojure-words
  (is (= "musics.algo.numeric.integer as integer/ (3 words)\n" (out "\"musics.algo.numeric.integer\" require")))
  (is (= [6] (:ok (run "12 18 integer/gcd"))) "under its last name")
  (out "\"musics.algo.numeric.integer\" 'n require")
  (is (= [12] (:ok (run "4 6 n/lcm"))) "or a short alias")
  (let [dir (doto (java.io.File/createTempFile "mu-words" "") (.delete) (.mkdirs))
        f   (java.io.File. dir "words.clj")]
    (spit f "(ns mu-test.words) (defn add3 \"plus three\" [n] (+ n 3))")
    (out (str "\"" f "\" 'w require"))
    (is (= [7] (:ok (run "4 w/add3"))) "a .clj file")
    (spit f "(ns mu-test.words) (defn add4 \"plus four\" [n] (+ n 4))")
    (out (str "\"" f "\" 'w require"))
    (is (= [8] (:ok (run "4 w/add4"))) "required again, reloaded")
    (is (re-find #"unknown word: w/add3" (:error (run "4 w/add3"))) "its old words gone"))
  (testing "a keyword tail is written before the word, or left out"
    (out "\"musics.algo.rhythmic.pattern\" 'r require")
    (is (= [[1 0 1 1]] (:ok (run "13 r/bits"))) "without it")
    (is (= [[1 0 1 1 0 0 0 0]] (:ok (run "13 :length 8 r/bits"))) "with a :key value pair")
    (is (= [[1 0 1 1] 5] (:ok (run "13 r/bits 5"))) "what is under it stays")
    (is (re-find #"\[:length v\]" (out "\"r/bits\" see")) "the stack effect shows the tail"))
  (is (re-find #"no namespace musics.nope" (:error (run "\"musics.nope\" require"))))
  (is (re-find #"'kernel is a namespace already" (:error (run "\"musics.algo.numeric.integer\" 'kernel require")))
      "mu's own namespaces are kept"))

(deftest namespaces-help-files
  (run "\"song\" in" ": tune 1 ;")
  (is (= [1] (:ok (run "song/tune"))))
  (is (re-find #"unknown word: tune" (:error (run "tune"))) "song isn't in use yet")
  (is (= [1] (:ok (run "\"song\" use" "tune"))))
  (is (str/includes? (out "\"tune\" see") ": tune 1 ;"))
  (is (str/includes? (out "words") "kernel:"))
  (let [f (java.io.File/createTempFile "mu-test" ".mus")]
    (spit f "[loaded: c4 d e]")
    (is (str/includes? (out (pr-str (.getPath f)) " load") ":loaded"))
    (is (repo/current :loaded) "load stores what the file says")))

(defn- temp-file [ext text]
  (doto (java.io.File/createTempFile "mu-test" ext) (spit text) (.deleteOnExit)))

(deftest load-converts-lilypond-abc-and-guido
  (testing "ABC"
    (out (pr-str (.getPath (temp-file ".abc" "X:1\nT:Test\nM:4/4\nL:1/8\nK:C\nC2 D2 E2 F2 |]\n"))) " load")
    (is (repo/current :tune1_test) "the converted tune is stored under its own id"))
  (testing "LilyPond and GUIDO store their music too"
    (doseq [[ext text] [[".ly" "{ c4 d4 e4 f4 }"] [".gmn" "[ c1/4 d e f ]"]]]
      (let [before (count (repo/root-children))]
        (is (str/includes? (out (pr-str (.getPath (temp-file ext text))) " load") "stored") ext)
        (is (< before (count (repo/root-children))) ext)))))

(deftest render-writes-a-midi-file
  (let [f (java.io.File/createTempFile "mu-test" ".mid")]
    (.deleteOnExit f)
    (is (str/includes? (out "[c d e]" (str " " (pr-str (.getPath f)) " render")) "written"))
    (is (pos? (.length f)))
    (is (= 3 (count (filter #(= javax.sound.midi.ShortMessage/NOTE_ON (.getCommand ^javax.sound.midi.ShortMessage %))
                            (for [t (.getTracks (javax.sound.midi.MidiSystem/getSequence f))
                                  i (range (.size t))
                                  :let [m (.getMessage (.get t i))]
                                  :when (and (instance? javax.sound.midi.ShortMessage m)
                                             (pos? (.getData2 ^javax.sound.midi.ShortMessage m)))]
                              m)))))
    (is (re-find #"endless" (:error (run "[c d] [~] \"x.mid\" render"))) "a loop needs its seconds")))

(deftest clear-clears-the-stack
  (is (= [] (:ok (run "1 2 [c d] clear"))))
  (is (= [3] (:ok (run "1 2 clear 3")))))

(deftest cls-clears-the-screen
  (is (= "\u001b[H\u001b[2J" (out "1 2 cls")))
  (is (= [1 2] (:ok (run "1 2 cls"))) "the stack stays"))

(deftest the-defaults
  (let [d (first (:ok (run "defaults")))]
    (is (str/starts-with? (p/show d) "^{ "))
    (is (str/includes? (p/show d) "!Tempo:100"))
    (is (= (p/show d) (p/show (first (:ok (run (p/show d)))))) "they read back")))

(deftest arithmetic-on-material
  (is (= "[!K:D.major D4/4 E4]" (p/show (first (:ok (run "[c d] 2 +"))))) "+ transposes up")
  (is (= "[!K:B&.major B&3/4 C4]" (p/show (first (:ok (run "[c d] 2 -"))))) "- transposes down")
  (is (= "[C3/4 D3]" (p/show (first (:ok (run "[c d] -12 +"))))) "or + with a negative number; whole octaves keep the key")
  (is (= "[!K:E.major D#4/4 !f E4]" (p/show (first (:ok (run "[!K:D.major c !f d] 2 +"))))) "its own key moves, instructions stay")
  (is (= "{!K:G.major [G4/4 A4] [B4 C5]}" (p/show (first (:ok (run "{[c d] [e f]} 7 +"))))) "a { } takes the key too")
  (run "[c d e f] 1 + ^{ !K:D.major } with :moved R!")
  (is (= [61 63 65 66] (mapv (comp first :pitches) (filter #(= :note (:kind %)) (ev/events (repo/registry) :moved))))
      "the notes keep their own key under another: e moved up is F, not F#")
  (is (= "C4/8" (p/show (first (:ok (run "c4 1/2 *"))))) "* scales durations")
  (is (= "[C4/8 D4]" (p/show (first (:ok (run "[c d] 2 /"))))) "/ divides them")
  (is (= "r2" (p/show (first (:ok (run "r4 2 *"))))) "a rest moves in time")
  (is (= "r4" (p/show (first (:ok (run "r4 2 +"))))) "but not in pitch"))

(deftest an-inherited-duration-is-left-out
  (is (= "[C4/4 D4/8 E4 F4/4]" (p/show (first (:ok (run "[c4 d8 e f4]"))))) "only where it changes")
  (is (= "[C4/8 [D4 E4] F4]" (p/show (first (:ok (run "[c8 [d e] f]"))))) "across nested seqs, as the reader inherits")
  (is (= "{[C4/8 D4] [E4 F4/4]}" (p/show (first (:ok (run "{[c8 d] [e f4]}"))))))
  (is (= "C4/4" (p/show (first (:ok (run "c4"))))) "the first is always written")
  (doseq [t ["[c4 d8 e f4]" "[c8 [d e] f]" "{[c8 d] [e f4]}" "[c4 r x\\36 <c e g>8 d]"]]
    (let [shown (p/show (first (:ok (run t))))]
      (is (= shown (p/show (first (:ok (run shown))))) (str t " reads back the same")))))

(deftest tuplets-print-as-tuplets
  (is (= "[A3/4 3:2(C4/8 D4 E4) F4]" (p/show (first (:ok (run "[a 3:2( c8 d e ) f]"))))))
  (is (= "[5:4(A3/16 B3 C4 D4 E4)]" (p/show (first (:ok (run "[5:4( a16 b c d e )]"))))))
  (is (= "[3:2(C4/8 D4 E4)]" (p/show (first (:ok (run "[c8 d e] 2/3 *"))))) "mu's * as a tuplet")
  (is (= "[3:2(C4/8 D4 E4)]" (p/show (first (:ok (run "[c8*2/3 d e]"))))) "a note's own factor too")
  (is (= "[3:2(C4/8 D4 E4)]" (p/show (first (:ok (run "3:2( c8 d e )"))))) "typed at the top level, a seq")
  (is (= "[3:2(C4/8 r x)]" (p/show (first (:ok (run "[3:2( c8 r8 x8 )]"))))) "rests and drums in the group")
  (doseq [t ["[a 3:2( c8 d e ) f]" "[c4 d e] 1/3 *" "[3:2( c8 3:2( d16 e f ) g8 )]" "[3:2( c8. d16 e8 )]"]]
    (let [shown (p/show (first (:ok (run t))))]
      (is (= shown (p/show (first (:ok (run shown))))) (str t " reads back the same"))))
  (is (re-find #"/3 is no note value" (:error (run "[c3]")))))

(deftest durations-print-as-written
  (is (= "C4/4." (p/show (first (:ok (run "c4 3/2 *"))))) "a dotted value, dotted")
  (is (= "C4/4*1/3" (p/show (first (:ok (run "c4 1/3 *"))))) "otherwise the value with the factor given")
  (is (= "C4/8*2/3" (p/show (first (:ok (run "c8 2/3 *"))))))
  (is (= "C4/4*1/3" (p/show (first (:ok (run "c4 3 /"))))) "/ divides the factor too")
  (is (= "[3:1(C4/4 D4 E4)]" (p/show (first (:ok (run "[c4*1/3 d e]"))))) "as read, the ratio inherited, as a tuplet")
  (let [shown (p/show (first (:ok (run "[c4*1/3 d e]"))))]
    (is (= shown (p/show (first (:ok (run shown))))) "reads back the same")))

(deftest arithmetic-keeps-bar-lines
  (is (= "[3:1(C4/4 D4) | 3:1(E4 F4)]" (p/show (first (:ok (run "[c d | e f] 1/3 *"))))))
  (is (= "[!K:D.major D4/4 | E4]" (p/show (first (:ok (run "[c | d] 2 +")))))))

(deftest hairpins
  (is (= "[!vol:50< C4/4 D4 E4 F4 !vol:90]" (p/show (first (:ok (run "[c d e f] 90 cresc")))))
      "a crescendo from the seq's volume (here the defaults') to 90")
  (is (= "[!mf !vol:60> C4/4 D4 !vol:30]" (p/show (first (:ok (run "[!mf c d] 30 dim")))))
      "a decrescendo from the seq's own mf, after its leading instructions")
  (let [shown (p/show (first (:ok (run "[c d e f] 90 cresc"))))]
    (is (= shown (p/show (first (:ok (run shown))))) "reads back the same"))
  (is (re-find #"target volume 0-100" (:error (run "[c d] 200 cresc")))))

(deftest inspecting-the-repo
  (run "[!tempo:90 [inner: c d] e] :verse R!" "[repeat 2 [:inner] f] :chorus R!" "^{ !ff } :loud R!")
  (testing "each prints what it reads"
    (is (= (with-out-str (prn (repo/ids))) (out "ids")))
    (is (= (with-out-str (prn (repo/usages :inner))) (out ":inner usages")))
    (is (= (with-out-str (r/ctx :inner)) (out ":inner ctx")))
    (is (= (with-out-str (r/ctx :verse)) (out ":verse R@ ctx")) "fetched material works too"))
  (is (= ":inner: (empty)\n:verse: Tempo=90@0\n" (out ":inner ctx")) "the enclosing tempo is in the chain")
  (is (re-find #"ctx: needs the name of something stored" (:error (run ":nope ctx"))))
  (is (re-find #"usages: needs the name of something stored" (:error (run "[c d] usages")))))

(deftest staged-play-waits-for-its-boundary
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (binding [engine/*engine* eng]
        (run "[!tempo:240 c16 d | e f | g a] ->")
          (run "[b16 c] | -|>")
          (is (= [[:mark 1] :enter] (first (keys (conductor/scheduled)))) "waits for the next |")
          (Thread/sleep 300)
          (is (empty? (conductor/scheduled)) "taken at the bar line")
          (run "[!tempo:240 c16 d | e f | g a b c d e f g] ->")
          (run "[c16 d] | -|>+")
          (Thread/sleep 300)
          (is (empty? (conductor/scheduled)) "-|>+ is taken at the bar line too")
          (is (= 2 (count @(:voices eng))) "and joins: the first voice goes on")
          (run "[c d] -|>")
          (run "stop")
          (is (empty? (conductor/scheduled)) "stop also forgets a staged play")
          (is (not (musics.engine/busy? eng)))))))

(deftest echo-prints-each-note-as-it-sounds
  (is (= "x\\38 Acoustic Snare" (musics.mu.material/echo-line {:channel 9 :pitches [38]})))
  (is (= "\\i:40 violin  C4 E4 G4" (musics.mu.material/echo-line {:channel 2 :pitches [60 64 67] :program 40})))
  (let [lines (atom [])]
    (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
      (with-redefs [engine/receiver (atom :silent)
                    e/print-above (atom #(swap! lines conj %))]
        (try
          (is (= "echo on\n" (out "echo")))
          (run "[x32\\36 x\\38 c\\i:40] ->")
          (loop [n 0] (when (and (< (count @lines) 3) (< n 100)) (Thread/sleep 20) (recur (inc n))))
          (is (= ["x\\36 Bass Drum 1" "x\\38 Acoustic Snare" "\\i:40 violin  C4"] @lines) "in playing order")
          (is (= "echo off\n" (out "echo")))
          (finally (reset! engine/echo nil) (engine/stop!)))))))

(deftest see-shows-effect-then-description
  (is (= "dup ( x -- x x )\n  copy the top\n" (out "\"dup\" see")))
  (is (= (out "\"dup\" see") (out "'dup see")) "a quoted name too")
  (is (str/starts-with? (out "'play/stop see") "play/stop ( [voice] -- )") "qualified"))

(deftest named-music-is-its-own-voice
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (try
        (let [ids #(set (keys (mt/live-voices)))]
          (run "[bass: !tempo:60 c1 d1] ->")
          (run "[mel: !tempo:60 e1 f1] ->")
          (is (= #{:bass :mel} (ids)) "named music adds or replaces its own voice, not the others")
          (run "[bass: !tempo:60 g1 a1] ->")
          (is (= #{:bass :mel} (ids)) "replacing :bass leaves :mel")
          (is (= "[bass: !tempo:60 G3/1 A3]" (p/show (first (:ok (run ":bass playing"))))) "playing gives what it plays")
          (run ":bass hush")
          (is (= #{[:bass]} (:hushed (engine/hushed))) "hushed, still a voice")
          (is (= #{:bass :mel} (ids)))
          (run ":bass hush")
          (run ":mel solo")
          (is (= [:mel] (:solo (engine/hushed))))
          (run ":mel solo")
          (is (nil? (:solo (engine/hushed))) "solo again and all sound")
          (is (= (p/show (first (:ok (run ":bass playing")))) (p/show (first (:ok (run ":bass <-"))))) "<- is playing")
          (is (re-find #":bass .*\[bass:" (out "<<-")) "<<- is voices")
          (is (re-find #"no voice 4" (:error (run "4 <-"))) "<- on a number is an index; past the voices an error")
          (run ":mel stop")
          (is (= #{:bass} (ids)) "stop one voice")
          (is (re-find #"isn't playing" (:error (run ":mel playing"))))
          (run "[!tempo:60 c1] ->+")
          (is (= 2 (count (ids))) "unnamed music joining gets an engine id")
          (is (some #(re-matches #"T[A-Z]" (name %)) (ids)))
          (run "[!tempo:60 c1] ->")
          (is (= 1 (count (ids))) "unnamed -> stops everything")
          (run "[bass: !tempo:60 c1 d1] [~] ->")
          (is (contains? (ids) :bass) "a loop plays as the seq it repeats")
          (run "[mel: !tempo:60 e1 f1] ->")
          (run "[bass: !tempo:60 g1] -|>")
          (is (= [[:bass :exit]] (filter #(= :bass (first %)) (keys (conductor/scheduled))))
              "-|> on a named voice waits for that voice's own pass")
          (is (not-any? #(= :mel (first %)) (keys (conductor/scheduled))) "not for the others'"))
        (finally (mt/stop-all!))))))

(deftest with-on-a-voice-id-changes-the-playing-voice
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (try
        (run "[bass: !tempo:60 c1 d1] [~] ->")
        (let [staged (atom [])]
          (with-redefs [mt/stage! (fn [x _ _ join? target] (swap! staged conj [(p/show x) join? target]))]
            (is (= [] (:ok (run ":bass !tempo:140 with"))) "the id is taken, nothing left")
            (run ":bass !volume:80 with")
            (run ":bass ^{ !mf !tempo:90 } with")
            (is (= [["[!tempo:140 repeat ~ [bass: !tempo:60 C4/1 D4]]" false :bass]
                    ["[!volume:80 repeat ~ [bass: !tempo:60 C4/1 D4]]" false :bass]
                    ["[!mf !tempo:90 repeat ~ [bass: !tempo:60 C4/1 D4]]" false :bass]]
                   @staged)
                "the voice's own music, the settings in front, replacing only it")))
        (is (re-find #"with: :zzz isn't a voice" (:error (run ":zzz !tempo:90 with"))))
        (finally (mt/stop-all!))))))

(deftest a-named-line-looping-inside-ends-its-pass-at-the-repeat
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (try
        (run "'[1/4] [~] '[60 62 64] zip :bass R!")
        (run ":bass R@ ->")
        (run ":bass !tempo:140 with")
        (is (< 1 (count (filter #(= :exit (second %)) (keys (conductor/scheduled)))))
            "the voice's own end and its repeat's body: either one ends a pass")
        (finally (mt/stop-all!))))))

(deftest a-voice-alone-plays-its-own-music-again
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (try
        (run "[bass: !tempo:60 c1 d1] [~] ->")
        (run "[mel: !tempo:60 e'1 f1] ^{ !mf } ->")
        (let [played (atom [])
              shown  (fn [[x ctx _ target]] [(p/show x) (some? ctx) target])]
          (with-redefs [mt/play!  (fn [x ctx join? target] (swap! played conj [x ctx join? target]))
                        mt/stage! (fn [x ctx _ join? target] (swap! played conj [x ctx join? target]))]
            (run "1 play")
            (run ":mel ->")
            (run "2 -|>")
            (is (= [["repeat ~ [bass: !tempo:60 C4/1 D4]" false :bass]
                    ["[mel: !tempo:60 E5/1 F5]" true :mel]
                    ["[mel: !tempo:60 E5/1 F5]" true :mel]]
                   (mapv shown @played))
                "a voice alone: its own music, under its own context, as that voice")
            (reset! played [])
            (run "[g1] 1 play")
            (is (= "[G3/1]" (p/show (ffirst @played))) "music under it plays as the voice, as before")))
        (is (re-find #":motif R@ ->" (:error (run ":motif ->"))) "a name that plays nothing: the hint to fetch it")
        (finally (mt/stop-all!))))))

(deftest a-named-voice-changes-at-its-own-boundary
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (binding [engine/*engine* eng]
        (try
          (run "[bass: !tempo:240 c16 d] [~] ->")
          (run "[mel: !tempo:60 e1 f1] ->")
          (run "[bass: !tempo:240 g16 a] [~] -|>")
          (is (= "[bass: !tempo:240 C4/16 D4]" (re-find #"\[bass: [^\]]*\]" (p/show (mt/playing :bass)))) "not yet")
          (Thread/sleep 400)
          (is (= "[bass: !tempo:240 G3/16 A3]" (re-find #"\[bass: [^\]]*\]" (p/show (mt/playing :bass)))) "changed at the end of its pass")
          (is (contains? (mt/live-voices) :mel) ":mel plays on")
          (finally (mt/stop-all!)))))))

(deftest voices-by-index-low-to-high-drums-from-0-down
  (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (try
        (run "[!tempo:60 C5/1 D5] ->")
        (run "[!tempo:60 C3/1 D3] ->+")
        (run "[!tempo:60 E4/1 F4] ->+")
        (run "[!tempo:60 x1 x1] ->+")
        (is (= "[!tempo:60 C3/1 D3]" (p/show (first (:ok (run "1 <-"))))) "1 the lowest")
        (is (= "[!tempo:60 C5/1 D5]" (p/show (first (:ok (run "3 <-"))))) "n the highest")
        (is (= "[!tempo:60 x1 x]" (p/show (first (:ok (run "0 <-"))))) "0 the drums")
        (run "2 hush")
        (is (= 1 (count (:hushed (engine/hushed)))) "an index wherever a voice goes")
        (run "[!tempo:60 C6/1] 1 ->")
        (is (= "[!tempo:60 C6/1]" (p/show (first (:ok (run "3 <-"))))) "x 1 -> plays as voice 1, which is now the highest")
        (is (re-find #"(?m)^0 drums +:" (out "<<-")) "<<- numbers them, drums 0")
        (is (re-find #"(?m)^1 lowest +:" (out "<<-")) "1 the lowest")
        (is (re-find #"(?m)^3 highest +:" (out "<<-")) "n the highest")
        (is (apply = (map #(str/index-of % "[") (str/split-lines (out "<<-")))) "the columns aligned")
        (is (not (str/includes? (out "<<-") "null")) "nothing after a voice that is neither hushed nor soloed")
        (run "[!tempo:60 x1\\hhc x1] ->+")
        (is (= "[!tempo:60 x1 x]" (p/show (first (:ok (run "0 <-"))))) "0 the drums with the lowest key (35, a plain x)")
        (is (= "[!tempo:60 x1\\42 x]" (p/show (first (:ok (run "-1 <-"))))) "-1 the next (42)")
        (is (= ["3" "2" "1" "0" "-1"] (map #(re-find #"-?\d+" %) (str/split-lines (out "<<-")))) "the highest on top, down to the drums")
        (is (re-find #"(?m)^-1 drums +:" (out "<<-")) "<<- numbers them, drums 0, -1 …")
        (run "0 stop")
        (run "-0 stop")
        (is (re-find #"no drum voice" (:error (run "0 <-"))))
        (run "[!tempo:60 C2/1 D2] [~] ->+")
        (run "[!tempo:60 C7/1 D7] 2 times ->+")
        (is (re-find #"C2/1" (p/show (first (:ok (run "1 <-"))))) "a loop ranks by its pitch")
        (is (re-find #"C7/1" (p/show (first (:ok (run "5 <-"))))) "a repeat too, not among the drums")
        (finally (mt/stop-all!))))))

(deftest a-group-is-one-line
  (let [show #(p/show (first (:ok (run %))))]
    (is (= "[[C4/4 D4] [E4 F4]]" (show "( [c d] [e f] )")) "what it leaves, gathered into a seq")
    (is (= "[C4/4 D4 E4]" (show "( c d e )")))
    (is (= "[!K:D&.major repeat 2 [D&4/4 E&4] volta [[F4] [G&4]]]" (show "( [c d] 2 times [e] [f] volta ) 1 +"))
        "one seq, so a word reaches all of it: the key covers the endings too")
    (is (= "[D4/4 D4]" (show ": twice ( $x -- y ) ( $x $x ) ; d4 twice")) "inside a definition, with locals")
    (is (= "[C4/4 D4 E4]" (show "c d e ()")) "() alone gathers the stack")
    (is (= "'[60 62]" (show "60 62 ()")))
    (is (= ["C4/4" "D4/4" "'[60 62]"] (map p/show (:ok (run "c d | 60 62 ()")))) "only what lies above a |")
    (is (re-find #"nothing on the stack" (:error (run "()"))))
    (is (= "[!M:3/4 C4/4 D4 E4]" (show "[c d e] M:3/4 with")) "a setting alone lies under a seq with with")
    (is (= "[!T:8=200 C4/4 D4 E4]" (show "[c d e] T:8=200 with")))
    (is (= "<C4 D#4 G4 A#4>4" (show "c:m7")) "a chord symbol is still a chord")
    (is (= "'[1 2]" (show "( 1 2 )")) "numbers gather into a data list")
    (is (= "'[1/4 1/8]" (show "( 1/4 1/8 )")))
    (is (= "'[72 62]" (show "( 60 12 + 62 )")) "computed values")
    (is (= "'['[1 2] '[3 4]]" (show "( '[1 2] '[3 4] )")) "data lists gather into a list of lists")
    (is (re-find #"gathers music into a seq, and left 1" (:error (run "( c 1 )"))) "the first value decides")
    (is (re-find #"gathers plain values into a '\[ \], and left C4" (:error (run "( 1 c )"))))
    (run "( [c d] 2 times [e] [f] volta ) 1 + ^{ !K:D.major } with :grouped R!")
    (is (= [61 63 65 61 63 66] (mapv (comp first :pitches) (filter #(= :note (:kind %)) (ev/events (repo/registry) :grouped))))
        "the first ending's F stays F under another key")))

(deftest a-name-on-top-of-play-is-a-voice
  (is (re-find #":motif R@ ->" (:error (run ":motif ->"))) "the hint to fetch what is stored"))

(deftest staged-play-with-no-boundary-plays-at-once
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [engine/receiver (atom :silent)]
      (binding [engine/*engine* eng]
        (try
          (mt/stop-all!)
          (engine/play [(first (:ids (store/read! "[!tempo:60 c1 d1]")))])
          (run "[e f] -|>")
          (is (empty? (conductor/scheduled)) "mu knows no pass of what plays: no wait for a | that may never come")
          (is (= 1 (count (mt/live-voices))))
          (finally (mt/stop-all!)))))))

(deftest endless-each-and-runaway-words
  (is (re-find #"each: an endless stream" (:error (run "'[1 2] [~] '( drop ) each"))))
  (is (re-find #"reduce: an endless stream" (:error (run "'[1 2] [~] 0 '( + ) reduce"))))
  (is (re-find #"overflowed" (:error (run ": spin ( -- ) spin ; spin"))) "a word calling itself ends in an error, not a crash"))


(deftest forgetting-and-renaming-a-stored-name
  (testing "rename: the node, :ROOT's children and every place that holds it"
    (run "[a: c d] R!")
    (run "clear [b: :a e :a] R!")
    (run "clear [r: repeat 2 [:a e]] R!")
    (run "clear ^{ lo: !mf } R!")
    (run "clear [d: :lo c] R!")
    (run "clear [u: ^{ q: !ff } c] R!")
    (run "clear :a :motif rename")
    (is (nil? (repo/current :a)) "the old name is gone")
    (is (= :motif (:id (repo/current :motif))) "the node carries its new name")
    (is (some #{:motif} (:children (repo/current :ROOT))) "and :ROOT lists it")
    (is (= "[b: :motif E4/4 :motif]" (str/join " " (map p/show (:ok (run ":b R@"))))) "a container's children")
    (is (= "[r: repeat 2 [:motif E4/4]]" (str/join " " (map p/show (:ok (run ":r R@"))))) "a repeat's body")
    (run "clear :lo :hi rename")
    (is (= "[d: :hi C4/4]" (str/join " " (map p/show (:ok (run ":d R@"))))) "an override marker's reference and text")
    (run "clear :q :w rename")
    (is (re-find #"\^\{ w: " (str/join " " (map p/show (:ok (run ":u R@"))))) "an inline context definition's name"))
  (testing "rename is refused where it would break something"
    (is (re-find #"is stored already" (:error (run ":motif :b rename"))))
    (is (re-find #"something stored" (:error (run ":nothing :x rename"))))
    (is (re-find #"keeps its name" (:error (run ":ROOT :x rename"))))
    (is (re-find #"new name on top" (:error (run ":motif 5 rename")))))
  (testing "forget"
    (is (re-find #"still used by :b :r" (:error (run ":motif forget"))) "refused while something uses it")
    (run "clear :r forget")
    (run "clear :b forget")
    (run "clear :motif forget")
    (is (nil? (repo/current :motif)))
    (is (not (some #{:motif} (:children (repo/current :ROOT)))) "out of :ROOT too")
    (is (re-find #"something stored" (:error (run ":motif forget"))) "gone is gone")
    (is (re-find #"root" (:error (run ":ROOT forget"))))))

(deftest ctx-takes-a-line-not-stored
  (is (= "(not stored under :ROOT -- its own context only)\n  Tempo=90@0\n" (out "[!T:90 c d] ctx")) "its own settings, where they begin")
  (run "[a: !T:90 c d] R! drop")
  (is (= ":a: Tempo=90@0\n" (out ":a ctx")) "a stored name, its chain")
  (is (= ":a: Tempo=90@0\n" (out ":a R@ ctx")) "or the seq fetched with R@")
  (is (re-find #"something stored" (:error (run "7 ctx"))) "anything else is refused"))

(deftest java-s-first-run-preferences-line-is-quiet
  (let [mu (requiring-resolve 'musics.mu/quiet-java-logging!)]
    (mu)
    (is (= java.util.logging.Level/WARNING
           (.getLevel (java.util.logging.Logger/getLogger "java.util.prefs"))))))


(deftest the-dotted-play-signs-say-what-replaced-them
  (doseq [[old new] [[".>" "->"] [".>+" "->+"] [".>>" "-|>"] [".>>+" "-|>+"] [".<" "<-"] [".<<" "<<-"]]]
    (is (= (str old ": isn't a word; write " new) (:error (run (str "[c] " old)))) old)))

;; R! refuses to redefine an id other stored parts use; R!! does it and says which
(deftest r!-refuses-what-r!!-says
  (run "[motif: c d] R!" "[verse: :motif e] R!")
  (testing "an id nothing uses, or a new one, is stored silently"
    (is (= [:fresh] (:ok (run "[fresh: g] R!"))))
    (is (= "" (with-out-str (run "[fresh: a] R!")))))
  (testing "R! refuses, naming where it is used, and stores nothing"
    (is (re-find #"^R!: :motif is used by :verse -- R!! stores it anyway" (:error (run "[motif: g a] R!"))))
    (is (= "[motif: C4/4 D4]" (first (map p/show (:ok (run ":motif R@"))))) "still the old one"))
  (testing "R!! stores it and says what changes with it"
    (let [out (with-out-str (is (= [:motif] (:ok (run "[motif: g a] R!!")))))]
      (is (re-find #"^R!!: :motif is used by :verse, so storing it changes them too" out)))
    (is (= "[motif: G3/4 A3]" (first (map p/show (:ok (run ":motif R@")))))))
  (testing "the named twins"
    (is (re-find #"^store: |^R!: " (:error (run "[motif: c] store"))))
    (is (= [:motif] (:ok (run "[motif: d] overwrite"))))))

;; help and see read the word after them, as Forth's see does
(deftest help-and-see-read-the-next-word
  (doseq [in ["help dup" "see dup" "'dup help" "\"dup\" see"]]
    (let [out (atom nil)]
      (swap! e/state assoc :stack [])
      (let [r (atom nil)]
        (reset! out (with-out-str (reset! r (e/eval-input in))))
        (is (nil? (:error @r)) (str in " runs without an error")))
      (is (str/starts-with? @out "dup ( x -- x x )") in)
      (is (= [] (:stack @e/state)) "dup was looked up, not run")))
  (testing "a sign or a literal after them is not read as the name"
    (swap! e/state assoc :stack [])
    (is (str/starts-with? (with-out-str (e/eval-input "help")) "mu: music is written as text") "help alone is the general help")
    (is (re-find #"^mu: music is written" (with-out-str (e/eval-input "help 3"))) "a number after it is a number"))
  (testing "it follows through a quotation and a definition"
    (swap! e/state assoc :stack [])
    (is (str/starts-with? (with-out-str (e/eval-input "'( see swap ) call")) "swap ( x1 x2 -- x2 x1 )"))
    (e/eval-input ": what see over ;")
    (is (str/starts-with? (with-out-str (e/eval-input "what")) "over ( x1 x2 -- x1 x2 x1 )"))))

;; about: what a name is, and where it can appear (read from the grammar)
(deftest about-says-what-a-name-is-and-where-it-can-appear
  (let [run-out (fn [in] (swap! e/state assoc :stack [])
                  (let [r (atom nil) out (with-out-str (reset! r (e/eval-input in)))]
                    (is (nil? (:error @r)) (str in " runs without an error"))
                    (str/trim-newline out)))]
    (testing "a musical parameter: one sentence"
      (is (= "tempo is a musical parameter (Tempo|T) and can appear in ^{ }, { } and [ ]." (run-out "about tempo"))))
    (testing "pitch, duration and articulation are properties of leaves"
      (is (re-find #"^pitch is a property of a note or chord\.\npitch is a word of mu" (run-out "about pitch")))
      (is (= "duration is a property of a note, chord or rest, or the length of a seq." (first (str/split-lines (run-out "about duration"))))))
    (testing "a mark, an instrument, a drum, a scale, an articulation"
      (is (= "!allegro is a mark (it sets Tempo to 138) and can appear in ^{ }, { } and [ ]." (run-out "about allegro")))
      (is (= "violin is an instrument (vln, program 40) and can appear in ^{ }, { } and [ ]." (run-out "about violin")))
      (is (re-find #"^Bass Drum 1 is a drum \(key 36\) and can appear in \[ \]\." (run-out "about kick")))
      (is (re-find #"^Major is a scale \(also ionian\)\." (run-out "about major")))
      (is (re-find #"staccato is an articulation .* and can appear in \[ \], after a note\." (run-out "about staccato"))))
    (testing "a word, and a name that is several things"
      (is (= "dup is a word of mu ( x -- x x )." (run-out "about dup")))
      (let [out (run-out "about volume")]
        (is (re-find #"(?m)^volume is a musical parameter" out))
        (is (re-find #"(?m)^volume is a word of mu \( note -- volume \)" out))))
    (testing "settings and marks say more about a parameter"
      (is (= "30..300, default 100, Beats per minute. Written !tempo:100; on a line: x !tempo:100 with"
             (run-out "settings tempo")))
      (is (re-find #"^!adagissimo " (run-out "marks tempo")))
      (is (re-find #"!adagissimo" (run-out "marks T")) "T is Tempo and transposition")
      (is (re-find #"default C\.major," (run-out "settings Key")) "a key as it is written")
      (is (re-find #"default 4/4," (run-out "settings Meter")))
      (is (re-find #"^c is a" (run-out "about c")) "a bare letter reads as a name, not a note")
      (is (not (re-find #"\d" (run-out "marks tempo"))) "names only"))
    (testing "where it can appear is the grammar's: notes are in [ ] only"
      (is (re-find #"^Leaf is a rule of the grammar and can appear in \[ \]\." (run-out "about Leaf")))
      (is (re-find #"^TempoMark is a rule of the grammar and can appear in \^\{ \}, \{ \} and \[ \]\." (run-out "about TempoMark"))))
    (testing "a string and a quoted name say the same"
      (is (= (run-out "about tempo") (run-out "\"tempo\" about")))
      (is (= (run-out "about tempo") (run-out "'tempo about"))))
    (testing "an unknown name lists the names that start with it; no name says how to ask"
      (is (= "nothing is called \"tem\"; names with it: Tempo" (run-out "about tem")))
      (is (re-find #"nothing is called \"zzzz\"" (run-out "about zzzz")))
      (is (re-find #"^about <name>:" (run-out "about"))))
    (testing "the stack is left as it was"
      (swap! e/state assoc :stack [])
      (e/eval-input "[c d] about tempo")
      (is (= 1 (count (:stack @e/state)))))))

(deftest at-and-bang-read-and-store-a-reference
  (let [cell (atom 5)
        r    (v/->Ref "bass/onsets" (fn [] @cell) (fn [x] (reset! cell x)))
        run  (fn [stack in] (swap! e/state assoc :stack stack) (let [res (e/eval-input in)] (assoc res :stack (:stack @e/state))))]
    (testing "@ on a reference gives what it holds, ! stores into it"
      (is (= [5] (:stack (run [r] "@"))))
      (is (nil? (:error (run [7 r] "!"))))
      (is (= 7 @cell) "stored")
      (is (= [] (:stack @e/state)) "! leaves nothing")
      (is (= [7] (:stack (run [r] "@")))))
    (testing "a reference prints as its label"
      (is (= "bass/onsets" (p/show r))))
    (testing "@ and ! on lines are nth and put as before"
      (is (= "E4/4" (p/show (first (:stack (run [] "[c d e] 2 @"))))))
      (is (= "[C4/4 G4 E4]" (p/show (first (:stack (run [] "[c d e] 1 g !")))))))
    (testing "! with nothing to store into says what it needs"
      (is (re-find #"needs" (:error (run [] "!")))))))

(deftest a-voice-input-is-the-reference-colon-voice-slash-input
  (inputs/set-inputs! :bass [["onsets" 5] ["pulses" 8] ["seed" 42]])
  (let [was (inputs/alive! (constantly true))]   ; no engine here: the voice is taken as playing
   (try
    (let [run (fn [in] (swap! e/state assoc :stack []) (let [res (e/eval-input in)] (assoc res :stack (:stack @e/state))))]
      (testing "read it, store into it"
        (is (= [5] (:stack (run ":bass/onsets @"))))
        (is (nil? (:error (run "7 :bass/onsets !"))))
        (is (= [7] (:stack (run ":bass/onsets @"))))
        (is (= 8 (inputs/value :bass "pulses")) "the others are as they were"))
      (testing "it prints as written"
        (is (= ":bass/onsets" (p/show (first (:stack (run ":bass/onsets")))))))
      (testing "a plain :bass is still the id"
        (is (= [:bass] (:stack (run ":bass")))))
      (testing "an input that is not there says which are"
        (is (re-find #"^:bass/pulse: :bass has no input pulse -- onsets, pulses, seed" (:error (run ":bass/pulse @")))))
      (testing "a voice without inputs says so"
        (is (re-find #"^:drums/onsets: no voice :drums has inputs -- voices with inputs: :bass" (:error (run ":drums/onsets @")))))
      (testing "without the colon, an unknown word points at it"
        (is (re-find #"did you mean :bass/onsets\?" (:error (run "bass/onsets @"))))))
    (finally (inputs/clear! :bass) (inputs/alive! was)))))

(deftest a-precondition-of-a-function-is-a-refusal-not-a-crash
  (swap! e/state assoc :stack [])
  (e/eval-input "\"musics.algo.rhythmic.pattern\" require")
  (let [r (e/eval-input "99 8 pattern/euclid")]
    (is (re-find #"^pattern/euclid: refused, it needs <= onsets pulses" (:error r)) (:error r))
    (is (= [] (:stack @e/state)) "the stack as it was")))

(deftest reqalgo-takes-a-quoted-or-written-name
  (swap! e/state assoc :stack [])
  (testing "reqalgo is require with musics.algo. in front"
    (is (re-find #"musics\.algo\.numeric\.rng as rng/" (with-out-str (e/eval-input "\"numeric.rng\" reqalgo"))))
    (is (nil? (:error (e/eval-input "7 rng/seed")))))
  (testing "the name may be quoted, dots and all, and an alias follows it"
    (is (re-find #"musics\.algo\.numeric\.random as x/" (with-out-str (e/eval-input "'numeric.random 'x reqalgo"))))
    (is (re-find #"musics\.algo\.bridge as b/" (with-out-str (e/eval-input "'bridge 'b reqalgo"))))
    (is (= 0 (count (:stack @e/state))) "nothing is left on the stack"))
  (testing "require takes a quoted name too"
    (is (re-find #"musics\.algo\.rhythmic\.pattern as p/"
                 (with-out-str (e/eval-input "'musics.algo.rhythmic.pattern 'p require"))))
    (is (= "'[1 0 0 1 0 0 1 0]" (p/show (first (:ok (e/eval-input "3 8 p/euclid")))))))
  (testing "an alias that is taken is refused, and a namespace that is not there is said"
    (is (re-find #"'x is a namespace already" (:error (e/eval-input "'numeric.rng 'x reqalgo"))))
    (is (re-find #"no namespace musics.algo.nothing.here" (:error (e/eval-input "'nothing.here reqalgo"))))))
