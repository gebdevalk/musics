(ns musics.midi.gervill
  "Gervill, the JDK's own software synthesizer, as a MIDI output: no
   qsynth, VirMIDI or aconnect, and any .sf2 SoundFont loaded from the
   REPL (doc/setup.md, \"Gervill\"). Its Receiver is what musics.engine
   plays into, exactly as the VirMIDI port's is.

   Gervill's latency is set through com.sun.media.sound.AudioSynthesizer,
   which java.desktop doesn't export: project.clj's :jvm-opts carries
   --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.sun.media.sound AudioSynthesizer)
           (javax.sound.midi Instrument MidiChannel MidiSystem Synthesizer)
           (javax.sound.sampled AudioFormat AudioInputStream)))

(def soundfont-dirs
  "Where soundfonts searches, in order."
  ["/usr/share/sounds/sf2" (str (System/getProperty "user.home") "/Music/sf")])

(def default-soundfonts
  "Loaded by open when none is given: the first that exists. Without any,
   Gervill plays its own thin Emergency GM sound set."
  ["/usr/share/sounds/sf2/FluidR3_GM.sf2" "/usr/share/sounds/sf2/default-GM.sf2"])

(defn expand
  "path with a leading ~ as the home directory."
  [path]
  (str/replace-first (str path) #"^~(?=/|$)" (System/getProperty "user.home")))

(defn soundfonts
  "Every .sf2 file in soundfont-dirs (and the folders inside them),
   sorted by name within each directory."
  []
  (for [dir soundfont-dirs
        :let [d (io/file dir)]
        :when (.isDirectory d)
        f (sort-by #(str/lower-case (.getName ^java.io.File %)) (file-seq d))
        :when (and (.isFile ^java.io.File f) (str/ends-with? (str/lower-case (.getName ^java.io.File f)) ".sf2"))]
    (.getPath ^java.io.File f)))

(defn load-soundfont!
  "Replace the instruments synth plays with those of the .sf2 at path;
   each channel keeps its program, now from the new set. Returns the
   soundbank's name."
  [^Synthesizer synth path]
  (let [f (io/file (expand path))]
    (when-not (.isFile f) (throw (ex-info (str "no soundfont at " (.getPath f)) {:path path})))
    (let [sb (MidiSystem/getSoundbank f)]
      (doseq [i (.getLoadedInstruments synth)] (.unloadInstrument synth i))
      (.loadAllInstruments synth sb)
      (doseq [^MidiChannel ch (.getChannels synth) :when ch] (.programChange ch (.getProgram ch)))
      (.getName sb))))

(defn soundbank-name
  "The name of the soundbank synth's instruments come from."
  [^Synthesizer synth]
  (when-let [^Instrument i (first (.getLoadedInstruments synth))]
    (.getName (.getSoundbank i))))

(defn- info [latency-ms soundfont]
  {"latency" (long (* 1000 latency-ms)) "load default soundbank" (nil? soundfont)})

(defn- initial-soundfont [soundfont]
  (or soundfont (some #(when (.isFile (io/file %)) %) default-soundfonts)))

(defn open
  "A Gervill synthesizer playing to the speakers, latency-ms of buffer
   (default 30), with soundfont loaded (else the first of
   default-soundfonts). Its receiver: (.getReceiver synth)."
  [& {:keys [soundfont latency-ms] :or {latency-ms 30}}]
  (let [sf    (initial-soundfont soundfont)
        synth ^AudioSynthesizer (MidiSystem/getSynthesizer)]
    (.open synth nil (info latency-ms sf))
    (when sf (load-soundfont! synth sf))
    synth))

(defn open-stream
  "[synth stream]: a Gervill synthesizer rendering into an
   AudioInputStream (16-bit stereo, 44.1 kHz) instead of the speakers --
   to render or test without a sound device. The stream is read as
   time; nothing sounds until it is read."
  [& {:keys [soundfont]}]
  (let [sf     (initial-soundfont soundfont)
        synth  ^AudioSynthesizer (MidiSystem/getSynthesizer)
        stream (.openStream synth (AudioFormat. 44100 16 2 true false) (info 30 sf))]
    (when sf (load-soundfont! synth sf))
    [synth ^AudioInputStream stream]))

(defn close [^Synthesizer synth] (when synth (.close synth)))
