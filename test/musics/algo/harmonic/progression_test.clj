(ns ^:algo musics.algo.harmonic.progression-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.test-support :refer [with-fresh-session]]
            [musics.input.grammar-parser :as gp]
            [musics.repo :as repo]
            [musics.events :as ev]
            [musics.algo.harmonic.progression :as hp]))

(use-fixtures :each (fn [f] (with-fresh-session (f))))

(defn- parse! [text] (with-out-str (gp/parse text)))

(defn- chords
  "[pitches beat] of every chord the part id plays."
  [id]
  (->> (ev/events (repo/registry) id)
       (filter #(= :note (:kind %)))
       (mapv (juxt :pitches :beat))))

(deftest every-blues-is-twelve-bars-of-4-4
  (doseq [[k _] hp/blues]
    (let [bars (hp/bars k {:end false})]
      (is (= 12 (count bars)) (str k))
      (is (every? #(= 1 (reduce + (map :duration %))) bars) (str k " every bar a whole note")))))

(deftest the-basic-blues-is-i-iv-v
  (parse! (hp/->mus :basic {:tonic "C" :name :b}))
  (let [cs (chords :b)
        c7 [48 52 55 58] f7 [53 57 60 63] g7 [55 59 62 65]]
    (is (= [c7 c7 c7 c7 f7 f7 c7 c7 g7 f7 c7 g7 c7] (map first cs)) "and a closing C7")
    (is (= (range 13) (map second cs)) "one chord a bar, on the downbeat")))

(deftest the-end-is-a-bar-on-the-tonic
  (is (re-find #"\| G3:m7 C4:7 \| F3/1:7 \]$" (hp/->mus :jazz {:tonic "F"})))
  (is (re-find #"\| G3:7 \| C3:m7 \]$" (hp/->mus :minor {:tonic "C"})) "the minor blues ends on Im7")
  (is (re-find #"\| G3:7 \| C3:maj7 \]$" (hp/->mus "IIm7 V7 | Imaj7 | V7" {})) "the first chord on I, not the first chord")
  (is (re-find #"\| C3/1:maj7 \]$" (hp/->mus "IIm7 V7 | Imaj7" {})) "a scheme ending on its tonic gets no second one")
  (is (re-find #"\| G3:7 \| C3:M \]$" (hp/->mus "IIm7 | V7" {})) "no I in the scheme: a plain I")
  (is (re-find #"\| G3:m7 C4:7 \]$" (hp/->mus :jazz {:tonic "F" :end false}))
      "without an end the form stays open, to loop"))

(deftest the-text-reads-as-chord-symbols
  (is (= "[blues: !M:4/4 F3/1:7 | B&3:7 | F3:7 | C4/2:m7 F3:7 | B&3/1:7 | B3:d7 | F3:7 | D4:7 | G3:m7 | C4:7 | F3/2:7 D4:7 | G3:m7 C4:7 ]"
         (hp/->mus :jazz {:tonic "F" :name :blues :end false}))
      "the raised IV of F is B, not C&; two chords share a bar as halves; a duration only where it changes"))

(deftest the-text-plays-as-written-out
  (parse! (hp/->mus :jazz {:tonic "F" :name :short :end false}))
  (parse! "[long: !M:4/4 F3/1:7 | B&3/1:7 | F3/1:7 | C4/2:m7 F3/2:7 | B&3/1:7 | B3/1:dim7 | F3/1:7 | D4/1:7 | G3/1:m7 | C4/1:7 | F3/2:7 D4/2:7 | G3/2:m7 C4/2:7 ]")
  (is (= (chords :long) (chords :short))))

(deftest roots-are-spelled-in-the-key
  (testing "a flat key's degrees take flats"
    (is (= ["B&3:m7" "E&4:m7" "G&4:7" "F4:7"]
           (->> (re-seq #"([A-G][&#]*\d)(?:/\d)?(:\w+)" (hp/->mus :minor {:tonic "B&"})) (map #(str (% 1) (% 2))) distinct vec))
        "Im7, IVm7, bVI7 (G&, the lowered sixth) and V7"))
  (testing "a sharp key's degrees take sharps"
    (is (= ["E3:7" "A3:7" "B3:7"]
           (->> (re-seq #"([A-G][&#]*\d)(?:/\d)?(:\w+)" (hp/->mus :basic {:tonic "E"})) (map #(str (% 1) (% 2))) distinct vec)))
    (is (re-find #"F##3:d7" (hp/->mus :jazz {:tonic "C#"})) "#IV of C# is F##")
    (parse! (hp/->mus :jazz {:tonic "C#" :name :cs}))
    (is (= [[55 58 61 64] 5] (nth (chords :cs) 6)) "F##3 reads back as G3's pitch")))

(deftest the-minor-blues-plays-its-lowered-sixth
  (parse! (hp/->mus :minor {:tonic "C" :name :mb}))
  (is (= [[56 60 63 66] 8] (nth (chords :mb) 8)) "bar 9: A&7"))

(deftest a-hit-strikes-each-chord-in-time
  (let [bars (hp/bars :jazz {:hit 1/4 :end false})]
    (is (= 48 (count (mapcat identity bars))) "four strikes a bar")
    (is (= [55 55 48 48] (map :root (nth bars 3))) "bar 4: Vm7 and I7, twice each in their half"))
  (parse! (hp/->mus :basic {:tonic "C" :hit 1/4 :name :comp :end false}))
  (is (= (range 0 12 1/4) (map second (chords :comp))) "a chord on every quarter"))

(deftest a-scheme-can-be-written-out
  (is (= "[!M:4/4 D2/2:m7 G2:7 | C2/1:maj7 ]"
         (hp/->mus "IIm7 V7 | Imaj7" {:octave 2 :end false})))
  (is (= "[!M:4/4 C3/1:M | G3:7.9- ]" (hp/->mus "I | V7.9-" {:end false}))
      "no quality is a major triad, a quality's additions pass through"))

(deftest what-isnt-a-scheme-says-so
  (is (thrown-with-msg? Exception #"no scheme" (hp/bars :boogie {})))
  (is (thrown-with-msg? Exception #"not a chord numeral" (hp/->mus "I7 | X7" {})))
  (is (thrown-with-msg? Exception #"not a tonic" (hp/->mus :basic {:tonic "H"})))
  (is (thrown-with-msg? Exception #"not a note value" (hp/->mus "I IV V" {}))
      "three chords can't share a 4/4 bar as plain note values"))

(deftest choruses-as-a-repeat-with-volta-endings
  (is (= "[!M:4/4 repeat 2 [ F3/1:7 | B&3:7 | F3:7 | C4/2:m7 F3:7 | B&3/1:7 | B3:d7 | F3:7 | D4:7 | G3:m7 | C4:7 ] volta [ [ F3/2:7 D4:7 | G3:m7 C4:7 ] [ F3/1:7 | F3:7 ] ] ]"
         (hp/->mus :jazz {:tonic "F" :repeat 2}))
      "ten bars twice, the turnaround first, the tonic last; a duration carries on into the endings")
  (parse! (hp/->mus :basic {:tonic "C" :repeat 3 :name :r3}))
  (let [cs (chords :r3)]
    (is (= (range 36) (map second cs)) "three choruses, a chord a bar")
    (is (= [[55 59 62 65] [48 52 55 58] [48 52 55 58]] (map first (map cs [23 34 35])))
        "the turnaround's G7 before the last pass, two bars of C7 to end"))
  (testing "with a hit the last bar is still held"
    (is (re-find #"\[ C3:7 C3:7 C3:7 C3:7 \| C3/1:7 \] \] \]$"
                 (hp/->mus :basic {:repeat 2 :hit 1/4}))))
  (is (thrown-with-msg? Exception #"at least three bars" (hp/->mus "IIm7 | V7" {:repeat 2}))))

(deftest the-choruses-from-music-data
  (testing "each in its own key by default, any other on request"
    (is (str/starts-with? (hp/->mus :blues-chorus {}) "[!M:4/4 C3/1:7 | F3:7 | C3:7 "))
    (is (str/starts-with? (hp/->mus :jazz-chorus {}) "[!M:4/4 B&3/2:M7 G4:7 | C4:m7 F4:7 "))
    (is (str/starts-with? (hp/->mus :jazz-chorus {:tonic "F"}) "[!M:4/4 F3/2:M7 D4:7 ")))
  (is (= 12 (count (hp/bars :blues-chorus {:end false}))))
  (is (= 32 (count (hp/bars :jazz-chorus {}))) "rhythm changes ends on its tonic: no closing bar added")
  (is (= 9 (count (hp/bars :jazz-ii-V-I-chorus {}))) "eight bars and the closing Cmaj7")
  (parse! (hp/->mus :jazz-ii-V-I-chorus {:name :iivi :end false}))
  (is (= [[[50 53 57 60] 0] [[55 59 62 65] 1/2] [[48 52 55 59] 1]] (take 3 (chords :iivi))) "Dm7 G7 | Cmaj7")
  (is (= [[49 53 56 59] 13/2] (nth (chords :iivi) 11)) "bar 7's tritone substitute, D&7")
  (parse! (hp/->mus :jazz-chorus {:name :rhythm}))
  (is (= [[64 67 70 73] 11/2] (nth (chords :rhythm) 11)) "bar 6: E dim7, the raised IV of B&"))
