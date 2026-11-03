(ns musics.arith
  "mu's + - * /, one word each for everything they work on: two numbers
   (the sum, difference, product, exact quotient), a data list or stream
   of numbers with a number (each element, the number on either side),
   two such lists (element by element, as long as the shorter), and music
   with a number (every pitch up or down by n semitones, every duration
   times or divided by n: musics.music's up, down, stretch, shrink). One
   multimethod, dispatching on what the two values are, so a new kind of
   value is one defmethod."
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.core :as core]
            [clojure.string :as str]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.music :as music]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

(defn- kind
  "What a value is to arithmetic: :number, :data (a list or stream of
   numbers), :key, :music, else :other."
  [x]
  (cond (number? x)              :number
        (mt/numeric-data? x)     :data
        (mt/key? x)              :key
        (or (mt/leaf? x) (v/material? x) (sequential? x)) :music
        :else                    :other))

(defn- calc
  "(f a b) for two numbers, else an error naming what came."
  [f a b]
  (when-not (and (number? a) (number? b))
    (fail "needs numbers, got" (p/show a) "and" (p/show b)))
  (f a b))

(defn- quotient [a b]
  (when (zero? b) (fail "division by zero"))
  (core// a b))

(def ^:private ops
  {"+" {:num core/+ :music music/up}
   "-" {:num core/- :music music/down}
   "*" {:num core/* :music music/stretch}
   "/" {:num quotient :music music/shrink}})

(defn- through
  "x, a data list or stream, with f of each element: a finite one's worked
   out at once (so an error comes with the word), an endless one's lazily.
   A seq, not a vector: a vector would print as a line."
  [x f]
  (let [lazy? (mt/endless? (mt/streamed x))]
    (mt/reshape x (fn [els] (if lazy? (map f els) (doall (map f els)))))))

(defn- pairwise
  "f of the elements of two data lists or streams in step, as long as the
   shorter; the result in the form of the finite one (the first if both are)."
  [f x y]
  (let [swap? (and (mt/endless? (mt/streamed x)) (not (mt/endless? (mt/streamed y))))
        [a b] (if swap? [y x] [x y])
        g     (if swap? (fn [e1 e2] (f e2 e1)) f)
        bels  (mt/elements (mt/streamed b))
        lazy? (mt/endless? (mt/streamed a))]
    (mt/reshape a (fn [els] (if lazy? (map g els bels) (doall (map g els bels)))))))

(defmulti ^:private arith (fn [_op x y] [(kind x) (kind y)]))

(defmethod arith [:number :number] [op x y] (calc (:num op) x y))
(defmethod arith [:data :number]   [op x y] (through x #(calc (:num op) % y)))
(defmethod arith [:number :data]   [op x y] (through y #(calc (:num op) x %)))
(defmethod arith [:data :data]     [op x y] (pairwise #(calc (:num op) %1 %2) x y))
(defmethod arith [:music :number]  [op x y] ((:music op) x y))
(defmethod arith [:key :number]    [op x y] ((:music op) x y))
(defmethod arith :default          [_ x y]
  (fail "needs two numbers, a list of numbers and a number, or music and a number, got"
        (p/show x) "and" (p/show y)))

(defn +
  "The sum: two numbers; a list or stream of numbers and a number, each
   element (either side), or two lists in step; music and n, every pitch
   up n semitones, its key with it: [c e g] 7 +"
  {:mu {:also ["up"]}}
  [x n] (arith (ops "+") x n))

(defn -
  "The difference, as + adds: 7 2 - gives 5, '[5 7] 1 - gives '[4 6];
   music and n, every pitch down n semitones: [c e g] 12 -"
  {:mu {:also ["down"]}}
  [x n] (arith (ops "-") x n))

(defn *
  "The product, as + adds: 3 4 * gives 12, '[1 2 3] 2 * gives '[2 4 6];
   music and f, every duration times f: [c d] 2 * gives halves"
  {:mu {:also ["stretch"]}}
  [x f] (arith (ops "*") x f))

(defn /
  "The exact quotient, as + adds: 1 4 / gives 1/4, '[2 4] 2 / gives '[1 2];
   music and f, every duration divided by f: [c d e] 3 / gives a triplet"
  {:mu {:also ["shrink"]}}
  [x f] (arith (ops "/") x f))
