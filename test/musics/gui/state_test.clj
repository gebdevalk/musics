(ns ^:repl musics.gui.state-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [musics.core :as m]
            [musics.domain.resolve :as resolve]
            [musics.gui.state :as gs]
            [musics.test-support :refer [with-fresh-session]]))

(deftest a-slider-only-for-a-key-playback-reads
  (is (seq gs/param-specs))
  (is (every? resolve/played-keys (keys gs/param-specs)))
  (is (not-any? #{:Delay :Reverb :Width} (keys gs/param-specs))))

(deftest named-choices-are-dropdowns-not-sliders
  (is (= #{:instrument :kit :accent} (set (keys gs/combo-specs))))
  (is (not-any? (set (keys gs/combo-specs)) (keys gs/param-specs)) "kit is no 0-127 slider"))

(deftest watching-a-part-reads-its-names
  (with-fresh-session
    (let [saved @gs/*state]
      (try
        (with-out-str (m/parse "[multi: !i:40,73 !kit:brush !accent:metric c4 d] [plain: e4]"))
        (gs/watch! "multi")
        (gs/watch! "plain")
        (testing "several instruments at once show the first; kit and accent by name"
          (is (= {:instrument "violin" :kit "brush" :accent "metric"}
                 (get-in @gs/*state [:watched :multi :combos]))))
        (is (= {:instrument "acoustic-grand" :kit "standard" :accent "off"}
               (get-in @gs/*state [:watched :plain :combos])) "the defaults")
        (testing "a dropdown writes the value its name stands for, while hot"
          (gs/toggle-hot! :plain)
          (gs/set-combo! :plain :accent "agogic")
          (gs/set-combo! :plain :kit "jazz")
          (is (= [:agogic 32] [(m/ctx-value :plain :accent 0) (m/ctx-value :plain :kit 0)])))
        (finally (reset! gs/*state saved))))))

(deftest a-context-shows-a-key-and-a-meter-as-written
  (with-fresh-session
    (with-out-str (m/parse "[k: !K:G.major !M:7/8 c4]"))
    (let [shown (with-out-str (m/ctx :k))]
      (is (str/includes? shown "Key=G.major@0"))
      (is (str/includes? shown "Meter=7/8@0")))))
