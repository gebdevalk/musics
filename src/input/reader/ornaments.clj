;; ornaments.clj
;; Ornament expansion — Leaf → sequence of sub-leaves using scale neighbors.
;; Applied at resolution time (needs Key from context).
;; Python source: src/input/reader/ornaments.py

(ns input.reader.ornaments
  (:require [core.domain.music-domain :as d]
            [common.elements.music-elements :as el]
            
            [clojure.string :as str]))

;; ============================================================
;; Scale neighbor helpers
;; ============================================================

(defn- scale-neighbor
  "Find scale degree above (dir=1) or below (dir=-1) the given MIDI pitch."
  [ks pitch dir]
  (let [pc (mod pitch 12)
        octave (quot pitch 12)
        scale-pcs (el/key-pitches ks)]
    (if (empty? scale-pcs) pitch
        (let [sorted (sort scale-pcs)
              idx (.indexOf (vec sorted) pc)]
          (if (neg? idx)
            (let [nearest (some #(when (>= % pc) %) sorted)
                  base (or nearest (first sorted))
                  ni (+ (.indexOf (vec sorted) base) dir)]
              (+ (* octave 12) (nth sorted (mod ni (count sorted)))))
            (let [ni (+ idx dir)]
              (if (<= 0 ni (dec (count sorted)))
                (+ (* octave 12) (nth sorted ni))
                (let [new-o (+ octave (if (pos? dir) 1 -1))
                      wrap (mod ni (count sorted))]
                  (+ (* new-o 12) (nth sorted wrap))))))))))

(defn upper [ks p] (scale-neighbor ks p 1))
(defn lower [ks p] (scale-neighbor ks p -1))

;; ============================================================
;; Ornament functions — Leaf + Key → [Leaf ...]
;; ============================================================

(defn plain [leaf _] [leaf])

(defn prall [leaf ks]
  (let [dur (:duration leaf) d8 (/ dur 8) p (:pitches leaf)
        ctx (:context leaf) art (:articulation leaf) dyn (:dynamic leaf)
        hi (mapv #(upper ks %) p)]
    [(d/leaf "" ctx d8 hi art dyn nil false)
     (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf (:id leaf) ctx (* dur 3/4) p art dyn nil false)]))

(defn upprall [leaf ks]
  (let [d8 (/ (:duration leaf) 8) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) hi (mapv #(upper ks %) p) lo (mapv #(lower ks %) p)]
    [(d/leaf "" ctx d8 lo art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)]))

(defn downprall [leaf ks]
  (let [d8 (/ (:duration leaf) 8) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) hi (mapv #(upper ks %) p) lo (mapv #(lower ks %) p)]
    [(d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 lo art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)]))

(defn prallprall [leaf ks]
  (let [d8 (/ (:duration leaf) 8) dur (:duration leaf) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) hi (mapv #(upper ks %) p)]
    [(d/leaf "" ctx d8 p art dyn nil false) (d/leaf "" ctx d8 hi art dyn nil false)
     (d/leaf "" ctx d8 p art dyn nil false) (d/leaf "" ctx d8 hi art dyn nil false)
     (d/leaf "" ctx (* dur 1/2) p art dyn nil false)]))

(defn lineprall [leaf ks]
  (let [d16 (/ (:duration leaf) 16) dur (:duration leaf) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) hi (mapv #(upper ks %) p)]
    [(d/leaf "" ctx (/ dur 2) hi art dyn nil false)
     (d/leaf "" ctx d16 p art dyn nil false) (d/leaf "" ctx d16 hi art dyn nil false)
     (d/leaf "" ctx d16 p art dyn nil false) (d/leaf "" ctx d16 hi art dyn nil false)
     (d/leaf "" ctx (/ dur 4) p art dyn nil false)]))

(defn prallmordent [leaf ks]
  (let [d8 (/ (:duration leaf) 8) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) hi (mapv #(upper ks %) p) lo (mapv #(lower ks %) p)]
    [(d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 lo art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)]))

(defn mordent [leaf ks]
  (let [dur (:duration leaf) d8 (/ dur 8) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) lo (mapv #(lower ks %) p)]
    [(d/leaf "" ctx d8 p art dyn nil false) (d/leaf "" ctx d8 lo art dyn nil false)
     (d/leaf "" ctx (* dur 3/4) p art dyn nil false)]))

(defn upmordent [leaf ks]
  (let [d8 (/ (:duration leaf) 8) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) hi (mapv #(upper ks %) p) lo (mapv #(lower ks %) p)]
    [(d/leaf "" ctx d8 lo art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 lo art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)]))

(defn downmordent [leaf ks]
  (let [d12 (/ (:duration leaf) 12) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) hi (mapv #(upper ks %) p) lo (mapv #(lower ks %) p)]
    [(d/leaf "" ctx d12 hi art dyn nil false) (d/leaf "" ctx d12 p art dyn nil false)
     (d/leaf "" ctx d12 lo art dyn nil false) (d/leaf "" ctx d12 p art dyn nil false)
     (d/leaf "" ctx d12 hi art dyn nil false) (d/leaf "" ctx d12 p art dyn nil false)
     (d/leaf "" ctx d12 hi art dyn nil false) (d/leaf "" ctx d12 p art dyn nil false)
     (d/leaf "" ctx d12 hi art dyn nil false) (d/leaf "" ctx d12 p art dyn nil false)
     (d/leaf "" ctx d12 lo art dyn nil false) (d/leaf "" ctx d12 p art dyn nil false)]))

(defn trill [leaf ks]
  (let [dur (:duration leaf) d8 (/ dur 8) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf)
        p (:pitches leaf) hi (mapv #(upper ks %) p)]
    [(d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false) (d/leaf "" ctx d8 p art dyn nil false)
     (d/leaf "" ctx d8 hi art dyn nil false)
     (d/leaf "" ctx (* dur 3/8) p art dyn nil false)]))

(defn turn [leaf ks]
  (let [d4 (/ (:duration leaf) 4) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf) p (:pitches leaf)]
    [(d/leaf "" ctx d4 (mapv #(upper ks %) p) art dyn nil false)
     (d/leaf "" ctx d4 p art dyn nil false)
     (d/leaf "" ctx d4 (mapv #(lower ks %) p) art dyn nil false)
     (d/leaf "" ctx d4 p art dyn nil false)]))

(defn reverseturn [leaf ks]
  (let [d4 (/ (:duration leaf) 4) ctx (:context leaf)
        art (:articulation leaf) dyn (:dynamic leaf) p (:pitches leaf)]
    [(d/leaf "" ctx d4 (mapv #(lower ks %) p) art dyn nil false)
     (d/leaf "" ctx d4 p art dyn nil false)
     (d/leaf "" ctx d4 (mapv #(upper ks %) p) art dyn nil false)
     (d/leaf "" ctx d4 p art dyn nil false)]))

(defn shortfermata [leaf _]
    [(d/leaf (:id leaf) (:context leaf) (* (:duration leaf) 3/2) (:pitches leaf) (:articulation leaf) (:dynamic leaf) nil false)])
(defn fermata [leaf _]
    [(d/leaf (:id leaf) (:context leaf) (* (:duration leaf) 2) (:pitches leaf) (:articulation leaf) (:dynamic leaf) nil false)])
(defn longfermata [leaf _]
    [(d/leaf (:id leaf) (:context leaf) (* (:duration leaf) 3) (:pitches leaf) (:articulation leaf) (:dynamic leaf) nil false)])
(defn verylongfermata [leaf _]
    [(d/leaf (:id leaf) (:context leaf) (* (:duration leaf) 4) (:pitches leaf) (:articulation leaf) (:dynamic leaf) nil false)])

;; ============================================================
;; Dispatch table
;; ============================================================

(def ornament-map
  {"prall" prall, "prallup" upprall, "pralldown" downprall,
   "upprall" upprall, "downprall" downprall, "prallprall" prallprall,
   "lineprall" lineprall, "prallmordent" prallmordent,
   "mordent" mordent, "upmordent" upmordent, "downmordent" downmordent,
   "trill" trill, "turn" turn, "reverseturn" reverseturn,
   "shortfermata" shortfermata, "fermata" fermata,
   "longfermata" longfermata, "verylongfermata" verylongfermata})

(defn expand
  "Expand leaf's ornament into sub-leaves. Looks up Key from context.
   Returns [leaf] unchanged if no ornament modifier is present."
  [leaf]
  (let [ornament-str (some #(when (= "ornament" (first %)) (second %))
                           (:modifiers leaf))]
    (if-not ornament-str
      [leaf]
      (let [name (str/replace ornament-str #"^\\\\" "")
            ks   (d/ctx-value (:context leaf) :keyScale 0.0)]
        (if-let [f (get ornament-map name)]
          (f leaf ks)
          [leaf])))))