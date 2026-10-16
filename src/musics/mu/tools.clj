(ns musics.mu.tools
  "mu's tools (doc/md/mu-repl.md, section 8): make, form, selection and
   map in namespace tool, assemble and the meaning tools in music. A
   tool keeps the form: on a line it gives a line, on a parallel a
   parallel, on a stream a stream. Endless results (cycle, iterate) are
   lazy streams; take makes them finite."
  (:require [clojure.string :as str]
            [musics.algo.bridge :as bridge]
            [musics.algo.random :as random]
            [musics.common.music-data :as data]
            [musics.common.music-elements :as el]
            [musics.algo.tree.lib :as lib]
            [musics.domain :as d]
            [musics.domain.context :as c]
            [musics.input.reader.leaf-parser :as lp]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.repo :as repo]))

;; -- helpers --------------------------------------------------------------

(defn- endless [xs] (with-meta xs {:mu/endless true}))

(defn- endless? [x] (and (sequential? x) (:mu/endless (meta x))))

(defn- finite!
  "xs, refused if it is an endless stream: word needs all of it."
  [word xs]
  (when (endless? xs) (e/mu-error (str word ":") "needs all of a stream, and this one is endless -- take some first"))
  xs)

(defn- reshape
  "f on x's elements, giving a value of x's form: rebuilt for a value,
   a stream for a stream."
  [x f]
  (let [els (f (mt/elements x))]
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

(word! "tool" ".." "( a b -- stream )  range" (:fn (get-in @e/state [:ns "tool" "range"])))

(word! "tool" "iterate" "( x q -- endless )  x, then q of x, then q of that, forever: 1 '( 2 .+ ) iterate 4 take"
       (fn [s]
         (need s 2 "iterate")
         (let [q (peek s) x (peek (pop s))]
           (when-not (v/quotation? q) (e/mu-error "iterate: needs a quotation '( … ) on top"))
           (conj (pop (pop s)) (endless (iterate #(peek (e/call [%] q)) x))))))

(defn key? [x] (instance? musics.common.music_elements.Key x))

(defn- key-line
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
       (fn [s] (let [n (count-arg s "take") s (pop s) x (peek s)]
                 (conj (pop s) (if (endless? x) (take n x) (reshape x (partial take n)))))))
(word! "tool" "drop" "( x n -- x' )  all but the first n elements" (with-n "drop" drop))
(word! "tool" "take-last" "( x n -- x' )  the last n elements" (with-n "take-last" (fn [n xs] (take-last n (finite! "take-last" xs)))))
(word! "tool" "drop-last" "( x n -- x' )  all but the last n elements" (with-n "drop-last" (fn [n xs] (drop-last n (finite! "drop-last" xs)))))
(word! "tool" "[*]" "( x n -- copies )  n copies of x, each its own element: [[a b] [a b] …]"
       (fn [s]
         (let [n (count-arg s "[*]") s (pop s) x (peek s)]
           (conj (pop s)
                 (if (or (mt/leaf? x) (and (v/material? x) (not= :DATA (mt/kind x))))
                   (do (when (zero? n) (e/mu-error "[*]: needs at least 1 copy"))
                       (mt/line (repeat n x)))
                   (reshape x #(apply concat (repeat n (finite! "[*]" %)))))))))
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

(word! "tool" "reverse" "( x -- x' )  in reverse order" (one "reverse" (comp reverse #(finite! "reverse" %))))
(word! "tool" "shuffle" "( x -- x' )  in a random order (also [?])" (one "shuffle" (comp random/shuffle #(finite! "shuffle" %))))
(word! "tool" "sputter" "( x -- x' )  some elements repeated at random, a stutter" (one "sputter" (comp random/sputter vec #(finite! "sputter" %))))
(word! "tool" "cycle" "( x -- endless )  the elements repeated forever -- take some before playing"
       (fn [s] (need s 1 "cycle") (conj (pop s) (endless (cycle (vec (finite! "cycle" (mt/elements (peek s)))))))))

(word! "tool" "then" "( a b -- a then b )  a followed by b (also [+])"
       (fn [s]
         (need s 2 "then")
         (let [b (peek s) a (peek (pop s))]
           (conj (pop (pop s)) (reshape a #(concat % (mt/elements b)))))))

(word! "tool" "[+]" "( a b -- a then b )  the two joined into one, as then" (:fn (get-in @e/state [:ns "tool" "then"])))
(word! "tool" "[%]" "( x n -- x' )  rotated by n, wrapping around, as rotate" (:fn (get-in @e/state [:ns "tool" "rotate"])))
(word! "tool" "[?]" "( x -- x' )  in a random order, as shuffle" (:fn (get-in @e/state [:ns "tool" "shuffle"])))

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

(defn- result
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

(word! "tool" "filter" "( x q -- x' )  the elements q keeps: [c e g c'] '( pitch 65 .< ) filter"
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

(defn- par-of [xs]
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

(defn- parts [x] (if (= :PAR (mt/kind x)) (mt/elements x) [x]))

(word! "music" "{+}" "( a b -- parallel )  the parts of the two in one { }: {[a] [b]} {[c] [d]} {+} gives {[a] [b] [c] [d]}; a line is one part"
       (fn [s]
         (need s 2 "{+}")
         (let [b (peek s) a (peek (pop s))]
           (conj (pop (pop s)) (if (= :PAR (mt/kind a))
                                 (reshape a #(concat % (parts b)))
                                 (par-of (cons a (parts b))))))))

(word! "music" "<>" "( leaves -- chord )  the notes on top of the stack as one chord: c e g <>"
       (fn [s]
         (let [leaves (reverse (take-while #(and (mt/leaf? %) (seq (:pitches %))) (rseq s)))]
           (when (empty? leaves) (e/mu-error "<>: needs notes on top of the stack"))
           (conj (subvec s 0 (- (count s) (count leaves)))
                 (assoc (first leaves) :pitches (vec (distinct (mapcat :pitches leaves))))))))

(defn- zipped
  "Leaves from durations and pitches: a rest among the durations stays a
   rest and uses no pitch, as the trees' zip does."
  [ds ps]
  (lazy-seq
   (when-let [[d & ds'] (seq ds)]
     (if (and (map? d) (= :REST (:type d)))
       (cons (lib/->part nil (:duration d)) (zipped ds' ps))
       (when-let [[x & ps'] (seq ps)]
         (let [p (if (mt/leaf? x) (let [p (:pitches x)] (if (next p) p (first p))) x)]
           (cons (lib/->part p (if (mt/leaf? d) (:duration d) d)) (zipped ds' ps'))))))))

(word! "music" "zip" "( durations pitches -- line )  a rest among the durations stays a rest, using no pitch"
       (fn [s]
         (need s 2 "zip")
         (let [ps (mt/elements (peek s)) ds (mt/elements (peek (pop s)))]
           (conj (pop (pop s)) (mt/line (zipped ds ps))))))

(word! "music" "sounds" "( leaf numbers -- line )  a copy of the leaf per number: a drum's key, a note's instrument (\\i:n)"
       (fn [s]
         (need s 2 "sounds")
         (let [ns (peek s) x (peek (pop s))]
           (when-not (and (mt/leaf? x) (not= :REST (:type x)))
             (e/mu-error "sounds: needs a note, chord or drum under the numbers, got" (p/show x)))
           (let [nums (finite! "sounds" (mt/elements ns))]
             (when-not (every? #(and (integer? %) (<= 0 % 127)) nums)
               (e/mu-error "sounds: needs whole numbers 0-127 (drum keys or instruments), as 35 82 range or '[36 38 42]"))
             (conj (pop (pop s))
                   (mt/line (for [n nums]
                              (if (= :DRUM (:type x)) (assoc x :program n) (assoc-in x [:overrides :instrument] n)))))))))

(defn- leaf-arg [s word]
  (need s 1 word)
  (let [x (peek s)]
    (when-not (mt/leaf? x) (e/mu-error (str word ":") "reads a note, chord, rest or drum, got" (p/show x)))
    x))

(defn- leaves-of
  "Every leaf of x, at any depth, in written order."
  [x]
  (let [ls (volatile! [])]
    (doall (mt/map-leaves #(do (vswap! ls conj %) %) x))
    @ls))

(defn- one-note
  "A word ( note -- value ): f of the note, chord, rest or drum on top.
   On a line it says to use `plural`, which reads every note."
  [word plural f]
  (fn [s]
    (need s 1 word)
    (let [x (peek s)]
      (cond
        (mt/leaf? x) (conj (pop s) (f x))
        (or (v/material? x) (sequential? x))
        (e/mu-error (str word ":") "reads one note; for every note of a line, use" (or plural (str "'( " word " ) map")))
        :else (leaf-arg s word)))))

(defn- each-note
  "A word ( line -- stream ): f of every note of a line (or of one note,
   or a stream of them), as a stream -- the line taken apart, ready for
   zip."
  [word f]
  (fn [s]
    (need s 1 word)
    (let [x (peek s)]
      (if (or (mt/leaf? x) (v/material? x) (sequential? x))
        (conj (pop s) (apply list (map f (leaves-of (finite! word x)))))
        (e/mu-error (str word ":") "reads a line, got" (p/show x))))))

(def ^:private articulation-order
  [:staccatissimo :stopped :staccato :marcato :portato :accent :tenuto :legato :sfz :fermata :ghost])

(defn- articulation-name
  "The articulation a leaf was written with, by its length and dynamic
   (a drum by its dynamic alone), else nil."
  [x]
  (let [drum? (= :DRUM (:type x))
        a     (:articulation x)
        dyn   (or (:dynamic x) 0)]
    (when (or a (not= 0 dyn))
      (some (fn [k] (let [v (data/articulations k)]
                      (when (and (= dyn (:dynamic v)) (or drum? (= a (:duration v)))) k)))
            articulation-order))))

(defn- note-pitch
  "A note's pitch, a chord's pitches lowest first, nil for a rest or drum."
  [x]
  (let [ps (some-> (:pitches x) seq sort)]
    (if (next ps) (apply list ps) (first ps))))

(defn- instrument-of [x]
  (if (= :DRUM (:type x)) (:program x) (get-in x [:overrides :instrument])))

(doseq [[one many f what]
        [["pitch" "pitches" note-pitch "the pitch: 60 for c4, '[60 64 67] for <c e g>, nil for a rest or drum"]
         ["duration" "durations" :duration "the length, in whole notes"]
         ["volume" "volumes" #(get-in % [:overrides :volume]) "its own volume (c4\\f, c4\\vol:90), else nil"]
         ["articulation" "articulations" articulation-name "its articulation (:staccato, :accent …), else nil"]
         ["instrument" "instruments" instrument-of "its own instrument (c4\\i:40), a drum's key; else nil"]]]
  (word! "music" one (str "( note -- value )  " what "; for a line, " many) (one-note one many f))
  (word! "music" many (str "( line -- stream )  each note's " one ", as a stream: x durations x pitches zip is x again")
         (each-note many f)))

(word! "music" "note?" "( leaf -- flag )  true for a note with one pitch" (one-note "note?" nil #(and (= :LEAF (:type %)) (= 1 (count (:pitches %))))))
(word! "music" "chord?" "( leaf -- flag )  true for a chord" (one-note "chord?" nil #(and (= :LEAF (:type %)) (< 1 (count (:pitches %))))))
(word! "music" "rest?" "( leaf -- flag )  true for a rest" (one-note "rest?" nil #(= :REST (:type %))))
(word! "music" "drum?" "( leaf -- flag )  true for a drum" (one-note "drum?" nil #(= :DRUM (:type %))))

(word! "music" "degrees" "( numbers key -- line )  scale steps of the key from its tonic in octave 4: 0 the tonic, 7 an octave up, -1 the step below"
       (fn [s]
         (need s 2 "degrees")
         (let [ks (peek s) ns (peek (pop s))]
           (when-not (key? ks) (e/mu-error "degrees: needs a key on top, as D.major, got" (p/show ks)))
           (let [nums (finite! "degrees" (mt/elements ns))]
             (when-not (every? integer? nums) (e/mu-error "degrees: needs whole numbers under the key, as '[0 2 4]"))
             (conj (pop (pop s)) (key-line ks (map #(bridge/degree->pitch % (el/key->str ks) 4) nums)))))))

(word! "music" "tonic" "( key -- note )  the key's tonic, in octave 4"
       (fn [s]
         (need s 1 "tonic")
         (let [ks (peek s)]
           (when-not (key? ks) (e/mu-error "tonic: needs a key, as D.major, got" (p/show ks)))
           (conj (pop s) (lib/->part (+ 60 (first (el/key-pitches ks))) 1/4)))))

(word! "music" "key" "( note|line -- key )  the key a note was written in, or a line's own (else the defaults')"
       (fn [s]
         (need s 1 "key")
         (let [x (peek s)]
           (conj (pop s)
                 (cond
                   (mt/leaf? x) (or (:key x) (e/mu-error "key: this note has no written key (written under !acc:explicit, or not written at all)"))
                   (v/material? x) (c/ctx-value-chain (keep :context [(v/node x) (repo/current :ROOT)]) :key 0)
                   :else (e/mu-error "key: needs a note or a line, got" (p/show x)))))))

(defn- with-settings
  "x with the settings (written instructions) in front: inserted at the
   start of a line -- its own come after them, so they still win -- or
   of a note made a line; anything else (a { }, a repeat) wrapped in a
   line that holds them."
  [x settings]
  (let [shown (p/show (mt/->material x))]
    (mt/read-text
     (if-let [[_ id body] (and (or (mt/leaf? x) (and (= :SEQ (mt/kind x)) (not (mt/repetition? x))))
                               (re-matches #"(?s)\[([a-zA-Z][a-zA-Z0-9_\-]*: )?(.*)\]" shown))]
       (str "[" id settings " " body "]")
       (str "[" settings " " shown "]")))))

(word! "music" "with" "( material ^{ }|key -- material )  the settings in front of a line (its own come after, so they win), or played in that key; a { } or a repeat is wrapped"
       (fn [s]
         (need s 2 "with")
         (let [ctx (peek s) x (peek (pop s))
               ctx (if (key? ctx) (mt/read-text (str "^{ !key:" (el/key->str ctx) " }")) ctx)]
           (when-not (= :CONTEXT (mt/kind ctx)) (e/mu-error "with: needs a context ^{ … } on top"))
           (conj (pop (pop s))
                 (with-settings x (str/join " " (map :raw (:children (v/node ctx)))))))))

(defn- pitch-of [x]
  (cond (number? x) x
        (mt/leaf? x) (or (first (:pitches x)) (e/mu-error "transpose: a rest has no pitch"))
        :else (e/mu-error "transpose: needs two notes or pitch numbers, got" (p/show x))))

(word! "music" "transpose" "( material from to -- material )  moved by the interval from one note to the other: [c e g] c d transpose"
       (fn [s]
         (need s 3 "transpose")
         (let [to (pitch-of (peek s)) from (pitch-of (peek (pop s))) x (peek (pop (pop s)))
               n (- to from)]
           (conj (pop (pop (pop s)))
                 (mt/map-leaves #(if (seq (:pitches %)) (update % :pitches (partial mapv (partial + n))) %) x)))))

(word! "music" "repeat" "( x n -- repeat )  x played n times, as the text's repeat n [ x ]"
       (fn [s]
         (let [n (count-arg s "repeat") s (pop s)]
           (need s 1 "repeat")
           (when (zero? n) (e/mu-error "repeat: needs at least 1 pass"))
           (conj (pop s) (mt/repetition (peek s) n [])))))

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

(defn- pitches-of [x]
  (let [ps (volatile! [])]
    (doall (mt/map-leaves #(do (vswap! ps into (:pitches %)) %) x))
    @ps))

(defn- invert-around
  "x mirrored through d/invert around (axis pitches), unchanged when it
   has no pitch."
  [axis x]
  (let [ps (pitches-of x)]
    (if (seq ps) (mt/map-leaves (d/invert (axis ps)) x) x)))

(def ^:private on-mean
  "The rounded mean, musics.algo.common.reshape/invert's axis."
  #(Math/round (double (/ (reduce + %) (count %)))))

(def ^:private crab (one "[R]" (comp reverse #(finite! "[R]" %))))

(defn- word-on-top [word f] (fn [s] (need s 1 word) (conj (pop s) (f (peek s)))))

(word! "music" "invert" "( material -- material )  every pitch mirrored around the mean pitch, as [-]"
       (word-on-top "invert" (partial invert-around on-mean)))
(word! "music" "[-]" "( material -- material )  the inversion around the mean pitch: - on the whole"
       (word-on-top "[-]" (partial invert-around on-mean)))
(word! "music" "[R]" "( material -- material )  the retrograde (the crab): in reverse order; exactly [R] -- [ R ] is a line with a bar's rest" crab)
(word! "music" "[I]" "( material -- material )  the inversion around the first note (a chord's first written pitch), which stays; I5 is [I] 5 +"
       (word-on-top "[I]" (partial invert-around first)))
(word! "music" "[RI]" "( material -- material )  the retrograde inversion: inverted around the first note, then backwards"
       (fn [s] (crab ((word-on-top "[RI]" (partial invert-around first)) s))))

(word! "music" "[~]" "( x -- loop )  x endlessly, repeat ~ [ x ]: [c d] [~] > plays until <.>"
       (fn [s] (need s 1 "[~]") (conj (pop s) (mt/repetition (peek s) :infinite []))))

(word! "music" "[:]" "( x -- [: x :] )  x in repeat signs, played twice"
       (fn [s] (need s 1 "[:]") (conj (pop s) (mt/repetition (peek s) 2 [] true))))
(word! "music" "[!]" "( x -- line )  x written out as it plays: repeats as their passes, jumps and ./. followed"
       (fn [s] (need s 1 "[!]") (conj (pop s) (mt/written-out (peek s)))))
(word! "music" "[[]]" "( x -- line )  x split by its meter into its measures, each a line of its own: [c2 d2 e2] [[]] gives [[C4/2 D4] [E4]], a note across a bar line cut and tied as [|] does"
       (fn [s] (need s 1 "[[]]") (conj (pop s) (mt/measures (peek s)))))
(word! "music" "[|]" "( x -- line )  x barred by its meter: a | every bar, a note across one cut and tied"
       (fn [s] (need s 1 "[|]") (conj (pop s) (mt/barred (peek s)))))

(word! "music" "mute" "( material q -- material )  each note or drum q keeps becomes a rest of its length: the rhythm stays -- [c e g c'] '( pitch 65 .> ) mute"
       (fn [s]
         (let [q (quoted s "mute") s (pop s)]
           (conj (pop s)
                 (mt/map-leaves #(if (and (not= :REST (:type %)) (result "mute" q %))
                                   (merge (d/rest* nil nil (:duration %)) (select-keys % [:ratio]))
                                   %)
                                (peek s))))))

(word! "music" "stretch" "( material f -- material )  every duration times f"
       (fn [s]
         (need s 2 "stretch")
         (let [f (peek s) x (peek (pop s))]
           (when-not (and (number? f) (pos? f)) (e/mu-error "stretch: needs a positive factor"))
           (conj (pop (pop s)) (mt/map-leaves #(update % :duration * f) x)))))
