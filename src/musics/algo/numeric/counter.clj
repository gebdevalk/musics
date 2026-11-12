(ns musics.algo.numeric.counter
  "Counter-based random: draw i of a seed is a hash of the two (SplitMix64), so
   a random stream is a pure function of its index -- the same on a restart, the
   same however many voices read it and in whatever order, readable at any index
   without the draws before it. musics.algo.numeric.random draws from one shared
   atom instead, where what is heard depends on the order of draws; this is
   the form for a voice, an index function, a stream read by next.

   A seed is any integer. A stream of draws is `(draws seed)`; a shuffle is lazy,
   a permutation for each pass of a finite pool (`shuffled`) or for each window of a
   stream (`shuffled-windows`), the permutation of pass k a function of the seed
   and k.")

(def ^:private gamma (.longValue 0x9E3779B97F4A7C15N))
(def ^:private mul1  (.longValue 0xBF58476D1CE4E5B9N))
(def ^:private mul2  (.longValue 0x94D049BB133111EBN))

(defn- mix
  "SplitMix64's finalizer: 64 bits in, 64 well mixed bits out."
  ^long [^long z]
  (let [z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 30)) mul1)
        z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 27)) mul2)]
    (bit-xor z (unsigned-bit-shift-right z 31))))

(defn- bits
  "The 64 bits of draw i of seed."
  ^long [seed i]
  (mix (unchecked-add (mix (long seed)) (unchecked-multiply gamma (unchecked-inc (long i))))))

(defn draw32
  "Draw i of seed as 32 bits, an integer in [0, 2^32): what musics.algo.numeric.rng steps through."
  [seed i]
  (bit-and (bits seed i) 0xFFFFFFFF))

(defn draw
  "Draw i of seed: a double in [0,1). The same seed and i, the same value."
  [seed i]
  (* (double (unsigned-bit-shift-right (bits seed i) 11)) 1.1102230246251565E-16))

(defn draw-int
  "Draw i of seed as an integer in [0,n)."
  [seed i n]
  (long (Math/floor (* (draw seed i) n))))

(defn pick
  "Draw i of seed picks one of items (a vector or seq), each as likely as another."
  [seed i items]
  (let [v (vec items)] (v (draw-int seed i (count v)))))

(defn pick-weighted
  "Draw i of seed picks one of items, as often as its weight says (weights need not sum to 1)."
  [seed i items weights]
  (let [r (* (draw seed i) (reduce + weights))]
    (loop [[x & xs] (seq items) [w & ws] (seq weights) acc 0.0]
      (let [acc (+ acc w)]
        (if (or (< r acc) (nil? xs)) x (recur xs ws acc))))))

(defn draws
  "The draws of seed, lazily: draw 0, draw 1 …"
  {:algo {:in [] :out :number
          :params {:seed {:type :int :min 0 :max 1000000 :default 1 :doc "the stream"}}}}
  [seed]
  (map #(draw seed %) (range)))

(defn subseed
  "A seed of its own derived from seed and k (a pass, a voice's path, a part's id): another
   stream, so two things drawing with the same seed do not draw the same numbers."
  [seed k]
  (mix (unchecked-add (long seed) (unchecked-multiply gamma (unchecked-inc (long k))))))

(defn permutation
  "The permutation of 0..n-1 that pass k of seed makes (Fisher-Yates, each step
   drawn from the pass's own stream). Pure: the same seed, k and n, the same vector."
  [seed k n]
  (let [sub (subseed seed k)]
    (loop [v (vec (range n)) i (dec n)]
      (if (pos? i)
        (let [j (draw-int sub i (inc i))]
          (recur (assoc v i (v j) j (v i)) (dec i)))
        v))))

(defn- permute [pool perm] (let [pool (vec pool)] (mapv pool perm)))

(defn shuffled
  "The elements of the finite pool in a new order each pass, endlessly: pass k is
   the permutation (seed, k) of the pool. Lazy; take what is needed.
     (take 6 (shuffled 7 [:a :b :c]))   ;; two passes of the three"
  {:algo {:in [:any] :out :same
          :params {:seed {:type :int :min 0 :max 1000000 :default 1 :doc "the order of every pass"}}}}
  [seed pool]
  (let [pool (vec pool) n (count pool)]
    (when (pos? n)
      (mapcat #(permute pool (permutation seed % n)) (range)))))

(defn shuffled-windows
  "xs with each window of window elements in a new order: the first window the
   permutation (seed, 0), the next (seed, 1) …; a short last window shuffles
   alone. Lazy and one window of look-ahead, so xs may be endless."
  {:algo {:in [:any] :out :same :works :shape
          :params {:window {:type :int :min 1 :max 256 :default 8 :doc "elements shuffled together"}
                   :seed   {:type :int :min 0 :max 1000000 :default 1 :doc "the order of every window"}}}}
  [window seed xs]
  (mapcat (fn [k block] (permute block (permutation seed k (count block))))
          (range) (partition-all window xs)))
