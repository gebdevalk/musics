(ns ^:repl musics.recipe-test
  "Tree recipes, @<< … >> (musics.recipe): an algo tree written in RPN,
   its settings collected into one flat tctx and resolved by the naming
   rule, music taken from the stack under it, run lazily."
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
  (let [text "@<< ^{ seed:7 } k:3 n:8 euclid len:8 pitch/walk zip >>"]
    (is (= text (top text)) "a value of its own, printed as written")
    (is (= 0 (:arity (recipe text))) "a generator takes nothing from the stack")))

(deftest words-build-the-tree-on-a-stack
  (is (re-find #"builds \(zip \(pulses->durations \(euclid\)\)"
               (with-out-str (top "@<< euclid lorenz zip >> see")))
      "an algo takes its inputs from the stack; bridges are put in")
  (is (= "[C4/8. E4 G4/8]" (top "@<< k:3 n:8 euclid '[60 64 67] [~] zip >> run")) "data, looped")
  (is (= "[C4/8. G4 C5/8]" (top "@<< 'deg k:3 euclid '[0 4 7] [~] degrees->pitches zip >> run")) "an algo on data")
  (is (re-find #"^\{" (top "@<< k:3 euclid '[60 64 67] [~] zip '[1/4] '[72] zip par >> run")) "par")
  (is (= "[C4/4 D4]" (top "@<< | '[1/4 1/4] '[60 62] zip >> run")) "all above a | mark"))

(deftest settings-resolve-by-the-naming-rule
  (testing "the default"
    (is (= "3" (top "@<< duration/euclid '[60] [~] zip >> run count"))))
  (testing "the head's plain name reaches every node"
    (is (= "5" (top "@<< ^{ k:5 } duration/euclid '[60] [~] zip >> run count"))))
  (testing "the algo's name before the plain one"
    (is (= "4" (top "@<< ^{ k:5 duration/euclid.k:4 } duration/euclid '[60] [~] zip >> run count"))))
  (testing "a node's own param, written right before it: a local override"
    (is (= "2" (top "@<< ^{ k:5 } k:2 duration/euclid '[60] [~] zip >> run count"))))
  (testing "an alias: its own value, its own name"
    (is (= "3" (top "@<< ^{ k:5 } 'bass k:3 duration/euclid '[60] [~] zip >> run count")))
    (is (= "6" (top "@<< ^{ k:5 bass.k:6 } 'bass duration/euclid '[60] [~] zip >> run count")))))

(deftest settings-given-from-outside
  (is (= "3" (top "@^{ k:3 n:8 len:8 } @<< euclid pitch/walk zip >> run count"))
      "a recipe without values, given its settings at use")
  (is (= "2" (top "@^{ k:5 } @<< k:2 duration/euclid '[60] [~] zip >> run count")) "a node's own value still wins")
  (is (re-find #"kk is not a param" (top "@^{ kk:3 } @<< euclid >> run")) "checked like the head"))

(deftest collecting-checks-every-setting
  (is (re-find #"kk is not a param" (top "@<< ^{ kk:3 } euclid >>")) "a typo in the head")
  (is (re-find #"zz is not a param" (top "@<< zz:3 euclid >>")) "a typo before an algo")
  (is (re-find #"at most" (top "@<< k:99 euclid >>")) "out of range")
  (is (re-find #"written twice.*give one an alias"
               (top "@<< k:3 duration/euclid k:5 duration/euclid zip >>")) "two un-aliased writers of one param")
  (is (re-find #"is no algo" (top "@<< nope >>")))
  (is (re-find #"leaves 2" (top "@<< euclid lorenz >>")) "one tree")
  (is (re-find #"param or alias is left" (top "@<< euclid k:3 >>"))))

(deftest music-comes-from-the-stack
  (is (= 1 (:arity (recipe "@<< dup duration/emi zip >>"))) "what the tree takes from below is its input")
  (let [out (top "[c8 d e f g a b c] @<< dup duration/emi zip >> run")]
    (is (str/starts-with? out "[C4") "the line, its durations varied, written back on it"))
  (is (re-find #"(?i)parse|expected|music" (top "@<< [c d] euclid zip >>")) "no music written inside")
  (is (re-find #"needs 1" (top "@<< dup duration/emi zip >> run")) "an input is needed"))

(deftest dup-is-one-node
  (let [x (recipe "@<< len:8 uniform dup numbers->pitches swap numbers->volumes '[1/8] [~] swap zip swap zip >>")
        shared (keep :share (tree-seq map? :children (:tree x)))]
    (is (= 2 (count shared)) "the dup'd node is used twice")
    (is (apply = shared) "both uses are one node, read once per run")
    (is (= 8 (count (r/result x))))))

(deftest a-recipe-is-lazy
  (let [x (recipe "@<< '[1/8] [~] '[60 62] [~] zip >>")]
    (is (= 32 (count (take 32 (r/result x)))) "an endless recipe gives what is taken of it")
    (is (re-find #"endless" (top "@<< '[1/8] [~] '[60 62] [~] zip >> run")))))

(deftest a-seed-gives-one-result
  (let [run #(top "@<< ^{ seed:7 } k:3 n:8 duration/euclid len:8 pitch/walk zip >> run")]
    (is (= (run) (run)))
    (is (= (run) (top "@<< ^{ seed:7 } k:3 n:8 euclid len:8 pitch/walk zip >> run"))
        "a derived algo, and its source with the bridge put in, give the same")))
