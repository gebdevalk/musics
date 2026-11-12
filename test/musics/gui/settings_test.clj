(ns musics.gui.settings-test
  "The settings window's logic: what a tctx entry is shown as, the first range of a slider and
   its zoom. The window itself is the user's to look at."
  (:require [clojure.test :refer [deftest is testing]]
            [musics.domain.context :as c]
            [musics.gui.settings :as s]))

(deftest an-entry-is-shown-by-what-it-is
  (let [tctx (-> (c/context)
                 (c/tctx! :steps 8) (c/tctx! :lag 3/8) (c/tctx! :width 1.0)
                 (c/tctx! :melody [64 67]) (c/tctx! :name "a")
                 (c/tctx! :wave (fn [t] (* 2 t)))
                 (c/tctx-derive :twice (fn [_ get] (* 2 (get :steps))) "'( :steps 2 * )"))
        kinds (into {} (map (fn [[k e]] [k (s/kind-of e)])) @(:envelopes-atom tctx))]
    (is (= {:steps :int :lag :ratio :width :double :melody :edn :name :edn :wave :function :twice :computed} kinds))))

(deftest a-slider-starts-on-the-values-own-range-including-zero
  (is (= {:min 0.0 :max 16.0} (s/first-bounds :int 8)))
  (is (= {:min 0.0 :max 0.75} (s/first-bounds :ratio 3/8)))
  (is (= {:min -1.0 :max 1.0} (s/first-bounds :double 0)))
  (is (= {:min -2.0 :max 0.0} (s/first-bounds :double -1.0))))

(deftest ranges-follow-the-entries-and-the-zoom-buttons
  (let [es     [[:steps 8 :int] [:name "a" :edn] [:twice 16 :computed]]
        ranges (s/with-ranges {} es)]
    (is (= {:steps {:full {:min 0.0 :max 16.0} :visible {:min 0.0 :max 16.0}}} ranges) "numbers only")
    (testing "a value that has left the visible range widens it, and the full range with it"
      (let [r2 (s/with-ranges ranges [[:steps 40 :int]])]
        (is (<= 40.0 (get-in r2 [:steps :visible :max])))
        (is (<= 40.0 (get-in r2 [:steps :full :max])))))
    (testing "Z widens symmetrically around the value, z narrows it again"
      (let [out (s/zoom-out ranges :steps :int 8)]
        (is (= {:min -8.0 :max 24.0} (get-in out [:steps :visible])))
        (is (= {:min -8.0 :max 24.0} (get-in out [:steps :full])) "and the full range grows with it")
        (let [in (s/zoom-in out :steps :int 8)]
          (is (= {:min 0.0 :max 16.0} (get-in in [:steps :visible])) "-8..24 around 8 halves to 0..16"))))))

(deftest the-window-description-builds-for-every-kind
  (let [es (mapv (fn [[k v]] [k v (s/kind-of v)])
                 [[:steps 8] [:lag 3/8] [:width 1.0] [:melody [64 67]] [:name "a"]])
        st {:entries es :ranges (s/with-ranges {} es) :showing true :theme :dark}
        v  (s/view st)]
    (is (= :stage (:fx/type v)))
    (is (= 5 (count (get-in v [:scene :root :children 0 :content :children]))) "a row each")))
