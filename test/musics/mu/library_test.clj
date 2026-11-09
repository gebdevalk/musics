(ns ^:repl musics.mu.library-test
  "The stack effects mu shows (help, see, Tab): read as Forth does, a result
   named like an input is that input, x' its changed version, any other name
   a new thing, ... any number of them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library :as lib]
            [musics.mu.tools]))

(defn- words
  "[ns word entry] for every word registered from Clojure."
  []
  (for [[ns ws] (:ns @e/state), [w entry] ws, :when (:effect entry)] [ns w entry]))

(defn- head-of
  "The first ( … ) of an effect string."
  [s] (re-find #"^\(.*?\)" (str s)))

(defn- alternatives
  "[min max] input counts of each ( … -- … ) in an effect string; an input
   in brackets is optional, one ending in ... is any number."
  [s]
  (for [[_ ins] (re-seq #"\(([^()]*?) --" (str/replace (str s) #"\(\s*\)" ""))
        :let [names (remove str/blank? (str/split (str/trim ins) #"\s+"))
              opt   (count (filter #(str/starts-with? % "[") names))]]
    [(- (count names) opt) (count names)]))

(deftest no-unicode-ellipsis-in-an-effect
  (doseq [[ns w entry] (words)
          :let [head (head-of (:effect entry))]
          :when head]
    (is (not (str/includes? head "…")) (str ns "/" w " writes ... in ascii: " head))))

(deftest the-table-matches-the-functions
  (let [vars (into {} (for [[_ w entry] (words) :when (:var entry)] [w (:var entry)]))]
    (doseq [[w eff] lib/stack-effects]
      (testing w
        (is (contains? vars w) "names a word registered from a function")
        (when-let [v (vars w)]
          (let [alts (alternatives eff)
                counts (set (map count (:arglists (meta v))))]
            (is (seq alts))
            (doseq [n counts]
              (is (some (fn [[lo hi]] (<= lo n hi)) alts)
                  (str w " takes " n " but its effect " eff " does not")))))))))

(deftest a-result-named-like-an-input-is-that-input
  (testing "a word that changes its first argument primes the name"
    (doseq [w ["take" "filter" "sort" "retrograde" "delay" "with" "+"]
            :let [eff (:effect (e/lookup @e/state w))
                  [_ outs] (re-find #"^\( .*? -- (.*?) \)" eff)]]
      (is (str/ends-with? outs "'") (str w " " eff))))
  (testing "no word is named by itself in its own result, but for the readers"
    (doseq [w ["R@" "fetch" "store" "find" "map" "at" "bar" "setting" "select" "iterate" "slice" "nth" "concat"]
            :let [eff (:effect (e/lookup @e/state w))
                  [_ outs] (re-find #" -- (.*?) \)" eff)]]
      (is (not= w (str/trim outs)) (str w " " eff)))))
