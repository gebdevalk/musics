(ns musics.rate
  "mu's rate words as Clojure functions (doc/md/feedback.md): rate what
   was heard, audition a line of sounds one by one, read the ratings
   back -- registered into mu's rate namespace by musics.mu.library. The
   audition and the rating itself are musics.mu.feedback's (the prompt's
   Alt keys use them too); the ratings are musics.feedback's."
  (:require [clojure.string :as str]
            [musics.engine :as engine]
            [musics.feedback :as fb]
            [musics.mu.eval :as e]
            [musics.mu.feedback :as feedback]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]))

(defn- fail [& parts] (throw (ex-info (str/join " " parts) {})))

(defn like!    "Rate what is heard (the audition's item, else the sound last played) 1; Alt+l" [] (feedback/rate-current! 1))
(defn meh!     "Rate it 0, neutral; Alt+k" [] (feedback/rate-current! 0))
(defn dislike! "Rate it -1; Alt+j" [] (feedback/rate-current! -1))
(defn again!   "Play it again; Alt+K" [] (feedback/again!))
(defn next!    "The audition's next item; Alt+L" [] (feedback/next!))
(defn prev!    "The audition's previous item; Alt+J" [] (feedback/back!))

(defn audition!
  "Play its sounds one by one, in the background, to rate each; stop (Alt+.) stops"
  [line]
  (let [items (filterv feedback/sound? (mt/elements line))]
    (when (empty? items) (fail "needs a line of notes, chords or drums, got" (p/show line)))
    (feedback/start-audition! items)))

(defn step!
  "Auditions wait for a rating or next, or move on by themselves again"
  []
  (println (if (swap! feedback/auto? not) "auditions move on by themselves" "auditions wait for a rating or next")))

(defn gap!
  "The silence after each sound of an audition before it moves on by itself (150 by default)"
  [ms]
  (when-not (and (number? ms) (<= 0 ms)) (fail "takes milliseconds, 0 or more"))
  (reset! feedback/gap-ms (long ms)))

(defonce ^:private ask-agent (agent nil :error-mode :continue))

(defn- ask-fn [_top]
  (when (and (nil? @feedback/audition) (feedback/sound? @mt/last-played))
    (send-off ask-agent
              (fn [_] (@e/print-above (str "rate " (p/show @mt/last-played) "?  Alt+j dislike · Alt+k meh · Alt+l like")) nil))))

(defn ask!
  "Ask for a rating when a sound played ends, or stop asking"
  []
  (if @engine/finished
    (do (reset! engine/finished nil) (println "ask off"))
    (do (reset! engine/finished ask-fn) (println "ask on"))))

(defn taste!
  "The ratings: per sound, then per instrument or drum, best first (mean, count)"
  []
  (let [rs (fb/ratings)]
    (if (empty? rs)
      (println "nothing rated yet")
      (do (println "sounds")
          (doseq [l (fb/table (map (fn [[w v]] [(feedback/describe w) v]) (fb/by-sound rs)))] (println " " l))
          (println "instruments and drums")
          (doseq [l (fb/table (map (fn [[part v]] [(fb/part-label part feedback/part-name) v]) (fb/by-part rs)))]
            (println " " l))))))

(defn- sounds-line [pred]
  (let [ws (fb/sounds-where pred)]
    (when (empty? ws) (fail "no sound rated so far"))
    (mt/read-text (str "[" (str/join " " ws) "]"))))

(defn liked    "The sounds rated above zero on the whole, best first" [] (sounds-line pos?))
(defn disliked "The sounds rated below zero on the whole, worst last" [] (sounds-line neg?))
