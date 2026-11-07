(ns ^:repl musics.mu.suggest-test
  "What mu says after an unknown word, and the help word."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.suggest :as s]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (:ns saved)))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- error [input] (:error (e/eval-input input)))
(defn- out [input] (with-out-str (e/eval-input input)))

(deftest distance-counts-typos
  (is (= 0 (s/distance "dup" "dup")))
  (is (= 1 (s/distance "dup" "dupp")))
  (is (= 1 (s/distance "swap" "swep")))
  (is (= 1 (s/distance "swap" "sawp")) "two neighbours swapped is one edit")
  (is (= 2 (s/distance "retrograde" "retrogrde-")))
  (is (= 3 (s/distance "abc" "xyz"))))

(deftest an-unknown-word-suggests-its-nearest
  (is (re-find #"^unknown word: retrogrde -- did you mean retrograde\?" (error "retrogrde")))
  (is (re-find #"did you mean insert" (error "[c d] 1 g insrt")))
  (is (re-find #"did you mean (swap|over)" (error "1 2 swpa")) "a swapped pair")
  (testing "nothing close says where to look"
    (is (re-find #"^unknown word: zzzzzz -- about zzzzzz looks for it; words lists them$" (error "zzzzzz")))))

(deftest a-renamed-word-says-what-replaced-it
  (doseq [[old new] [["add" "+"] ["lt" "<"] ["eq" "="] ["neg" "negate"] ["pop" "drop"] ["up" "+"] ["quit" "bye"]]]
    (is (re-find (re-pattern (str "^unknown word: " old " -- it is now written " (java.util.regex.Pattern/quote new) "$"))
                 (error (str "1 " old)))
        old)))

(deftest see-suggests-too
  (is (re-find #"unknown word: retrogrde -- did you mean retrograde" (out "\"retrogrde\" see"))))

(deftest help-shows-the-way-in-explains-a-word-and-searches
  (let [guide (out "help")]
    (is (str/includes? guide "mu: music is written as text"))
    (is (str/includes? guide "->   -|>   ->+") "the play words as they are written now")
    (is (str/includes? guide "doc/md/mu-tutorial.md")))
  (is (= (out "\"dup\" see") (out "\"dup\" help")) "a word: as see")
  (is (= (out "'dup see") (out "'dup help")))
  (testing "other text lists the words that mention it, names first"
    (let [hits (out "\"chor\" help")
          lines (str/split-lines hits)]
      (is (str/starts-with? (first lines) "chord") "names that contain it first")
      (is (str/includes? hits "chord-name"))
      (is (every? #(re-find #"\S" %) lines))))
  (is (str/includes? (out "\"nonsensenotaword\" help") "nothing mentions \"nonsensenotaword\" -- "))
  (testing "a long list is cut"
    (is (re-find #"… and \d+ more" (out "\"a\" help")))))
