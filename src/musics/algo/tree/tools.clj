(ns musics.algo.tree.tools
  "The tools of musics.algo.tree: algos that change any material and keep its
   type -- the tree's own take, map, filter and so on. Their names are
   clojure.core's, so this namespace excludes those and the bodies say
   clojure.core/ where they mean the real one; refer them yourself only
   where that is wanted, or call them as tools/take.

     cycle shuffle  :in any   repeat forever / reshuffle every pass
     take           :len      first :len items
     rotate         :steps    the front items round to the back
     reverse                  the items in reverse order
     map filter     :fn       each item through a fn / the items a fn keeps
     remove         :fn       the items a fn doesn't drop
     mute           :fn       leaves: the notes a fn is true for become rests
     rescale        :from-lo .. :to-hi   numbers from one range onto another
     stretch        :factor   durations or notes, each duration times :factor"
  (:refer-clojure :exclude [map filter remove take cycle shuffle reverse])
  (:require [musics.algo.common.rotate :as rot]
            [musics.algo.numeric.random :as random]
            [musics.algo.tree :refer [defalgo]]
            [musics.common.music-data :refer [quantity]]
            [musics.domain :as d]))

(defalgo cycle "Its child, repeated forever (lazy)."
  {:algo {:category "tool" :in [:any] :out :same}}
  [xs] (clojure.core/cycle xs))

(defalgo shuffle "Its child, reshuffled on every pass, forever (lazy)."
  {:algo {:category "tool" :in [:any] :out :same}}
  [xs] (let [v (vec xs)] (mapcat identity (repeatedly #(random/shuffle v)))))

(defalgo take "The first :len items of its child."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:len {:type :int :min 0 :max 256 :default 16 :doc "items kept"}}}}
  [xs len] (vec (clojure.core/take len xs)))

(defalgo rotate "Its child (finite) moved :steps places to the left, the front items coming round to the back; negative steps go right."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:steps {:type :int :min -256 :max 256 :default 1 :doc "places to the left"}}}}
  [xs steps] (let [v (vec xs)] (if (empty? v) v (rot/rotate v steps))))

(defalgo reverse "Its child (finite) in reverse order."
  {:algo {:category "tool" :in [:any] :out :same}}
  [xs] (vec (clojure.core/reverse xs)))

(defalgo map "Each item through :fn (set at the REPL), lazily; the type stays."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:fn {:type :fn :default identity :doc "applied to each item"}}}}
  [xs fn] (clojure.core/map fn xs))

(defalgo filter "The items :fn (set at the REPL) keeps, lazily."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:fn {:type :fn :default some? :doc "true keeps the item"}}}}
  [xs fn] (clojure.core/filter fn xs))

(defalgo remove "The items :fn (set at the REPL) doesn't drop, lazily: on leaves the line gets shorter, later notes move earlier."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:fn {:type :fn :default nil? :doc "true drops the item"}}}}
  [xs fn] (clojure.core/remove fn xs))

(defalgo mute "Each note :fn (set at the REPL) is true for becomes a rest of its duration, lazily: the rhythm stays. Rests stay."
  {:algo {:category "tool" :in [:leaf] :out :leaf
          :params {:fn {:type :fn :default (constantly false) :doc "true silences the note"}}}}
  [leaves fn] (clojure.core/map #(if (and (not (d/rest? %)) (fn %)) (d/rest* nil nil (:duration %)) %) leaves))

(defalgo rescale "Each number's place in :from-lo..:from-hi onto :to-lo..:to-hi, lazily (not clamped): a factor is 0..1 onto 0..factor."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:from-lo {:type :double :min ##-Inf :max ##Inf :default 0.0 :doc "maps to :to-lo"}
                   :from-hi {:type :double :min ##-Inf :max ##Inf :default 1.0 :doc "maps to :to-hi"}
                   :to-lo   {:type :double :min ##-Inf :max ##Inf :default 0.0 :doc "lowest out"}
                   :to-hi   {:type :double :min ##-Inf :max ##Inf :default 1.0 :doc "highest out"}}}}
  [xs from-lo from-hi to-lo to-hi]
  (let [span (- from-hi from-lo)
        k    (if (zero? span) 0.0 (/ (- to-hi to-lo) (double span)))]
    (clojure.core/map #(+ to-lo (* k (- % from-lo))) xs)))

(defn- stretched [factor x]
  (cond (number? x)   (* factor x)
        (:duration x) (update x :duration #(* factor %))
        :else         x))

(defalgo stretch "Every duration times :factor -- numbers, or notes' :duration; anything else stays."
  {:algo {:category "tool" :in [:any] :out :same
          :params {:factor (quantity :ratio {:doc "duration multiplier"})}}}
  [xs factor] (clojure.core/map (partial stretched factor) xs))
