(ns ^:repl musics.mu.kernel-test
  "mu's kernel words: printing, numbers, control, bindings, the repo,
   playback (on a silent engine), namespaces, help, files, defaults."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.core :as m]
            [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.conductor :as conductor]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.print :as p]
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
        (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "play" "music"])))
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
  (is (= "0> [C4/4 D4]\n1> 7\n" (out "7 [c d] .s")) "top first, numbered as pick counts")
  (is (= "" (out ".s")) "an empty stack shows nothing"))

(deftest numbers
  (is (= [3 1/4 6 1/2 2 -3 3 2 4 3 0.75 3/4]
         (:ok (run "1 2 add  1/2 1/4 sub  2 3 mul  1 2 div  9 7 mod  3 neg  -3 abs  2 3 min  4 3 max  7/2 int  3/4 float  0.75 rational"))))
  (is (= [3] (:ok (run "2.7 round"))))
  (is (re-find #"add: needs two numbers" (:error (run "1 \"x\" add"))))
  (is (re-find #"\+: is musical; on numbers write add" (:error (run "1 2 +"))))
  (is (re-find #"<: isn't a word; to compare numbers write lt" (:error (run "1 1 <")))))

(deftest comparisons-and-control
  (is (= [true false true true] (:ok (run "1 2 lt  1 2 eq  1 1 eq  2 2 ge"))))
  (is (= [true false] (:ok (run "1 2 ne  \"a\" \"a\" ne"))) "ne on any values")
  (is (= [true true false true] (:ok (run "[c d e] [c d e] eq  c4 c4 eq  [c d e] [c d f] eq  [c d e] [c d f] ne")))
      "music is equal when it has the same notes")
  (is (= [true] (:ok (run "[c d e] [C4 D4 E4] eq"))) "however they are written")
  (is (= [true] (:ok (run "[zz1: c d] [c d] eq"))) "whatever they are called")
  (is (= [true false] (:ok (run "'[1 2] [~] '[1 2] [~] eq  '[1 2] [~] '[1 3] [~] eq"))) "loops by what they repeat")
  (is (= [:yes] (:ok (run "1 2 lt '( :yes ) '( :no ) if"))))
  (is (= [:no] (:ok (run "2 1 lt '( :yes ) '( :no ) if"))))
  (is (= [5] (:ok (run "5 1 2 gt '( pop ) when"))) "when false: nothing")
  (is (= ["C4/4" "D4/4" "E4/4"] (mapv p/show (:ok (run "[c d e] '( ) each")))) "each: every element")
  (is (= [6] (:ok (run "'[1 2 3] 0 '( add ) reduce")))))

(deftest quoting-and-executing-words
  (is (= [5 5] (:ok (run "5 'dup execute"))))
  (is (= ["[D4/4 E4]"] (mapv p/show (:ok (run "[c d] 2 '+ execute")))) "a symbol word")
  (is (= [3 3] (:ok (run ": twice dup ;" "3 'twice execute"))) "a word of mine")
  (is (= [6] (:ok (run "'w 'mul :=" "2 3 \\w execute"))) "bound, and executed later")
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
  (is (re-find #"bound in this input" (:error (run "'m [c d] := [\\m e]"))))
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
  (testing "a leaf is stored as a one-leaf line"
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
    (with-redefs [m/receiver (atom :silent)]
      (let [before (set (keys @(repo/registry)))]
        (is (:ok (run "[c4 d e] play")))
        (is (= 1 (count @(:voices engine/*engine*))) "a voice started")
        (is (:ok (run "[c4 d] ^{ !tempo:90 } play")) "with a context")
        (is (= before (set (keys @(repo/registry)))) "nothing stored")
        (is (:ok (run "[e f] play+")) "play+ joins")
        (is (= 2 (count @(:voices engine/*engine*))) "the earlier voice keeps playing")
        (is (:ok (run "[g] >+")) ">+ is play+")
        (is (= 3 (count @(:voices engine/*engine*))))
        (is (:ok (run "[g] >")) "> is play")
        (is (= 1 (count @(:voices engine/*engine*))) "play stops everything first")
        (run "pause")
        (is (engine/paused?) "pause pauses what plays")
        (run "pause")
        (is (engine/playing?) "and resumes it")
        (engine/stop!)
        (is (= "nothing plays\n" (out "pause")) "with nothing playing it says so")))))

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
  (is (= "[D4/4 E4]" (p/show (first (:ok (run "[c d] 2 +"))))) "+ transposes up")
  (is (= "[A#3/4 C4]" (p/show (first (:ok (run "[c d] 2 -"))))) "- transposes down")
  (is (= "[C3/4 D3]" (p/show (first (:ok (run "[c d] -12 +"))))) "or + with a negative number")
  (is (= "C4/8" (p/show (first (:ok (run "c4 1/2 *"))))) "* scales durations")
  (is (= "[C4/8 D4]" (p/show (first (:ok (run "[c d] 2 /"))))) "/ divides them")
  (is (= "r2" (p/show (first (:ok (run "r4 2 *"))))) "a rest moves in time")
  (is (= "r4" (p/show (first (:ok (run "r4 2 +"))))) "but not in pitch"))

(deftest an-inherited-duration-is-left-out
  (is (= "[C4/4 D4/8 E4 F4/4]" (p/show (first (:ok (run "[c4 d8 e f4]"))))) "only where it changes")
  (is (= "[C4/8 [D4 E4] F4]" (p/show (first (:ok (run "[c8 [d e] f]"))))) "across nested lines, as the reader inherits")
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
  (is (= "[3:2(C4/8 D4 E4)]" (p/show (first (:ok (run "3:2( c8 d e )"))))) "typed at the top level, a line")
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
  (is (= "[D4/4 | E4]" (p/show (first (:ok (run "[c | d] 2 +")))))))

(deftest hairpins
  (is (= "[!vol:50.0< C4/4 D4 E4 F4 !vol:90]" (p/show (first (:ok (run "[c d e f] 90 cresc")))))
      "a crescendo from the line's volume (here the defaults') to 90")
  (is (= "[!mf !vol:60> C4/4 D4 !vol:30]" (p/show (first (:ok (run "[!mf c d] 30 dim")))))
      "a decrescendo from the line's own mf, after its leading instructions")
  (let [shown (p/show (first (:ok (run "[c d e f] 90 cresc"))))]
    (is (= shown (p/show (first (:ok (run shown))))) "reads back the same"))
  (is (re-find #"target volume 0-100" (:error (run "[c d] 200 cresc")))))

(deftest inspecting-the-repo
  (run "[!tempo:90 [inner: c d] e] :verse R!" "[repeat 2 [:inner] f] :chorus R!" "^{ !ff } :loud R!")
  (testing "each prints what its musics.core namesake gives at the REPL"
    (is (= (with-out-str (prn (m/ids))) (out "ids")))
    (is (= (with-out-str (prn (m/usages :inner))) (out ":inner usages")))
    (is (= (with-out-str (m/ctx :inner)) (out ":inner ctx")))
    (is (= (with-out-str (m/ctx :verse)) (out ":verse R@ ctx")) "fetched material works too"))
  (is (= ":inner: (empty)\n:verse: Tempo=90@0\n" (out ":inner ctx")) "the enclosing tempo is in the chain")
  (is (re-find #"ctx: needs the name of something stored" (:error (run ":nope ctx"))))
  (is (re-find #"usages: needs the name of something stored" (:error (run "[c d] usages")))))

(deftest staged-play-waits-for-its-boundary
  (let [eng (engine/engine nil (repo/registry) :ROOT)]
    (with-redefs [m/receiver (atom :silent)]
      (binding [engine/*engine* eng]
        (run "[!tempo:240 c16 d | e f | g a] >")
          (run "[b16 c] | >>")
          (is (= [[:mark 1] :enter] (first (keys (conductor/scheduled)))) "waits for the next |")
          (Thread/sleep 300)
          (is (empty? (conductor/scheduled)) "taken at the bar line")
          (run "[!tempo:240 c16 d | e f | g a b c d e f g] >")
          (run "[c16 d] | >>+")
          (Thread/sleep 300)
          (is (empty? (conductor/scheduled)) ">>+ is taken at the bar line too")
          (is (= 2 (count @(:voices eng))) "and joins: the first voice goes on")
          (run "[c d] >>")
          (run "stop")
          (is (empty? (conductor/scheduled)) "stop also forgets a staged play")
          (is (not (musics.engine/busy? eng)))))))

(deftest soundfont-words
  (testing "soundfonts numbers the files soundfont takes"
    (is (= (apply str (map-indexed #(str %1 "> " %2 "\n") (m/soundfonts))) (out "soundfonts"))))
  (testing "soundfont refuses what is neither a file nor a number, and a number too high"
    (is (re-find #"soundfont: needs a file in quotes or a number" (:error (run "c4 soundfont"))))
    (is (re-find #"soundfont: no number 100000" (:error (run "100000 soundfont"))))))

(deftest echo-prints-each-note-as-it-sounds
  (is (= "x\\38 Acoustic Snare" (musics.mu.kernel/echo-line {:channel 9 :pitches [38]})))
  (is (= "\\i:40 violin  C4 E4 G4" (musics.mu.kernel/echo-line {:channel 2 :pitches [60 64 67] :program 40})))
  (let [lines (atom [])]
    (binding [engine/*engine* (engine/engine nil (repo/registry) :ROOT)]
      (with-redefs [m/receiver (atom :silent)
                    e/print-above (atom #(swap! lines conj %))]
        (try
          (is (= "echo on\n" (out "echo")))
          (run "[x32\\36 x\\38 c\\i:40] >")
          (loop [n 0] (when (and (< (count @lines) 3) (< n 100)) (Thread/sleep 20) (recur (inc n))))
          (is (= ["x\\36 Bass Drum 1" "x\\38 Acoustic Snare" "\\i:40 violin  C4"] @lines) "in playing order")
          (is (= "echo off\n" (out "echo")))
          (finally (reset! engine/echo nil) (engine/stop!)))))))

(deftest see-shows-effect-then-description
  (is (= "dup ( a -- a a )\n  copy the top\n" (out "\"dup\" see")))
  (is (= (out "\"dup\" see") (out "'dup see")) "a quoted name too")
  (is (str/starts-with? (out "'play/stop see") "play/stop ( -- )") "qualified"))
