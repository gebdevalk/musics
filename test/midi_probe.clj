(ns midi-probe
  (:import [javax.sound.midi MidiSystem]))

(defn -main [& _]
  (println "=== MIDI Devices ===")
  (let [infos (MidiSystem/getMidiDeviceInfo)]
    (doseq [info infos]
      (let [dev (MidiSystem/getMidiDevice info)]
        (println (str "  " (.getName info)))
        (println (str "    desc: " (.getDescription info)))
        (println (str "    vendor: " (.getVendor info) " v" (.getVersion info)))
        (println (str "    max receivers: " (.getMaxReceivers dev)))
        (println (str "    max transmitters: " (.getMaxTransmitters dev)))
        (println (str "    class: " (.getName (class dev)))))))

  (println "\n=== Default Synthesizer ===")
  (let [synth (MidiSystem/getSynthesizer)]
    (println "  Name:" (.getName (.getDeviceInfo synth)))
    (.open synth)
    (println "  Opened OK")
    (println "  Channels:" (count (.getChannels synth)))
    (println "  Available instruments:" (count (.getAvailableInstruments synth)))
    (println "  Loaded instruments:" (count (.getLoadedInstruments synth)))
    (println "  Default soundbank:" (.getName (.getDefaultSoundbank synth)))
    (.close synth)
    (println "  Closed OK"))

  (println "\n=== Done ==="))
