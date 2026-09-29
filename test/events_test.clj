(ns ^:engine events-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [test-support :refer [with-fresh-session]]
            [musics.core :as m]
            [core.repo :as repo]
            [core.compose :as compose]
            [core.events :as ev]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]
            [output.midi.midi-file :as midi-file])
  (:import [javax.sound.midi MidiSystem ShortMessage]))

(use-fixtures :each (fn [f] (with-fresh-session (f))))

(defn- parse! [text] (with-out-str (m/parse text)))

(defn- notes [evs] (filter #(= :note (:kind %)) evs))

(deftest a-seq-after-a-par-starts-when-the-longest-branch-ends
  (parse! "[t: !tempo:240 { [c4 d4] [e4] } g4 a4 ]")
  (is (= [[0 [60]] [0 [64]] [1/4 [62]] [1/2 [67]] [3/4 [69]]]
         (map (juxt :beat :pitches) (notes (ev/events (repo/registry) :t)))))
  (is (= [0.0 0.0 0.25 0.5 0.75]
         (map #(/ (Math/round (* 1000 (:t %))) 1000.0) (notes (ev/events (repo/registry) :t))))))

(defn- display-notes
  "display's steps, :par voices flattened, as sorted [onset pitches]."
  [steps]
  (letfn [(flat [ss] (mapcat #(if (= :par (:kind %)) (mapcat flat (:voices %)) [%]) ss))]
    (->> (flat steps) (remove :kind) (filter (comp seq :pitches))
         (map (juxt #(/ (Math/round (* 1e6 (:onset %))) 1e6) :pitches)) sort)))

(deftest agrees-with-display
  (parse! (str "[piece: !tempo:132 !Meter:3/4 c4 d8 e8 | { [f4 g4 a4] [A3 B3] } "
               "\\repeat volta 2 [ c'8 b8 ] \\alternative [ [ a4 ] ] c4\\prall r4 d4 ]"))
  (is (= (display-notes (compose/display-timed (repo/registry) :piece))
         (->> (notes (ev/events (repo/registry) :piece))
              (map (juxt #(/ (Math/round (* 1e6 (:t %))) 1e6) :pitches)) sort))))

(deftest endless-material-is-read-only-as-far-as-needed
  (repo/commit-many!
   {:endless (d/iterator :REPEAT :endless (c/context)
                         {:type :SEQ :id :endless-body :context (c/context)
                          :children [(d/leaf :n1 (c/context) 1/8 [60])]}
                         {:count :infinite})})
  (is (= (map #(* 1/8 %) (range 50))
         (map :beat (take 50 (notes (m/events :endless)))))))

(deftest bars-and-marks
  (parse! "[b: !tempo:240 !Meter:2/4 c4 d4 | e4 f4 || g2 ]")
  (let [evs (m/events :b)]
    (is (= [[1/2 2] [1 3] [3/2 4]] (map (juxt :beat :n) (filter #(= :bar (:kind %)) evs))))
    (is (= [[1/2 1 1] [1 2 1]] (map (juxt :beat :count :n) (filter #(= :mark (:kind %)) evs))))))

(deftest algo-tags-transform-their-own-span
  ;; a wall fn sees a container's children, then each leaf again -- it
  ;; marks what it has done, as algo.tree.live's does
  (wall/build-algo! :up (fn [nodes _ _]
                          (map #(if (and (:pitches %) (not (::up %)))
                                  (assoc (update % :pitches (partial mapv inc)) ::up true)
                                  %)
                               nodes)))
  (parse! "[a: c4 d4 ]")
  (parse! "[b: e4 ]")
  (is (= [[61] [63] [64]]
         (map :pitches (notes (m/events [[:a :algo :up] :b])))))
  (is (= [[61] [63]] (map :pitches (notes (m/events :a :algo :up))))))

(deftest a-top-level-set-mints-top-level-voices-lowest-first
  (parse! "[hi: c'4 ]")
  (parse! "[lo: C3 ]")
  (is (= {[:TAA] [[48]] [:TAB] [[72]]}
         (update-vals (group-by :path (notes (m/events #{:hi :lo}))) #(map :pitches %)))))

(deftest render-writes-what-events-says
  (parse! "[r: !tempo:240 { [c4 d4] [e4] } g4 ]")
  (let [f   (java.io.File/createTempFile "events-test" ".mid")
        _   (midi-file/write-events (m/events :r) f)
        ons (for [t (.getTracks (MidiSystem/getSequence f))
                  i (range (.size t))
                  :let [e (.get t i) msg (.getMessage e)]
                  :when (and (instance? ShortMessage msg)
                             (= ShortMessage/NOTE_ON (.getCommand ^ShortMessage msg)))]
              [(.getTick e) (.getData1 ^ShortMessage msg)])]
    (.delete f)
    (is (= [[0 60] [0 64] [250 62] [500 67]] (sort ons)))))
