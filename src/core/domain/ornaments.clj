;; ornaments.clj
;; Leaf expansion — ornaments, grace notes, tremolo.
;; Each expander: Leaf → [Leaf ...] (sub-leaves that replace the original).
;; Applied at resolution time (ornaments need Key from context).
;; Python source: src/input/reader/ornaments.py

(ns core.domain.ornaments
  (:require [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [common.music-elements :as el]
            [common.defaults :as defaults]
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
;; Same source the real system :ROOT is built from (flat-core-builder's
;; empty-session), not a separate hand-picked map -- so this fallback
;; never drifts out of sync with a default added there (e.g. :key's
;; own C.major default), and expand works the same whether leaf's own
;; context chain reaches a real :ROOT or not (e.g. called directly on
;; a hand-built leaf, as the unit tests below do).
(def root-ctx (c/context-root (defaults/root-defaults)))

(defn- carry-tie
  "The original leaf's own :tied flag belongs on whichever sub-leaf ends
   the expansion (the last one) -- every ornament/tremolo/grace expander
   above hardcodes :tied false on every sub-leaf it builds, including the
   last, since a decorated note's own internal notes never tie to each
   other; only whatever note FOLLOWS the whole ornament might be tied to
   it, exactly the tie the original (un-expanded) leaf itself carried.
   Applied once here, at expand's single call site, rather than
   duplicated across every expander fn above -- confirmed live as a real,
   not hypothetical, bug: a note written with an ornament AND a tie
   (`e4\\prallmordent~`) silently lost the tie, since none of the sub-
   leaves the ornament produced ever looked at the original leaf's own
   :tied at all."
  [sub-leaves leaf]
  (update sub-leaves (dec (count sub-leaves)) assoc :tied (:tied leaf)))

(defn expand
  "Expand leaf modifiers into sub-leaves.
   Handles ornaments, tremolo, and grace notes.
   Returns [leaf] unchanged if no expandable modifier is present.

   ctx-chain, if given, should be the leaf's *complete* ancestor chain
   (nearest-first, e.g. built by musics.clj/full-ctx-chain) -- an
   ornament's :key is sampled from it, so a Key set on any intermediate
   container is found, not just the leaf's own immediate context or
   :ROOT. Without one (the 1-arg form -- also what every ornament-
   function unit test below exercises indirectly via the ornament
   functions themselves, not expand), falls back to [leaf's own
   context, root-ctx] -- correct only when nothing relevant sits on an
   intermediate container between the two, but expand has no way to
   discover the leaf's real ancestors from the bare leaf value alone;
   only a caller that actually has the tree (see musics.clj/expand) can
   supply the real one."
  ([leaf] (expand leaf nil))
  ([leaf ctx-chain]
   (let [mods     (:modifiers leaf)
         find-mod (fn [tag] (some #(when (= tag (first %)) (second %)) mods))]
     (cond
      ;; Ornament: look up key-scale, dispatch to ornament function
      (find-mod "ornament")
      (let [name  (str/replace (find-mod "ornament") #"^\\\\" "")
            chain (or ctx-chain [(:context leaf) root-ctx])
            ;; sample-many, not ctx-value-chain directly -- chain here is
            ;; the SAME ctx-chain async-engine's build-chain threads
            ;; through play-node, whose elements are (as of this pass)
            ;; [ctx offset] pairs rather than bare, pre-shifted Contexts
            ;; (see build-chain's own docstring on why: sample-many's
            ;; link->ctx+offset already normalized either shape, ctx-
            ;; value-chain never did). Passing {:key nil} as the sole
            ;; key+default keeps the exact same "nothing found -> nil"
            ;; contract ctx-value-chain always had.
            ks    (:key (c/sample-many chain {:key nil} 0.0))]
        (if-let [f (get ornament-map name)]
          (carry-tie (f leaf ks) leaf)
          [leaf]))

      ;; Tremolo: subdivide into repeated sub-notes
      (find-mod "tremolo")
      (carry-tie (expand-tremolo leaf (find-mod "tremolo")) leaf)

      ;; Grace: assign a short real duration
      (find-mod "grace")
      (carry-tie (expand-grace leaf (find-mod "grace")) leaf)

      :else [leaf]))))
