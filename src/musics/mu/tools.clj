(ns musics.mu.tools
  "mu's tools (doc/md/mu-repl.md, section 8): make, form, selection and
   map in namespace tool, assemble and the meaning tools in music. A
   tool keeps the form: on a line it gives a line, on a parallel a
   parallel, on a stream a stream. Endless results (cycle, iterate) are
   lazy streams; take makes them finite."
  (:require [clojure.string :as str]
            [musics.algo.random :as random]
            [musics.common.music-elements :as el]
            [musics.domain :as d]
            [musics.input.reader.leaf-parser :as lp]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

;; -- helpers --------------------------------------------------------------

(defn- endless [xs] (with-meta xs {:mu/endless true}))


(defn endless? [x] (and (sequential? x) (:mu/endless (meta x))))

(defn streamed
  "x, or an endless repeat (a loop) as the endless stream of what it
   plays, so the tools take from it as from cycle's."
  [x]
  (if (and (mt/repetition? x) (= :infinite (mt/repeat-count x))) (mt/elements x) x))

(defn finite!
  "xs, refused if it is an endless stream: word needs all of it."
  [word xs]
  (when (endless? xs) (e/mu-error (str word ":") "needs all of a stream, and this one is endless -- take some first"))
  xs)

(defn reshape
  "f on x's elements, giving a value of x's form: rebuilt for a value,
   a stream for a stream."
  [x f]
  (let [x   (streamed x)
        els (f (mt/elements x))]
    (cond
      (or (v/material? x) (mt/leaf? x)) (mt/rebuild (if (mt/leaf? x) (mt/line [x]) x) els)
      (endless? x) (endless els)
      :else els)))

(defn- count-arg [s word]
  (need s 2 word)
  (let [n (peek s)]
    (when-not (and (integer? n) (>= n 0)) (e/mu-error (str word ":") "needs a whole number on top, got" (p/show n)))
    n))

(defn- with-n
  "A word ( x n -- y ), y = (f n x-elements) in x's form."
  [word f]
  (fn [s]
    (let [n (count-arg s word) s (pop s)]
      (conj (pop s) (reshape (peek s) (partial f n))))))

(defn- one
  "A word ( x -- y ), y = (f x-elements) in x's form."
  [word f]
  (fn [s] (need s 1 word) (conj (pop s) (reshape (peek s) f))))

;; -- make (tool) ----------------------------------------------------------

(word! "tool" "range" "( a b -- stream )  the numbers from a up to (not including) b; also .."
       (fn [s]
         (need s 2 "range")
         (let [b (peek s) a (peek (pop s))]
           (when-not (and (number? a) (number? b)) (e/mu-error "range: needs two numbers"))
           (conj (pop (pop s)) (range a b)))))

(word! "tool" ".." "( a b -- stream )  range" (e/same-as "tool" "range"))

(word! "tool" "iterate" "( x q -- endless )  x, then q of x, then q of that, forever: 1 '( 2 add ) iterate 4 take"
       (fn [s]
         (need s 2 "iterate")
         (let [q (peek s) x (peek (pop s))]
           (when-not (v/quotation? q) (e/mu-error "iterate: needs a quotation '( … ) on top"))
           (conj (pop (pop s)) (endless (iterate #(peek (e/call [%] q)) x))))))

(defn key? [x] (instance? musics.common.music_elements.Key x))

(defn key-line
  "Pitches as a line of quarter notes, spelled in ks."
  [ks pitches]
  (mt/read-text (str "[" (str/join " " (map #(str (lp/spell-pitch % nil ks) "4") pitches)) "]")))

(defn- scale-numbers [s]
  (need s 2 "scale")
  (let [ivs (mt/elements (peek s)) root (peek (pop s))]
    (when-not (number? root) (e/mu-error "scale: needs a root pitch number under the intervals, or a key"))
    (conj (pop (pop s)) (map #(+ root %) ivs))))

(word! "tool" "scale" "( root intervals -- pitches ) or ( key -- line )  a key's scale, one octave up from its tonic"
       (fn [s]
         (need s 1 "scale")
         (if (key? (peek s))
           (let [ks (peek s) ps (el/key-pitches ks)]
             (conj (pop s) (key-line ks (map #(+ 60 %) ps))))
           (scale-numbers s))))

;; -- access (tool) --------------------------------------------------------

(word! "tool" "first" "( x -- element )  the first element"
       (fn [s] (need s 1 "first")
         (let [els (mt/elements (peek s))]
           (when (empty? els) (e/mu-error "first: nothing in" (p/show (peek s))))
           (conj (pop s) (first els)))))
(word! "tool" "last" "( x -- element )  the last element"
       (fn [s] (need s 1 "last")
         (let [els (finite! "last" (mt/elements (peek s)))]
           (when (empty? els) (e/mu-error "last: nothing in" (p/show (peek s))))
           (conj (pop s) (last els)))))
(word! "tool" "nth" "( x n -- element )  counted from 0"
       (fn [s] (let [n (count-arg s "nth") s (pop s) els (mt/elements (peek s))
                     e (nth els n ::none)]
                 (when (= ::none e) (e/mu-error "nth:" n "is past the end of" (p/show (peek s))))
                 (conj (pop s) e))))
(word! "tool" "count" "( x -- n )  how many elements"
       (fn [s] (need s 1 "count") (conj (pop s) (count (finite! "count" (mt/elements (peek s)))))))

;; -- form (tool) ----------------------------------------------------------

(word! "tool" "take" "( x n -- x' )  the first n elements"
       (fn [s] (let [n (count-arg s "take") s (pop s) x (streamed (peek s))]
                 (conj (pop s) (if (endless? x) (take n x) (reshape x (partial take n)))))))
(word! "tool" "drop" "( x n -- x' )  all but the first n elements" (with-n "drop" drop))
(word! "tool" "take-last" "( x n -- x' )  the last n elements" (with-n "take-last" (fn [n xs] (take-last n (finite! "take-last" xs)))))
(word! "tool" "drop-last" "( x n -- x' )  all but the last n elements" (with-n "drop-last" (fn [n xs] (drop-last n (finite! "drop-last" xs)))))
(defn music? [x] (or (mt/leaf? x) (and (v/material? x) (not= :DATA (mt/kind x)))))

(word! "tool" "rotate" "( x n -- x' )  rotated by n, wrapping around (also [%])"
       (with-n "rotate" (fn [n xs] (let [v (vec (finite! "rotate" xs)) k (if (seq v) (mod n (count v)) 0)]
                                     (concat (drop k v) (take k v))))))
(word! "tool" "partition" "( x n -- groups )  in groups of n elements"
       (fn [s]
         (let [n (count-arg s "partition") s (pop s) x (peek s)]
           (when (zero? n) (e/mu-error "partition: needs a size above 0"))
           (let [groups (partition-all n (finite! "partition" (mt/elements x)))]
             (conj (pop s) (if (or (v/material? x) (mt/leaf? x)) (mt/line (map mt/line groups)) groups))))))
(word! "tool" "[/]" "( x n -- parts )  x split into n parts as equal as can be, the longer first: [a b c d] 2 [/] gives [[a b] [c d]]"
       (fn [s]
         (let [n (count-arg s "[/]") s (pop s) x (peek s)
               xs (vec (finite! "[/]" (mt/elements x))) k (count xs)]
           (when-not (<= 1 n k) (e/mu-error "[/]: can't split" k "elements into" n "parts"))
           (let [sizes (map #(+ (quot k n) (if (< % (rem k n)) 1 0)) (range n))
                 parts (map (fn [from size] (subvec xs from (+ from size))) (reductions + 0 sizes) sizes)]
             (conj (pop s) (if (or (v/material? x) (mt/leaf? x)) (mt/line (map mt/line parts)) parts))))))
(word! "tool" "split-at" "( x n -- a b )  the first n elements and the rest, as two values"
       (fn [s]
         (let [n (count-arg s "split-at") s (pop s) x (peek s)]
           (conj (pop s) (reshape x (partial take n)) (reshape x (partial drop n))))))

(word! "tool" "shuffle" "( x -- x' )  in a random order, now ([?] orders music anew as it plays)" (one "shuffle" (comp random/shuffle #(finite! "shuffle" %))))
(word! "tool" "sputter" "( x -- x' )  some elements repeated at random, a stutter" (one "sputter" (comp random/sputter vec #(finite! "sputter" %))))

(word! "tool" "then" "( a b -- a then b )  a followed by b (also [+])"
       (fn [s]
         (need s 2 "then")
         (let [b (peek s) a (peek (pop s))]
           (conj (pop (pop s)) (reshape a #(concat % (mt/elements b)))))))

(word! "tool" "[+]" "( a b -- a then b )  the two joined into one, as then" (e/same-as "tool" "then"))
(word! "tool" "[%]" "( x n -- x' )  rotated by n, wrapping around, as rotate" (e/same-as "tool" "rotate"))
(word! "tool" "rotating" "( x n -- part )  rotate n [ x ]: rotated n places further each time it plays, as written the first time ([c d e] 1 rotating [~] rotates every pass; [%] rotates now)"
       (fn [s]
         (let [n (count-arg s "rotating") s (pop s)]
           (need s 1 "rotating")
           (conj (pop s) (mt/read-text (str "rotate " n " " (p/show (mt/as-line (peek s)))))))))

(word! "tool" "[?]" "( x -- x' )  music: shuffle [ x ], in a new random order each time it plays ([c d e] [?] [~] loops, shuffled every pass); data or a stream: shuffled now, as shuffle"
       (fn [s]
         (need s 1 "[?]")
         (let [x (peek s)]
           (if (or (mt/leaf? x) (and (v/material? x) (not= :DATA (mt/kind x))))
             (conj (pop s) (mt/read-text (str "shuffle " (p/show (mt/as-line x)))))
             ((e/same-as "tool" "shuffle") s)))))

(word! "tool" "interleave" "( a b -- a b alternating )  the elements of a and b, alternating"
       (fn [s]
         (need s 2 "interleave")
         (let [b (peek s) a (peek (pop s))]
           (conj (pop (pop s)) (reshape a #(interleave % (mt/elements b)))))))

;; -- a chunk: slice, splice, within (tool) --------------------------------

(defn- span
  "[s' x els start end]: the x under start and end (whole numbers, 0 <=
   start <= end <= its element count, counted as nth counts; start < end
   when the chunk must hold something), its elements, and the stack
   below them all."
  [s word non-empty?]
  (need s 3 word)
  (let [end (peek s) start (peek (pop s)) x (peek (pop (pop s)))
        els (vec (finite! word (mt/elements x)))
        lt  (if non-empty? "<" "<=")]
    (when-not (and (integer? start) (integer? end) (<= 0 start end (count els))
                   (or (not non-empty?) (< start end)))
      (e/mu-error (str word ":") (str "needs a start and an end with 0 <= start " lt " end <= " (count els))
                  "-- got" (p/show start) (p/show end)))
    [(pop (pop (pop s))) x els start end]))

(defn- put-back
  "x with its elements start..end replaced by y's."
  [x els start end y]
  (reshape x (fn [_] (concat (subvec els 0 start) (mt/elements y) (subvec els end)))))

(word! "tool" "slice" "( x start end -- chunk )  the elements from start up to (not including) end, counted from 0 as nth counts: [c d e f g] 1 3 slice gives [d e]"
       (fn [s]
         (let [[s' x els start end] (span s "slice" true)]
           (conj s' (reshape x (fn [_] (subvec els start end)))))))

(word! "tool" "splice" "( x start end y -- x' )  x with the elements from start up to end replaced by y's: [c d e f g] 1 3 [a b c] splice; start = end inserts"
       (fn [s]
         (need s 4 "splice")
         (let [y (peek s) [s' x els start end] (span (pop s) "splice" false)]
           (conj s' (put-back x els start end y)))))

(word! "tool" "within" "( x start end q -- x' )  the chunk from start up to end through q, put back where it came from: [c d e f g] 1 4 '( reverse ) within"
       (fn [s]
         (need s 4 "within")
         (let [q (peek s)
               _ (when-not (v/quotation? q) (e/mu-error "within: needs a quotation '( … ) on top"))
               [s' x els start end] (span (pop s) "within" true)
               chunk (reshape x (fn [_] (subvec els start end)))
               out (e/call [chunk] q)]
           (when-not (= 1 (count out))
             (e/mu-error "within: the quotation must leave one value, the chunk changed, left" (count out)))
           (conj s' (put-back x els start end (peek out))))))

;; -- selection and map (tool) ---------------------------------------------

(defn result
  "What quotation q leaves for x: exactly one value."
  [word q x]
  (let [out (e/call [x] q)]
    (when-not (= 1 (count out))
      (e/mu-error (str word ":") "the quotation must leave one value for each element, left" (count out)))
    (peek out)))

(defn- quoted [s word]
  (need s 2 word)
  (let [q (peek s)]
    (when-not (v/quotation? q) (e/mu-error (str word ":") "needs a quotation '( … ) on top"))
    q))

(defn- marker? [x] (or (d/bar? x) (d/jump? x)))

(defn- by-test
  "A word ( x q -- x' ): f given a test of each element; a bar line or a
   jump mark passes every test, so it stays where it is."
  [word f]
  (fn [s]
    (let [q (quoted s word) s (pop s)
          test #(or (marker? %) (result word q %))]
      (conj (pop s) (reshape (peek s) (partial f test))))))

(word! "tool" "filter" "( x q -- x' )  the elements q keeps: [c e g c'] '( pitch 65 lt ) filter"
       (by-test "filter" filter))
(word! "tool" "remove" "( x q -- x' )  the elements q drops; on a line the later notes move earlier"
       (by-test "remove" (fn [t xs] (remove #(and (not (marker? %)) (t %)) xs))))
(word! "tool" "take-while" "( x q -- x' )  the elements up to the first q refuses"
       (by-test "take-while" take-while))
(word! "tool" "drop-while" "( x q -- x' )  the elements from the first q refuses"
       (by-test "drop-while" (fn [t xs] (drop-while #(and (not (marker? %)) (t %)) xs))))

(defn- by-print
  "f on xs, with each element compared as mu prints it."
  [f xs]
  (let [shown (map (fn [x] [(p/show x) x]) xs)]
    (map second (f shown))))

(word! "tool" "distinct" "( x -- x' )  each element once, the first time it comes (as printed)"
       (one "distinct" (partial by-print (fn [shown] (let [seen (volatile! #{})]
                                                       (filter (fn [[k]] (when-not (@seen k) (vswap! seen conj k))) shown))))))
(word! "tool" "dedupe" "( x -- x' )  an element repeated at once kept once (as printed)"
       (one "dedupe" (partial by-print #(map first (partition-by first %)))))

(word! "tool" "choose-n" "( x n -- x' )  n elements, drawn at random without putting back"
       (with-n "choose-n" (fn [n xs] (random/choose-n n (vec (finite! "choose-n" xs))))))
(word! "tool" "choose-from" "( x -- x' )  as many elements, each drawn at random from x"
       (one "choose-from" (comp random/choose-from vec #(finite! "choose-from" %))))
(word! "tool" "select" "( x weights -- element )  one element, drawn by the weights"
       (fn [s]
         (need s 2 "select")
         (let [ws (vec (finite! "select" (mt/elements (peek s))))
               xs (vec (finite! "select" (mt/elements (peek (pop s)))))]
           (when-not (and (seq xs) (= (count xs) (count ws)) (every? #(and (number? %) (not (neg? %))) ws))
             (e/mu-error "select: needs as many weights (numbers, not negative) as elements"))
           (conj (pop (pop s)) (random/weighted-choose xs ws)))))

(word! "tool" "map" "( x q -- y )  each element through q: notes give x's form back, anything else a stream -- [c e g] '( pitch ) map"
       (fn [s]
         (let [q (quoted s "map") s (pop s) x (peek s)
               ys (map #(if (marker? %) % (result "map" q %)) (mt/elements x))]
           (conj (pop s)
                 (cond
                   (endless? x) (endless ys)
                   (and (or (v/material? x) (mt/leaf? x)) (every? #(or (mt/leaf? %) (v/material? %) (marker? %)) ys))
                   (mt/rebuild (if (mt/leaf? x) (mt/line [x]) x) ys)
                   :else (remove marker? ys))))))

;; -- assemble and meaning (music) ----------------------------------------------

(defn- gather
  "A word that gathers what lies above the nearest | mark into (f xs)."
  [word f]
  (fn [s]
    (let [[below xs] (e/to-mark s word)]
      (when (empty? xs) (e/mu-error (str word ":") "has nothing above its | mark"))
      (conj below (f xs)))))

(word! "music" "seq" "( | … -- line )  what lies above the | mark, one after another, as a line [ ]" (gather "seq" mt/line))

(defn par-of [xs]
  (mt/read-text (str "{" (apply str (interpose " " (map #(p/show (mt/->material %)) xs))) "}")))

(word! "music" "par" "( | … -- parallel )  what lies above the | mark, side by side, as { }" (gather "par" par-of))
(defn- top-n
  "A word taking the top n (pushed by the grammar, written <open>n<close>)
   into (f items)."
  [open close f]
  (fn [s]
    (let [n (peek s) s (pop s) w (str open n close)]
      (when (< n 2) (e/mu-error (str w ":") "takes 2 or more, as" (str open 2 close)))
      (need s n w)
      (conj (subvec s 0 (- (count s) n)) (f (subvec s (- (count s) n)))))))

(word! "music" "{n}" "( a … n -- parallel )  the top n side by side, written {2} {3} … : [c d] [e f] {2} gives {[c d] [e f]}"
       (top-n "{" "}" par-of))
(word! "music" "[n]" "( a … n -- line )  the top n one after another, each an element, written [2] [3] … : [c d] [e f] [2] gives [[c d] [e f]]"
       (top-n "[" "]" mt/line))

(word! "music" "<>" "( leaves -- chord )  the notes on top of the stack as one chord: c e g <>"
       (fn [s]
         (let [leaves (reverse (take-while #(and (mt/leaf? %) (seq (:pitches %))) (rseq s)))]
           (when (empty? leaves) (e/mu-error "<>: needs notes on top of the stack"))
           (conj (subvec s 0 (- (count s) (count leaves)))
                 (assoc (first leaves) :pitches (vec (distinct (mapcat :pitches leaves))))))))

;; -- a stream onto a line: volumes, articulations, instruments -----------------

(defn- above-repeat
  "[below repeat xs]: what lies above the nearest repeat on the stack,
   the repeat itself working as the mark. An error if there is none or
   nothing is above it."
  [s word]
  (let [i (some #(when (mt/repetition? (s %)) %) (range (dec (count s)) -1 -1))]
    (when-not i (e/mu-error (str word ":") "needs a repeat below what it takes"))
    (when (= i (dec (count s))) (e/mu-error (str word ":") "has nothing above the repeat"))
    [(subvec s 0 i) (s i) (subvec s (inc i))]))

(word! "music" "volta" "( repeat ending … -- repeat )  the endings above a repeat, as volta … in the text: [a b] 2 repeat [c] [d] volta"
       (fn [s]
         (let [[below rep ends] (above-repeat s "volta")]
           (conj below (mt/with-endings rep (mapv mt/as-line ends))))))

(word! "music" "vary" "( repeat q … -- line )  each pass a quotation applied to the body as written, the endings after it; one quotation every pass, or one a pass: [a b] 3 repeat '( ) '( 12 + ) '( shuffle ) vary"
       (fn [s]
         (let [[below rep qs] (above-repeat s "vary")
               n (mt/repeat-count rep) k (count qs)]
           (when-not (every? v/quotation? qs) (e/mu-error "vary: takes quotations '( … ) above the repeat"))
           (when-not (or (= k 1) (= k n))
             (e/mu-error "vary:" k "quotations for" n "passes -- give one, or one a pass"))
           (conj below (mt/passes rep (fn [i x]
                                        (let [out (e/call [x] (qs (if (= k 1) 0 i)))]
                                          (when-not (= 1 (count out))
                                            (e/mu-error "vary: a quotation must leave one value a pass, left" (count out)))
                                          (peek out))))))))

;; -- the retrograde -------------------------------------------------------

(defn- alternatives-word
  "( x q … -- part ) or ( x -- part ): the text's kw [ … ] of the
   alternatives -- the quotations on top applied to x, or without them
   x's own elements."
  [word kw]
  (fn [s]
    (need s 1 word)
    (let [weights? (and (= "choose" kw) (= :DATA (mt/kind (peek s))) (seq (mt/elements (peek s)))
                        (every? number? (mt/elements (peek s))))
          ws   (when weights? (vec (mt/elements (peek s))))
          s    (if weights? (pop s) s)
          qs   (vec (reverse (take-while v/quotation? (rseq s))))
          s    (subvec s 0 (- (count s) (count qs)))
          _    (need s 1 word)
          x    (peek s)
          alts (if (seq qs)
                 (mapv (fn [q] (let [out (e/call [x] q)]
                                 (when-not (= 1 (count out))
                                   (e/mu-error (str word ":") "a quotation must leave one value, left" (count out)))
                                 (peek out)))
                       qs)
                 (mt/elements x))]
      (when (empty? alts) (e/mu-error (str word ":") "nothing to choose from"))
      (when (and ws (not= (count ws) (count alts)))
        (e/mu-error (str word ":") (count ws) "weights for" (count alts) "alternatives -- give one weight each"))
      (conj (pop s) (mt/read-text (str kw (when ws (str " " (str/join "," ws))) " [" (str/join " " (map p/show alts)) "]"))))))

(word! "music" "[|]" "( x q … [weights] -- part ) or ( x [weights] -- part )  choose [ … ]: one of the alternatives, picked at random each time it plays -- the quotations on top applied to x ([c d e g] '([I]) '([R]) [|]), or without them x's own elements ([[c d] [e f]] [|]); data of weights on top, one each, weighs them ([[c d] [e f]] '[3 1] [|])"
       (alternatives-word "[|]" "choose"))

(word! "music" "[\\]" "( x q … -- part ) or ( x -- part )  alternate [ … ]: the alternatives in turn, one each time it plays -- the quotations on top applied to x ([c d e g] '([I]) '([R]) [\\] [~] plays I, R, I, …), or without them x's own elements"
       (alternatives-word "[\\]" "alternate"))

;; -- head and tail: a count on top, else 1 ---------------------------------

(defn- counted
  "The word ( x [n] -- x' ): the tool word with n, 1 when the top is no
   whole number over something that isn't a number."
  [sign tool]
  (fn [s]
    (need s 1 sign)
    (let [counted? (and (integer? (peek s)) (> (count s) 1) (not (number? (peek (pop s)))))]
      ((e/same-as "tool" tool) (if counted? s (conj s 1))))))

(word! "tool" "[H]" "( x [n] -- head )  the first n elements, 1 without a count: [c d e f] [H] gives [c], [c d e f] 2 [H] gives [c d]"
       (counted "[H]" "take"))
(word! "tool" "[T]" "( x [n] -- tail )  all after the first n, 1 without a count: [c d e f] [T] gives [d e f]; n [H] and n [T] split x in two"
       (counted "[T]" "drop"))

;; -- twins: a word beside each sign, a name Clojure can carry too ----------

(defn- twin!
  "Define word name in ns as the sign word already defined in sign-ns."
  [ns name sign-ns sign]
  (let [{:keys [fn effect]} (get-in @e/state [:ns sign-ns sign])]
    (word! ns name (str effect " (also " sign ")") (e/same-as sign-ns sign))
    (word! sign-ns sign (str effect " (also " name ")") fn)))

(doseq [[ns name sign-ns sign]
        [["music" "retrograde"           "music" "[R]"]
         ["music" "choosing"             "music" "[|]"]
         ["music" "alternating"          "music" "[\\]"]
         ["music" "line"                 "music" "[n]"]
         ["music" "together"             "music" "{n}"]
         ["music" "chord"                "music" "<>"]
         ["tool"  "head"                 "tool"  "[H]"]
         ["tool"  "shuffling"            "tool"  "[?]"]
         ["tool"  "SFL"                  "tool"  "shuffle"]
         ["tool"  "ROT"                  "tool"  "rotate"]
         ["kernel" "store"               "kernel" "R!"]
         ["kernel" "fetch"               "kernel" "R@"]
         ["tool"  "tail"                 "tool"  "[T]"]
         ["tool"  "parts"                "tool"  "[/]"]]]
  (twin! ns name sign-ns sign))

;; -- harmony ----------------------------------------------------------------

;; -- finding names ----------------------------------------------------------

