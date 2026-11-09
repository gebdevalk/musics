(ns ^:engine musics.midi.gervill-test
  "Gervill rendering into a stream, no sound device: a note and a drum
   sound, silence before them, a soundfont swap keeps channels playing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [musics.midi.gervill :as gv]
            [musics.midi.live :as live])
  (:import (javax.sound.sampled AudioInputStream)))

(def ^:private small-sf2
  "A small GM soundfont, quick to load; the tests skip without it."
  "/usr/share/sounds/sf2/TimGM6mb.sf2")

(defn- peak
  "The loudest 16-bit sample in the next ms of stream."
  [^AudioInputStream stream ms]
  (let [buf (byte-array (* 4 (quot (* 44100 ms) 1000)))]
    (.read stream buf)
    (reduce max 0 (for [i (range 0 (alength buf) 2)]
                    (Math/abs (int (short (bit-or (bit-and (aget buf i) 0xff) (bit-shift-left (aget buf (inc i)) 8)))))))))

(deftest gervill-renders-notes-and-drums
  (if-not (.isFile (io/file small-sf2))
    (println "skipped: no" small-sf2)
    (let [[synth stream] (gv/open-stream :soundfont small-sf2)
          rcv            (.getReceiver synth)]
      (try
        (is (zero? (peak stream 100)) "silent before any note")
        (testing "a note on an instrument channel"
          (live/program-change rcv 0 0)
          (live/note-on rcv 0 60 100)
          (is (pos? (peak stream 200)))
          (live/note-off rcv 0 60)
          (peak stream 1500))
        (testing "a drum on channel 9"
          (live/note-on rcv 9 38 100)
          (is (pos? (peak stream 200)))
          (live/note-off rcv 9 38)
          (peak stream 1500))
        (testing "after a soundfont swap the channels still play"
          (is (= (gv/soundbank-name synth) (gv/load-soundfont! synth small-sf2)))
          (live/note-on rcv 0 64 100)
          (is (pos? (peak stream 200)))
          (live/note-on rcv 9 36 100)
          (is (pos? (peak stream 200))))
        (finally (gv/close synth))))))

(deftest soundfont-paths
  (is (= (str (System/getProperty "user.home") "/x.sf2") (gv/expand "~/x.sf2")))
  (is (= "/a/~b" (gv/expand "/a/~b")))
  (is (every? #(.endsWith (.toLowerCase ^String %) ".sf2") (gv/soundfonts)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no soundfont at"
                        (gv/load-soundfont! nil "/nowhere/none.sf2"))))

(deftest an-output-that-doesnt-open-is-an-error
  (let [undone (promise)
        within #'gv/within]
    (is (= 3 (within 1000 "late" #(+ 1 2) #(deliver undone true))) "in time: its result")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"late"
                          (within 50 "late" #(Thread/sleep 300) #(deliver undone true)))
        "too late: an error, not a wait")
    (is (true? (deref undone 2000 false)) "and undone once it opens after all")
    (is (thrown-with-msg? IllegalStateException #"boom"
                          (within 1000 "late" #(throw (IllegalStateException. "boom")) (fn [])))
        "its own error as it is")))

(deftest what-to-say-when-there-is-no-soundfont
  (testing "none to load: where mu looked and where to put one"
    (with-redefs [gv/default-soundfonts ["/nowhere/FluidR3_GM.sf2"]]
      (let [m (gv/no-soundfont-message nil)]
        (is (string? m))
        (is (re-find #"no SoundFont" m))
        (is (str/includes? m (first gv/soundfont-dirs)) "names the folder to use")
        (is (re-find #"thin built-in" m) "and what plays meanwhile")
        (is (re-find #"soundfonts/README.md" m)))))
  (testing "one that will load: nothing to say"
    (let [f (java.io.File/createTempFile "mu-gm" ".sf2")]
      (try
        (with-redefs [gv/default-soundfonts [(.getPath f)]]
          (is (nil? (gv/no-soundfont-message nil)) "a default is found")
          (is (nil? (gv/no-soundfont-message "/any/given.sf2")) "one is given: loading it will say if it fails"))
        (finally (.delete f))))))

(deftest what-to-say-when-the-output-wont-open
  (is (boolean? (gv/output-available?)))
  (testing "no audio output at all"
    (with-redefs [gv/output-available? (constantly false)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no audio output found.*render still writes a MIDI file"
                            (gv/open)))))
  (testing "whatever Java throws is put readably"
    (let [m (gv/open-error-message (javax.sound.sampled.LineUnavailableException. "seq busy"))]
      (is (re-find #"could not open the sound output \(seq busy\)" m))
      (is (re-find #"held by another program" m))
      (is (re-find #"render" m)))
    (is (re-find #"IllegalStateException" (gv/open-error-message (IllegalStateException.))) "no message: its kind")))

