(ns ^:engine musics.events-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.test-support :refer [with-fresh-session]]
            [musics.persist :as persist]
            [musics.repo :as repo]
            [musics.compose :as compose]
            [musics.events :as ev]
            [musics.wall :as wall]
            [musics.domain :as d]
            [musics.domain.context :as c]
            [musics.midi.file :as midi-file]
            [musics.input.grammar-parser :as gp]
            [musics.algo.random.core :as rc])
  (:import [javax.sound.midi MidiSystem ShortMessage]))

(use-fixtures :each (fn [f] (with-fresh-session (f))))

(defn- parse! [text] (with-out-str (gp/parse text)))

(defn- notes [evs] (filter #(= :note (:kind %)) evs))

(deftest a-seq-after-a-par-starts-when-the-longest-branch-ends
  (parse! "[t: !tempo:240 { [c4 d4] [e4] } g4 a4 ]")
  (is (= [[0 [60]] [0 [64]] [1/4 [62]] [1/2 [67]] [3/4 [69]]]
         (map (juxt :beat :pitches) (notes (ev/events (repo/registry) :t)))))
  (is (= [0.0 0.0 0.25 0.5 0.75]
         (map #(/ (Math/round (* 1000 (:t %))) 1000.0) (notes (ev/events (repo/registry) :t))))))

(deftest a-shuffle-part-plays-a-new-order-each-pass
  (parse! "[s: repeat 6 [ shuffle [c4 d | e f] ] g4]")
  (rc/seed! 3)
  (let [ns     (notes (ev/events (repo/registry) :s))
        passes (partition 4 (map (comp first :pitches) (butlast ns)))]
    (is (= 25 (count ns)) "every note once a pass, then g")
    (is (every? #(= #{60 62 64 65} (set %)) passes) "each pass all four")
    (is (< 1 (count (distinct passes))) "in more than one order")
    (is (= (range 0 25/4 1/4) (map :beat ns)) "the timing as written"))
  (testing "a seed repeats it"
    (rc/seed! 3) (let [a (mapv :pitches (notes (ev/events (repo/registry) :s)))]
                   (rc/seed! 3) (is (= a (mapv :pitches (notes (ev/events (repo/registry) :s))))))))

(deftest a-rotate-part-moves-one-step-further-each-pass
  (parse! "[r: repeat 3 [ rotate 1 [c4 d e] ]]")
  (is (= [60 62 64 62 64 60 64 60 62] (map (comp first :pitches) (notes (ev/events (repo/registry) :r))))))

(deftest a-retrograde-keeps-each-notes-dynamics
  (parse! "[a: c4 !f d e] [b: R ( c4 !f d e )]")
  (let [vel (fn [id] (map :velocity (notes (ev/events (repo/registry) id))))]
    (is (= (reverse (vel :a)) (vel :b)))))

(deftest a-choose-part-plays-one-alternative-each-pass
  (parse! "[s: repeat 6 [ choose [ [I ( C4 d e g )] [R ( C4 d e g )] ] ] c1]")
  (rc/seed! 4)
  (let [ns     (notes (ev/events (repo/registry) :s))
        passes (set (map #(mapv (comp first :pitches) %) (partition 4 (butlast ns))))]
    (is (= #{[60 58 56 53] [67 64 62 60]} passes) "each pass one of the two, both heard")
    (is (= 6 (:beat (last ns))) "the timing runs straight on")
    (is (= 7 (d/duration @(repo/registry) (repo/current :s))) "its length is its longest alternative's")))

(defn- display-notes-in-order [steps]
  (filter :pitches (mapcat #(if (= :par (:kind %)) (mapcat identity (:voices %)) [%]) steps)))

(deftest an-alternate-part-plays-its-elements-in-turn
  (parse! "[s: repeat 5 [ alternate [ [I ( C4 d e g )] [R ( C4 d e g )] [C5] ] ] c1]")
  (let [ps [60 58 56 53 67 64 62 60 72 60 58 56 53 67 64 62 60 72]]
    (is (= ps (map (comp first :pitches) (notes (ev/events (repo/registry) :s)))) "I, R, C5, I, R, then c")
    (is (= ps (map (comp first :pitches) (display-notes-in-order (compose/display-timed (repo/registry) :s))))
        "the preview counts the passes too")))

(deftest a-choose-part-follows-its-weights
  (parse! "[s: repeat 200 [ choose 3,1 [ [C4] [D4] ] ] repeat 200 [ choose 1,3 [ [C4] [D4] ] ]]")
  (rc/seed! 1)
  (let [ps (map (comp first :pitches) (notes (ev/events (repo/registry) :s)))
        f1 (frequencies (take 200 ps)) f2 (frequencies (drop 200 ps))]
    (is (< 130 (f1 60 0) 170) "3,1: the first about three times as often (150 of 200)")
    (is (< 130 (f2 62 0) 170) "1,3: the second likewise")))

(deftest a-shuffle-part-keeps-bar-lines-in-place
  (parse! "[s: shuffle [c4 d | e f]]")
  (is (= [:note :note :mark :note :note]
         (map :kind (filter (comp #{:note :mark} :kind) (ev/events (repo/registry) :s))))
      "the bar line stays after the second note"))

(defn- display-notes
  "display's steps, :par voices flattened, as sorted [onset pitches]."
  [steps]
  (letfn [(flat [ss] (mapcat #(if (= :par (:kind %)) (mapcat flat (:voices %)) [%]) ss))]
    (->> (flat steps) (remove :kind) (filter (comp seq :pitches))
         (map (juxt #(/ (Math/round (* 1e6 (:onset %))) 1e6) :pitches)) sort)))

(deftest agrees-with-display
  (parse! (str "[piece: !tempo:132 !Meter:3/4 c4 d8 e8 | { [f4 g4 a4] [A3 B3] } "
               "repeat 2 [ c'8 b8 ] volta [ [ a4 ] ] c4\\prall r4 d4 ]"))
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
         (map :beat (take 50 (notes (ev/events (repo/registry) :endless)))))))

(deftest bars-and-marks
  (parse! "[b: !tempo:240 !Meter:2/4 c4 d4 | e4 f4 || g2 ]")
  (let [evs (ev/events (repo/registry) :b)]
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
         (map :pitches (notes (ev/events (repo/registry) [[:a :algo :up] :b])))))
  (is (= [[61] [63]] (map :pitches (notes (ev/events (repo/registry) :a :algo :up))))))

(deftest a-top-level-set-mints-top-level-voices-lowest-first
  (parse! "[hi: c'4 ]")
  (parse! "[lo: C3 ]")
  (is (= {[:TA] [[48]] [:TB] [[72]]}
         (update-vals (group-by :path (notes (ev/events (repo/registry) #{:hi :lo}))) #(map :pitches %)))))

(deftest render-writes-what-events-says
  (parse! "[r: !tempo:240 { [c4 d4] [e4] } g4 ]")
  (let [f   (java.io.File/createTempFile "musics.events-test" ".mid")
        _   (midi-file/write-events (ev/events (repo/registry) :r) f)
        ons (for [t (.getTracks (MidiSystem/getSequence f))
                  i (range (.size t))
                  :let [e (.get t i) msg (.getMessage e)]
                  :when (and (instance? ShortMessage msg)
                             (= ShortMessage/NOTE_ON (.getCommand ^ShortMessage msg)))]
              [(.getTick e) (.getData1 ^ShortMessage msg)])]
    (.delete f)
    (is (= [[0 60] [0 64] [250 62] [500 67]] (sort ons)))))

(deftest an-instrument-list-is-one-event-rendered-once-per-instrument
  (parse! "[r: !tempo:240 !i:40,73 c4 d4 ]")
  (is (= [[40 73] [40 73]] (map :program (notes (ev/events (repo/registry) :r)))))
  (let [f   (java.io.File/createTempFile "musics.events-test" ".mid")
        _   (midi-file/write-events (ev/events (repo/registry) :r) f)
        msgs (for [t (.getTracks (MidiSystem/getSequence f))
                   i (range (.size t))
                   :let [e (.get t i) msg (.getMessage e)]
                   :when (instance? ShortMessage msg)]
               msg)
        cmd  (fn [c] (filter #(= c (.getCommand ^ShortMessage %)) msgs))]
    (.delete f)
    (is (= #{40 73} (set (map #(.getData1 ^ShortMessage %) (cmd ShortMessage/PROGRAM_CHANGE)))))
    (is (= 4 (count (cmd ShortMessage/NOTE_ON))) "two notes, each on two channels")
    (is (= 2 (count (distinct (map #(.getChannel ^ShortMessage %) (cmd ShortMessage/NOTE_ON))))))))

(deftest a-layered-drum-sounds-every-key-at-once
  (parse! "[r: x8\\36,39 x\\42 ]")
  (is (= [[36 39] [42]] (map :pitches (filter #(= :drum (:kind %)) (ev/events (repo/registry) :r))))))

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
  (map #(/ (Math/round (* 1000 (:t %))) 1000.0) (notes (ev/events (repo/registry) form))))

(deftest a-referencing-containers-context-reaches-the-referenced-notes
  (parse! "[mot: c4 d e f]")
  (parse! "[fast: !tempo:240 :mot]")
  (parse! "[loud: !ff :mot]")
  (is (= [0.0 0.25 0.5 0.75] (note-times :fast)))
  (is (= [102 102 102 102] (map :velocity (notes (ev/events (repo/registry) :loud))))))

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
  (is (= [0.0 1.0] (note-times (compose/sq :own))))
  (is (= [102 102] (map :velocity (notes (ev/events (repo/registry) (compose/sq :own)))))))

(defn- note-pitches [form] (map :pitches (notes (ev/events (repo/registry) form))))

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
  (parse! "[tr: transpose c d ( c f b )]")
  (parse! "[trG: !key:G.major :tr]")
  (is (= [[62] [67] [73]] (note-pitches :tr)))
  (is (= [[62] [68] [73]] (note-pitches :trG)) "c f# b in G, up a tone"))

(deftest extracted-notes-follow-the-key-they-are-played-in
  (parse! "[mot: c f]")
  (parse! "^{inG: !key:G.major}")
  (is (= [[60] [66]] (note-pitches [:inG (compose/sq :mot)]))))

(deftest a-chord-symbol-follows-the-key-by-its-root
  ;; the written quality stays: F major in C plays F# major under G
  (parse! "[chords: F4:M C4:M/E]")
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

(deftest volta-endings-follow-lilyponds-rule
  (parse! "[v: repeat 3 [ a4 ] volta [ [ c4 ] [ e4 ] ] ]")
  (is (= [[57] [60] [57] [60] [57] [64]] (note-pitches :v))
      "every pass its ending: the first on the earlier passes, the last on the last")
  (parse! "[w: repeat 2 [ a4 ] volta [ c4 ] ]")
  (is (= [[57] [60] [57] [60]] (note-pitches :w)) "one ending, after every pass"))

(deftest repeat-signs-play-twice
  (parse! "[r: [: c4 d4 :] e4 ]")
  (is (= [[60] [62] [60] [62] [64]] (note-pitches :r)))
  (parse! "[s: [: a4 :] volta [ [ c4 ] [ e4 ] ] g4 ]")
  (is (= [[57] [60] [57] [64] [67]] (note-pitches :s)) "with endings, as repeat 2"))

(deftest jumps-play-dal-segno-and-da-capo
  (parse! "[ds: C4 D4 $ E4 F4 DS G4 ]")
  (is (= [[60] [62] [64] [65] [64] [65] [67]] (note-pitches :ds))
      "back to the segno, on past the DS")
  (parse! "[dc: C4 D4 DC E4 ]")
  (is (= [[60] [62] [60] [62] [64]] (note-pitches :dc)) "back to the start")
  (parse! "[fine: C4 $ D4 Fine E4 DS F4 ]")
  (is (= [[60] [62] [64] [62]] (note-pitches :fine)) "the return pass stops at Fine")
  (parse! "[coda: C4 $ D4 Coda E4 DS F4 Coda G4 ]")
  (is (= [[60] [62] [64] [62] [67]] (note-pitches :coda))
      "the return pass leaves at the first Coda for the second")
  (is (= 5/4 (d/duration @(repo/registry) :coda)) "the length is the played length")
  (is (= (display-notes (compose/display-timed (repo/registry) :coda))
         (->> (notes (ev/events (repo/registry) :coda))
              (map (juxt #(/ (Math/round (* 1e6 (:t %))) 1e6) :pitches)) sort))
      "display plays the same"))

(deftest a-return-pass-takes-the-context-where-it-is-written
  (parse! "[tt: !tempo:60 C4 $ D4 !tempo:120 E4 DS F4 ]")
  (is (= [[60 1.0] [62 1.0] [64 0.5] [62 1.0] [64 0.5] [65 0.5]]
         (map (juxt (comp first :pitches) #(/ (Math/round (* 1000 (:dur-secs %))) 1000.0))
              (notes (ev/events (repo/registry) :tt))))))

(deftest a-simile-repeats-the-bar-before-it
  (parse! "[sim: C4 D4 | E4 F4 | ./. | G4 ]")
  (is (= [[60] [62] [64] [65] [64] [65] [67]] (note-pitches :sim)))
  (parse! "[sim2: C4 D4 | ./. | ./. ]")
  (is (= [[60] [62] [60] [62] [60] [62]] (note-pitches :sim2)) "./. after ./. repeats it again")
  (is (= 3/2 (d/duration @(repo/registry) :sim2)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"right after a bar line"
                        (d/play-segments [(d/leaf :a (c/context) 1/4 [60]) (d/jump :simile)])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fills its bar alone"
                        (d/play-segments [(d/leaf :a (c/context) 1/4 [60]) (d/bar 1) (d/jump :simile)
                                          (d/leaf :b (c/context) 1/4 [62])]))
      "[c | ./. d] is refused"))

(deftest an-endless-repeat-plays-on
  (parse! "[loop: repeat ~ [ C4 D4 ] ]")
  (is (= [[60] [62] [60] [62] [60]] (take 5 (note-pitches :loop))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no volta endings"
                        (gp/parse-domain-string "[repeat ~ [c d] volta [e]]"))))

(defn- drum-hits [form]
  (map (juxt :beat (comp first :pitches)) (filter #(= :drum (:kind %)) (ev/events (repo/registry) form))))

(deftest a-bare-x-is-the-previous-drum
  (parse! "[kit: x8\\36 x x\\snare x x\\ghost]")
  (is (= [[0 36] [1/8 36] [1/4 38] [3/8 38] [1/2 38]] (drum-hits :kit))))

(deftest a-tied-drum-is-struck-once
  (parse! "[held: x4\\38~ x8 x\\36]")
  (is (= [[0 38] [3/8 36]] (drum-hits :held)) "the tied-to hit rests; time goes on")
  (parse! "[other: x4\\38~ x8\\36]")
  (is (= [[0 38] [1/4 36]] (drum-hits :other)) "tied to another drum, both are struck")
  (parse! "[across: x2.\\38 x4~ | x4]")
  (is (= [[0 38] [3/4 38]] (drum-hits :across)) "across a bar line"))

(deftest a-context-before-the-music-comes-first
  (parse! "^{ loud: !ff } [v: !pp c d] [plain: c d] [w: :loud [!pp c d]] [z: c d ^{ !ff } e [!pp f g]]")
  (let [vel  #(map :velocity (notes (ev/events (repo/registry) %)))
        disp #(->> (compose/display-timed (repo/registry) %)
                   (tree-seq (some-fn map? sequential?) (fn [x] (if (map? x) (:voices x) x)))
                   (keep :velocity))]
    (is (= [38 38] (vel :v)) "on its own, the line's own !pp")
    (is (= [102 102] (vel [:loud :v])) "a play arg's leading context overrides it")
    (is (= [102 102] (vel [:loud :plain])) "and fills what it leaves open")
    (is (= [102 102] (vel :w)) "in the text, :loud before a line overrides its !pp")
    (is (= [64 64 102 102 102] (vel :z)) "an inline ^{ } from where it is written, nested lines included")
    (is (= (vel :z) (disp :z)) "display-timed agrees")
    (is (= [102 102 102 102] (vel [:loud [:plain :v]])) "nested groups stay under it")
    (parse! "^{ soft: !pp }")
    (is (= [38 38] (vel [:loud [:soft :v]])) "of two contexts before the music, the nearer wins")))

(deftest a-context-before-the-music-survives-write-and-load
  (parse! "[z: c d ^{ !ff } e [!pp f g]]")
  (let [tmp (java.io.File/createTempFile "musics-session" ".edn")]
    (try
      (with-out-str (persist/write (.getPath tmp)))
      (repo/reset-all!)
      (with-out-str (persist/load (.getPath tmp)))
      (is (= [64 64 102 102 102] (map :velocity (notes (ev/events (repo/registry) :z)))))
      (finally (.delete tmp)))))

(defn- heard
  "[onset-ms played-ms] of each note as heard: :t plus :micro, :dur-played."
  [id]
  (mapv (fn [e] [(Math/round (* 1000 (+ (:t e) (:micro e 0.0)))) (Math/round (* 1000 (:dur-played e)))])
        (filter #(#{:note :drum} (:kind %)) (ev/events (repo/registry) id))))

(deftest swing-moves-the-off-beat-as-heard
  (parse! "[st: !tempo:60 c8 d e4]")
  (parse! "[sw: !tempo:60 !shuffle c8 d e4]")
  (let [[[_ c] _ [_ e]] (heard :st)]
    (is (= [[0 600] [667 300] [1000 e]] (heard :sw))
        "2:1: the beat's note held to 2/3, the off-beat starts there; a quarter untouched")
    (is (= 450 c) "straight, an eighth plays 0.45 s"))
  (is (= (map :t (filter #(= :note (:kind %)) (ev/events (repo/registry) :st)))
         (map :t (filter #(= :note (:kind %)) (ev/events (repo/registry) :sw))))
      "the voice's own time stays straight")
  (parse! "[s16: !tempo:60 !swing:0.75 !swingUnit:16 c16 d e8]")
  (is (= [[0 338] [375 113] [500 450]] (heard :s16)) "sixteenths, dotted")
  (parse! "[up: !tempo:60 !shuffle partial 8 c8 d]")
  (is (= [[167 300] [500 600]] (heard :up)) "a pickup eighth is an off-beat")
  (parse! "[dr: !tempo:60 !shuffle x8 x8]")
  (is (= 667 (first (second (heard :dr)))) "drums swing too")
  (parse! "[jz: !tempo:60 !swing c8 d]")
  (is (= [[0 540] [600 360]] (heard :jz)) "!swing is jazz swing, 0.6"))

(deftest swing-starts-anew-each-bar
  (parse! "[b3: !tempo:60 !Meter:3/8 !shuffle c8 d e f]")
  (is (= [[0 600] [667 300] [1000 450] [1500 600]] (heard :b3))
      "in 3/8 the eighth left over isn't swung, and the next bar starts on its downbeat")
  (parse! "[u: !tempo:60 !shuffle !swingUnit:16.0 c16 d]")
  (is (= 83 (- (first (second (heard :u))) 250)) "16.0 is a sixteenth too: the off-beat 83 ms late")
  (parse! "[w: !tempo:60 !swing:2 c8 d]")
  (is (every? #(pos? (second %)) (heard :w)) "a ratio out of range is kept to it")
  (parse! "[fp: !tempo:60 !shuffle partial 8 c8 { [d8 e8] [c4] }]")
  (is (= [[167 300] [500 600] [500 900] [1167 300]] (sort (heard :fp)))
      "a { } after the pickup doesn't count it again"))

(deftest groove-lays-back-or-pushes-by-the-place-in-the-bar
  (parse! "[gs: !tempo:60 c16 d e f]")
  (parse! "[gl: !tempo:60 !groove:1.0 c16 d e f]")
  (parse! "[gp: !tempo:60 !pushed c16 d e f]")
  (let [straight (map first (heard :gs))
        laid     (map first (heard :gl))
        pushed   (map first (heard :gp))]
    (is (= (first straight) (first laid) (first pushed)) "the downbeat stays")
    (is (every? pos? (map - (rest laid) (rest straight))) "laid back: the rest later")
    (is (every? neg? (map - (rest pushed) (rest straight))) "pushed: earlier")
    (is (< (- (nth laid 2) (nth straight 2)) (- (nth laid 1) (nth straight 1)))
        "the eighth off-beat moves less than the weak sixteenth before it")
    (is (<= (apply max (map - laid straight)) 125) "at most a 32nd (125 ms at 60)")))

(deftest a-drum-plays-in-its-kit
  (parse! "[kt: !kit:tr808 x8\\bd1 c8]")
  (let [[d n] (filter #(#{:note :drum} (:kind %)) (ev/events (repo/registry) :kt))]
    (is (= [9 25] ((juxt :channel :program) d)) "on the drum channel, the kit its program")
    (is (= 0 (:program n)) "a note keeps its own instrument"))
  (let [seq-  (midi-file/events->sequence (ev/events (repo/registry) :kt))
        pcs   (for [tr (.getTracks seq-) i (range (.size tr))
                    :let [m (.getMessage (.get tr i))]
                    :when (and (instance? ShortMessage m) (= ShortMessage/PROGRAM_CHANGE (.getCommand ^ShortMessage m)))]
                [(.getChannel ^ShortMessage m) (.getData1 ^ShortMessage m)])]
    (is (some #{[9 25]} pcs) "a file sends the kit on the drum channel")))

(deftest drums-take-accents
  (parse! "[da: x8\\sna x8\\sna-> x8\\sna\\vol:90]")
  (let [[plain accent loud] (map :velocity (filter #(= :drum (:kind %)) (ev/events (repo/registry) :da)))]
    (is (< plain accent) "an accented drum is louder")
    (is (< plain loud) "and one with its own volume")))

(deftest the-key-by-any-of-its-names
  (doseq [n ["K" "Key" "key" "k"]]
    (parse! (str "[kx" n ": !" n ":D.major c d e f]"))
    (is (= [61 62 64 66] (map (comp first :pitches) (notes (ev/events (repo/registry) (keyword (str "kx" n))))))
        (str "!" n ":D.major sets the key")))
  (parse! "^{kd: !K:D.major}")
  (parse! "[k0: c d e f]")
  (is (= [61 62 64 66] (map (comp first :pitches) (notes (ev/events (repo/registry) [:kd :k0]))))
      "and as an override in front of the music"))

(deftest accents-heard-by-a-setting
  (parse! "[ap: !tempo:60 c8 d e f g a b c]")
  (parse! "[am: !tempo:60 !accent:metric c8 d e f g a b c]")
  (parse! "[ag: !tempo:60 !accent:agogic c16 d8 e4 f2]")
  (let [vel  #(mapv :velocity (filter (fn [e] (#{:note :drum} (:kind e))) (ev/events (repo/registry) %)))
        flat (vel :ap) met (vel :am) ago (vel :ag)]
    (is (apply = flat) "without it, all alike")
    (is (= (first met) (apply max met)) "metric: the downbeat loudest")
    (is (> (nth met 4) (nth met 1)) "beat 3 above an off-beat")
    (is (apply < ago) "agogic: the longer, the louder"))
  (parse! "[a232: !tempo:60 !M:232 !accent:metric c8 c c c c c c c c c c c]")
  (let [v (mapv :velocity (filter #(= :note (:kind %)) (ev/events (repo/registry) :a232)))]
    (is (= (v 2) (v 4) (v 8) (v 10)) "232 by level: every pair starts alike")
    (is (apply = (map v [1 3 5 7 9 11])) "every off-beat alike")
    (is (> (v 2) (v 1)) "a pair's start above its off-beat"))
  (parse! "[ab: !tempo:60 !M:232 !accent:barlow c8 c c c c c c c c c c c]")
  (let [v (mapv :velocity (filter #(= :note (:kind %)) (ev/events (repo/registry) :ab)))]
    (is (not= (v 2) (v 4)) "barlow: a finer ranking, every pulse its own"))
  (parse! "[ad: !tempo:60 !accent:metric x8 x x x]")
  (let [vs (mapv :velocity (filter #(= :drum (:kind %)) (ev/events (repo/registry) :ad)))]
    (is (> (first vs) (second vs)) "drums too")))

