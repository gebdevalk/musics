;; spec.clj
;; Param specs shared by the :algo metadata of the numeric and rhythmic generators: identical
;; specs give one shared tctx key, so (uniform) and (triangular) in one tree read the same :lo/:hi.

(ns musics.algo.numeric.spec
  "Shared param specs and the sampler metadata of the single-value generators."
  (:require [musics.common.music-data :refer [quantity]]))


;; Param specs shared by the :algo metadata below (musics.algo.tree): identical
;; specs give one shared tctx key, so (uniform) and (triangular) in one
;; tree read the same :lo/:hi.
(def num-spec  {:type :double :min ##-Inf :max ##Inf})

(def pos-spec  {:type :double :min 0.0 :max ##Inf})

(def unit-spec {:type :double :min 0.0 :max 1.0})

(def lo  (assoc num-spec :default 0.0 :doc "lowest value"))

(def hi  (assoc num-spec :default 1.0 :doc "highest value"))

(def ilo (quantity :pitch {:default 60 :doc "lowest value"}))

(def ihi (quantity :pitch {:default 72 :doc "highest value, exclusive"}))

(def bias (assoc unit-spec :default 0.5 :doc "0 = uniform, 1 = strongest"))

(def shape (assoc pos-spec :default 2.0 :doc "shape"))

(def scale-p (assoc pos-spec :default 1.0 :doc "spread"))

(def dof {:type :int :min 1 :max 100 :default 3 :doc "degrees of freedom"})

(def clip-lo (assoc num-spec :default ##-Inf :doc "lowest value reached"))

(def clip-hi (assoc num-spec :default ##Inf :doc "highest value reached"))

(def boundary {:type :keyword :choices [:clamp :reflect :wrap] :default :clamp
                         :doc "at a clip bound: stop, bounce back or come in at the other end"})


(defn sampler
  "The :algo metadata of a single-value sampler: :len draws -> :number,
   or :pitch for the int-* ones (their default range is a pitch range)."
  ([params] (sampler :number params))
  ([out params] {:repeat :len :in [] :out out :params params}))
