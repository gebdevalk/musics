;; sonification.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py section 15
;; -- converting arbitrary numerical data, stock-style price series, and
;; text into rhythmic patterns. Fully deterministic, no randomness.

(ns algo.rithmic.sonification
  (:require [clojure.string :as str]))

(defn- mean [xs] (/ (reduce + xs) (double (count xs))))

(defn- median [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)
        mid (quot n 2)]
    (if (odd? n)
      (double (nth sorted mid))
      (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0))))

(defn- smooth
  [data window]
  (let [n (count data) half (quot window 2)]
    (mapv (fn [i]
            (let [start (max 0 (- i half))
                  end (min n (+ i half 1))]
              (mean (subvec (vec data) start end))))
          (range n))))

(defn data-to-rhythm
  "Binary pattern from any numerical data sequence: 1 where a value
   exceeds a threshold, 0 otherwise. threshold-type is \"median\"
   (default), \"mean\", or \"adaptive\" (each position compared against
   the mean of its own local +-2 window instead of one global
   threshold). smoothing > 1 first replaces each value with the mean of
   its own +-(smoothing/2) window."
  ([data] (data-to-rhythm data "median" 1))
  ([data threshold-type] (data-to-rhythm data threshold-type 1))
  ([data threshold-type smoothing]
   (if (empty? data)
     []
     (let [data (if (> smoothing 1) (smooth data smoothing) (vec data))]
       (if (= threshold-type "adaptive")
         (let [n (count data) half 2]
           (mapv (fn [i]
                   (let [start (max 0 (- i half))
                         end (min n (+ i half 1))
                         local-threshold (mean (subvec (vec data) start end))]
                     (if (> (nth data i) local-threshold) 1 0)))
                 (range n)))
         (let [threshold (case threshold-type
                            "median" (median data)
                            "mean" (mean data)
                            (mean data))]
           (mapv #(if (> % threshold) 1 0) data)))))))

(defn stock-market-rhythm
  "Rhythm from price trends: for each price at or past lookback, compare
   it to the simple moving average of the previous lookback prices --
   2 (strong beat) if more than 2% above the SMA, 1 (weak beat) if
   above it, -1 (a rest/silence marker) if more than 2% below it, 0
   otherwise. The first lookback positions are always 0 (no SMA yet)."
  ([prices] (stock-market-rhythm prices 5))
  ([prices lookback]
   (if (< (count prices) (inc lookback))
     (vec (repeat (count prices) 0))
     (let [prices (vec prices)]
       (mapv (fn [i]
               (if (< i lookback)
                 0
                 (let [sma (mean (subvec prices (- i lookback) i))
                       price (nth prices i)]
                   (cond
                     (> price (* sma 1.02)) 2
                     (> price sma) 1
                     (< price (* sma 0.98)) -1
                     :else 0))))
             (range (count prices)))))))

(defn- count-syllables
  [word]
  (let [word (str/lower-case word)
        vowels #{\a \e \i \o \u \y}
        n (count word)
        base (if (vowels (nth word 0)) 1 0)
        internal (reduce (fn [c i]
                            (if (and (vowels (nth word i)) (not (vowels (nth word (dec i)))))
                              (inc c) c))
                          0 (range 1 n))
        total (+ base internal)
        total (if (str/ends-with? word "e") (dec total) total)]
    (if (zero? total) 1 total)))

(defn text-to-rhythm
  "Rhythmic pattern from text's own linguistic structure. mode
   \"syllables\" (default): each word contributes a beat on its first
   syllable, rests for the rest. \"words\": each word contributes one
   beat, strong (2) if longer than 7 characters, weak (1) otherwise.
   \"stress\": single-syllable words get a beat; multi-syllable words
   alternate stressed/unstressed starting on the stress. Any sentence-
   ending punctuation (. ! ?) anywhere in text appends one trailing
   rest."
  ([text] (text-to-rhythm text "syllables"))
  ([text mode]
   (let [words (str/split (str/trim text) #"\s+")
         words (if (= words [""]) [] words)
         pattern
         (case mode
           "syllables"
           (vec (mapcat (fn [word]
                          (into [1] (repeat (dec (count-syllables word)) 0)))
                        words))

           "words"
           (mapv (fn [word] (if (> (count word) 7) 2 1)) words)

           "stress"
           (vec (mapcat (fn [word]
                          (let [syllables (count-syllables word)]
                            (if (= syllables 1)
                              [1]
                              (mapv #(if (even? %) 1 0) (range syllables)))))
                        words))

           [])]
     (if (some #(str/includes? text %) ["." "!" "?"])
       (conj pattern 0)
       pattern))))

(comment
  (data-to-rhythm [1.0 3.0 2.0 5.0 1.0 4.0 2.0 6.0] "median")
  (stock-market-rhythm [100 101 99 102 105 98 110 95 103 108])
  (text-to-rhythm "Hello world this is rhythm." "syllables")
  )
