;; music_tools.clj
;; Math utilities used by algorithms. Most Python tools are Clojure-native.
;; Python source: functions.py

(ns common.tools.music-tools
  (:require [clojure.string :as str]))

(defn gcd [a b]
  (loop [a (long (Math/abs a)) b (long (Math/abs b))]
    (if (zero? b) a (recur b (rem a b)))))

(defn lcm [a b]
  (if (or (zero? a) (zero? b)) 0
      (quot (* (long a) (long b)) (gcd a b))))

(defn lcm-multiple [& nums]
  (reduce #(lcm %1 (long (Math/abs %2))) (or (first nums) 0) (rest nums)))

(defn coprime? [a b] (= 1 (gcd a b)))

(defn modular-inverse [a m]
  (letfn [(ext-gcd [a b]
            (if (zero? b) [(Math/abs a) 1 0]
                (let [[g x1 y1] (ext-gcd b (rem a b))]
                  [g y1 (- x1 (* (quot a b) y1))])))]
    (let [[g x _] (ext-gcd a m)]
      (if (not= g 1)
        (throw (ex-info "No modular inverse" {:a a :m m}))
        (mod x m)))))

(defn fraction-from-string
  "Parse '1/4', '4', '4.', '4..' into a Ratio."
  [s]
  (cond
    (str/includes? s "/")
    (let [[n d] (map #(Long/parseLong %) (str/split s #"/"))] (/ n d))
    (str/includes? s ".")
    (let [base (Long/parseLong (str/replace s #"\." ""))
          dots (count (filter #(= % \.) s))]
      (loop [val (/ 1 base) add (/ 1 base 2) i (dec dots)]
        (if (zero? i) val
            (recur (+ val add) (/ add 2) (dec i)))))
    :else (/ 1 (Long/parseLong s))))

(comment
  (gcd 48 18)          ;; => 6
  (lcm 4 6)            ;; => 12
  (lcm-multiple 3 4 5) ;; => 60
  (fraction-from-string "4.")  ;; => 3/8
  (fraction-from-string "4..") ;; => 7/16
  )
