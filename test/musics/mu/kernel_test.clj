(ns ^:repl musics.mu.kernel-test
  "mu's kernel words: printing, numbers, control, variables, the repo,
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
  (is (= "<2> 7 [C4/4 D4/4]\n" (out "7 [c d] .s"))))

(deftest numbers
  (is (= [3 1/4 6 1/2 2 -3 3 2 4 3 0.75 3/4]
         (:ok (run "1 2 +  1/2 1/4 -  2 3 *  1 2 /  9 7 mod  3 neg  -3 abs  2 3 min  4 3 max  7/2 int  3/4 float  0.75 rational"))))
  (is (= [3] (:ok (run "2.7 round"))))
  (is (re-find #"\+: needs two numbers" (:error (run "1 \"x\" +")))))

(deftest comparisons-and-control
  (is (= [true false true] (:ok (run "1 2 <  1 2 =  1 1 ="))))
  (is (= [:yes] (:ok (run "1 2 < '( :yes ) '( :no ) if"))))
  (is (= [:no] (:ok (run "2 1 < '( :yes ) '( :no ) if"))))
  (is (= [5] (:ok (run "5 1 2 > '( pop ) when"))) "when false: nothing")
  (is (= ["C4/4" "D4/4" "E4/4"] (mapv p/show (:ok (run "[c d e] '( ) each")))) "each: every element")
  (is (= [6] (:ok (run "'[1 2 3] 0 '( + ) reduce")))))

(deftest variables
  (is (= [7 7] (:ok (run "variable shift" "7 shift !" "shift @ shift @"))))
  (is (re-find #"@: needs a variable" (:error (run "7 @")))))

(deftest storing-and-fetching
  (testing "an explicit name"
    (is (= ":motif\n" (out "[c4 d e] :motif R!")))
    (is (= "[motif: C4/4 D4/4 E4/4]" (p/show (first (:ok (run ":motif R@"))))) "fetched, with its id")
    (is (some #{:motif} (:children (repo/current :ROOT))) "a top-level part"))
  (testing "the literal's own id"
    (is (= ":hook\n" (out "[hook: g a] R!"))))
  (testing "an explicit name wins over the literal's own"
    (is (= ":theme\n" (out "[hook: g a] :theme R!")))
    (is (repo/current :theme)))
  (testing "an auto id"
    (is (re-matches #":s\d+\n" (out "[e f] R!"))))
  (testing "a leaf is stored as a one-leaf line"
    (is (= ":one\n" (out "c4 :one R!")))
    (is (= "[one: C4/4]" (p/show (first (:ok (run ":one R@")))))))
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
        (is (:ok (run "[g] play")))
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

(deftest the-defaults
  (let [d (first (:ok (run "defaults")))]
    (is (str/starts-with? (p/show d) "^{ "))
    (is (str/includes? (p/show d) "!Tempo:100"))
    (is (= (p/show d) (p/show (first (:ok (run (p/show d)))))) "they read back")))

(deftest arithmetic-on-material
  (is (= "[D4/4 E4/4]" (p/show (first (:ok (run "[c d] 2 +"))))) "+ transposes by semitones")
  (is (= "[C3/4 D3/4]" (p/show (first (:ok (run "[c d] -12 +"))))) "down with a negative number")
  (is (= "C4/8" (p/show (first (:ok (run "c4 1/2 *"))))) "* scales durations")
  (is (= "r2" (p/show (first (:ok (run "r4 2 *"))))) "a rest moves in time")
  (is (= "r4" (p/show (first (:ok (run "r4 2 +"))))) "but not in pitch")
  (is (re-find #"negative number" (:error (run "[c d] 2 -"))))
  (is (re-find #"1/2 \*" (:error (run "[c d] 2 /")))))
