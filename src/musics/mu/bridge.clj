(ns musics.mu.bridge
  "mu's words as Clojure functions, interned into musics.mu.words
   (doc/md/mu-repl.md, \"From Clojure\").

   A word becomes a fn of what it takes from the stack, bottom first, in
   mu's order: (times x 3) is x 3 times, (head x) and (head x 2) are
   x [H] and x 2 [H]. It returns the one value the word leaves, nil
   for none, a vector for several. A word's name is its Clojure name;
   the letter signs are names too, as their short forms: R I RI M H T;
   = is eq, comparing music by its notes.
   line and together count their arguments (mu's [n] and {n}). A
   Clojure fn stands in for a quotation: it takes the top value and
   gives its result. (mu \"text\") runs a mu input on a fresh stack:
   a literal, or a phrase. Words clojure.core already has (take, filter,
   first …) are left out: (elements x) gives a value's elements as a
   Clojure seq for them, and line makes music of the result."
  (:require [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.mu.kernel]
            [musics.mu.tools]
            [musics.mu.feedback]
            [musics.mu.library]
            [musics.mu.print :as p]
            [musics.mu.value])
  (:import (musics.mu.value Material)))

(def namespaces
  "The namespaces whose words become fns."
  ["kernel" "tool" "music" "play" "rate"])

(def skipped
  "Words that only make sense on mu's own stack or prompt: the stack,
   control, printing, binding, namespaces, gathering to a mark."
  #{"dup" "pop" "swap" "over" "rot" "nip" "tuck" "pick" "depth" "call"
    "execute" "if" "when" "each" "reduce" "clear" "cls" "words" "use"
    "in" "seq" "par" "bye" "R!"})

(def clojure-has
  "Words clojure.core already has, by the same name and meaning (the
   argument order may differ: mu puts the music first). From Clojure,
   use clojure.core's on (elements x), and line to make music again."
  #{"abs" "mod" "not" "min" "max" "int" "float" "count" "first" "last" "nth"
    "range" "distinct" "dedupe" "shuffle" "interleave" "take" "drop"
    "take-last" "drop-last" "take-while" "drop-while" "filter" "remove"
    "map" "partition" "split-at" "iterate"})

(def short-forms
  "The letter signs, a name as they are, and = for eq (music equal
   when it has the same notes, which clojure.core's = doesn't see)."
  {"R" "[R]" "I" "[I]" "RI" "[RI]" "M" "[M]" "H" "[H]" "T" "[T]" "=" "eq"})

(def ^:private counting
  "Words that take their count after their arguments: [n] and {n}."
  #{"line" "together"})

(defn clojure-name?
  "A word name that is also a Clojure symbol a fn can carry."
  [w]
  (boolean (re-matches #"[a-zA-Z][a-zA-Z0-9\-?!+>*]*" w)))

(defn- leaf? [x] (and (map? x) (#{:LEAF :REST :DRUM} (:type x))))

(defn- shown
  "x, a leaf or a stream marked to print as mu prints it (print-method
   dispatches on :type metadata), so the REPL shows C4/4, not the map."
  [x]
  (cond (leaf? x)                                  (vary-meta x assoc :type ::leaf)
        (and (sequential? x) (not (vector? x)))    (vary-meta x assoc :type ::stream)
        :else                                      x))

(defn- results [out]
  (case (count out) 0 nil 1 (shown (peek out)) (mapv shown out)))

(defn run
  "Run mu word w (qualified ns/word) with args on a fresh stack: the one
   value it leaves, nil for none, a vector for several."
  [w args]
  (results (e/run-word (vec args) w)))

(defn word
  "Run the mu word called w (its mu name, \"[~]\" or \"+\", or ns/word)
   with args, as mu's stack holds them: (word \"[?]\" x), (word \"+\" x 7)
   -- any word, a sign without a Clojure name too."
  [w & args]
  (run (str w) args))

(defn mu
  "Run mu text on a fresh stack: a literal (mu \"[c d e]\") or a phrase
   (mu \"[c d e] [R] 2 +\"); results as run gives them."
  [text]
  (let [r (e/read-input text)]
    (when (:incomplete r) (e/mu-error "mu: unfinished input:" text))
    (results (e/run-items [] (:items r) {:locals {} :values (:values r)}))))

(defn elements
  "x's elements as a Clojure seq, for clojure.core's take, filter, map …:
   a line's notes, a repeat's as it plays them (a loop's endlessly), a
   parallel's parts, data's values. line makes music of them again."
  [x]
  (let [xs (mt/elements x)
        ys (map shown xs)]
    (shown (if (:mu/endless (meta xs)) (with-meta ys {:mu/endless true}) ys))))

(defn- word-fn [qualified w]
  (let [{:keys [effect] v :var} (e/lookup @e/state qualified)
        f (cond
            ;; a word that is a Clojure function: that function itself
            v           (fn [& args] (apply @v args))
            (counting w) (fn [& args] (run qualified (concat args [(count args)])))
            :else        (fn [& args] (run qualified args)))]
    [f (str (or (:doc (meta v)) effect))]))

(defn fns
  "{name [f doc]}: every word of namespaces with a Clojure name, not
   skipped, and the short forms."
  []
  (let [st @e/state
        words (for [ns namespaces
                    w  (sort (keys (get-in st [:ns ns])))
                    :when (and (clojure-name? w) (not (skipped w)) (not (clojure-has w)))]
                [w (word-fn (str ns "/" w) w)])
        shorts (for [[short sign] short-forms
                     :let [ns (some #(when (get-in st [:ns % sign]) %) namespaces)]]
                 [short (word-fn (str ns "/" sign) sign)])]
    (into (sorted-map) (concat words shorts [["mu" [mu (:doc (meta #'mu))]]
                                             ["elements" [elements (:doc (meta #'elements))]]
                                             ["word" [word (:doc (meta #'word))]]]))))

(defn install!
  "Intern every fn of fns into namespace ns, each with its word's doc."
  [ns]
  (doseq [[w [f doc]] (fns)]
    (intern ns (with-meta (symbol w) {:doc doc}) f)))

(defn- print-mu [x ^java.io.Writer w] (.write w (str "#mu " (pr-str (p/show x)))))

(defmethod print-method Material [x w] (print-mu x w))
(defmethod print-method ::leaf [x w] (print-mu (vary-meta x dissoc :type) w))
(defmethod print-method ::stream [x w] (print-mu (vary-meta x dissoc :type) w))
