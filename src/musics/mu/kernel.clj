(ns musics.mu.kernel
  "mu's kernel words (namespace kernel): the stack and quotations
   (doc/mu-repl.md, section 8)."
  (:require [musics.mu.eval :as e :refer [word! need]]))

;; -- stack ------------------------------------------------------------

(word! "kernel" "dup" "( a -- a a )"
       (fn [s] (need s 1 "dup") (conj s (peek s))))

(word! "kernel" "pop" "( a -- )"
       (fn [s] (need s 1 "pop") (pop s)))

(word! "kernel" "swap" "( a b -- b a )"
       (fn [s]
         (need s 2 "swap")
         (let [n (count s)] (assoc s (- n 1) (s (- n 2)) (- n 2) (s (- n 1))))))

(word! "kernel" "over" "( a b -- a b a )"
       (fn [s] (need s 2 "over") (conj s (s (- (count s) 2)))))

(word! "kernel" "rot" "( a b c -- b c a )"
       (fn [s]
         (need s 3 "rot")
         (let [n (count s)] (conj (subvec s 0 (- n 3)) (s (- n 2)) (s (- n 1)) (s (- n 3))))))

(word! "kernel" "nip" "( a b -- b )"
       (fn [s] (need s 2 "nip") (conj (pop (pop s)) (peek s))))

(word! "kernel" "tuck" "( a b -- b a b )"
       (fn [s]
         (need s 2 "tuck")
         (let [b (peek s) a (peek (pop s))] (conj (pop (pop s)) b a b))))

(word! "kernel" "pick" "( xn … x0 n -- xn … x0 xn )"
       (fn [s]
         (need s 1 "pick")
         (let [n (peek s) s (pop s)]
           (when-not (and (integer? n) (< -1 n (count s)))
             (e/mu-error "pick: needs an index 0 to" (dec (count s)) "under it, got" n))
           (conj s (s (- (count s) 1 n))))))

(word! "kernel" "depth" "( -- n )"
       (fn [s] (conj s (count s))))

;; -- quotations ---------------------------------------------------------

(word! "kernel" "call" "( q -- … )"
       (fn [s] (need s 1 "call") (e/call (pop s) (peek s))))
