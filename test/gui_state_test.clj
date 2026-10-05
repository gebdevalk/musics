(ns ^:repl gui-state-test
  (:require [clojure.test :refer [deftest is]]
            [musics.domain.resolve :as resolve]
            [musics.gui.state :as gs]))

(deftest a-slider-only-for-a-key-playback-reads
  (is (seq gs/param-specs))
  (is (every? resolve/played-keys (keys gs/param-specs)))
  (is (not-any? #{:Delay :Reverb :Width} (keys gs/param-specs))))
