(ns musics.mu.tools
  "The tool and music words that read the stack's shape
   (doc/md/mu-repl.md, section 8): split-at (two values), what lies
   above a | mark or a repeat, the top n ({n} [n] <>), and choose and
   alternate with their quotations; each with its named twin. The other
   tool and music words are musics.tool and musics.music."
  (:require [clojure.string :as str]
            [musics.mu.eval :as e :refer [word! need]]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.recipe :as recipe]
            [musics.algo.tree :as t]))

;; -- helpers --------------------------------------------------------------

(defn- count-arg [s word]
  (need s 2 word)
  (let [n (peek s)]
    (when-not (and (integer? n) (>= n 0)) (e/mu-error (str word ":") "needs a whole number on top, got" (p/show n)))
    n))

;; -- split-at (tool) ------------------------------------------------------

(word! "tool" "split-at" "( x n -- a b )  the first n elements and the rest, as two values"
       (fn [s]
         (let [n (count-arg s "split-at") s (pop s) x (peek s)]
           (conj (pop s) (mt/reshape x (partial take n)) (mt/reshape x (partial drop n))))))

;; -- assemble and meaning (music) ----------------------------------------------

(defn- gather
  "A word that gathers what lies above the nearest | mark into (f xs)."
  [word f]
  (fn [s]
    (let [[below xs] (e/to-mark s word)]
      (when (empty? xs) (e/mu-error (str word ":") "has nothing above its | mark"))
      (conj below (f xs)))))

(word! "music" "seq" "( | … -- line )  what lies above the | mark, one after another, as a line [ ]" (gather "seq" #(mt/line (map (partial mt/sounding! "seq") %))))

(word! "music" "par" "( | … -- parallel )  what lies above the | mark, side by side, as { }" (gather "par" mt/par-of))
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
       (top-n "{" "}" mt/par-of))
(word! "music" "[n]" "( a … n -- line )  the top n one after another, each an element, written [2] [3] … : [c d] [e f] [2] gives [[c d] [e f]]"
       (top-n "[" "]" #(mt/line (map (partial mt/sounding! "[n]") %))))

(word! "music" "<>" "( leaves -- chord )  the notes on top of the stack as one chord: c e g <>"
       (fn [s]
         (let [leaves (reverse (take-while #(and (mt/leaf? %) (seq (:pitches %))) (rseq s)))]
           (when (empty? leaves) (e/mu-error "<>: needs notes on top of the stack"))
           (conj (subvec s 0 (- (count s) (count leaves)))
                 (assoc (first leaves) :pitches (vec (distinct (mapcat :pitches leaves))))))))

;; -- zip: leaves from their parts ------------------------------------------------

(word! "music" "zip" "( durations pitches -- line ) or ( | [:part] stream … -- line )  a note's parts joined: the top two, or all above a | mark -- durations, pitches (or drums), articulations, dynamics, ornaments or tremolos, ties, then overrides (:volume :instrument :pan …). A stream a reader or algo word gave knows its part, a :name before one names it, the rest take the free parts in that order. One note per duration, the other parts cycling and stepping once per note; at a rest (r, or a nil pitch) their values go unused, and a nil writes nothing. Durations in a loop give a loop: | [c8 d e] [~] durations [c e g a] pitches volume/walk zip. An unnamed line is written on, keeping its form, a part given replacing its own: [c d e] volume/walk zip. Without durations and pitches it gives a bundle, the parts as columns, each as long as it is: '[:pitch '[60 62 64] :volume '[90 70]] -- zip takes it again as its parts"
       (fn [s]
         (if (some v/mark? s)
           (let [[below xs] (e/to-mark s "zip")]
             (conj below (mt/zip-parts xs)))
           (do (need s 2 "zip")
               (conj (pop (pop s)) (mt/zip-parts [(peek (pop s)) (peek s)]))))))

(word! "music" ">ctx" "( | :duration d :key v … -- ^{ } )  a control as a context: above a | mark, :duration and one or more context keys (:volume :Tempo :pan :Key …), each a stream, and an optional :curve (step lin smooth in out). The steps follow each other by the durations, each value holding until the next (step) or gliding to it; the longest finite column sets how many, the shorter ones cycling: | :duration '[1/1 ./. 2/1] :volume '[50 70 90] >ctx gives ^{ !volume:50 !volume:70@1 !volume:90@2 }, laid under music with with"
       (fn [s]
         (let [[below xs] (e/to-mark s ">ctx")]
           (conj below (mt/control->ctx xs)))))

(defn- recipe-value
  "What a tree recipe gave, as a mu value: a line, a { } for parts, a
   stream otherwise -- refused when it is endless."
  [r x]
  (let [out (t/out-type (:tree r))
        cut (fn [xs] (let [h (vec (take 10001 xs))]
                       (when (> (count h) 10000) (e/mu-error "run: the recipe is endless -- play it, or take some (take> in it)"))
                       h))]
    (cond (= :leaf out)        (mt/line (cut x))
          (= :part out)        (mt/par-of (map #(mt/line (cut %)) x))
          (t/bundle-type? out) (e/mu-error "run: the recipe gives a bundle" (pr-str out) "-- zip it with durations and pitches")
          (sequential? x)      (mt/as-part out (apply list (cut x)))
          :else x)))

(word! "music" "run" "( inputs… [@^{ }] recipe -- result )  a tree recipe's result: run on the values it takes from the stack under it (the music it changes), with settings given before it merged into its own: @^{ k:3 n:8 len:8 } @<< euclid pitch/walk zip >> run; [c8 d e f] @<< dup duration/emi zip >> run. A line, a { } for parts, a stream otherwise; an endless one is refused -- play it"
       (fn [s]
         (need s 1 "run")
         (let [r (peek s) s (pop s)]
           (when-not (v/recipe? r) (e/mu-error "run: takes a tree recipe @<< … >> -- got" (p/show r)))
           (let [[r s] (if (and (seq s) (= :TCONTEXT (mt/kind (peek s))))
                         [(recipe/with-settings r (into {} (:params (v/node (peek s))))) (pop s)]
                         [r s])
                 n     (:arity r)
                 _     (need s n "run")
                 ins   (subvec s (- (count s) n))]
             (conj (subvec s 0 (- (count s) n)) (recipe-value r (recipe/result r (:tctx r) nil ins)))))))

;; -- repeats: their endings and their passes ----------------------------------

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

;; -- alternatives: choose and alternate --------------------------------------

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

;; -- twins: a word beside each sign, a name Clojure can carry too ----------

(defn- twin!
  "Define word name in ns as the sign word already defined in sign-ns."
  [ns name sign-ns sign]
  (let [{:keys [fn effect]} (get-in @e/state [:ns sign-ns sign])]
    (word! ns name (str effect " (also " sign ")") (e/same-as sign-ns sign))
    (word! sign-ns sign (str effect " (also " name ")") fn)))

(doseq [[ns name sign-ns sign]
        [["music" "choosing"             "music" "[|]"]
         ["music" "alternating"          "music" "[\\]"]
         ["music" "line"                 "music" "[n]"]
         ["music" "together"             "music" "{n}"]
         ["music" "chord"                "music" "<>"]]]
  (twin! ns name sign-ns sign))
