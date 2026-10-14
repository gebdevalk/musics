(ns musics.mu.tools
  "mu's tools, the first set (doc/mu-repl.md, section 8): make and form
   in namespace tool, assemble and the meaning value tools in music. A
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

(word! "tool" "range" "( a b -- stream )"
       (fn [s]
         (need s 2 "range")
         (let [b (peek s) a (peek (pop s))]
           (when-not (and (number? a) (number? b)) (e/mu-error "range: needs two numbers"))
           (conj (pop (pop s)) (range a b)))))

(word! "tool" ".." "( a b -- stream )  range" (:fn (get-in @e/state [:ns "tool" "range"])))

(word! "tool" "iterate" "( x q -- endless )"
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

(word! "tool" "first" "( x -- element )"
       (fn [s] (need s 1 "first")
         (let [els (mt/elements (peek s))]
           (when (empty? els) (e/mu-error "first: nothing in" (p/show (peek s))))
           (conj (pop s) (first els)))))
(word! "tool" "last" "( x -- element )"
       (fn [s] (need s 1 "last")
         (let [els (finite! "last" (mt/elements (peek s)))]
           (when (empty? els) (e/mu-error "last: nothing in" (p/show (peek s))))
           (conj (pop s) (last els)))))
(word! "tool" "nth" "( x n -- element )  counted from 0"
       (fn [s] (let [n (count-arg s "nth") s (pop s) els (mt/elements (peek s))
                     e (nth els n ::none)]
                 (when (= ::none e) (e/mu-error "nth:" n "is past the end of" (p/show (peek s))))
                 (conj (pop s) e))))
(word! "tool" "count" "( x -- n )"
       (fn [s] (need s 1 "count") (conj (pop s) (count (finite! "count" (mt/elements (peek s)))))))

;; -- form (tool) ----------------------------------------------------------

(word! "tool" "take" "( x n -- x' )"
       (fn [s] (let [n (count-arg s "take") s (pop s) x (peek s)]
                 (conj (pop s) (if (endless? x) (take n x) (reshape x (partial take n)))))))
(word! "tool" "drop" "( x n -- x' )" (with-n "drop" drop))
(word! "tool" "take-last" "( x n -- x' )" (with-n "take-last" (fn [n xs] (take-last n (finite! "take-last" xs)))))
(word! "tool" "drop-last" "( x n -- x' )" (with-n "drop-last" (fn [n xs] (drop-last n (finite! "drop-last" xs)))))
(word! "tool" "[*]" "( x n -- copies )  n copies of x, each its own element: [[a b] [a b] …]"
       (fn [s]
         (let [n (count-arg s "[*]") s (pop s) x (peek s)]
           (conj (pop s)
                 (if (or (mt/leaf? x) (and (v/material? x) (not= :DATA (mt/kind x))))
                   (do (when (zero? n) (e/mu-error "[*]: needs at least 1 copy"))
                       (mt/line (repeat n x)))
                   (reshape x #(apply concat (repeat n (finite! "[*]" %)))))))))
(word! "tool" "rotate" "( x n -- x' )"
       (with-n "rotate" (fn [n xs] (let [v (vec (finite! "rotate" xs)) k (if (seq v) (mod n (count v)) 0)]
                                     (concat (drop k v) (take k v))))))
(word! "tool" "partition" "( x n -- groups )"
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
(word! "tool" "split-at" "( x n -- a b )"
       (fn [s]
         (let [n (count-arg s "split-at") s (pop s) x (peek s)]
           (conj (pop s) (reshape x (partial take n)) (reshape x (partial drop n))))))

(word! "tool" "reverse" "( x -- x' )" (one "reverse" (comp reverse #(finite! "reverse" %))))
(word! "tool" "shuffle" "( x -- x' )" (one "shuffle" (comp random/shuffle #(finite! "shuffle" %))))
(word! "tool" "sputter" "( x -- x' )" (one "sputter" (comp random/sputter vec #(finite! "sputter" %))))
(word! "tool" "cycle" "( x -- endless )"
       (fn [s] (need s 1 "cycle") (conj (pop s) (endless (cycle (vec (finite! "cycle" (mt/elements (peek s)))))))))

(word! "tool" "then" "( a b -- a then b )"
       (fn [s]
         (need s 2 "then")
         (let [b (peek s) a (peek (pop s))]
           (conj (pop (pop s)) (reshape a #(concat % (mt/elements b)))))))

(word! "tool" "[+]" "( a b -- a then b )  the two joined into one, as then" (:fn (get-in @e/state [:ns "tool" "then"])))
(word! "tool" "[%]" "( x n -- x' )  rotated by n, wrapping around, as rotate" (:fn (get-in @e/state [:ns "tool" "rotate"])))
(word! "tool" "[?]" "( x -- x' )  in a random order, as shuffle" (:fn (get-in @e/state [:ns "tool" "shuffle"])))

(word! "tool" "interleave" "( a b -- a b alternating )"
       (fn [s]
         (need s 2 "interleave")
         (let [b (peek s) a (peek (pop s))]
           (conj (pop (pop s)) (reshape a #(interleave % (mt/elements b)))))))

;; -- assemble and meaning (music) ----------------------------------------------

(defn- gather
  "A word that gathers what lies above the nearest | mark into (f xs)."
  [word f]
  (fn [s]
    (let [[below xs] (e/to-mark s word)]
      (when (empty? xs) (e/mu-error (str word ":") "has nothing above its | mark"))
      (conj below (f xs)))))

(word! "music" "seq" "( | … -- line )" (gather "seq" mt/line))

(defn- par-of [xs]
  (mt/read-text (str "{" (apply str (interpose " " (map #(p/show (mt/->material %)) xs))) "}")))

(word! "music" "par" "( | … -- parallel )" (gather "par" par-of))
(word! "music" "[&]" "( a b -- parallel )  the two at once, { a b }: [+] side by side"
       (fn [s] (need s 2 "[&]") (conj (pop (pop s)) (par-of [(peek (pop s)) (peek s)]))))

(word! "music" "<>" "( leaves -- chord )"
       (fn [s]
         (let [leaves (reverse (take-while #(and (mt/leaf? %) (seq (:pitches %))) (rseq s)))]
           (when (empty? leaves) (e/mu-error "<>: needs notes on top of the stack"))
           (conj (subvec s 0 (- (count s) (count leaves)))
                 (assoc (first leaves) :pitches (vec (distinct (mapcat :pitches leaves))))))))

(word! "music" "zip" "( durations pitches -- line )"
       (fn [s]
         (need s 2 "zip")
         (let [ps (mt/elements (peek s)) ds (mt/elements (peek (pop s)))
               pitch (fn [x] (cond (mt/leaf? x) (let [p (:pitches x)] (if (next p) p (first p))) :else x))]
           (conj (pop (pop s)) (mt/line (map (fn [d x] (lib/->part (pitch x) d))
                                             (map #(if (mt/leaf? %) (:duration %) %) ds) ps))))))

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

(defn- reader
  "A word ( leaf -- value ): f of the leaf on top."
  [word f]
  (fn [s] (let [x (leaf-arg s word)] (conj (pop s) (f x)))))

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

(word! "music" "pitch" "( leaf -- n )  the pitch, a chord's lowest; nil for a rest or drum"
       (reader "pitch" #(some->> (:pitches %) seq (apply min))))
(word! "music" "pitches" "( leaf -- '[n …] )  every pitch, lowest first"
       (reader "pitches" #(some->> (:pitches %) seq sort (apply list))))
(word! "music" "duration" "( leaf -- r )  the length, in whole notes"
       (reader "duration" :duration))
(word! "music" "volume" "( leaf -- n )  its own volume (c4\\f, c4\\vol:90), else nil"
       (reader "volume" #(get-in % [:overrides :volume])))
(word! "music" "articulation" "( leaf -- :name )  its articulation (:staccato, :accent …), else nil"
       (reader "articulation" articulation-name))
(word! "music" "instrument" "( leaf -- n )  a note's own instrument (c4\\i:40), a drum's key; else nil"
       (reader "instrument" #(if (= :DRUM (:type %)) (:program %) (get-in % [:overrides :instrument]))))
(word! "music" "note?" "( leaf -- flag )" (reader "note?" #(and (= :LEAF (:type %)) (= 1 (count (:pitches %))))))
(word! "music" "chord?" "( leaf -- flag )" (reader "chord?" #(and (= :LEAF (:type %)) (< 1 (count (:pitches %))))))
(word! "music" "rest?" "( leaf -- flag )" (reader "rest?" #(= :REST (:type %))))
(word! "music" "drum?" "( leaf -- flag )" (reader "drum?" #(= :DRUM (:type %))))

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

(word! "music" "with" "( material ^{ }|key -- material )  under that context, or played in that key"
       (fn [s]
         (need s 2 "with")
         (let [ctx (peek s) x (peek (pop s))
               ctx (if (key? ctx) (mt/read-text (str "^{ !key:" (el/key->str ctx) " }")) ctx)]
           (when-not (= :CONTEXT (mt/kind ctx)) (e/mu-error "with: needs a context ^{ … } on top"))
           (conj (pop (pop s))
                 (mt/read-text (str "[" (apply str (interpose " " (map :raw (:children (v/node ctx)))))
                                    " " (p/show (mt/->material x)) "]"))))))

(defn- pitch-of [x]
  (cond (number? x) x
        (mt/leaf? x) (or (first (:pitches x)) (e/mu-error "transpose: a rest has no pitch"))
        :else (e/mu-error "transpose: needs two notes or pitch numbers, got" (p/show x))))

(word! "music" "transpose" "( material from to -- material )"
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

(def ^:private crab (one "[<]" (comp reverse #(finite! "[<]" %))))

(defn- word-on-top [word f] (fn [s] (need s 1 word) (conj (pop s) (f (peek s)))))

(word! "music" "invert" "( material -- material )  every pitch mirrored around the mean pitch, as [-]"
       (word-on-top "invert" (partial invert-around on-mean)))
(word! "music" "[-]" "( material -- material )  the inversion around the mean pitch: - on the whole"
       (word-on-top "[-]" (partial invert-around on-mean)))
(word! "music" "[^]" "( material -- material )  the inversion around the first note (a chord's first written pitch), which stays"
       (word-on-top "[^]" (partial invert-around first)))
(word! "music" "[<]" "( material -- material )  the crab: in reverse order" crab)
(word! "music" "[<^]" "( material -- material )  the crab, then its inversion around its own first note (the original's last)"
       (fn [s] ((word-on-top "[<^]" (partial invert-around first)) (crab s))))
(word! "music" "[^<]" "( material -- material )  the inversion around the first note, then its crab"
       (fn [s] (crab ((word-on-top "[^<]" (partial invert-around first)) s))))

(word! "music" "[~]" "( x -- loop )  x endlessly, repeat ~ [ x ]: [c d] [~] > plays until []"
       (fn [s] (need s 1 "[~]") (conj (pop s) (mt/repetition (peek s) :infinite []))))

(word! "music" "[:]" "( x -- [: x :] )  x in repeat signs, played twice"
       (fn [s] (need s 1 "[:]") (conj (pop s) (mt/repetition (peek s) 2 [] true))))
(word! "music" "[!]" "( x -- line )  x written out as it plays: repeats as their passes, jumps and ./. followed"
       (fn [s] (need s 1 "[!]") (conj (pop s) (mt/written-out (peek s)))))
(word! "music" "[|]" "( x -- line )  x barred by its meter: a | every bar, a note across one cut and tied"
       (fn [s] (need s 1 "[|]") (conj (pop s) (mt/barred (peek s)))))

(word! "music" "stretch" "( material f -- material )"
       (fn [s]
         (need s 2 "stretch")
         (let [f (peek s) x (peek (pop s))]
           (when-not (and (number? f) (pos? f)) (e/mu-error "stretch: needs a positive factor"))
           (conj (pop (pop s)) (mt/map-leaves #(update % :duration * f) x)))))
