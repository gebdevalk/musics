(ns ^:engine musics.events-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [musics.test-support :refer [with-fresh-session]]
            [musics.core :as m]
            [musics.repo :as repo]
            [musics.compose :as compose]
            [musics.events :as ev]
            [musics.wall :as wall]
            [musics.domain :as d]
            [musics.domain.context :as c]
            [musics.midi.file :as midi-file])
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
  ;; marks what it has done, as musics.algo.tree.live's does
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
  (let [f   (java.io.File/createTempFile "musics.events-test" ".mid")
        _   (midi-file/write-events (m/events :r) f)
        ons (for [t (.getTracks (MidiSystem/getSequence f))
                  i (range (.size t))
                  :let [e (.get t i) msg (.getMessage e)]
                  :when (and (instance? ShortMessage msg)
                             (= ShortMessage/NOTE_ON (.getCommand ^ShortMessage msg)))]
              [(.getTick e) (.getData1 ^ShortMessage msg)])]
    (.delete f)
    (is (= [[0 60] [0 64] [250 62] [500 67]] (sort ons)))))

(deftest octave-shifts-pitches-by-twelve-per-step
  (parse! "[t: c4 !octave:1 d4 !octave:-2 e4 ]")
  (is (= [[60] [74] [40]] (map :pitches (notes (ev/events (repo/registry) :t))))))

(deftest dur-scale-stretches-notes-and-what-follows
  (parse! "[t: !tempo:240 c4 !durScale:2 d4 r4 e4 ]")
  (let [evs (notes (ev/events (repo/registry) :t))]
    (is (= [0.0 0.25 1.25] (map #(/ (Math/round (* 1000 (:t %))) 1000.0) evs))
        "a quarter at 240 is 0.25 s; doubled, d4 and the rest take 0.5 s each")
    (is (= [0 1/4 3/4] (map :beat evs)) "structural beats are untouched")))

(defn- note-times [form]
  (map #(/ (Math/round (* 1000 (:t %))) 1000.0) (notes (m/events form))))

(deftest a-referencing-containers-context-reaches-the-referenced-notes
  (parse! "[mot: c4 d e f]")
  (parse! "[fast: !tempo:240 :mot]")
  (parse! "[loud: !ff :mot]")
  (is (= [0.0 0.25 0.5 0.75] (note-times :fast)))
  (is (= [102 102 102 102] (map :velocity (notes (m/events :loud))))))

(deftest a-referenced-containers-own-setting-wins
  (parse! "[own: !tempo:60 c4 d]")
  (parse! "[ownref: !tempo:240 :own]")
  (is (= [0.0 1.0] (note-times :ownref))))

(deftest a-reused-container-leaves-its-original-parent-behind
  (parse! "[piece: !tempo:60 [inner: c4 d]]")
  (parse! "[reuse: !tempo:240 :inner]")
  (is (= [0.0 1.0] (note-times :piece)))
  (is (= [0.0 0.25] (note-times :reuse))))

(deftest extracted-notes-keep-their-containers-context
  (parse! "[own: !tempo:60 !ff c4 d]")
  (is (= [0.0 1.0] (note-times (m/sq :own))))
  (is (= [102 102] (map :velocity (notes (m/events (m/sq :own)))))))

(defn- note-pitches [form] (map :pitches (notes (m/events form))))

(deftest a-motif-follows-the-key-it-is-played-in
  (parse! "[mot: c d e f g]")
  (parse! "[inG: !key:G.major :mot]")
  (parse! "[inD: !key:D.major :mot]")
  (is (= [[60] [62] [64] [65] [67]] (note-pitches :mot)))
  (is (= [[60] [62] [64] [66] [67]] (note-pitches :inG)))
  (is (= [[61] [62] [64] [66] [67]] (note-pitches :inD))))

(deftest written-accidentals-own-keys-and-explicit-mode-stay
  (parse! "[chrom: c f# b&]")
  (parse! "[chromG: !key:G.major :chrom]")
  (parse! "[own: !key:C.major f]")
  (parse! "[ownG: !key:G.major :own]")
  (parse! "[lit: !acc:explicit f]")
  (parse! "[litG: !key:G.major :lit]")
  (is (= [[60] [66] [70]] (note-pitches :chromG)) "chromatic notes keep their accidentals")
  (is (= [[65]] (note-pitches :ownG)) "a motif's own key wins")
  (is (= [[65]] (note-pitches :litG)) "letters parsed under :explicit are literal"))

(deftest explicit-where-played-turns-it-off
  (parse! "[mot: f]")
  (parse! "[g: !key:G.major !acc:explicit :mot]")
  (is (= [[65]] (note-pitches :g))))

(deftest transposed-notes-follow-the-playing-key-transposed
  (parse! "[tr: \\transpose c d ( c f b )]")
  (parse! "[trG: !key:G.major :tr]")
  (is (= [[62] [67] [73]] (note-pitches :tr)))
  (is (= [[62] [68] [73]] (note-pitches :trG)) "c f# b in G, up a tone"))

(deftest extracted-notes-follow-the-key-they-are-played-in
  (parse! "[mot: c f]")
  (parse! "^{inG: !key:G.major}")
  (is (= [[60] [66]] (note-pitches [:inG (m/sq :mot)]))))

(deftest a-chord-symbol-follows-the-key-by-its-root
  ;; the written quality stays: F major in C plays F# major under G
  (parse! "[chords: \\chordmode ( F4 C4/E )]")
  (parse! "[chordsG: !key:G.major :chords]")
  (is (= [[65 69 72] [52 60 67]] (note-pitches :chords)))
  (is (= [[66 70 73] [52 60 67]] (note-pitches :chordsG))))

(deftest a-note-plays-its-own-overrides
  (parse! "[ovr: !vol:50 c4\\vol:90\\i:40 d4 e4\\pan:-1.0\\tempo:60 f4\\trill\\vol:20]")
  (let [[c d e & trill] (notes (ev/events (repo/registry) :ovr))]
    (is (= [114 40] ((juxt :velocity :program) c)) "volume 90, program 40")
    (is (= [64 0] ((juxt :velocity :program) d)) "the next note is back to the context's")
    (is (= [0 1.0] [(get-in e [:cc 10]) (:dur-secs e)]) "panning hard left, a quarter at 60 bpm")
    (is (every? #(= 25 (:velocity %)) trill) "every note of the trill keeps the override")))
