(ns musics.mu.tools
  "mu's tools, the first set (doc/mu-repl.md, section 8): make and form
   in namespace tool, assemble and the meaning value tools in music. A
   tool keeps the form: on a line it gives a line, on a parallel a
   parallel, on a stream a stream. Endless results (cycle, iterate) are
   lazy streams; take makes them finite."
  (:require [musics.algo.random :as random]
            [musics.algo.tree.lib :as lib]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

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

(word! "tool" "repeat" "( x n -- stream )"
       (fn [s] (let [n (count-arg s "repeat") s (pop s)] (conj (pop s) (repeat n (peek s))))))

(word! "tool" "iterate" "( x q -- endless )"
       (fn [s]
         (need s 2 "iterate")
         (let [q (peek s) x (peek (pop s))]
           (when-not (v/quotation? q) (e/mu-error "iterate: needs a quotation '( … ) on top"))
           (conj (pop (pop s)) (endless (iterate #(peek (e/call [%] q)) x))))))

(word! "tool" "scale" "( root intervals -- pitches )"
       (fn [s]
         (need s 2 "scale")
         (let [ivs (mt/elements (peek s)) root (peek (pop s))]
           (when-not (number? root) (e/mu-error "scale: needs a root pitch number under the intervals"))
           (conj (pop (pop s)) (map #(+ root %) ivs)))))

;; -- form (tool) ----------------------------------------------------------

(word! "tool" "take" "( x n -- x' )"
       (fn [s] (let [n (count-arg s "take") s (pop s) x (peek s)]
                 (conj (pop s) (if (endless? x) (take n x) (reshape x (partial take n)))))))
(word! "tool" "drop" "( x n -- x' )" (with-n "drop" drop))
(word! "tool" "take-last" "( x n -- x' )" (with-n "take-last" (fn [n xs] (take-last n (finite! "take-last" xs)))))
(word! "tool" "drop-last" "( x n -- x' )" (with-n "drop-last" (fn [n xs] (drop-last n (finite! "drop-last" xs)))))
(word! "tool" "**" "( x n -- x repeated n times )" (with-n "**" (fn [n xs] (apply concat (repeat n (finite! "**" xs))))))
(word! "tool" "rotate" "( x n -- x' )"
       (with-n "rotate" (fn [n xs] (let [v (vec (finite! "rotate" xs)) k (if (seq v) (mod n (count v)) 0)]
                                     (concat (drop k v) (take k v))))))
(word! "tool" "partition" "( x n -- groups )"
       (fn [s]
         (let [n (count-arg s "partition") s (pop s) x (peek s)]
           (when (zero? n) (e/mu-error "partition: needs a size above 0"))
           (let [groups (partition-all n (finite! "partition" (mt/elements x)))]
             (conj (pop s) (if (or (v/material? x) (mt/leaf? x)) (mt/line (map mt/line groups)) groups))))))
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

(word! "tool" "interleave" "( a b -- a b alternating )"
       (fn [s]
         (need s 2 "interleave")
         (let [b (peek s) a (peek (pop s))]
           (conj (pop (pop s)) (reshape a #(interleave % (mt/elements b)))))))

;; -- assemble and meaning (music) ----------------------------------------------

(defn- gather [word f]
  (fn [s]
    (let [n (count-arg s word) s (pop s)]
      (need s n word)
      (conj (subvec s 0 (- (count s) n)) (f (subvec s (- (count s) n)))))))

(word! "music" "seq" "( … n -- line )" (gather "seq" mt/line))

(word! "music" "par" "( … n -- parallel )"
       (gather "par" (fn [xs] (mt/read-text (str "{" (apply str (interpose " " (map #(p/show (mt/->material %)) xs))) "}")))))

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

(word! "music" "with" "( material ^{ } -- material )"
       (fn [s]
         (need s 2 "with")
         (let [ctx (peek s) x (peek (pop s))]
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

(word! "music" "stretch" "( material f -- material )"
       (fn [s]
         (need s 2 "stretch")
         (let [f (peek s) x (peek (pop s))]
           (when-not (and (number? f) (pos? f)) (e/mu-error "stretch: needs a positive factor"))
           (conj (pop (pop s)) (mt/map-leaves #(update % :duration * f) x)))))
