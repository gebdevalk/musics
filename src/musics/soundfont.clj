(ns musics.soundfont
  "mu's soundfont words as Clojure functions: the .sf2 files Gervill
   plays with, General MIDI's groups of instruments, and trying
   soundfonts' instruments one by one -- registered into mu's soundfont
   namespace by musics.mu.library. The audition is musics.mu.feedback's."
  (:require [clojure.string :as str]
            [musics.common.music-data :as data]
            [musics.engine :as engine]
            [musics.midi.gervill :as gervill]
            [musics.mu.feedback :as feedback]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

(defn soundfonts!
  "The .sf2 files soundfont takes by number"
  []
  (doseq [[i f] (map-indexed vector (engine/soundfonts))] (println (str i "> " f))))

(defn- font-name [path]
  (str/replace (.getName (java.io.File. ^String path)) #"(?i)\.sf2$" ""))

(defn list-soundfonts!
  "Each soundfont's number (what soundfont takes) and name"
  []
  (let [fs (engine/soundfonts)
        w  (count (str (dec (count fs))))]
    (doseq [[i f] (map-indexed vector fs)]
      (println (format (str "%" w "d  %s") i (font-name f))))))

(defn list-groups!
  "General MIDI's groups of eight instruments: number, name, programs"
  []
  (let [w (apply max (map count data/gm-groups))]
    (doseq [[i g] (map-indexed vector data/gm-groups)]
      (println (format (str "%2d  %-" w "s  %3d-%d") i g (* 8 i) (+ 7 (* 8 i)))))))

(defn soundfont!
  "Gervill with that soundfont, or the n-th of soundfonts; what plays goes on"
  [file-or-n]
  (when-not (or (string? file-or-n) (integer? file-or-n))
    (fail "needs a file in quotes or a number from soundfonts, got" (p/show file-or-n)))
  (println (engine/soundfont! file-or-n)))

;; -- trying them ------------------------------------------------------------

(def phrase
  "What try-soundfonts plays when it is given no music: a line up two
   octaves and a chord."
  "[C3/8 G3 C4 E4 G4 C5 <C4 E4 G4>2]")

(defn- data? [x] (and (v/material? x) (= :DATA (mt/kind x))))

(defn- music? [x]
  (and (or (v/material? x) (mt/leaf? x) (mt/endless? x)) (not (data? x))))

(defn- pair
  "[lo hi] of a data pair of programs '[56 63], else nil."
  [x]
  (when (data? x)
    (let [xs (vec (mt/elements x))]
      (when (and (= 2 (count xs)) (every? integer? xs)) (sort xs)))))

(defn- pick? [x] (or (string? x) (some? (pair x))))

(defn- squash [s] (str/replace (str/lower-case s) #"[-_ ]" ""))

(defn- groups
  "The numbers of the groups whose name starts with text."
  [text]
  (keep-indexed (fn [i g] (when (str/starts-with? (squash g) (squash text)) i)) data/gm-groups))

(defn- picker
  "A test of [program name] for pick: programs lo to hi for a pair; the
   groups a text starts the name of, else the instruments whose own or
   General MIDI name holds it."
  [pick]
  (if-let [[lo hi] (pair pick)]
    (fn [program _] (<= lo program hi))
    (if-let [gs (seq (groups pick))]
      (let [gs (set gs)] (fn [program _] (contains? gs (quot program 8))))
      (let [t (squash pick)]
        (fn [program name]
          (or (str/includes? (squash name) t)
              (some-> (data/program->name program) clojure.core/name squash (str/includes? t))))))))

(defn try-soundfonts!
  "Play music through every instrument of every soundfont in turn, in
   the background, as an audition: with a number only that soundfont's,
   with a pair only programs '[lo hi], with a text only a group's
   (\"brass\", list-groups), else the instruments it names (\"horn\").
   Without music, a phrase of its own. Alt+L the next, Alt+J back, Alt+K
   again, a rating records the soundfont, stop (Alt+.) stops; n
   soundfont keeps one"
  {:arglists '([] [x] [n] [pick] [x n] [x pick] [n pick] [x n pick])
   :mu {:optional {:x music? :n integer? :pick pick?}}}
  [& args]
  (let [x     (or (first (filter music? args)) (mt/read-text phrase))
        n     (first (filter integer? args))
        pick  (first (filter pick? args))
        all   (vec (engine/soundfonts))
        fonts (cond (empty? all) (fail "no .sf2 file in" (str/join " or " gervill/soundfont-dirs))
                    (nil? n)     (map-indexed vector all)
                    (get all n)  [[n (all n)]]
                    :else        (fail "no soundfont" n "-- list-soundfonts numbers them 0 to" (dec (count all))))]
    (mt/cancel-staged!)
    (if-let [found (feedback/start-soundfont-trial! x fonts (if pick (picker pick) (constantly true)))]
      (println found (if (= 1 found) "instrument" "instruments"))
      (fail "no instrument" (if (string? pick) (str "named " (pr-str pick)) (str "in " (p/show pick)))
            (if n (str "in soundfont " n) "in a soundfont")
            "-- list-groups names the groups"))))
