(ns ^:repl musics.mu.kernel-test
  "mu's kernel words: printing, numbers, control, bindings, the repo,
   playback (on a silent engine), namespaces, help, files, defaults."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.core :as m]
            [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.print :as p]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh
  "A fresh registry with a :ROOT and a mu state with only the kernel
   words; mu's state is restored afterwards."
  [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc-in (e/fresh-state) [:ns "kernel"] (get-in saved [:ns "kernel"])))
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
  (is (= "mu<2>\n0> [C4/4 D4/4]\n1> 7\n" (out "7 [c d] .s")) "top first, numbered as pick counts")
  (is (= "mu<0>\n" (out ".s"))))

(deftest numbers
  (is (= [3 1/4 6 1/2 2 -3 3 2 4 3 0.75 3/4]
         (:ok (run "1 2 .+  1/2 1/4 .-  2 3 .*  1 2 ./  9 7 mod  3 neg  -3 abs  2 3 min  4 3 max  7/2 int  3/4 float  0.75 rational"))))
  (is (= [3] (:ok (run "2.7 round"))))
  (is (re-find #"\.\+: needs two numbers" (:error (run "1 \"x\" .+"))))
  (is (re-find #"\+: is musical; on numbers write \.\+" (:error (run "1 2 +"))))
  (is (re-find #"<: isn't a word; to compare numbers write \.<" (:error (run "1 1 <")))))

(deftest comparisons-and-control
  (is (= [true false true true] (:ok (run "1 2 .<  1 2 .=  1 1 .=  2 2 .>="))))
  (is (= [:yes] (:ok (run "1 2 .< '( :yes ) '( :no ) if"))))
  (is (= [:no] (:ok (run "2 1 .< '( :yes ) '( :no ) if"))))
  (is (= [5] (:ok (run "5 1 2 .> '( pop ) when"))) "when false: nothing")
  (is (= ["C4/4" "D4/4" "E4/4"] (mapv p/show (:ok (run "[c d e] '( ) each")))) "each: every element")
  (is (= [6] (:ok (run "'[1 2 3] 0 '( .+ ) reduce")))))

(deftest bindings
  (is (= [7 7] (:ok (run "'shift 7 =" "\\shift \\shift"))))
  (is (= ["'shift"] (mapv p/show (:ok (run "'shift")))) "a name, held")
  (testing "music bound to a name goes in a literal, as a text variable does"
    (run "'m [!mf c d e] =")
    (is (= "[!mf C4/4 D4/4 E4/4 F4/4]" (p/show (first (:ok (run "[\\m f]"))))))
    (run "'k c4 =")
    (is (= "[C4/4 C4/4 E4/4]" (p/show (first (:ok (run "[\\k \\k e]"))))))
    (run "'n {[c d] [e f]} =")
    (is (= "[{[C4/4 D4/4] [E4/4 F4/4]} G3/4]" (p/show (first (:ok (run "[\\n g]"))))))
    (is (:ok (run "[\\n g] :both R!")))
    (is (= :PAR (:type (repo/current (first (:children (repo/current :both))))))
        "stored with what it binds"))
  (testing "a rebinding is seen from then on"
    (run "'m [g a] =")
    (is (= "[G3/4 A3/4 B3/4]" (p/show (first (:ok (run "[\\m b]")))))))
  (is (re-find #"nothing is bound to zz" (:error (run "\\zz"))))
  (is (re-find #"nothing is bound to zz" (:error (run "[\\zz]"))))
  (is (re-find #"holds 7 -- only music" (:error (run "'q 7 =" "[\\q]"))))
  (is (re-find #"bound in this input" (:error (run "'m [c d] = [\\m e]"))))
  (is (re-find #"=: needs a name under the value" (:error (run "3 4 =")))))

(deftest storing-and-fetching
  (testing "an explicit name"
    (is (= "" (out "[c4 d e] :motif R!")))
    (is (= "[motif: C4/4 D4/4 E4/4]" (p/show (first (:ok (run ":motif R@"))))) "fetched, with its id")
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
    (is (= "[C4/4 D4/4]" (p/show (first (:ok (run "[c d] R! R@"))))) "so it can be fetched")
    (is (= [:p] (:ok (run "'id [x: c] :p R! =" "\\id"))) "or bound"))
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
        (is (:ok (run "[g] >>")) ">> is play+")
        (is (= 3 (count @(:voices engine/*engine*))))
        (is (:ok (run "[g] >")) "> is play")
        (is (= 1 (count @(:voices engine/*engine*))) "play stops everything first")
        (engine/stop!)))))

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

(deftest clear-clears-the-screen
  (is (= "\u001b[H\u001b[2J" (out "1 2 clear")))
  (is (= [1 2] (:ok (run "1 2 clear"))) "the stack stays"))

(deftest the-defaults
  (let [d (first (:ok (run "defaults")))]
    (is (str/starts-with? (p/show d) "^{ "))
    (is (str/includes? (p/show d) "!Tempo:100"))
    (is (= (p/show d) (p/show (first (:ok (run (p/show d)))))) "they read back")))

(deftest arithmetic-on-material
  (is (= "[D4/4 E4/4]" (p/show (first (:ok (run "[c d] 2 +"))))) "+ transposes up")
  (is (= "[A#3/4 C4/4]" (p/show (first (:ok (run "[c d] 2 -"))))) "- transposes down")
  (is (= "[C3/4 D3/4]" (p/show (first (:ok (run "[c d] -12 +"))))) "or + with a negative number")
  (is (= "C4/8" (p/show (first (:ok (run "c4 1/2 *"))))) "* scales durations")
  (is (= "[C4/8 D4/8]" (p/show (first (:ok (run "[c d] 2 /"))))) "/ divides them")
  (is (= "r2" (p/show (first (:ok (run "r4 2 *"))))) "a rest moves in time")
  (is (= "r4" (p/show (first (:ok (run "r4 2 +"))))) "but not in pitch"))

(deftest arithmetic-keeps-bar-lines
  (is (= "[C4/1*1/12 D4/1*1/12 | E4/1*1/12 F4/1*1/12]" (p/show (first (:ok (run "[c d | e f] 1/3 *"))))))
  (is (= "[D4/4 | E4/4]" (p/show (first (:ok (run "[c | d] 2 +")))))))

(deftest hairpins
  (is (= "[!vol:50.0< C4/4 D4/4 E4/4 F4/4 !vol:90]" (p/show (first (:ok (run "[c d e f] 90 cr")))))
      "a crescendo from the line's volume (here the defaults') to 90")
  (is (= "[!mf !vol:60> C4/4 D4/4 !vol:30]" (p/show (first (:ok (run "[!mf c d] 30 decr")))))
      "a decrescendo from the line's own mf, after its leading instructions")
  (let [shown (p/show (first (:ok (run "[c d e f] 90 cr"))))]
    (is (= shown (p/show (first (:ok (run shown))))) "reads back the same"))
  (is (re-find #"target volume 0-100" (:error (run "[c d] 200 cr")))))

(deftest inspecting-the-repo
  (run "[!tempo:90 [inner: c d] e] :verse R!" "[\\repeat unfold 2 [:inner] f] :chorus R!" "^{ !ff } :loud R!")
  (testing "each prints what its musics.core namesake gives at the REPL"
    (is (= (with-out-str (prn (m/ids))) (out "ids")))
    (is (= (with-out-str (prn (m/usages :inner))) (out ":inner usages")))
    (is (= (with-out-str (m/ctx :inner)) (out ":inner ctx")))
    (is (= (with-out-str (m/ctx :verse)) (out ":verse R@ ctx")) "fetched material works too"))
  (is (= ":inner: (empty)\n:verse: Tempo=90@0\n" (out ":inner ctx")) "the enclosing tempo is in the chain")
  (is (re-find #"ctx: needs the name of something stored" (:error (run ":nope ctx"))))
  (is (re-find #"usages: needs the name of something stored" (:error (run "[c d] usages")))))
