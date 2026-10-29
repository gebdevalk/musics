(ns ^:repl musics.recipe-test
  "Recipes, the algo tree written @( ) @[ ] @{ } with its head @^{ }
   (musics.recipe): read and printed back, settings collected into one
   flat tctx and resolved by the naming rule, run lazily."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.print :as p]
            [musics.mu.tools]
            [musics.recipe :as r]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(use-fixtures :each
  (fn [t]
    (let [saved @e/state]
      (try
        (with-fresh-registries
          (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "tool" "music" "play"])))
          (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
          (t))
        (finally (reset! e/state saved))))))

(defn- top [input]
  (swap! e/state assoc :stack [])
  (let [r (e/eval-input input)]
    (if (:error r) (:error r) (str/join " " (map p/show (:ok r))))))

(defn- recipe [input]
  (swap! e/state assoc :stack [])
  (e/eval-input input)
  (peek (:stack @e/state)))

(deftest a-recipe-reads-and-prints-back
  (let [text "@^{ seed:7 } @[ @( pulses->durations @( euclid k:3 n:8 ) ) @( pitch/walk len:8 ) ]"]
    (is (= text (top text)) "a value of its own, printed as written")
    (is (= "@( bass euclid k:3 )" (top "@( bass euclid k:3 )")) "an alias")))

(deftest settings-resolve-by-the-naming-rule
  (testing "the default"
    (is (= "3" (top "@[ @( duration/euclid ) @( cycle> '[60] ) ] run count"))))
  (testing "the head's plain name reaches every node"
    (is (= "5" (top "@^{ k:5 } @[ @( duration/euclid ) @( cycle> '[60] ) ] run count"))))
  (testing "the algo's name before the plain one"
    (is (= "4" (top "@^{ k:5 duration/euclid.k:4 } @[ @( duration/euclid ) @( cycle> '[60] ) ] run count"))))
  (testing "a node's own value: a local override, over the head's plain name"
    (is (= "2" (top "@^{ k:5 } @[ @( duration/euclid k:2 ) @( cycle> '[60] ) ] run count"))))
  (testing "an alias: its own value, its own name"
    (is (= "3" (top "@^{ k:5 } @[ @( bass duration/euclid k:3 ) @( cycle> '[60] ) ] run count")))
    (is (= "6" (top "@^{ k:5 bass.k:6 } @[ @( bass duration/euclid ) @( cycle> '[60] ) ] run count")))))

(deftest collecting-checks-every-setting
  (is (re-find #"kk is not a param" (top "@^{ kk:3 } @( euclid )")) "a typo in the head")
  (is (re-find #"zz is not a param" (top "@( euclid zz:3 )")) "a typo in a node")
  (is (re-find #"at most 32" (top "@( euclid k:99 )")) "out of range")
  (is (re-find #"written twice.*give one of them an alias"
               (top "@[ @( duration/euclid k:3 ) @( duration/euclid k:5 ) ]"))
      "two un-aliased nodes of one algo writing one param: the tctx is flat")
  (is (= "3 5" (top "@[ @( a duration/euclid k:3 ) @( cycle> '[60] ) ] run count @[ @( b duration/euclid k:5 ) @( cycle> '[60] ) ] run count"))
      "with aliases, each its own")
  (is (re-find #"no algo of that name" (top "@( nope )"))))

(deftest zip-and-par
  (is (= "[C4/8 D4/4]" (top "@[ '[1/8 1/4] '[60 62 64] ] run")) "a literal gives durations, then pitches")
  (is (= "[C4/8. E4 G4/8]" (top "@[ [c e g] @( duration/euclid k:3 n:8 ) ] run")) "a line is written on, as zip writes on one")
  (is (re-find #"name the part a literal gives" (top "@[ [c e g] '[1/8] ] run")) "an untyped literal onto notes is refused")
  (is (re-find #"is a setting, no input" (top "@[ k:5 '[1/4] '[60] ]")))
  (is (= "{[C4/4] [C5/8]}" (top "@{ @[ '[1/4] '[60] ] @[ '[1/8] '[72 74] ] } run")) "lines side by side")
  (is (re-find #"par takes two lines" (top "@{ @[ '[1/4] '[60] ] }"))))

(deftest a-recipe-is-lazy
  (let [x (recipe "@[ @( cycle> '[1/8] ) @( cycle> '[60 62] ) ]")]
    (is (= 32 (count (take 32 (r/result x)))) "an endless recipe gives what is taken of it")
    (is (re-find #"endless -- play it, or take some" (top "@[ @( cycle> '[1/8] ) @( cycle> '[60 62] ) ] run")))))

(deftest a-seed-gives-one-result
  (let [run #(top "@^{ seed:7 } @[ @( duration/euclid k:3 n:8 ) @( pitch/walk len:8 ) ] run")]
    (is (= (run) (run)))
    (is (= (run) (top "@^{ seed:7 } @[ @( pulses->durations @( euclid k:3 n:8 ) ) @( pitch/walk len:8 ) ] run"))
        "a derived algo and its source with its bridge give the same")))
