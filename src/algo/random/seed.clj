;; seed.clj
;; A seedable substitute for clojure.core/rand, rand-int, rand-nth, and
;; shuffle -- the JVM's own Math/random()-backed versions have no seed
;; hook, so nothing built on them can be replayed deterministically.
;; Every other algo.random namespace (distributions/chance/rand) requires
;; this instead, so a whole generative run -- or a single test -- can be
;; pinned to a fixed sequence via with-seed. logistic.clj/lorentz.clj
;; don't need it: they're already fully deterministic given their own
;; explicit seed!/factor! state, no Math/random() involved.

(ns algo.random.seed
  (:refer-clojure :exclude [rand rand-int rand-nth shuffle]))

(def ^:dynamic *rng*
  "The java.util.Random every fn in this namespace draws from. Rebind it
   with with-seed for reproducible output; left as a fresh, unseeded
   Random by default so ordinary (non-test) use behaves just like
   clojure.core's own versions -- genuinely random, no seed required."
  (java.util.Random.))

(defn rand
  "Like clojure.core/rand, but draws from *rng*."
  ([] (.nextDouble *rng*))
  ([n] (* n (.nextDouble *rng*))))

(defn rand-int
  "Like clojure.core/rand-int, but draws from *rng*."
  [n]
  (.nextInt *rng* n))

(defn rand-nth
  "Like clojure.core/rand-nth, but draws from *rng*."
  [coll]
  (nth coll (rand-int (count coll))))

(defn shuffle
  "Like clojure.core/shuffle, but permutes using *rng* instead of an
   unseedable internal Random."
  [coll]
  (let [al (java.util.ArrayList. coll)]
    (java.util.Collections/shuffle al *rng*)
    (vec al)))

(defmacro with-seed
  "Runs body with every algo.random draw pinned to a deterministic
   sequence seeded by seed -- same seed, same output, every run. For
   tests, or any generative run you want to be able to reproduce exactly.

   (with-seed 42 (repeatedly 5 #(rand-int 100)))"
  [seed & body]
  `(binding [*rng* (java.util.Random. ~seed)]
     ~@body))

(comment
  (with-seed 42 (repeatedly 5 #(rand-int 100)))
  ;; same seed -> same output, every time:
  (= (with-seed 7 (vec (repeatedly 5 rand)))
     (with-seed 7 (vec (repeatedly 5 rand))))
  )
