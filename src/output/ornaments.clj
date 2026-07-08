;; ornaments.clj
;; Leaf expansion — ornaments, grace notes, tremolo.
;; Each expander: Leaf → [Leaf ...] (sub-leaves that replace the original).
;; Applied at resolution time (ornaments need Key from context).
;; Python source: src/input/reader/ornaments.py

(ns output.ornaments
  (:require [core.domain.context :as c]
            [core.domain.music-domain :as d]
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
;; Grace note expansion
;; ============================================================

(def ^:private grace-durations
  "Default durations for grace note types.
   The tree walker sets grace-note duration to 0; expansion restores
   a playable value."
  {"acciaccatura"  1/32
   "slashedGrace"  1/32
   "appoggiatura"  1/16
   "afterGrace"    1/32
   "grace"         1/32})

(defn- expand-grace
  "Expand a grace-tagged leaf — assign a short real duration."
  [leaf grace-type]
  (let [dur (get grace-durations grace-type 1/32)]
    [(d/leaf (:id leaf) (:context leaf) dur (:pitches leaf)
             (:articulation leaf) (:dynamic leaf) nil false)]))

;; ============================================================
;; Tremolo expansion
;; ============================================================

(defn- expand-tremolo
  "Expand a tremolo-tagged leaf into repeated sub-notes.
   subdiv is the denominator: 8 → eighths, 16 → sixteenths,
   32 → thirty-seconds.  The original duration is filled with
   notes of length 1/subdiv."
  [leaf subdiv]
  (let [sub-dur (/ 1 subdiv)
        n       (max 1 (long (/ (:duration leaf) sub-dur)))]
    (vec (repeat n
           (d/leaf "" (:context leaf) sub-dur (:pitches leaf)
                   (:articulation leaf) (:dynamic leaf) nil false)))))

;; ============================================================
;; Dispatch table (ornaments only — grace and tremolo are
;; dispatched directly by modifier tag in `expand`)
;; ============================================================

(def ornament-map
  {"prall" prall, "prallup" upprall, "pralldown" downprall,
   "upprall" upprall, "downprall" downprall, "prallprall" prallprall,
   "lineprall" lineprall, "prallmordent" prallmordent,
   "mordent" mordent, "upmordent" upmordent, "downmordent" downmordent,
   "trill" trill, "turn" turn, "reverseturn" reverseturn,
   "shortfermata" shortfermata, "fermata" fermata,
   "longfermata" longfermata, "verylongfermata" verylongfermata})

;; ============================================================
;; Unified expand — ornament / tremolo / grace
;; ============================================================
(def root-ctx (c/context-root {"tempo" 120 "volume" 0.8 "timbre" 42}))

(defn expand
  "Expand leaf modifiers into sub-leaves.
   Handles ornaments, tremolo, and grace notes.
   Returns [leaf] unchanged if no expandable modifier is present."
  [leaf]
  (let [mods     (:modifiers leaf)
        find-mod (fn [tag] (some #(when (= tag (first %)) (second %)) mods))]
    (cond
      ;; Ornament: look up key-scale, dispatch to ornament function
      (find-mod "ornament")
      (let [name (str/replace (find-mod "ornament") #"^\\\\" "")
            ;ks   (c/ctx-value (:context leaf) :key 0.0)
            ks   (c/ctx-value-chain [(:context leaf) root-ctx] :key 0.0)]
        (if-let [f (get ornament-map name)]
          (f leaf ks)
          [leaf]))

      ;; Tremolo: subdivide into repeated sub-notes
      (find-mod "tremolo")
      (expand-tremolo leaf (find-mod "tremolo"))

      ;; Grace: assign a short real duration
      (find-mod "grace")
      (expand-grace leaf (find-mod "grace"))

      :else [leaf])))
