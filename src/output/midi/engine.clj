(ns output.midi.engine
  "Tree walker: traverse score composites, resolve leaves, send MIDI.
   Pipeline: Score → walk tree → resolve each Leaf → MidiNote → MIDI receiver"
  (:require [core.domain.music-domain :as d]
            [output.midi.midi-output :as mo]
            [output.midi.midi-live :as live]
            [input.reader.ornaments :as ornaments]
            [common.elements.music-elements :as el]))

(defrecord WalkState [time channel tempo sounding])

(defn initial-state [channel]
  (->WalkState 0 channel (el/tempo 4 120) #{}))

;; Live sender: sends notes immediately via MIDI receiver
(defn live-sender
  [rcv & {:keys [start-ms] :or {start-ms (System/currentTimeMillis)}}]
  (let [start-time (atom start-ms)]
    (fn send-note [state note]
      (let [onset-ms (+ @start-time (long (* 1000 (double (:time state)))))
            dur-ms   (long (* 1000 (double (:duration-played note))))
            now      (System/currentTimeMillis)
            wait     (- onset-ms now)]
        (when (pos? wait) (Thread/sleep wait))
        (when-not (contains? (:sounding state) (:pitches note))
          (doseq [p (:pitches note)]
            (live/note-on rcv (:channel state) p (:velocity note)))
          (.start (Thread. (fn []
                            (Thread/sleep dur-ms)
                            (doseq [p (:pitches note)]
                              (live/note-off rcv (:channel state) p))))))
        (if (:tied note)
          (update state :sounding conj (:pitches note))
          (update state :sounding disj (:pitches note)))))))

;; Core walker
(declare walk-part)

(defn- walk-children [state children handler]
  (reduce (fn [st child] (walk-part st child handler)) state children))


(defn- resolve-leaf [state leaf handler]
  (let [ctx      (:context leaf)
        time     (double (:time state))
        tempo    (or (when-let [tv (d/ctx-value ctx :tempo time)]
                       (if (number? tv) (el/tempo 4 (int tv)) tv))
                     (:tempo state))
        expanded (ornaments/expand leaf)]
    (reduce (fn [st sub-leaf]
              (let [note (mo/render-leaf sub-leaf time tempo (:channel st))]
                (handler (update st :time + (:duration leaf)) note)))
            state expanded)))

(defn- resolve-rest [state rest _handler]
  (update state :time + (:duration rest)))

(defn- walk-part [state part handler]
  (cond
    (d/leaf? part)    (resolve-leaf state part handler)
    (d/rest? part)    (resolve-rest state part handler)
    (d/drum? part)
    (let [note (mo/render-drum part (double (:time state)) (:tempo state))]
      (handler state note))
    (d/composite? part)
    (case (:type part)
      (:SEQ :LIST :ALGO :DATA :QUOTE)
      (walk-children state (d/composite-children part) handler)
      :PAR
      (let [children (d/composite-children part)
            t0       (:time state)]
        (let [states (mapv (fn [ch] (walk-part (assoc state :time t0) ch handler))
                           children)]
          (assoc state :time (apply max (map :time states)))))
      (walk-children state (d/composite-children part) handler))
    :else state))

;; Public API
(defn walk [score handler & {:keys [channel] :or {channel 0}}]
  (walk-part (initial-state channel) score handler))

(defn collect-notes [score & {:keys [channel] :or {channel 0}}]
  (let [notes-atom (atom [])]
    (walk score (fn [st note] (swap! notes-atom conj note) st) :channel channel)
    @notes-atom))

(defn play-live [rcv score & {:keys [channel] :or {channel 0}}]
  (let [sender (live-sender rcv)]
    (walk score sender :channel channel)))

(comment
  (require '[input.reader.parser.music-parser :as p])
  (def notes (collect-notes (:score (p/parse "!mf c4 d4 e4 r4 f4"))))
  (println (count notes) "notes"))
