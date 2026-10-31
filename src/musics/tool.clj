(ns musics.tool
  "mu's tool words as Clojure functions: making, reading and reshaping
   any material -- a line, a parallel, data, a stream -- keeping its form
   (a line gives a line, a stream a stream). Registered as the words of
   mu's tool namespace by musics.mu.library, as musics.music's are as
   music's; their names are mu's, so clojure.core's own are written in
   full here."
  (:refer-clojure :exclude [first last nth count take drop take-last drop-last partition shuffle
                            interleave filter remove take-while drop-while distinct dedupe map
                            iterate range concat flatten sort find])
  (:require [clojure.core :as core]
            [clojure.string :as str]
            [musics.algo.random :as random]
            [musics.common.music-elements :as el]
            [musics.mu.eval :as e]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

(defn- count!
  "n, a whole number from 0."
  [n]
  (when-not (and (integer? n) (>= n 0)) (fail "needs a whole number on top, got" (p/show n)))
  n)

(defn- quotation! [q]
  (when-not (v/quotation? q) (fail "needs a quotation '( … ) on top"))
  q)

(defn- each
  "f (a fn of the elements) on x's elements, in x's form."
  [x f]
  (mt/reshape-keeping x f))

;; -- make -------------------------------------------------------------------

(defn range
  "The numbers from a up to (not including) b."
  {:mu {:names [".."]}}
  [a b]
  (when-not (and (number? a) (number? b)) (fail "needs two numbers"))
  (core/range a b))

(defn iterate
  "x, then q of x, then q of that, forever: 1 '( 2 add ) iterate 4 take"
  [x q]
  (quotation! q)
  (mt/endless (core/iterate #(peek (e/call [%] q)) x)))

(defn scale
  "A key's scale, one octave up from its tonic, as a line; or a root
   pitch number and intervals as the pitches."
  {:mu {:optional {:intervals (complement mt/key?)}}}
  ([key]
   (when-not (mt/key? key) (fail "needs a key, or a root pitch number and intervals, got" (p/show key)))
   (mt/key-line key (core/map #(+ 60 %) (el/key-pitches key))))
  ([root intervals]
   (when-not (number? root) (fail "needs a root pitch number under the intervals, or a key"))
   (core/map #(+ root %) (mt/elements intervals))))

;; -- access -----------------------------------------------------------------

(defn first
  "The first element"
  [x]
  (let [els (mt/elements x)]
    (when (empty? els) (fail "nothing in" (p/show x)))
    (core/first els)))

(defn last
  "The last element"
  [x]
  (let [els (mt/finite! "last" (mt/elements x))]
    (when (empty? els) (fail "nothing in" (p/show x)))
    (core/last els)))

(defn- path-of
  "An index as a vector of whole numbers: n, or a data list '[1 2], the
   part counted 1, then the element counted 2 inside it."
  [word idx]
  (let [path (cond (integer? idx) [idx]
                   (or (v/material? idx) (sequential? idx)) (vec (mt/elements idx))
                   :else nil)]
    (when-not (and (seq path) (every? #(and (integer? %) (>= % 0)) path))
      (fail (str word ":") "needs a whole number from 0 or a path '[1 2] of them, got" (p/show idx)))
    path))

(defn nth
  "The element counted n from 0; a path '[1 2] goes down: element 1, then
   its element 2"
  {:mu {:names ["@"]}}
  [x n]
  (reduce (fn [c i]
            (let [e (core/nth (mt/elements c) i ::none)]
              (when (= ::none e) (fail i "is past the end of" (p/show c)))
              e))
          x (path-of "nth" n)))

(defn- matching
  "A test of one element: the quotation q, or the value q is, compared as
   mu prints it; a bar line or a jump mark never matches."
  [word q]
  (if (v/quotation? q)
    #(and (not (mt/marker? %)) (mt/result word q %))
    (let [shown (p/show q)] #(and (not (mt/marker? %)) (= shown (p/show %))))))

(defn find
  "The first element a quotation accepts, else nil:
   [c e g c'] '( pitch 65 gt ) find gives G4/4"
  [x q]
  (core/first (core/filter (matching "find" q) (mt/finite! "find" (mt/elements x)))))

(defn index-of
  "Where the first element is that a quotation accepts, or that equals a
   value as mu prints it, counted from 0 as nth counts (a bar line is an
   element too), else nil: [c e g] e index-of gives 1"
  [x q]
  (let [t (matching "index-of" q)]
    (core/first (keep-indexed (fn [i e] (when (t e) i)) (mt/finite! "index-of" (mt/elements x))))))

(defn sum
  "The numbers of a stream added: '[1 2 3] sum gives 6; x durations sum is
   a line's length"
  [x]
  (reduce + 0 (mt/numbers-of "sum" x)))

(defn mean
  "The mean of a stream's numbers, exact: '[60 62 65] mean gives 187/3"
  [x]
  (let [ns (mt/numbers-of "mean" x)]
    (when (empty? ns) (fail "needs at least one number"))
    (/ (reduce + ns) (core/count ns))))

(defn sort
  "A stream's numbers, the smallest first: '[3 1 2] sort gives '[1 2 3]"
  [x]
  (each x (fn [_] (core/sort (mt/numbers-of "sort" x)))))

(defn intervals
  "The steps between a stream's numbers, each minus the one before:
   '[60 64 67] intervals gives '[4 3]; x pitches intervals for a line"
  [x]
  (each x (fn [_] (let [ns (mt/numbers-of "intervals" x)] (core/map - (core/rest ns) ns)))))

(defn count
  "How many elements"
  [x]
  (core/count (mt/finite! "count" (mt/elements x))))

;; -- form -------------------------------------------------------------------

(defn take
  "The first n elements"
  [x n]
  (let [n (count! n) x (mt/streamed x)]
    (if (mt/endless? x) (core/take n x) (each x (partial core/take n)))))

(defn drop
  "All but the first n elements"
  [x n]
  (each x (partial core/drop (count! n))))

(defn take-last
  "The last n elements"
  [x n]
  (each x #(core/take-last (count! n) (mt/finite! "take-last" %))))

(defn drop-last
  "All but the last n elements"
  [x n]
  (each x #(core/drop-last (count! n) (mt/finite! "drop-last" %))))

(defn head
  "The first n elements, 1 without a count: [c d e f] [H] gives [c],
   [c d e f] 2 [H] gives [c d]"
  {:mu {:names ["[H]"] :optional {:n integer?}}}
  ([x] (head x 1))
  ([x n] (take x n)))

(defn tail
  "All after the first n, 1 without a count: [c d e f] [T] gives
   [d e f]; n [H] and n [T] split x in two"
  {:mu {:names ["[T]"] :optional {:n integer?}}}
  ([x] (tail x 1))
  ([x n] (drop x n)))

(defn rotate
  "Rotated by n, wrapping around, now"
  {:mu {:names ["[%]" "ROT"]}}
  [x n]
  (each x (fn [xs] (let [v (vec (mt/finite! "rotate" xs)) k (if (seq v) (mod (count! n) (core/count v)) 0)]
                     (core/concat (core/drop k v) (core/take k v))))))

(defn rotating
  "rotate n [ x ]: rotated n places further each time it plays, as
   written the first time ([c d e] 1 rotating [~] rotates every pass;
   [%] rotates now)"
  [x n]
  (mt/read-text (str "rotate " (count! n) " " (p/show (mt/as-line x)))))

(defn- grouped
  "The groups of x's elements as x's form gives them: a line of lines
   for music, else the groups."
  [x groups]
  (if (or (v/material? x) (mt/leaf? x)) (mt/line (core/map mt/line groups)) groups))

(defn partition
  "In groups of n elements"
  [x n]
  (when (zero? (count! n)) (fail "needs a size above 0"))
  (grouped x (partition-all n (mt/finite! "partition" (mt/elements x)))))

(defn parts
  "x split into n parts as equal as can be, the longer first:
   [a b c d] 2 [/] gives [[a b] [c d]]"
  {:mu {:names ["[/]"]}}
  [x n]
  (let [xs (vec (mt/finite! "[/]" (mt/elements x))) k (core/count xs)]
    (when-not (<= 1 (count! n) k) (fail "can't split" k "elements into" n "parts"))
    (let [sizes (core/map #(+ (quot k n) (if (< % (rem k n)) 1 0)) (core/range n))]
      (grouped x (core/map (fn [from size] (subvec xs from (+ from size))) (reductions + 0 sizes) sizes)))))

(defn shuffle
  "In a random order, now ([?] orders music anew as it plays)"
  {:mu {:names ["SFL"]}}
  [x]
  (each x (comp random/shuffle #(mt/finite! "shuffle" %))))

(defn shuffling
  "Music: shuffle [ x ], in a new random order each time it plays
   ([c d e] [?] [~] loops, shuffled every pass); data or a stream:
   shuffled now, as shuffle"
  {:mu {:names ["[?]"]}}
  [x]
  (if (mt/music? x)
    (mt/read-text (str "shuffle " (p/show (mt/as-line x))))
    (shuffle x)))

(defn sputter
  "Some elements repeated at random, a stutter"
  [x]
  (each x (comp random/sputter vec #(mt/finite! "sputter" %))))

(defn- joined
  "a and b as lines of one: every instruction of both stays where it is
   written, so b's settings begin where b does (and carry on after it, as
   they would in text); anything else by elements."
  [a b]
  (let [sa (mt/items a) sb (mt/items b)]
    (if (and sa sb (= :SEQ (mt/kind a) (mt/kind b)))
      (mt/from-items a (into sa sb))
      (each a #(core/concat % (mt/elements b))))))

(defn concat
  "a followed by b, one line, as the text of both written together:
   [!mf c d] [!pp e f] concat gives [!mf c d !pp e f]"
  {:mu {:names ["[+]"]}}
  [a b]
  (joined a b))

(defn flatten
  "The lines inside x opened into it, one level: a b 2 line flatten is a
   b concat"
  [x]
  (if-let [slots (mt/items x)]
    (mt/from-items x (vec (mapcat (fn [[tag v :as slot]]
                                    (or (and (= tag :el) (= :SEQ (mt/kind v)) (mt/items v)) [slot]))
                                  slots)))
    (each x #(core/mapcat (fn [e] (if (and (v/material? e) (= :SEQ (mt/kind e))) (mt/elements e) [e])) %))))

(defn interleave
  "The elements of a and b, alternating"
  [a b]
  (each a #(core/interleave % (mt/elements b))))

;; -- a chunk: slice, splice, within -----------------------------------------

(defn- span
  "x's elements, checked for a chunk from start up to end (whole numbers,
   0 <= start <= end <= the element count; start < end when the chunk
   must hold something)."
  [word x start end non-empty?]
  (let [els (vec (mt/finite! word (mt/elements x)))]
    (when-not (and (integer? start) (integer? end) (<= 0 start end (core/count els))
                   (or (not non-empty?) (< start end)))
      (fail (str "needs a start and an end with 0 <= start " (if non-empty? "<" "<=") " end <= " (core/count els))
            "-- got" (p/show start) (p/show end)))
    els))

(defn- put-back
  "x with its elements start..end replaced by y's."
  [x start end y]
  (each x (fn [els] (let [els (vec els)] (core/concat (subvec els 0 start) (mt/elements y) (subvec els end))))))

(defn- el-positions
  "Where the elements are among the slots (instructions between)."
  [slots]
  (vec (keep-indexed (fn [i [tag]] (when (= tag :el) i)) slots)))

(defn- edit
  "x with f applied to its slots at the end of path (f: slots, element
   positions, index -> slots), the instructions staying where written; a
   path goes down through the nested lines."
  [word x idx f]
  (let [path (path-of word idx)
        slots (or (mt/items x) (mapv (fn [e] [:el e]) (mt/finite! word (mt/elements x))))
        pos   (el-positions slots)
        i     (core/first path)
        back  (fn [slots'] (if (mt/items x)
                             (mt/from-items x slots')
                             (each x (fn [_] (core/map second slots')))))]
    (if (next path)
      (do (when-not (< i (core/count pos)) (fail i "is past the end of" (p/show x)))
          (back (assoc slots (core/nth pos i) [:el (edit word (second (core/nth slots (core/nth pos i))) (vec (core/rest path)) f)])))
      (back (f slots pos i)))))

(defn- present!
  "The slot position of element i, else refused as span refuses."
  [pos i]
  (when-not (< i (core/count pos))
    (fail "needs an index with 0 <= n <" (core/count pos) "-- got" i))
  (core/nth pos i))

(defn put
  "x with the element at n replaced by y, one element; n a number or a
   path '[1 2]: [c d e] 1 g ! gives [c g e]"
  {:mu {:names ["!"]}}
  [x n y]
  (edit "put" x n (fn [slots pos i] (assoc slots (present! pos i) [:el y]))))

(defn insert
  "x with y, one element, put in before the element at n (n = the count
   appends); n a number or a path: [c d e] 1 g insert gives [c g d e]"
  [x n y]
  (edit "insert" x n
        (fn [slots pos i]
          (when-not (<= i (core/count pos)) (fail "needs an index with 0 <= n <=" (core/count pos) "-- got" i))
          (let [at (if (< i (core/count pos)) (core/nth pos i) (core/count slots))]
            (vec (core/concat (subvec slots 0 at) [[:el y]] (subvec slots at)))))))

(defn delete
  "x without the element at n; n a number or a path: [c d e] 1 _ gives
   [c e]"
  {:mu {:names ["_"]}}
  [x n]
  (edit "delete" x n
        (fn [slots pos i]
          (let [at (present! pos i)]
            (vec (core/concat (subvec slots 0 at) (subvec slots (inc at))))))))

(defn cut
  "x without the elements from start up to end, its instructions staying:
   [c d e f g] 1 3 [_] gives [c f g]"
  {:mu {:names ["[_]"]}}
  [x start end]
  (let [els (span "cut" x start end true)
        slots (or (mt/items x) (mapv (fn [e] [:el e]) els))
        pos   (el-positions slots)
        gone  (set (core/subvec pos start end))
        slots' (vec (keep-indexed (fn [i s] (when-not (gone i) s)) slots))]
    (if (mt/items x)
      (mt/from-items x slots')
      (each x (fn [_] (core/map second slots'))))))

(defn slice
  "The elements from start up to (not including) end, counted from 0 as
   nth counts: [c d e f g] 1 3 slice gives [d e]"
  [x start end]
  (span "slice" x start end true)
  (each x (fn [els] (subvec (vec els) start end))))

(defn splice
  "x with the elements from start up to end replaced by y's:
   [c d e f g] 1 3 [a b c] splice; start = end inserts"
  [x start end y]
  (span "splice" x start end false)
  (put-back x start end y))

(defn within
  "The chunk from start up to end through q, put back where it came from:
   [c d e f g] 1 4 '( reverse ) within"
  [x start end q]
  (quotation! q)
  (span "within" x start end true)
  (let [out (e/call [(each x (fn [els] (subvec (vec els) start end)))] q)]
    (when-not (= 1 (core/count out))
      (fail "the quotation must leave one value, the chunk changed, left" (core/count out)))
    (put-back x start end (peek out))))

;; -- selection and map ------------------------------------------------------

(defn- by-test
  "f given a test of each element by q; a bar line or a jump mark passes
   every test, so it stays where it is."
  [word f x q]
  (quotation! q)
  (each x (partial f #(or (mt/marker? %) (mt/result word q %)))))

(defn filter
  "The elements q keeps: [c e g c'] '( pitch 65 lt ) filter"
  [x q] (by-test "filter" core/filter x q))

(defn remove
  "The elements q drops; on a line the later notes move earlier"
  [x q] (by-test "remove" (fn [t xs] (core/remove #(and (not (mt/marker? %)) (t %)) xs)) x q))

(defn take-while
  "The elements up to the first q refuses"
  [x q] (by-test "take-while" core/take-while x q))

(defn drop-while
  "The elements from the first q refuses"
  [x q] (by-test "drop-while" (fn [t xs] (core/drop-while #(and (not (mt/marker? %)) (t %)) xs)) x q))

(defn- by-print
  "f on xs, each element compared as mu prints it."
  [f xs]
  (core/map second (f (core/map (fn [x] [(p/show x) x]) xs))))

(defn distinct
  "Each element once, the first time it comes (as printed)"
  [x]
  (each x (partial by-print (fn [shown] (let [seen (volatile! #{})]
                                          (core/filter (fn [[k]] (when-not (@seen k) (vswap! seen conj k))) shown))))))

(defn dedupe
  "An element repeated at once kept once (as printed)"
  [x]
  (each x (partial by-print #(core/map core/first (partition-by core/first %)))))

(defn choose-n
  "n elements, drawn at random without putting back"
  [x n]
  (each x #(random/choose-n (count! n) (vec (mt/finite! "choose-n" %)))))

(defn choose-from
  "As many elements, each drawn at random from x"
  [x]
  (each x (comp random/choose-from vec #(mt/finite! "choose-from" %))))

(defn select
  "One element, drawn by the weights"
  [x weights]
  (let [ws (vec (mt/finite! "select" (mt/elements weights)))
        xs (vec (mt/finite! "select" (mt/elements x)))]
    (when-not (and (seq xs) (= (core/count xs) (core/count ws)) (every? #(and (number? %) (not (neg? %))) ws))
      (fail "needs as many weights (numbers, not negative) as elements"))
    (random/weighted-choose xs ws)))

(defn map
  "Each element through q: notes give x's form back, anything else a
   stream -- [c e g] '( pitch ) map"
  [x q]
  (quotation! q)
  (let [ys (core/map #(if (mt/marker? %) % (mt/result "map" q %)) (mt/elements x))]
    (cond
      (mt/endless? x) (mt/endless ys)
      (and (or (v/material? x) (mt/leaf? x)) (every? #(or (mt/leaf? %) (v/material? %) (mt/marker? %)) ys))
      (mt/reshape-keeping (if (mt/leaf? x) (mt/line [x]) x) (constantly ys))
      :else (core/remove mt/marker? ys))))
