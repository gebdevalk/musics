(ns musics.mu.suggest
  "What mu says after an unknown word: the word it was renamed to, else
   the words that are a typo away (same letters but for one or two), else
   where to look. Pure: it reads the namespaces and the ones in use from
   the state it is given."
  (:require [clojure.string :as str]))

(def renamed
  "Names mu used to have, or that another language has, and what is
   written now."
  {"add" "+", "sub" "-", "mul" "*", "div" "/", "inc" "1+", "dec" "1-"
   "lt" "<", "gt" ">", "le" "<=", "ge" ">=", "eq" "=", "ne" "<>", "neg" "negate"
   "pop" "drop", "up" "+", "down" "-", "stretch" "*", "shrink" "/"
   "reverse" "retrograde", "rest" "tail", "first" "head", "size" "length", "len" "length", "count" "length"
   "print" ".", "show" "see", "quit" "bye", "exit" "bye", "loop" "cycle"
   "repeat" "times", "context-of" "setting"
   "line" "[2] [3] … (the count in the sign: [c d] [e f] [2])", "together" "{2} {3} … (the count in the sign)", "beside" "{+}"
   "ROT" "rotate (the stack's is rot)", "SFL" "shuffle", "TRP" "transpose"})

(defn distance
  "The number of edits (an insertion, a deletion, a changed letter or two
   neighbouring letters swapped) from a to b."
  [a b]
  (let [a (vec a) b (vec b) m (count a) n (count b)
        start (vec (for [i (range (inc m))]
                     (vec (for [j (range (inc n))] (cond (zero? i) j (zero? j) i :else 0)))))
        d (reduce
           (fn [d i]
             (reduce
              (fn [d j]
                (let [cost (if (= (a (dec i)) (b (dec j))) 0 1)
                      v (min (inc (get-in d [(dec i) j])) (inc (get-in d [i (dec j)]))
                             (+ (get-in d [(dec i) (dec j)]) cost))
                      v (if (and (> i 1) (> j 1) (= (a (dec i)) (b (- j 2))) (= (a (- i 2)) (b (dec j))))
                          (min v (inc (get-in d [(- i 2) (- j 2)])))
                          v)]
                  (assoc-in d [i j] v)))
              d (range 1 (inc n))))
           start (range 1 (inc m)))]
    (get-in d [m n])))

(defn- pool
  "Every word of every namespace: {:shown how to write it, :bare the name
   without its namespace, :used? it is written without one}."
  [st]
  (for [[ns ws] (:ns st) w (keys ws)
        :let [used? (boolean (some #{ns} (:use st)))]]
    {:shown (if used? w (str ns "/" w)) :bare w :full (str ns "/" w) :used? used?}))

(defn close-words
  "Up to n words written like name but for a typo or two, nearest first,
   the ones in use before the others; an exact name in a namespace not in
   use first of all, written with its namespace."
  [st name n]
  (when (re-find #"^[a-zA-Z]" name)
    (let [limit (if (<= (count name) 3) 1 2)
          qualified? (str/includes? name "/")
          scored (for [{:keys [shown bare full used?]} (pool st)
                       :when (re-find #"^[a-zA-Z]" bare)
                       :let [d (distance name (if qualified? full bare))]
                       :when (<= d limit)]
                   [d (if used? 0 1) shown])]
      (->> scored sort (map #(nth % 2)) distinct (take n) vec))))

(defn hint
  "What to add after `unknown word: name`, or nil."
  [st name]
  (if-let [now (renamed name)]
    (str "it is now written " now)
    (if (contains? (:bindings st) name)
      (str name " is a value, not a word: \\" name " puts it on the stack")
      (let [close (close-words st name 3)]
      (if (seq close)
        (str "did you mean " (str/join ", " close) "?")
        (if (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*" name)
          (str "about " name " looks for it; words lists them")
          "words lists them, help shows the way"))))))
