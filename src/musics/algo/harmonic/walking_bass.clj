(ns musics.algo.harmonic.walking-bass
  "A walking bass under a line of chords: a quarter note a beat, the
   chord's root on its first beat, the last beat before the next chord
   an approach to that chord's root (a half step below or above, a
   step of the scale, or its fifth), and the beats between walking
   from the one towards the other on the chord's scale, chord tones
   preferred on the strong beats. Rests stay rests. Drawn from
   musics.algo.random, so a seed repeats a line.

   A chord is {:pitches [...] :duration d}, as a leaf holds them; its
   lowest pitch is the root, so an inversion (C4:M/E) walks
   from its bass note."
  (:require [clojure.string :as str]
            [musics.algo.random :as random]
            [musics.domain :as d]
            [musics.input.reader.leaf-parser :as lp]
            [musics.repo :as repo]))

(def defaults
  "The bass's range (E1 to G3, MIDI 28-55) and its beat."
  {:lo 28 :hi 55 :beat 1/4})

(defn- scale-of
  "The chord's scale, pitch classes above its root: its third, fifth
   and seventh pick the mode (mixolydian, major, dorian, locrian,
   whole-half, whole-tone), the chord's own tones always included."
  [rel]
  (let [has? (set rel)
        mode (cond (and (has? 3) (has? 6) (has? 9)) [0 2 3 5 6 8 9 11]
                   (has? 6)                         [0 1 3 5 6 8 10]
                   (has? 8)                         [0 2 4 6 8 10]
                   (has? 3)                         (if (has? 11) [0 2 3 5 7 9 11] [0 2 3 5 7 9 10])
                   :else                            (if (has? 11) [0 2 4 5 7 9 11] [0 2 4 5 7 9 10]))]
    (into (sorted-set) (concat mode rel))))

