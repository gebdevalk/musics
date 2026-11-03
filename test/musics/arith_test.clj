(ns ^:repl musics.arith-test
  "+ - * / on everything: numbers, data lists and streams of numbers,
   music, and mixtures of them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.print :as p]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh [t]
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

(deftest numbers-are-added-subtracted-multiplied-and-divided
  (is (= "5 1/4 12 1/4" (top "2 3 +  1/2 1/4 -  3 4 *  1 4 /")))
  (is (re-find #"/: division by zero" (top "1 0 /")))
  (is (re-find #"\+: needs two numbers" (top "1 \"x\" +"))))

(deftest a-data-list-works-element-by-element
  (is (= "'[72 74]" (top "'[60 62] 12 +")))
  (is (= "'[48 50]" (top "'[60 62] 12 -")))
  (is (= "'[2 4 6]" (top "'[1 2 3] 2 *")))
  (is (= "'[1 2]" (top "'[2 4] 2 /")))
  (testing "the number on either side, in the order written"
    (is (= "'[9 8 7]" (top "10 '[1 2 3] -")))
    (is (= "'[1/2 1/4]" (top "1 '[2 4] /"))))
  (testing "two lists in step, as long as the shorter"
    (is (= "'[11 22]" (top "'[1 2 3] '[10 20] +")))
    (is (= "'[10 40 90]" (top "'[1 2 3] '[10 20 30] *"))))
  (is (= "'[]" (top "'[] 1 +")) "an empty list stays one")
  (is (re-find #"needs numbers, got :a" (top "'[1 :a] 1 +")) "a bad element is named, at once"))

(deftest a-stream-works-lazily
  (is (= "'[72 74]" (top "[c d] pitches 12 +")) "a line's pitches")
  (is (= "'[1 3 5 7]" (top "1 '( 2 + ) iterate 4 take")))
  (is (= "'[2 4 6]" (top "1 '( 1 + ) iterate 2 * 3 take")) "an endless stream stays endless")
  (is (= "'[11 22]" (top "'[1 2] [~] '[10 20] + 2 take")) "a loop with a list: the finite one's length"))

(deftest music-with-a-number-is-up-down-stretch-shrink
  (is (= (top "[c e g] 7 up") (top "[c e g] 7 +")))
  (is (= (top "[c e g] 12 down") (top "[c e g] 12 -")))
  (is (= (top "[c d] 2 stretch") (top "[c d] 2 *")))
  (is (= (top "[c d e] 3 shrink") (top "[c d e] 3 /")))
  (is (= "C4/4" (top "c 0 +")) "a note too")
  (is (re-find #"^up: is for music; on numbers write \+" (top "'[1 2] 3 up")))
  (is (re-find #"^up: is for music" (top "1 2 up"))))

(deftest what-fits-no-kind-is-refused-naming-both
  (is (re-find #"\+: needs two numbers, a list of numbers and a number, or music and a number, got \[C4/4\] and \[E4/4\]"
               (top "[c] [e] +")))
  (is (re-find #"\*: needs two numbers" (top "5 [c] *")) "a number first, music second"))
