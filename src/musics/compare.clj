(ns musics.compare
  "mu's comparison and flags, as Forth writes them: = <> < > <= >= on
   values, and true false and or xor not on flags. = and <> take any two
   values (two numbers by value, so 1 1.0 = and 1/2 0.5 = are true;
   music by its notes, data and streams element by element); the order
   signs take numbers, ratios and durations (/4 /8 > is true). A flag is
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
  (let [data?    #(core/and (v/material? %) (core/= :DATA (mt/kind %)))
        music?   #(core/and (core/or (v/material? %) (v/leaf? %)) (core/not (data? %)))
        endless? #(core/and (sequential? %) (:mu/endless (meta %)))
        items?   #(core/or (data? %) (v/pcset? %) (core/and (sequential? %) (core/not (string? %))))]
    (cond
      (core/and (number? a) (number? b)) (== a b)
      (core/and (music? a) (music? b)) (binding [p/*names* false] (core/= (p/show a) (p/show b)))
      (core/and (mt/loop? a) (mt/loop? b)) (same? (first (mt/repeat-of a)) (first (mt/repeat-of b)))
      (core/or (endless? a) (endless? b)) (fail "an endless stream never ends -- take some first")
      (core/and (items? a) (items? b)) (let [xs (vec (mt/elements a)) ys (vec (mt/elements b))]
                                    (core/and (core/= (count xs) (count ys)) (every? true? (map same? xs ys))))
      :else (core/= a b))))

(defn- ordered
  "(f a b) for two numbers; else an error naming what came, and for the
   signs that used to play, the way to play."
  [f a b hint]
  (when-not (core/and (number? a) (number? b))
    (fail "needs two numbers, got" (p/show a) "and" (p/show b)
          (when (core/or (v/material? a) (v/material? b) (v/leaf? a) (v/leaf? b) (keyword? a) (keyword? b))
            (str "-- " hint))))
  (f a b))

(defn =
  "True when a equals b: two numbers by value (1 1.0 = is true), music by
   its notes, data and streams element by element"
  {:mu {:out "flag"}}
  [a b] (same? a b))

(defn <>
  "True when a differs from b, as = compares"
  {:mu {:out "flag"}}
  [a b] (core/not (same? a b)))

(defn <
  "True when a is less than b: numbers, ratios and durations"
  {:mu {:out "flag" :hint "what a voice plays is .<"}}
  [a b] (ordered core/< a b "what a voice plays is .<"))

(defn >
  "True when a is greater than b: numbers, ratios and durations"
  {:mu {:out "flag" :hint "to play, write .>"}}
  [a b] (ordered core/> a b "to play, write .>"))

(defn <=
  "True when a is at most b"
  {:mu {:out "flag"}}
  [a b] (ordered core/<= a b nil))

(defn >=
  "True when a is at least b"
  {:mu {:out "flag"}}
  [a b] (ordered core/>= a b nil))

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
