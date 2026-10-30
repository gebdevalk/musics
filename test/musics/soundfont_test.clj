(ns ^:repl musics.soundfont-test
  "mu's soundfont words: listing the soundfonts and General MIDI's
   groups, and trying instruments -- a soundfont's, a range of programs,
   a group's or those a name picks -- without playing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.engine :as engine]
            [musics.input.reader.builder :as builder]
            [musics.midi.gervill :as gervill]
            [musics.mu.eval :as e]
            [musics.mu.feedback :as mfb]
            [musics.mu.library]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.repo :as repo]
            [musics.soundfont]
            [musics.test-support :refer [with-fresh-registries]]))

(def ^:private said (atom []))

(defn- fresh [t]
  (let [saved @e/state above @e/print-above]
    (reset! said [])
    (reset! e/print-above #(swap! said conj %))
    (reset! mfb/audition nil)
    (try
      (with-fresh-registries
        (reset! e/state (e/fresh-state))
        (swap! e/state assoc :ns (:ns saved))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally (reset! e/state saved) (reset! e/print-above above) (reset! mfb/audition nil)))))

(use-fixtures :each fresh)

(defn- run [input]
  (swap! e/state assoc :stack [])
  (e/eval-input input))

(defn- out [input] (with-out-str (run input)))

(defn- wait-until [pred ms]
  (loop [n (quot ms 10)] (cond (pred) true (zero? n) false :else (do (Thread/sleep 10) (recur (dec n))))))

(deftest listing
  (testing "soundfonts numbers the files soundfont takes"
    (is (= (apply str (map-indexed #(str %1 "> " %2 "\n") (engine/soundfonts))) (out "soundfonts"))))
  (testing "list-soundfonts: each number and name, aligned"
    (with-redefs [engine/soundfonts (constantly (vec (for [i (range 11)] (str "/sf/f" i ".sf2"))))]
      (let [ls (str/split-lines (out "list-soundfonts"))]
        (is (= [" 0  f0" "10  f10"] [(first ls) (last ls)])))))
  (testing "list-groups: General MIDI's groups of eight"
    (let [ls (str/split-lines (out "list-groups"))]
      (is (= 16 (count ls)))
      (is (= " 7  brass                  56-63" (nth ls 7)))))
  (testing "soundfont refuses what is neither a file nor a number, and a number too high"
    (is (re-find #"soundfont: needs a file in quotes or a number" (:error (run "c4 soundfont"))))
    (is (re-find #"soundfont: no number 100000" (:error (run "100000 soundfont"))))))

(def ^:private holds
  {"/sf/a.sf2" [{:bank 0 :program 40 :name "Violin" :percussion? false}
                {:bank 0 :program 0 :name "Piano " :percussion? false}
                {:bank 0 :program 0 :name "Kit" :percussion? true}
                {:bank 8 :program 1 :name "Other bank" :percussion? false}
                {:bank 0 :program 60 :name "Horn" :percussion? false}]
   "/sf/b.sf2" [{:bank 0 :program 48 :name "Strings" :percussion? false}
                {:bank 0 :program 61 :name "Brass section" :percussion? false}]})

(defn- tried
  "What try-soundfonts plays for input: each soundfont it loads, and the
   music with the context it plays under."
  [input]
  (let [played (atom [])]
    (with-redefs [engine/soundfonts   (constantly ["/sf/a.sf2" "/sf/b.sf2"])
                  engine/soundfont!   (fn [path] (swap! played conj path) "bank")
                  gervill/instruments holds
                  mt/play!            (fn [x ctx & _] (swap! played conj (str (p/show x) " " (p/show ctx))))
                  engine/busy?        (constantly false)
                  mfb/gap-ms          (atom 30)]
      (with-out-str (run input))
      (is (wait-until #(nil? @mfb/audition) 2000))
      @played)))

(deftest trying-every-instrument-of-every-soundfont
  (is (= ["/sf/a.sf2" "[C4/4 D4] ^{ !i:0 }" "[C4/4 D4] ^{ !i:40 }" "[C4/4 D4] ^{ !i:60 }"
          "/sf/b.sf2" "[C4/4 D4] ^{ !i:48 }" "[C4/4 D4] ^{ !i:61 }"]
         (tried "[c d] try-soundfonts"))
      "each soundfont loaded once, its bank-0 instruments in program order, no kit")
  (is (some #{"2/5  0> a.sf2  !i:40 Violin"} @said) "shown with the number soundfont takes it by")
  (testing "without music, a phrase of its own"
    (is (= (str (p/show (mt/read-text @#'musics.soundfont/phrase)) " ^{ !i:0 }") (second (tried "try-soundfonts"))))))

(deftest choosing-what-to-try
  (testing "a number: that soundfont"
    (is (= ["/sf/b.sf2" "[C4/4] ^{ !i:48 }" "[C4/4] ^{ !i:61 }"] (tried "[c] 1 try-soundfonts")))
    (is (some #{"1/2  1> b.sf2  !i:48 Strings"} @said) "keeping its number"))
  (testing "a pair: those programs"
    (is (= ["/sf/a.sf2" "[C4/4] ^{ !i:40 }" "/sf/b.sf2" "[C4/4] ^{ !i:48 }"] (tried "[c] '[40 55] try-soundfonts"))))
  (testing "a text: a group, by the start of its name"
    (is (= ["/sf/a.sf2" "[C4/4] ^{ !i:60 }" "/sf/b.sf2" "[C4/4] ^{ !i:61 }"] (tried "[c] \"brass\" try-soundfonts")))
    (is (= ["/sf/a.sf2" "[C4/4] ^{ !i:40 }"] (tried "[c] \"STRING\" try-soundfonts")) "strings: 40-47, not program 48"))
  (testing "a text no group starts: the instruments whose own or General MIDI name holds it"
    (is (= ["/sf/a.sf2" "[C4/4] ^{ !i:40 }"] (tried "[c] \"viol\" try-soundfonts")))
    (is (= ["/sf/a.sf2" "[C4/4] ^{ !i:0 }"] (tried "[c] \"acousticGrand\" try-soundfonts")) "program 0's General MIDI name"))
  (testing "a number and a pick together, music or not"
    (is (= ["/sf/b.sf2" "[C4/4] ^{ !i:61 }"] (tried "[c] 1 \"brass\" try-soundfonts")))
    (is (= 2 (count (tried "1 '[0 50] try-soundfonts"))) "soundfont 1's 48, the phrase"))
  (testing "refused"
    (with-redefs [engine/soundfonts   (constantly ["/sf/a.sf2" "/sf/b.sf2"])
                  gervill/instruments holds]
      (is (re-find #"no soundfont 7 -- list-soundfonts numbers them 0 to 1" (:error (run "7 try-soundfonts"))))
      (is (re-find #"no instrument named \"zzz\" in a soundfont" (:error (run "\"zzz\" try-soundfonts"))))
      (is (re-find #"no instrument in '\[100 110\] in soundfont 1" (:error (run "1 '[100 110] try-soundfonts")))))))
