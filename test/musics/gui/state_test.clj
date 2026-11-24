(ns ^:repl musics.gui.state-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [musics.common.music-elements :as el]
            [musics.mu.store :as store]
            [musics.domain.resolve :as resolve]
            [musics.gui.state :as gs]
            [musics.test-support :refer [with-fresh-session]]))

(deftest a-slider-only-for-a-key-playback-reads
  (is (seq gs/param-specs))
  (is (every? resolve/played-keys (keys gs/param-specs)))
  (is (not-any? #{:Delay :Reverb :Width} (keys gs/param-specs))))

(deftest named-choices-are-dropdowns-not-sliders
  (is (= #{:instrument :kit :accent :key-tonic :key-scale :accidentals :Meter} (set (keys gs/combo-specs))))
  (is (not-any? (set (keys gs/combo-specs)) (keys gs/param-specs)) "kit is no 0-127 slider"))

(deftest watching-a-part-reads-its-names
  (with-fresh-session
    (let [saved @gs/*state]
      (try
        (with-out-str (store/read! "[multi: !i:40,73 !kit:brush !accent:metric c4 d] [plain: e4]"))
        (gs/watch! "multi")
        (gs/watch! "plain")
        (testing "several instruments at once show the first; kit and accent by name"
          (is (= {:instrument "violin" :kit "brush" :accent "metric"}
                 (select-keys (get-in @gs/*state [:watched :multi :combos]) [:instrument :kit :accent]))))
        (is (= {:instrument "acoustic-grand" :kit "standard" :accent "off"}
               (select-keys (get-in @gs/*state [:watched :plain :combos]) [:instrument :kit :accent])) "the defaults")
        (testing "a dropdown writes the value its name stands for, while hot"
          (gs/toggle-hot! :plain)
          (gs/set-combo! :plain :accent "agogic")
          (gs/set-combo! :plain :kit "jazz")
          (is (= [:agogic 32] [(resolve/ctx-value :plain :accent 0) (resolve/ctx-value :plain :kit 0)])))
        (finally (reset! gs/*state saved))))))

(deftest a-context-shows-a-key-and-a-meter-as-written
  (with-fresh-session
    (with-out-str (store/read! "[k: !K:G.major !M:7/8 c4]"))
    (let [shown (with-out-str (resolve/ctx :k))]
      (is (str/includes? shown "!Key:G.major@0"))
      (is (str/includes? shown "!Meter:7/8@0")))))

(deftest controls-come-by-importance
  (is (= ["General" "Playing" "Feel"] (map first gs/control-groups)) "Feel last")
  (is (= [:Tempo ["Key" :key-tonic :key-scale] :Meter :volume :accidentals :panning [:instrument :kit]]
         (second (first gs/control-groups)))
      "tonic and scale share a line, instrument and kit another")
  (is (= (set (concat (keys gs/param-specs) (keys gs/combo-specs)))
         (set (mapcat #(if (vector? %) (remove string? %) [%]) (mapcat second gs/control-groups))))
      "every slider and dropdown has its place"))

(deftest key-meter-and-accidentals-from-the-panel
  (with-fresh-session
    (let [saved @gs/*state]
      (try
        (with-out-str (store/read! "[p: c4 d]"))
        (gs/watch! "ROOT")
        (gs/watch! "p")
        (is (= {:key-tonic "C" :key-scale "major" :accidentals "implied" :Meter "4/4"}
               (select-keys (get-in @gs/*state [:watched :ROOT :combos]) [:key-tonic :key-scale :accidentals :Meter]))
            "the defaults, as !K: and !M: write them")
        (gs/toggle-hot! :p)
        (gs/set-combo! :p :key-tonic "B&")
        (gs/set-combo! :p :key-scale "minor")
        (is (= "B&.minor" (el/key->str (resolve/ctx-value :p :Key 0))) "tonic and scale make one key")
        (gs/set-combo! :p :Meter "7/8(2+2+3)")
        (is (= "7/8(2+2+3)" (el/meter->str (resolve/ctx-value :p :Meter 0))) "a typed meter")
        (gs/set-combo! :p :Meter "not a meter")
        (is (= "7/8(2+2+3)" (el/meter->str (resolve/ctx-value :p :Meter 0))) "one that doesn't read changes nothing")
        (is (= "7/8(2+2+3)" (get-in @gs/*state [:watched :p :combos :Meter])))
        (gs/set-combo! :p :accidentals "explicit")
        (is (= :explicit (resolve/ctx-value :p :accidentals 0)))
        (finally (reset! gs/*state saved))))))


(deftest a-click-on-a-slider-label-resets-it-to-its-default
  (with-fresh-session
    (let [saved @gs/*state]
      (try
        (gs/watch! "ROOT")
        (gs/set-param! :ROOT :Tempo 180.0)
        (is (= 180.0 (get-in @gs/*state [:watched :ROOT :params :Tempo])))
        (gs/reset-param! :ROOT :Tempo)
        (is (= 100.0 (get-in @gs/*state [:watched :ROOT :params :Tempo])) "music-data's tempo default")
        (finally (reset! gs/*state saved))))))

(deftest zoom-goes-four-steps-in-stops-and-z-walks-back
  (with-fresh-session
    (let [saved @gs/*state
          zoom #(get-in @gs/*state [:watched :ROOT :zoom :Tempo])
          span #(let [z (zoom)] (when z (- (:max z) (:min z))))]
      (try
        (gs/watch! "ROOT")
        (gs/set-param! :ROOT :Tempo 165.0)
        (dotimes [_ 4] (gs/zoom! :ROOT :Tempo))
        (let [spans (span)]
          (is (some? spans) "four steps in")
          (gs/zoom! :ROOT :Tempo)
          (is (= spans (span)) "a fifth does not wrap, it stays"))
        (dotimes [_ 4] (gs/zoom-out! :ROOT :Tempo))
        (is (nil? (zoom)) "four Z are back at the whole range")
        (gs/zoom-out! :ROOT :Tempo)
        (is (nil? (zoom)) "and no further")
        (finally (reset! gs/*state saved))))))
