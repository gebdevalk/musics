;; ranges.clj
;; Parameter range definitions — source of truth for numeric defaults.
;; Python source: src/common/data/defaults/ranges.py

(ns common.data.defaults.ranges)

(def ranges
  "Parameter keyword → {:min :default :max}."
  {:delay          {:min 0.0  :default 0.0   :max 2.0}
   :reverb         {:min 0.0  :default 0.0   :max 1.0}
   :width          {:min 0.0  :default 0.5   :max 1.0}
   :tempo          {:min 20   :default 92    :max 300}
   :articulation   {:min 0.2  :default 0.9   :max 2.0}
   :bend           {:min -2.0 :default 0.0   :max 2.0}
   :conformity     {:min 0.0  :default 0.0   :max 1.0}
   :density        {:min 1    :default 1     :max 16}
   :humanization   {:min 0.0  :default 0.0   :max 1.0}
   :instrument     {:min 0    :default 0     :max 127}
   :micro          {:min -0.5 :default 0.0   :max 0.5}
   :octave         {:min -4   :default 0     :max 4}
   :panning        {:min -1.0 :default 0.0   :max 1.0}
   :quant-strength {:min 0.0  :default 1.0   :max 1.0}
   :rate           {:min 0.1  :default 1.0   :max 10.0}
   :swing          {:min 0.0  :default 0.0   :max 1.0}
   :transposition  {:min -24  :default 0     :max 24}
   :dur-scale      {:min 0.1  :default 1.0   :max 4.0}
   :volume         {:min 0.0  :default 50.0  :max 100.0}
   :window         {:min 0    :default 0     :max 64}
   :pitch          {:min 0    :default 60    :max 127}
   :duration       {:min 0    :default 1/4   :max 4}
   :dynamic        {:min 0    :default 0     :max 10}
   :accent         {:min 0    :default 0     :max 10}
   :vibrato        {:min 0    :default 0     :max 127}
   :tie            {:min false :default false :max true}
   :channel        {:min 0    :default 0     :max 15}
   :key            {:min 0    :default 0     :max 11}
   :effects        {:min 0    :default 0     :max 10}})

(defn min-val [kw] (get-in ranges [kw :min]))
(defn max-val [kw] (get-in ranges [kw :max]))
(defn default [kw] (get-in ranges [kw :default]))

(defn clamp [kw val]
  (if-let [{:keys [min max]} (get ranges kw)]
    (max min (min max val)) val))

(defn normalize [kw val]
  (if-let [{:keys [min max]} (get ranges kw)]
    (if (= min max) 0.0 (double (/ (- val min) (- max min)))) val))
