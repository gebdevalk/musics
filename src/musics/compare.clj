(ns musics.compare
  "mu's comparison and flags, as Forth writes them: = <> < > <= >= on
   values, and true false and or xor not on flags. = and <> take any two
   values (two numbers by value, so 1 1.0 = and 1/2 0.5 = are true;
   music by its notes, data and streams element by element); the order
   signs take numbers, ratios and durations (/4 /8 > is true), or any two
   values by a key: a quotation on top reads each one (c d '( pitch ) <,
   [c d] [e f g] '( duration ) <), and = and <> take a key too. A flag is
   true or false and nothing else: and, or, xor, not, if and when refuse
   any other value, naming it."
  (:refer-clojure :exclude [= < > <= >= and or not])
  (:require [clojure.core :as core]
            [clojure.string :as str]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

(defn- same?
  "a and b equal: two numbers by value; music (a line or a note) when it
   prints the same, its names left out, so two lines of the same notes
   are equal wherever each came from and whatever they are called; two
   loops when they repeat the same; data and streams element by element.
   An endless stream that is no loop is refused: it would never end."
  [a b]
  (let [music?   #(core/or (v/material? %) (v/leaf? %))
        endless? #(core/and (sequential? %) (:mu/endless (meta %)))
        items?   #(core/or (v/pcset? %) (core/and (sequential? %) (core/not (string? %))))]
    (cond
      (core/and (number? a) (number? b)) (== a b)
      (core/and (music? a) (music? b)) (binding [p/*names* false] (core/= (p/show a) (p/show b)))
      (core/and (mt/loop? a) (mt/loop? b)) (same? (first (mt/repeat-of a)) (first (mt/repeat-of b)))
      (core/or (endless? a) (endless? b)) (fail "an endless stream never ends -- take some first")
      (core/and (items? a) (items? b)) (let [xs (vec (mt/elements a)) ys (vec (mt/elements b))]
                                    (core/and (core/= (count xs) (count ys)) (every? true? (map same? xs ys))))
      :else (core/= a b))))

(defn- ordered
  "(f a b) for two numbers; else an error naming what came, the way to order
   them by a key and, for the signs that used to play, the way to play."
  [f a b sign hint]
  (when-not (core/and (number? a) (number? b))
    (fail "needs two numbers, got" (p/show a) "and" (p/show b)
          (when (core/or (v/material? a) (v/material? b) (v/leaf? a) (v/leaf? b) (keyword? a) (keyword? b))
            (str "-- to order by something, give a key: c d '( pitch ) " sign
                 (when hint (str "; " hint))))))
  (f a b))

(defn- keyed
  "Each of a and b read by the quotation q: [ka kb], the key's one value."
  [word q a b]
  (mapv #(mt/result word q %) [a b]))

(defn- ordered-by
  "(f ka kb) for the numbers the key q reads from a and b."
  [f word q a b]
  (let [[ka kb] (keyed word q a b)]
    (when-not (core/and (number? ka) (number? kb))
      (fail (str word ":") "the key must read a number from each, got" (p/show ka) "and" (p/show kb)))
    (f ka kb)))

(defn =
  "True when a equals b: two numbers by value (1 1.0 = is true), music by
   its notes, data and streams element by element; with a key q on top, as
   the key reads each: c e '( pitch 12 mod ) = (the same pitch class)"
  {:mu {:out "flag" :optional {:q v/quotation?}}}
  ([a b] (same? a b))
  ([a b q] (let [[ka kb] (keyed "=" q a b)] (same? ka kb))))

(defn <>
  "True when a differs from b, as = compares (with a key q too)"
  {:mu {:out "flag" :optional {:q v/quotation?}}}
  ([a b] (core/not (same? a b)))
  ([a b q] (let [[ka kb] (keyed "<>" q a b)] (core/not (same? ka kb)))))

(defn <
  "True when a is less than b: numbers, ratios and durations; with a key q
   on top, the numbers it reads: c d '( pitch ) <, [c d] [e f g] '( duration ) <"
  {:mu {:out "flag" :hint "what a voice plays is .<" :optional {:q v/quotation?}}}
  ([a b] (ordered core/< a b "<" "what a voice plays is .<"))
  ([a b q] (ordered-by core/< "<" q a b)))

(defn >
  "True when a is greater than b: numbers, ratios and durations; with a key
   q on top, the numbers it reads"
  {:mu {:out "flag" :hint "to play, write .>" :optional {:q v/quotation?}}}
  ([a b] (ordered core/> a b ">" "to play, write .>"))
  ([a b q] (ordered-by core/> ">" q a b)))

(defn <=
  "True when a is at most b (with a key q on top, the numbers it reads)"
  {:mu {:out "flag" :optional {:q v/quotation?}}}
  ([a b] (ordered core/<= a b "<=" nil))
  ([a b q] (ordered-by core/<= "<=" q a b)))

(defn >=
  "True when a is at least b (with a key q on top, the numbers it reads)"
  {:mu {:out "flag" :optional {:q v/quotation?}}}
  ([a b] (ordered core/>= a b ">=" nil))
  ([a b q] (ordered-by core/>= ">=" q a b)))

;; -- flags -----------------------------------------------------------------

(defn flag!
  "x, a flag: true or false and nothing else."
  [x]
  (when-not (boolean? x) (fail "needs a flag (true or false), got" (p/show x)))
  x)

(defn not
  "True becomes false, false true"
  {:mu {:out "flag"}}
  [flag] (core/not (flag! flag)))

(defn and
  "True when both flags are"
  {:mu {:out "flag"}}
  [a b] (let [a (flag! a) b (flag! b)] (core/and a b)))

(defn or
  "True when either flag is"
  {:mu {:out "flag"}}
  [a b] (let [a (flag! a) b (flag! b)] (core/or a b)))

(defn xor
  "True when exactly one of the flags is"
  {:mu {:out "flag"}}
  [a b] (core/not= (flag! a) (flag! b)))
