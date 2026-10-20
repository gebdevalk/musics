(ns musics.algo.clam
  (:require [clojure.math :as m]))

;; ============================================================
;; Config
;; ============================================================

(def ^:const eps 1.0) ;; detection radius
(def ^:const grow 2.0) ;; range doubling factor per find

(def ^:const default-r0 10.0) ;; initial range
(def ^:const default-t0 1.0) ;; initial jump interval
(def ^:const default-max-jumps 100000) ;; per-search safety cap
(def ^:const default-max-time 1e6) ;; per-search simulated time cap (optional)

;; ============================================================
;; Core geometry
;; ============================================================

(defn- rand-angle []
  (* 2 m/PI (rand)))

(defn- target-at-edge
  "Target always sits at distance R from the agent, at an unknown angle."
  [x y R]
  (let [theta (rand-angle)]
    {:x (+ x (* R (m/cos theta))) :y (+ y (* R (m/sin theta)))}))

(defn- jump
  "DIRECTION strategy: distance fixed at R, re-guess the angle each jump."
  [x y R]
  (let [theta (rand-angle)]
    {:x (+ x (* R (m/cos theta))) :y (+ y (* R (m/sin theta)))}))

(defn- hit?
  [p t]
  (<= (m/hypot (- (:x p) (:x t)) (- (:y p) (:y t))) eps))

(defn- jump-interval
  "Interval halves for every doubling of the range, relative to R0."
  [R0 R t0]
  (/ t0 (m/pow 2.0 (/ (m/log (/ R R0)) (m/log grow)))))

;; ============================================================
;; Search — every attempt passed to on-try
;; ============================================================

(defn search
  [x y R R0 t0 on-try & {:keys [max-jumps max-time]
                         :or {max-jumps default-max-jumps
                              max-time default-max-time}}]
  (let [t (target-at-edge x y R)
        dt (jump-interval R0 R t0)]
    (loop [j 1 elapsed 0.0]
      (let [p (jump x y R)
            h (hit? p t)
            attempt {:attempt j
                 :interval dt
                 :time elapsed
                 :jump p
                 :target t
                 :hit h
                 :range R}]
        (on-try attempt)
        (cond
          h {:jumps j :time elapsed :target t}
          (> j max-jumps) nil
          (> (+ elapsed dt) max-time)
          nil
          :else (recur (inc j) (+ elapsed dt)))))))

;; ============================================================
;; Runs
;; πR/ε jumps per find, halving dt per doubling of R → expected
;; search time per find stays roughly constant ≈ (πR/ε)·dt.
;; ============================================================

(defn run
  "Full session of n-finds finds. After each find the range doubles
   and the jump interval halves. `on-try` receives every attempt, with
   the :find it belongs to."
  [n-finds & {:keys [R0 t0 on-try max-jumps max-time]
              :or {R0 default-r0 t0 default-t0
                   on-try (fn [_]) max-jumps default-max-jumps
                   max-time default-max-time}}]
  (letfn [(go [x y R k acc]
            (if (= k n-finds)
              acc
              (if-let [r (search x y R R0 t0 #(on-try (assoc % :find (inc k)))
                                 :max-jumps max-jumps :max-time max-time)]
                (go (:x (:target r)) (:y (:target r))
                    (* R grow) (inc k)
                    (conj acc {:find (inc k) :range R
                               :jumps (:jumps r) :search-time (:time r)}))
                (conj acc {:find (inc k) :range R :failed true}))))]
    (go 0 0 R0 0 [])))

;; ============================================================
;; Telemetry — what the callback makes possible
;; ============================================================

(defn summary
  "Condense a trace of tries into run-level metrics."
  [tries]
  (let [by-find (group-by :find tries)
        ->time (fn [ts] (reduce + (map :interval ts)))]
    {:n-tries (count tries)
     :intervals (distinct (map :interval tries))
     :searches (into (sorted-map)
                     (for [[find ts] by-find]
                       [find {:range (:range (first ts))
                              :n-tries (count ts)
                              :tries->time (->time ts)
                              :last-hit (boolean (some :hit ts))}]))}))


(defn instrumented-run
  "Convenience: run with a built-in trace and return result + telemetry."
  [n-finds & opts]
  (let [trace  (atom [])
        result (apply run n-finds (concat opts [:on-try #(swap! trace conj %)]))]
    {:result  result
     :tries   @trace
     :summary (summary @trace)}))

(comment
  ;; silent
  (run 5)

  ;; trace everything, each try with its find number
  (instrumented-run 5)

  ;; custom callback: count tries per interval class
  (let [tries (atom [])]
    (run 5 :on-try #(swap! tries conj %))
    (frequencies (map :interval @tries))))