(defn- harmony
  "{:root pc :tones #{pc} :scale #{pc}} of a chord's pitches."
  [pitches]
  (let [root (mod (apply min pitches) 12)
        rel  (map #(mod (- % root) 12) pitches)]
    {:root  root
     :tones (set (map #(mod (+ root %) 12) rel))
     :scale (set (map #(mod (+ root %) 12) (scale-of rel)))}))

(defn- in-range [pcs lo hi] (filter #(pcs (mod % 12)) (range lo (inc hi))))

(defn- nearest
  "The pitch of class pc in lo..hi nearest to ref."
  [pc ref lo hi]
  (apply min-key #(abs (- % ref)) (in-range #{pc} lo hi)))

(defn- next-root
  "The next chord's root, pitch class pc: up or down from ref, at most a
   fifth, the way back towards the middle of lo..hi weighed up, so the
   line doesn't climb or sink away."
  [pc ref lo hi]
  (let [mid   (/ (+ lo hi) 2)
        all   (in-range #{pc} lo hi)
        close (filter #(<= (abs (- % ref)) 7) all)]
    (apply min-key #(+ (abs (- % ref)) (* 0.75 (abs (- % mid)))) (if (seq close) close all))))

(defn- approach
  "The last beat before target: a half step below or above it, the
   step of scale before it, or its fifth -- never prev again."
  [{:keys [scale]} target prev lo hi]
  (let [from-below? (< prev target)
        step  (first (sort-by #(abs (- % target))
                              (filter #(and (scale (mod % 12)) (not= % target)
                                            (if from-below? (< % target) (> % target)))
                                      (range (- target 3) (+ target 4)))))
        fifth (min-key #(abs (- % prev)) (- target 5) (+ target 7))
        opts  (->> [[(dec target) 3] [(inc target) 3] [step 2] [fifth 1]]
                   (filter (fn [[p _]] (and p (<= lo p hi) (<= 1 (abs (- p prev)) 7) (not= p target)))))]
    (if (seq opts)
      (random/weighted-choose (map first opts) (map second opts))
      (dec target))))

(defn- walk-between
  "m notes from root towards to: each near the straight way between
   them (or an arch away from root when the two lie close), a chord
   tone weighed up on a strong beat, no note twice in a row, no leap
   past a fifth -- the last one into to neither."
  [{:keys [tones scale]} root to m lo hi]
  (let [n   (inc m)
        dir (if (< root (quot (+ lo hi) 2)) 1 -1)]
    (loop [i 1 prev root acc []]
      (if (> i m)
        acc
        (let [t      (/ i n)
              ideal  (+ root (* t (- to root))
                        (if (<= (abs (- to root)) 2) (* dir 5 (Math/sin (* Math/PI t))) 0))
              strong (even? i)
              cands  (->> (in-range scale lo hi)
                          (filter #(and (not= % prev) (<= (abs (- % prev)) 7)
                                        (or (< i m) (<= 1 (abs (- % to)) 7)))))
              weight (fn [p] (/ (if (and strong (tones (mod p 12))) 3.0 1.0)
                                (Math/pow (inc (abs (- p ideal))) 2)))
              p      (if (seq cands)
                       (random/weighted-choose cands (map weight cands))
                       prev)]
          (recur (inc i) p (conj acc p)))))))

(defn walk
  "chords -> the walking bass under them, a vector of {:pitch :duration}
   (a rest {:rest d}). opts: :lo/:hi the range, :beat the walking note
   value (see defaults), :choruses the times through the chords (1),
   :start the pitch the bass begins near (lo + 12), :end (true) the last
   chord an ending, played once after the choruses, its root held.
   Without an end the line leads into :then -- a chord, or the very
   pitch to arrive at -- or, without one, back to the first chord, so it
   loops. Its metadata's :next is the pitch it leads into."
  ([chords] (walk chords {}))
  ([chords opts]
   (let [{:keys [lo hi beat choruses start end then]} (merge defaults {:choruses 1 :end true} opts)
         chords (vec chords)
         form   (if end (pop chords) chords)
         played (cond-> (vec (apply concat (repeat choruses form))) end (conj (peek chords)))
         last-i (dec (count played))
         harm   (mapv #(when (seq (:pitches %)) (harmony (:pitches %))) played)
         after  (fn [root]
                  (cond (number? then) then
                        then           (next-root (:root (harmony (:pitches then))) root lo hi)
                        :else          (next-root (:root (first (keep identity harm))) root lo hi)))
         target (fn [i root]
                  (if-let [h (some harm (range (inc i) (count played)))]
                    (next-root (:root h) root lo hi)
                    (after root)))]
     (loop [i 0 root (nearest (:root (first (keep identity harm))) (or start (+ lo 12)) lo hi) acc []]
       (if (= i (count played))
         (with-meta acc {:next root})
         (let [{:keys [duration]} (played i)
               h (harm i)]
           (if-not h
             (recur (inc i) root (conj acc {:rest duration}))
             (let [n      (if (and end (= i last-i)) 1 (max 1 (long (quot duration beat))))
                   left   (- duration (* (dec n) beat))
                   to     (target i root)
                   last-p (when (> n 1) (approach h to root lo hi))
                   middle (when (> n 2) (walk-between h root last-p (- n 2) lo hi))
                   ps     (concat [root] middle (when last-p [last-p]))
                   durs   (concat (repeat (dec n) beat) [left])]
               (recur (inc i) to
                      (into acc (map (fn [p dur] {:pitch p :duration dur}) ps durs)))))))))))

(defn- flat-chords
  "The chords and rests under node, in the order they're written (nested
   lines included, a repeat left out)."
  [reg node]
  (vec (mapcat #(cond (d/leaf? %)         [{:pitches (:pitches %) :duration (:duration %) :token (str (:id %))}]
                      (= :REST (:type %)) [{:pitches nil :duration (:duration %)}]
                      (d/container? %)    (flat-chords reg %)
                      :else               [])
               (d/children reg node))))

(defn chords-of
  "The chords and rests of the part id, in the order they're written
   (nested lines included), as walk takes them."
  [id]
  (let [reg @(repo/registry)] (flat-chords reg (get reg id))))

(defn form-of
  "The part id's form: {:chords [...]} for a line, or, for a part that
   is one repeat, {:count n :body [...] :endings [[...] ...]} -- its
   volta endings, none, one or several."
  [id]
  (let [reg  @(repo/registry)
        part (get reg id)
        reps (filter #(= :REPEAT (:type %)) (d/children reg part))
        outs (flat-chords reg part)]
    (if (and (= 1 (count reps)) (empty? outs))
      (let [{:keys [source params]} (first reps)
            alt  (:alternative params)
            ends (when alt (d/children reg alt))]
        {:count   (:count params)
         :body    (flat-chords reg source)
         :endings (cond (nil? alt)               []
                        (every? d/container? ends) (mapv #(flat-chords reg %) ends)
                        :else                    [(flat-chords reg alt)])})
      {:chords outs})))

(defn walk-repeat
  "The walking bass of a repeat (form-of): [body endings]. The body leads
   into the first ending; each ending begins where the body arrives, the
   last held (:end, true), the others leading back to the body's first
   note. Without endings the body loops. opts as walk's."
  [{:keys [body endings]} opts]
  (let [into-ending (some #(when (seq (:pitches %)) %) (first endings))
        b     (walk body (assoc opts :choruses 1 :end false :then into-ending))
        arr   (:next (meta b))
        first (:pitch (some #(when (:pitch %) %) b))
        k     (count endings)]
    [b (vec (map-indexed (fn [i e]
                           (walk e (assoc opts :choruses 1 :start arr :then first
                                          :end (and (= i (dec k)) (:end opts true)))))
                         endings))]))

(defn- duration-text [v]
  (let [d (/ 1 v)]
    (if (integer? d) (str d) (str "1*" (numerator v) "/" (denominator v)))))

(defn- notes-text
  "Walked notes as musics text, a bar line after each whole note's
   worth, pitches spelled with flats when flat? (else sharps), a
   duration only where it differs from the one before (last, a
   volatile, the text so far) -- notes inherit it, in the order they're
   written."
  [notes flat? last]
  (let [lens  (map #(or (:duration %) (:rest %)) notes)
        total (reduce + 0 lens)
        dur   (fn [d] (when (not= d @last) (vreset! last d) (duration-text d)))]
    (str/join " " (mapcat (fn [{:keys [pitch rest]} d at]
                            (cons (if rest
                                    (str "r" (or (dur d) (duration-text d)))
                                    (let [p (lp/spell-pitch pitch nil nil flat?)]
                                      (if-let [t (dur d)] (str p t) (subs p 0 (dec (count p))))))
                                  (when (and (integer? at) (< at total)) ["|"])))
                          notes lens (rest (reductions + 0 lens))))))

(defn flats?
  "Were chords written with flats and no sharps?"
  [chords]
  (let [t (str/join (keep :token chords))]
    (and (str/includes? t "&") (not (str/includes? t "#")))))

(defn line->mus
  "The walking bass under chords (walk's, e.g. a line's leaves) as a
   line of musics text, `[!i:acousticBass F2/4 ... ]`, spelled with
   flats when the chords were. opts as walk's."
  [chords opts]
  (str "[!i:acousticBass " (notes-text (walk chords opts) (flats? chords) (volatile! nil)) " ]"))

(defn ->mus
  "The walking bass under the chords of the part id as musics text, in
   the part's form -- a line, or a repeat with its volta endings
   (walk-repeat) -- named name (:bass) and played on an acoustic bass,
   ready for musics.core/parse; then (play #{id :bass}). opts as walk's."
  ([id] (->mus id {}))
  ([id opts]
   (let [{:keys [chords body endings] :as form} (form-of id)
         flat? (flats? (concat chords body (apply concat endings)))
         last  (volatile! nil)
         music (if chords
                 (notes-text (walk chords opts) flat? last)
                 (let [[b es] (walk-repeat form opts)
                       n      (if (= :infinite (:count form)) "~" (:count form))
                       body   (notes-text b flat? last)]
                   (str "repeat " n " [ " body " ]"
                        (when (seq es)
                          (str " volta [ " (str/join " " (mapv #(str "[ " (notes-text % flat? last) " ]") es)) " ]")))))]
     (str "[" (name (:name opts :bass)) ": !i:acousticBass " music " ]"))))
