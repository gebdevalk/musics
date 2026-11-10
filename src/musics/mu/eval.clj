(ns musics.mu.eval
  "mu's evaluator: one input is parsed, its literals read as values (all
   together, so durations and relative pitches carry over within it),
   then its items run in order on the stack (doc/md/mu-repl.md).

   Words live in namespaces, looked up when run (late binding): a
   redefinition changes every word that uses it. A name is found in the
   namespaces in use; in two of them it is an error until qualified
   (ns/word). An error leaves the stack as it was."
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [musics.common.music-elements :as el]
            [musics.input.reader.builder :as builder]
            [musics.mu.grammar :as g]
            [musics.mu.inputs :as inputs]
            [musics.mu.print :as p]
            [musics.mu.suggest :as suggest]
            [musics.mu.value :as v]
            [musics.registries :as reg]
            [musics.repo :as repo]))

;; ------------------------------------------------------------------
;; State: the stack, the namespaces, which are in use, where words go
;; ------------------------------------------------------------------

(defn fresh-state []
  {:stack [] :ns {} :use ["kernel" "tool" "music" "play" "soundfont" "mine"] :in "mine" :bindings {}})

(defonce state (atom (fresh-state)))

(defonce ^{:doc "How a seq printed while mu waits for input reaches the terminal:
   println, or the line reader's printAbove (above the prompt)."}
  print-above (atom println))

(defonce ^{:doc "How cls clears the screen: the escape codes, or the line reader's
   terminal clearing it, so the prompt that follows is drawn on the clean screen."}
  clear-screen (atom (fn [] (print "\u001b[H\u001b[2J") (flush))))

(defn mu-error
  "Throw a mu error: its message is what the user sees."
  [& parts]
  (throw (ex-info (str/join " " parts) {:mu/error true})))

;; ------------------------------------------------------------------
;; Words
;; ------------------------------------------------------------------

(defn- leaf-name?
  "Would name, written bare, read as a leaf (a note, rest, drum)?"
  [name]
  (let [r (g/parse name)]
    (and (not (g/failure? r)) (= 1 (count (rest r))) (v/leaf-node? (second r)))))

(defn check-name!
  "Refuse a word name that is a leaf: bare it would always
   be the note, never the word."
  [name]
  (when (leaf-name? name)
    (mu-error name "is a note (or rest or drum), so it can't be a word -- choose another name")))

(defn define!
  "Make entry the word name in namespace ns. An entry is {:fn (fn
   [stack] stack') :doc …} for a word written in Clojure, or {:body
   nodes :effect … :values …} for one defined with : … ;."
  [ns name entry]
  (swap! state assoc-in [:ns ns name] entry)
  name)

(defn word!
  "Define a mu word written in Clojure: (word! \"kernel\" \"dup\"
   \"( a -- a a )\" (fn [s] (conj s (peek s))))."
  [ns name effect f]
  (define! ns name {:fn f :effect effect}))

(declare run-word)

(defn same-as
  "A word's fn that runs the word ns/name, looked up when it runs: an
   alias (> for play) that follows a redefinition of what it names."
  [ns name]
  (fn [stack] (run-word stack (str ns "/" name))))

(defn need
  "Assert the stack holds at least n items for word."
  [stack n word]
  (when (< (count stack) n)
    (mu-error (str word ":") "needs" n (if (= 1 n) "item" "items") "on the stack, has" (count stack))))

(defn to-mark
  "[below above]: stack split at its nearest | mark, the mark dropped --
   what a word taking as many values as you like gathers; with no mark,
   all of the stack is gathered."
  [stack]
  (if-let [i (some #(when (v/mark? (nth stack %)) %) (range (dec (count stack)) -1 -1))]
    [(subvec stack 0 i) (subvec stack (inc i))]
    [[] stack]))

(defn lookup
  "The entry a name refers to: ns/word directly, else the one in the
   namespaces in use; nil if none, an error if several."
  [st name]
  (if-let [[_ ns w] (re-matches #"([a-zA-Z_][a-zA-Z0-9_\-?>+]*)/(.+)" name)]
    (get-in st [:ns ns w])
    (let [found (keep (fn [ns] (when-let [e (get-in st [:ns ns name])] [ns e])) (:use st))]
      (case (count found)
        0 nil
        1 (second (first found))
        (mu-error name "is ambiguous, in" (str/join " and " (map first found))
                  "-- write" (str/join " or " (map #(str (first %) "/" name) found)))))))

;; ------------------------------------------------------------------
;; Running items
;; ------------------------------------------------------------------

(declare run-items)

(defn- number-value [tag text]
  (case tag
    :SignedInt   (Long/parseLong (str/replace text #"^\+" ""))
    :SignedFloat (Double/parseDouble text)
    :SignedRatio (let [[n d] (str/split (str/replace text #"^\+" "") #"/")]
                   (/ (Long/parseLong n) (Long/parseLong d)))))

(defn- effect-word?
  "Is v a function done for its effect (its name ends in !), leaving nothing?"
  [v]
  (str/ends-with? (str (:name (meta v))) "!"))

(defn- split-tail
  "[fixed tail] of an arglist: its arguments before a `&`, and the form after it
   (a destructuring map such as {:keys [rotation]}, or a symbol), nil without one."
  [al]
  (let [[fixed more] (split-with #(not= '& %) al)]
    [(vec fixed) (second more)]))

(defn- tail-keys
  "The keywords a `& {:keys [..]}` tail reads, nil for any other tail."
  [tail]
  (when (and (map? tail) (:keys tail)) (set (map keyword (:keys tail)))))

(defn- keyword-pairs
  "How many :key value pairs on top of stack belong to a keyword tail: pairs
   whose key the tail reads (any keyword for a tail that names none), leaving
   the nfixed arguments below them."
  [stack tail nfixed]
  (let [ks (tail-keys tail)]
    (loop [n 0]
      (let [i (- (count stack) (* 2 (inc n)))]
        (if (and (>= i nfixed) (keyword? (stack i)) (or (nil? ks) (ks (stack i))))
          (recur (inc n))
          n)))))

(defn- arity-for
  "The fixed arguments of v to call with what stack holds (an arglist up to
   its `&`): the longest one whose arguments past the shortest's all pass
   their :optional test (v's :mu metadata, by argument name) on the values in
   their places; a longer arity whose arguments have no test is never taken --
   unless the function is an algorithm that names its full arity (:algo :arity),
   taken when the stack has that many."
  [v stack]
  (let [lists    (sort-by count (map (comp first split-tail) (:arglists (meta v))))
        base     (count (first lists))
        optional (:optional (:mu (meta v)))
        choose   (:arity (:mu (meta v)))
        ;; an algorithm says how many arguments a full call takes (:algo :arity):
        ;; called as a word it takes them all, its defaults being the registry's
        full     (:arity (:algo (meta v)))]
    (or (when choose (let [n (choose stack)] (first (filter #(= n (count %)) lists))))
        (when (and full (<= full (count stack))) (first (filter #(= full (count %)) lists)))
        (last (filter (fn [al]
                        (let [extra (drop base al)]
                          (and (<= (count al) (count stack))
                               (every? true? (map (fn [p x] (boolean (when-let [t (get optional (keyword p))] (t x))))
                                                  extra (take-last (count extra) stack))))))
                      lists))
        (first lists))))

(defn- named
  "The error ex, named by word name: left as it is when it is named already,
   by this word or by another it ran."
  [name ex]
  (let [msg (ex-message ex)]
    (cond
      (str/starts-with? msg (str name ":")) ex
      (re-find #"^\S+:\s" msg)            (if (:mu/error (ex-data ex)) ex (ex-info msg {:mu/error true}))
      :else (ex-info (str name ": " msg) {:mu/error true}))))

(def ^:dynamic *lookup*
  "nil, or a function of a keyword: its value, or nil when there is none. A voice
   binds it around each run (with-lookup), with the time it asks at inside it."
  nil)

(defn with-lookup
  "Call f (no arguments) with get, a function of a keyword that answers its value
   or nil, bound as *lookup*: a keyword argument of a word that get answers is
   that value (run-var). get closes over what it asks, a context chain with a tctx
   in front and the time: (musics.domain.context/getter chain beat), one for each
   leaf, so its lookups are one pull."
  [get f]
  (binding [*lookup* get]
    (f)))

(defn- from-lookup
  "stack with, in the places the arguments of the function v stand, each keyword
   that *lookup* answers replaced by its value; a keyword it does not answer
   stays, so :hoketus R@ is untouched, and so is a keyword v's own tail reads
   (3 8 :rotation 2 euclid). Unbound, stack as it is."
  [stack v]
  (if-let [get *lookup*]
    (let [lists  (map split-tail (:arglists (meta v)))
          tailks (into #{} (mapcat (comp tail-keys second)) lists)
          width  (+ (apply max 0 (map (comp count first) lists)) (* 2 (count tailks)))]
      (loop [i (max 0 (- (count stack) width))
             s stack]
        (if (< i (count s))
          (let [x     (nth s i)
                found (when (and (keyword? x) (not (tailks x))) (get x))]
            (recur (inc i) (if (some? found) (assoc s i found) s)))
          s)))
    stack))

(defn- run-var
  "Run the Clojure function v as the word name: its arguments taken from
   stack, music first; its value pushed, unless it is done for its effect.
   A keyword tail (& {:keys [..]}) takes the :key value pairs written just before the
   word, or none: 3 8 euclid, 3 8 :rotation 2 euclid. A keyword among the arguments
   that *lookup* answers is its value (:onsets 8 euclid). An error it throws
   is a mu error, named by the word."
  [stack name v]
  (let [below stack          ; what is left under the arguments stays as it was
        stack (try (from-lookup stack v)
                   (catch clojure.lang.ExceptionInfo ex (throw (named name ex))))
        al   (arity-for v stack)
        _    (try (need stack (count al) name)
                  (catch clojure.lang.ExceptionInfo ex
                    (if-let [h (:hint (:mu (meta v)))] (mu-error (ex-message ex) "--" h) (throw ex))))
        tail (some (fn [a] (let [[f t] (split-tail a)] (when (and (= f al) (map? t)) t))) (:arglists (meta v)))
        n    (- (count stack) (count al) (if tail (* 2 (keyword-pairs stack tail (count al))) 0))
        r    (try (apply @v (subvec stack n))
                  (catch clojure.lang.ExceptionInfo ex (throw (named name ex)))
                  ;; a function's :pre condition, an Error and not an Exception
                  (catch AssertionError ex
                    (mu-error (str name ":") "refused, it needs"
                              (or (second (re-find #"Assert failed: (?:.*\n)?\(?(.*?)\)?\s*$" (str (ex-message ex)))) "its arguments right"))))]
    ;; a ! function leaves nothing; so does one that answers :mu/nothing
    (if (or (effect-word? v) (= :mu/nothing r)) (subvec below 0 n) (conj (subvec below 0 n) r))))

(defn- binds?
  "Is this stack-effect input one that binds a local ($name)?"
  [s] (str/starts-with? s "$"))

(defn- plain [s] (str/replace s #"^\$" ""))

(defn- variadic? [s] (or (str/ends-with? s "...") (str/ends-with? s "…")))

(defn- same-value? [a b]
  (or (identical? a b) (and (not (seq? a)) (not (seq? b)) (= a b))))

(defn- check-effect!
  "A word's stack effect is kept: it leaves as many values as it names after
   --, and a result named like an input without a prime (x for x) is that
   input. Not checked where a name ends in ... (any number)."
  [name {:keys [in out]} depth args result]
  (when-not (some variadic? (concat in out))
    (let [below (- depth (count in))
          left  (- (count result) below)]
      (when (not= left (count out))
        (mu-error (str name ":") "leaves" left (if (= 1 left) "value" "values")
                  "-- its stack effect says" (count out)))
      (let [given (zipmap (map plain in) args)]
        (doseq [[o v] (map vector out (subvec result below))
                :when (and (contains? given o) (not (same-value? v (get given o))))]
          (mu-error (str name ":") (str o) "is the same on both sides of its stack effect, but the word changed it -- write"
                    (str o "'")))))))

(defn- run-defined
  "Run a word written with : … ;. The inputs of its stack effect marked $ are
   taken off the stack and bound as locals; the others stay on it."
  [stack name entry]
  (let [{:keys [in] :as effect} (:effect entry)
        _      (need stack (count in) name)
        n      (- (count stack) (count in))
        args   (subvec stack n)
        locals (into {} (for [[s a] (map vector in args) :when (binds? s)] [(plain s) a]))
        kept   (vec (for [[s a] (map vector in args) :when (not (binds? s))] a))
        result (run-items (into (subvec stack 0 n) kept) (:body entry) {:locals locals :values (:values entry)})]
    (when effect (check-effect! name effect (count stack) args result))
    result))

(defn live-inputs
  "For a word written with : … ; that has $ inputs: its inputs as [name live?]
   pairs in the order of its stack effect (live? true for a $ input); else nil."
  [name]
  (when-let [in (some-> (lookup @state name) :effect :in)]
    (when (some binds? in) (mapv (fn [s] [(plain s) (binds? s)]) in))))

(defn run-word
  "Run the word called name on stack."
  [stack name]
  (let [entry (or (lookup @state name)
                  (mu-error (str "unknown word: " name " -- " (suggest/hint @state name))))]
    (cond
      (:var entry) (run-var stack name (:var entry))
      (:fn entry)  (try ((:fn entry) stack)
                        (catch clojure.lang.ExceptionInfo ex
                          (if (:mu/error (ex-data ex)) (throw (named name ex)) (throw ex))))
      :else
      (run-defined stack name entry))))

(defn call
  "Run a quotation on stack."
  [stack q]
  (when-not (v/quotation? q) (mu-error "call: expected a quotation '( … ), got" (p/show q)))
  (if (fn? q)
    (do (need stack 1 "a Clojure fn") (conj (pop stack) (q (peek stack))))
    (run-items stack (:body q) (:env q))))

(defn- node-name [node]
  (let [x (second node)]
    (if (vector? x) (second x) x)))

(defn- run-item
  "Run one parse node on stack, in env ({:locals :values}): values maps a
   literal node to the value it was read as."
  [stack node env]
  (case (first node)
    (:SignedInt :SignedFloat :SignedRatio) (conj stack (number-value (first node) (second node)))
    :BareDuration (conj stack ((requiring-resolve 'musics.input.reader.walker/duration-value) node))
    :StringLit (conj stack (second node))
    :Mark      (conj stack (v/->Mark (count (second node))))
    :KeyValue  (conj stack (or (el/parse-key (second node))
                               (mu-error (second node) "names no key -- a tonic (# sharp, & flat) and a scale: D.major, B&.minor, F#.dorian")))
    :IdName    (let [[_ id input] node]
                 (conj stack (if input (inputs/ref-of (keyword id) input) (keyword id))))
    :PcSet     (conj stack ((requiring-resolve 'musics.mu.pcset/read-node) node))
    :Local     (let [n (second node)]
                 (if (contains? (:locals env) n)
                   (conj stack (get (:locals env) n))
                   (mu-error (str "$" n) "is not a local here")))
    :Quotation (conj stack (assoc (v/->Quotation (vec (rest node)) (:source (meta node)))
                                  :env env))
    :TopCommand
    (mu-error "a command needs a line: write" (str "[" (:source (meta node)) "]")
              "-- or the word after the music: x c d transpose, x [R]")
    ;; an instruction written alone is a context holding it: x !tempo:89 with
    :LooseInstruction
    (conj stack ((requiring-resolve 'musics.mu.material/read-text) (str "^{ " (:source (meta node)) " }")))
    :QuotedName (conj stack (symbol (node-name node)))
    :VarRef    (let [n (node-name node) b (:bindings @state)]
                 (if (contains? b n)
                   (conj stack (get b n))
                   (mu-error (str "\\" n ":") "nothing is bound to" n "-- bind it with" (str "'" n) "value :=")))
    :Definition (let [[_ name-node & more] node
                      [effect body] (if (= :StackEffect (ffirst more)) [(first more) (rest more)] [nil more])
                      ins  (some->> effect rest (some #(when (= :EffectIn (first %)) %)) rest vec)
                      outs (some->> effect rest (some #(when (= :EffectOut (first %)) %)) rest vec)]
                  (check-name! (second name-node))
                  (define! (:in @state) (second name-node)
                           {:body (vec body) :effect (when effect {:in ins :out outs})
                            :values (:values env) :source (:source (meta node))
                            :at (first (insta/span node))})
                  stack)
    :Group (let [xs (run-items [] (vec (rest node)) env)]
             (if (empty? xs)
               ;; () alone takes its values from the stack: those above the nearest |, else all
               (let [[below xs] (to-mark stack)]
                 (when (empty? xs) (mu-error "(): has nothing on the stack to gather"))
                 (conj below ((requiring-resolve 'musics.mu.tools/gathered) xs)))
               ;; what it makes follows its first value: music a seq, plain values a '[ ]
               (conj stack ((requiring-resolve 'musics.mu.tools/gathered) xs))))
    :Word (run-word stack (node-name node))
    :Retrograde (run-word stack "[R]")
    :Measures   (run-word stack "[[]]")
    :Parallels  (run-word (conj stack (parse-long (second node))) "{n}")
    :Lines      (run-word (conj stack (parse-long (second node))) "[n]")
    ;; a literal: the value it was read as, with the input
    (if-let [i (:mu/index (meta node))]
      (conj stack (nth (:values env) i))
      (mu-error "can't run" (pr-str (first node))))))

(defn- name-asked-for
  "When node is help, see, about, marks, value (or settings, with no voice on top) and the next item is a plain word, that
   word's name: help dup, see dup and about tempo read the word as Forth's see
   does, not run it ('dup help and \"dup\" help are the same); 5 value five,
   5 variable five and 7 to five read the name they define or change. Else nil."
  [stack node following]
  (when (and (vector? node) (= :Word (first node))
             (or (#{"help" "see" "about" "marks" "variable" "value" "to"} (node-name node))
                 (and (= "settings" (node-name node))
                      (not (let [t (peek stack)] (or (keyword? t) (integer? t))))))
             (vector? following))
    (cond
      (= :Word (first following)) (node-name following)
      ;; a bare letter reads as a note (about c, about e): its text is the name
      (:mu/index (meta following)) (re-matches #"[A-Za-z]+" (str (:source (meta following)))))))

(defn run-items [stack nodes env]
  (loop [s stack nodes (seq nodes)]
    (if-let [[node & more] nodes]
      (if-let [asked (name-asked-for s node (first more))]
        (recur (run-item (conj s (symbol asked)) node env) (next more))
        (recur (run-item s node env) more))
      s)))

;; ------------------------------------------------------------------
;; One input
;; ------------------------------------------------------------------

(defn- annotate
  "The parse tree with each node's source text in its metadata (for
   quotations, definitions and messages), and each musical literal --
   not descended into -- numbered in reading order (:mu/index). Returns
   [tree literals]: the annotated tree and its literal nodes, in order."
  [tree text]
  (let [lits (atom [])
        src  (fn [x] (if-let [[s e] (insta/span x)] (vary-meta x assoc :source (subs text s e)) x))
        walk (fn walk [x]
               (cond
                 (v/literal? x) (let [x' (vary-meta (src x) assoc :mu/index (count @lits))]
                                  (swap! lits conj x')
                                  x')
                 (and (vector? x) (keyword? (first x)))
                 (src (with-meta (into [(first x)] (map walk (rest x))) (meta x)))
                 :else x))
        tree' (walk tree)]
    [tree' @lits]))

(defn- refs-in
  "The names \\name refers to inside the literal nodes."
  [lits]
  (set (for [l lits x (tree-seq vector? seq l)
             :when (and (vector? x) (= :VarRef (first x)))]
         (second (second x)))))

(defn- check-refs!
  "Refuse a \\name in a segment's literals that isn't bound yet -- one
   the segment's own := binds comes too late, its literals being read
   before the := runs -- or that holds no music."
  [items lits]
  (let [refs  (refs-in lits)
        bound (set (for [x (tree-seq vector? seq (vec items))
                         :when (and (vector? x) (= :QuotedName (first x)))]
                     (node-name x)))
        b     (:bindings @state)]
    (doseq [n refs]
      (cond
        (and (bound n) (not (contains? b n))) (mu-error (str "\\" n ":") "is bound by the := it comes before; its literals are read before := runs,"
                            "so use it after the :=")
        (not (contains? b n)) (mu-error (str "\\" n ":") "nothing is bound to" n "-- bind it with" (str "'" n) "value :=")
        (not (or (v/material? (b n)) (v/leaf? (b n))))
        (mu-error (str "\\" n ":") "holds" (p/show (b n)) "-- only music goes in a literal")))))

(defn current-repo
  "What is stored now, as literals are read against it: with a :ROOT
   even before anything is stored."
  []
  (let [current (into {} @(repo/registry))]
    (if (:ROOT current) current (merge (:repo (builder/empty-session)) current))))

(defn- word-at
  "[word index]: the known word written where a parse failed at index,
   if any -- the token around index, or just after an opening bracket it
   starts with -- and where it starts."
  [text index]
  (let [start (inc (or (last (keep-indexed #(when (Character/isWhitespace ^char %2) %1)
                                           (subs text 0 (min index (count text)))))
                       -1))
        word  (fn [i] (when-let [w (re-find #"^(?:[a-zA-Z_][a-zA-Z0-9_\-?>+]*/)?(?:\[[^\s\[\]]{1,2}\]|\[\]|[^\s\[\]{}()]+)"
                                            (subs text i))]
                        (when (try (lookup @state w) (catch Exception _ true)) [w i])))]
    (or (word start)
        (when (and (< start (count text)) (#{\[ \{} (.charAt ^String text start)))
          (word (inc start))))))

(defn- parse-error
  "The message for text that can't be read: a word inside a literal gets
   a hint, anything else instaparse's report."
  [text failure]
  (if-let [[w i] (word-at text (:index failure))]
    (let [before (subs text 0 i)
          line   (inc (count (filter #{\newline} before)))
          column (- i (or (str/last-index-of before "\n") -1))]
      (str w " is a word (line " line ", column " column "), and words don't run"
           " inside a literal: [ … ] is musics text, read before anything runs.\n"
           "Gather on the stack instead: ( [a b] [?] [c d] [?] )  (| … par for { })"))
    (let [{:keys [line column reason]} failure
          src      (nth (str/split-lines (str text "\n")) (max 0 (dec (or line 1))) "")
          literals (distinct (for [r reason :when (and (= :string (:tag r)) (string? (:expecting r)))] (:expecting r)))
          shown    (take 12 literals)]
      (str "can't read this (line " line ", column " column "):\n" src "\n" (apply str (repeat (dec (or column 1)) \space)) "^"
           (when (re-find #"\{\s*[^\s\[\]{}]+$" (subs text 0 (min (count text) (:index failure))))
             "\na { } holds seqs, one a voice: {[c] [d]}; <c d> is a chord, [c d] a seq")
           (when (and (seq shown) (not (re-find #"\{\s*[^\s\[\]{}]+$" (subs text 0 (min (count text) (:index failure))))))
             (str "\nexpected " (str/join " " (map #(str "`" % "`") shown)) (when (> (count literals) 12) " …")
                  ", or more music or a word"))
           (when (re-find #"\[\s*-?\d+(?:[\s,]+-?\d+)*\s*\]" text)
             "\nnumbers in a list are data: '[1 2 3]")))))

(defn- parse-input
  "Parse text: {:items [...] :lits [...]} (each literal numbered by its
   :mu/index), or {:incomplete true}, or {:error message}."
  [text]
  (let [tree (g/parse text)]
    (cond
      (and (g/failure? tree) (g/incomplete? text)) {:incomplete true}
      (g/failure? tree) {:error (parse-error text tree)}
      :else (let [[tree' lits] (annotate tree text)]
              {:items (vec (rest tree')) :lits lits}))))

(defn- bind? [item] (and (= :Word (first item)) (= ":=" (node-name item))))

(defn- segments
  "items cut after each := : each segment's literals are read when it
   runs, so a name bound by one segment can be used by the next."
  [items]
  (let [[segs last-seg] (reduce (fn [[segs cur] item]
                                  (if (bind? item) [(conj segs (conj cur item)) []] [segs (conj cur item)]))
                                [[] []] items)]
    (cond-> segs (seq last-seg) (conj last-seg))))

(defn- literals-in [items]
  (for [item items x (tree-seq vector? seq item)
        :when (and (vector? x) (:mu/index (meta x)))]
    x))

(defn- read-segment
  "values with the segment's literals read into their places."
  [values items text]
  (let [lits (sort-by (comp :mu/index meta) (literals-in items))]
    (check-refs! items lits)
    (if (empty? lits)
      values
      (let [{vs :values auto-ids :auto-ids}
            (v/read-literals lits text (current-repo) (:auto-ids @reg/session) (:bindings @state))]
        (swap! reg/session assoc :auto-ids auto-ids)
        (reduce (fn [acc [lit x]] (assoc acc (:mu/index (meta lit)) ((requiring-resolve 'musics.mu.material/as-value) x)))
                values (map vector lits vs))))))

(defn- run-input
  "Run a parsed input on stack, segment by segment."
  [stack {:keys [items lits]} text]
  (loop [stack stack values (vec (repeat (count lits) nil)) segs (segments items)]
    (if-let [seg (first segs)]
      (let [values (read-segment values seg text)]
        (recur (run-items stack seg {:locals {} :values values}) values (rest segs)))
      stack)))

(defn run-text
  "Run mu text on stack, returning the stack it leaves; throws on an
   error or an unfinished input."
  [stack text]
  (let [r (parse-input text)]
    (cond
      (:incomplete r) (mu-error "unfinished input")
      (:error r)      (mu-error (:error r))
      :else           (run-input stack r text))))

(defn eval-input
  "Evaluate one input. Returns {:ok stack}, {:incomplete true}, or
   {:error message} -- the stack as it was before the input. Nothing is
   printed here: as in Forth, only words that print do (the REPL says ok)."
  [text]
  (let [r (try (parse-input text)
               (catch StackOverflowError _ {:error "the stack of calls overflowed reading this"})
               (catch Exception e {:error (.getMessage e)}))]
    (cond
      (:incomplete r) r
      (:error r)      r
      :else
      (try
        (let [s (run-input (:stack @state) r text)]
          (swap! state assoc :stack s)
          {:ok s})
        (catch clojure.lang.ExceptionInfo e
          (when (:mu/bye (ex-data e)) (throw e))
          {:error (.getMessage e)})
        (catch StackOverflowError _
          {:error "the stack of calls overflowed -- a word calling itself without end?"})
        (catch Exception e
          {:error (str (.getSimpleName (class e)) ": " (.getMessage e))})))))
