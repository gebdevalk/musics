(ns musics.gui.settings-test
  "The settings window's logic: what a tctx entry is shown as, the first range of a slider and
   its zoom. The window itself is the user's to look at."
  (:require [clojure.test :refer [deftest is testing]]
            [musics.domain.context :as c]
            [musics.gui.settings :as s]
            [musics.mu.inputs :as inputs]
            [musics.registries :as reg]))

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

(defn- with-tctx
  "f with the session's tctx holding entries, and no inputs, afterwards cleaned."
  [entries f]
  (try (reset! (:envelopes-atom reg/tctx) {})
       (doseq [[k v] entries] (c/tctx! reg/tctx k v))
       (f)
       (finally (reset! (:envelopes-atom reg/tctx) {}) (inputs/clear! :bass))))

(deftest the-shared-keys-come-first-then-a-section-for-each-voice
  (with-tctx [[:steps 8] [:lag 3/8] [:bass/steps 5] [:bass/onsets 3] [:bass/pulses 8] [:lead/steps 2]]
    #(do (inputs/set-inputs! :bass [["onsets" 3] ["pulses" 8]] {:onsets {:type :int :min 0 :max 32}})
         (let [secs (s/sections)]
           (is (= [nil "bass" "lead"] (map :scope secs)))
           (is (= [:lag :steps] (map :key (:rows (first secs)))) "shared, by name")
           (testing "a voice's inputs in the order of the word, then its other keys by name"
             (is (= [:bass/onsets :bass/pulses :bass/steps] (map :key (:rows (second secs))))))
           (testing "an input has its spec, the others none, and a key is shown without the voice"
             (is (= [true true false] (map :input? (:rows (second secs)))))
             (is (= {:type :int :min 0 :max 32} (:spec (first (:rows (second secs))))))
             (is (nil? (:spec (nth (:rows (second secs)) 2))))
             (is (= ["onsets" "pulses" "steps"] (map :label (:rows (second secs))))))
           (is (= 6 (count (s/entries secs))) "every row, for the slider ranges")))))

(deftest a-voice-has-a-scope-when-the-tctx-holds-a-key-written-with-its-name
  (with-tctx [[:steps 8] [:bass/steps 5]]
    #(do (is (s/scope? :bass))
         (is (s/scope? "bass"))
         (is (not (s/scope? :lead)) "a voice with no key of its own")
         (is (not (s/scope? :steps)) "a shared key is no voice"))))

(deftest the-window-scrolls-to-a-voices-section
  (let [secs [{:scope nil :rows [1 2]} {:scope "bass" :rows [1 2 3]} {:scope "lead" :rows [1]}]]
    (is (= 0.0 (s/scroll-fraction secs nil)))
    (is (= 0.0 (s/scroll-fraction secs "nothing")) "an unknown voice: the top")
    (is (= (/ 3.0 9) (s/scroll-fraction secs :bass)) "the rows and headers before it, over all")
    (is (= (/ 7.0 9) (s/scroll-fraction secs "lead")))))

(deftest the-window-description-builds-for-every-kind-and-scope
  (with-tctx [[:steps 8] [:lag 3/8] [:width 1.0] [:melody [64 67]] [:name "a"] [:bass/steps 5]
              [:bass/onsets 3] [:bass/twice (c/->Derived (fn [_ _] 1) "'( 1 )")]]
    #(do (inputs/set-inputs! :bass [["onsets" 3]] {:onsets {:type :int :min 0 :max 32}})
         (let [secs (s/sections)
               es   (s/entries secs)
               st   {:sections secs :ranges (s/with-ranges {} es) :showing true :theme :dark :focus "bass" :zoom {}}
               v    (s/view st)
               panels (get-in v [:scene :root :children 0 :content :children])]
           (is (= :stage (:fx/type v)))
           (is (= 2 (count panels)) "shared, and the bass")
           (is (= 6 (count (get-in (first panels) [:children]))) "the title and a row each in the shared section")
           (is (= 4 (count (get-in (second panels) [:children]))) "and in the bass's")
           (is (some? (:vvalue (get-in v [:scene :root :children 0]))) "scrolled to the voice asked for")))))
