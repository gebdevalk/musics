(ns ^:repl musics.feedback-test
  "The ratings file: appended, read back, summarized per sound and per
   instrument or drum key."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [musics.feedback :as fb]))

(defn- scratch-file
  "Ratings go to a fresh scratch file, never ~/.mu-feedback.edn."
  [t]
  (let [saved @fb/file
        f     (java.io.File/createTempFile "musics.feedback-test" ".edn")]
    (.delete f)
    (reset! fb/file (.getPath f))
    (try (t) (finally (reset! fb/file saved) (io/delete-file f true)))))

(use-fixtures :each scratch-file)

(deftest ratings-are-appended-and-read-back
  (is (= [] (fb/ratings)) "no file yet")
  (fb/rate! {:what "C4/4\\instrument:40" :synth :gervill :soundfont "FluidR3" :where :repl} 1)
  (fb/rate! {:what "x8\\36,39" :where :audition} -1)
  (let [[a b] (fb/ratings)]
    (is (= {:what "C4/4\\instrument:40" :synth :gervill :soundfont "FluidR3" :where :repl :score 1}
           (dissoc a :at)))
    (is (inst? (:at a)))
    (is (= -1 (:score b))))
  (is (thrown? clojure.lang.ExceptionInfo (fb/rate! {:what "c4"} 2))))

(deftest a-line-that-does-not-read-is-skipped
  (spit @fb/file "{:what \"c4\" :score 1}\n{broken\n")
  (is (= 1 (count (fb/ratings)))))

(deftest a-sound-is-read-from-its-text
  (is (= {:kind :note :instruments [40 73]} (fb/sound-parts "C4/4\\instrument:40,73")))
  (is (= {:kind :note :instruments [40]} (fb/sound-parts "C4/4\\instrument:40")))
  (is (= {:kind :note :instruments [0]} (fb/sound-parts "<C4 E4 G4>4")) "the default instrument")
  (is (= {:kind :drum :keys [36 39]} (fb/sound-parts "x8\\36,39")))
  (is (= {:kind :drum :keys [42]} (fb/sound-parts "x8\\42")))
  (is (nil? (fb/sound-parts "r4")))
  (is (nil? (fb/sound-parts "[[["))))

(deftest means-per-sound-and-per-part
  (doseq [[what score] [["C4/4\\instrument:40" 1] ["C4/4\\instrument:40" 0]
                        ["C4/4\\instrument:40,73" 1] ["x8\\36" -1]]]
    (fb/rate! {:what what} score))
  (is (= [["C4/4\\instrument:40,73" {:n 1 :mean 1.0}]
          ["C4/4\\instrument:40" {:n 2 :mean 0.5}]
          ["x8\\36" {:n 1 :mean -1.0}]]
         (fb/by-sound)))
  (is (= [[[:instrument 73] {:n 1 :mean 1.0}]
          [[:instrument 40] {:n 3 :mean (double 2/3)}]
          [[:drum 36] {:n 1 :mean -1.0}]]
         (fb/by-part))
      "a layer counts for each instrument in it")
  (is (= ["C4/4\\instrument:40,73" "C4/4\\instrument:40"] (fb/sounds-where pos?))))
