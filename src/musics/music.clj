(ns musics.music
  "Music as Clojure functions, which are mu's words too: a public
   function here is the mu word of its name (a trailing ! left off), its
   arguments taken from mu's stack, music first, the one value it gives
   pushed back -- a function whose name ends in ! is done for its effect
   and leaves nothing. Its :mu metadata says what else mu needs, when it
   needs more than the name, the docstring and the arglists: :ns, the mu
   namespace (music by default); :names, the signs it is written as too
   ([R], +); :optional, a test per argument name, for an arity whose
   extra arguments are taken only when the values in their places pass
   (musics.mu.library registers them, musics.mu.eval runs them)."
  (:require [musics.algo.harmonic.walking-bass :as walking-bass]
            [musics.common.music-data :as data]
            [musics.common.music-elements :as el]
            [musics.mu.material :as mt]
            [musics.mu.tools :as tools]
            [musics.mu.value :as v]))

(defn retrograde
  "The retrograde (the crab): x backwards, at any depth, ties turned
   round; exactly [R] -- [ R ] is a line with a bar's rest."
  {:mu {:names ["[R]"]}}
  [x]
  (tools/retrograde x))

(defn times
  "x played n times, as the text's repeat n [ x ]; data and streams
   written out n times."
  {:mu {:ns "tool" :names ["[*]"]}}
  [x n]
  (when-not (and (integer? n) (not (neg? n)))
    (throw (ex-info (str "needs a whole number of times, got " (pr-str n)) {:n n})))
  (tools/times-of "[*]" x n))

(defn- musical? [x] (or (mt/leaf? x) (v/material? x) (and (sequential? x) (not (string? x)))))

(defn up
  "Every pitch up n semitones, its key with it (!K: at the start of its
   line): [c e g] 7 +; a key moves up too"
  {:mu {:names ["+"]}}
  [x n]
  (cond
    (and (instance? musics.common.music_elements.Key x) (integer? n)) (el/transpose-key x n)
    (and (number? x) (number? n)) (throw (ex-info "is musical; on numbers write add" {}))
    (not (and (musical? x) (number? n)))
    (throw (ex-info (str "needs material and a number, got " (pr-str x) " and " (pr-str n)) {}))
    (not (integer? n)) (throw (ex-info (str "transposes by a whole number of semitones, got " n) {}))
    :else (mt/transposed x n)))

(defn walking-bass
  "A walking bass under a line of chords, made as it runs, new each
   time: a quarter a beat, the root on each chord's first, a step into
   the next, on an acoustic bass. With n, as long as the chords n times,
   each time walked anew, the last chord held; without, an ostinato: one
   line leading back into its own start, to loop with the chords."
  {:mu {:optional {:n integer?}}}
  ([chords] (walking-bass chords nil))
  ([chords n]
   (when (and n (< n 1)) (throw (ex-info (str "walks a number of times from 1, got " n) {})))
   (let [cs (vec (for [l (tools/played-leaves "walking-bass" chords)]
                   {:pitches (when (= :LEAF (:type l)) (:pitches l)) :duration (:duration l) :token (str (:id l))}))]
     (when-not (some :pitches cs) (throw (ex-info "there are no chords to walk under" {})))
     (mt/read-text (if n
                     (walking-bass/line->mus (vec (apply concat (repeat n cs))) {:end true})
                     (walking-bass/line->mus cs {:end false}))))))

(defn find-instrument!
  "List every instrument a name, short name or its start can mean, one a
   line: program, name, short name; \"\" lists all."
  [start]
  (let [start (if (keyword? start) (name start) (str start))
        rows  (data/instrument-matches start)]
    (if (seq rows)
      (doseq [{:keys [program name short]} rows] (println (format "%3d  %-22s %s" program name short)))
      (println (str "no instrument is called or starts with " (pr-str start))))))
