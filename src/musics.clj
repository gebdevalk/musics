(ns musics
  "Central REPL entry point. Book -> Scores -> Composites."
  (:refer-clojure :exclude [find])
  (:require [input.reader.parser.music-parser :as p]
            [core.domain.music-domain :as d]
            [output.midi.midi-live :as live]
            [output.midi.engine :as engine]))

(defonce registry  (atom {}))
(defonce book      (atom []))
(defonce receiver  (atom nil))
(defonce ^:dynamic *nav* (atom nil))    ;; current navigation target

;; --- Registry ---

(defn register [composite]
  (when-let [id (:id composite)]
    (swap! registry assoc id composite)))

(defn find [id] (get @registry id))
(defn list-ids [] (keys @registry))

;; --- Book / Scores ---

(defn parse [text]
  (let [result (p/parse text)
        s      (:score result)]
    (swap! book conj s)
    (letfn [(walk [c]
              (register c)
              (when (d/composite? c)
                (doseq [ch (d/composite-children c)]
                  (walk ch))))]
      (walk s))
    (reset! *nav* s)
    result))

(defn scores [] @book)
(defn current-score [] @*nav*)
(defn nth-score [n] (get @book n))
(defn score-count [] (count @book))

;; --- Navigation ---

(defn- resolve-target
  "Resolve a single navigation argument to a composite."
  [x]
  (cond
    (integer? x)      (get @book x)
    (or (string? x) (keyword? x)) (get @registry x)
    (d/composite? x)  x
    (sequential? x)   (reduce (fn [c idx]
                                (when (d/composite? c)
                                  (get (vec (d/composite-children c)) idx)))
                              (resolve-target (first x))
                              (rest x))
    :else (throw (ex-info (str "Cannot navigate to: " x) {:arg x}))))

(defn nav
  "Navigate into a composite. (nav) returns current target.
   (nav 0) first score. (nav 0 0) first child of first score.
   (nav 0 0 2) third child of that. (nav \"id\") by registry id."
  ([] @*nav*)
  ([x] (reset! *nav* (resolve-target x)))
  ([x y & more] (nav (vec (cons x (cons y more))))))

(defn children
  "Return children of current nav target (or given composite)."
  ([] (children @*nav*))
  ([c] (when (d/composite? c) (d/composite-children c))))

(defn leaves
  "Return leaf children of current nav target (or given composite)."
  ([] (leaves @*nav*))
  ([c] (when (d/composite? c) (filter d/leaf? (d/composite-children c)))))

;; --- MIDI ---

(defn connect []
  (reset! receiver (live/open-receiver))
  (println "[musics] Connected."))

(defn disconnect []
  (reset! receiver nil)
  (println "[musics] Disconnected."))

(defn play
  "Play the current nav target (or the given composite) through MIDI.
   Requires a connected receiver: (connect) first.
   (play)         — plays current nav target
   (play score)   — plays a specific composite
   (play score :channel 1) — plays on a specific channel"
  ([] (play @*nav*))
  ([score & {:keys [channel] :or {channel 0}}]
   (if-let [rcv @receiver]
     (do (println "[musics] Playing...")
         (engine/play-live rcv score :channel channel)
         (println "[musics] Done."))
     (println "[musics] Not connected. Run (connect) first."))))

(defn collect
  "Collect MIDI notes from the current nav target (offline, no playback).
   Returns [notes final-state]."
  ([] (collect @*nav*))
  ([score] (engine/collect-notes score)))

(defn play-note
  ([pitch] (play-note 0 pitch 80))
  ([channel pitch velocity]
   (when-let [rcv @receiver]
     (live/note-on rcv channel pitch velocity))
   pitch))

(defn stop-note
  ([pitch] (stop-note 0 pitch))
  ([channel pitch]
   (when-let [rcv @receiver]
     (live/note-off rcv channel pitch))))

(defn all-notes-off []
  (when-let [rcv @receiver]
    (doseq [ch (range 16)]
      (live/all-notes-off rcv ch))))

;; --- Inspection ---

(defn inspect
  "Print book contents and current nav target."
  []
  (println "Book:" (count @book) "score(s)")
  (doseq [i (range (count @book))]
    (let [s (get @book i)]
      (println (str "  [" i "] " (:id s) " "
                    (d/composite-count s) " children"))))
  (println "Nav:" (when-let [n @*nav*]
                     (str (:id n) " (" (:type n) ")"))))

(defn reset []
  (reset! book [])
  (reset! registry {})
  (reset! *nav* nil)
  (disconnect)
  (println "[musics] Reset."))