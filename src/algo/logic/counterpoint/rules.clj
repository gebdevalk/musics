(ns algo.logic.counterpoint.rules
  "Jeppesen's rules for species counterpoint, as checks on a score
   being built (see doc/counterpoint.md, \"The rules\").

   The score: a context and a state.
     ctx {:parts [{:name :kind :range :cands :cantus?} ...]  top to bottom
          :n bars, :nv voices, :mode, :final (a note), :lt leading-tone pc}
     st  {part [event ...]}
     event {:n note-or-nil :on t :len l :bar b :tied? bool
            :dis #{figure ...} :after-cambiata bool}
   Time is counted in eighths, 8 to a bar; a cantus note fills a bar.
   :kind is 1-5 for an added voice, :cantus for the cantus.

   Hard rules (`violations`) run as each event is placed, against what
   is already decided: the search prunes on them, and `check` reports
   them. A dissonance is judged as a figure of the voice that makes it
   (passing tone, lower neighbour, nota cambiata, suspension): its
   approach is checked when it is placed, its departure when the next
   note of that voice is. Soft rules (`penalties`) score a finished
   score: Jeppesen's preferences."
  (:require [algo.logic.counterpoint.intervals :as iv]))

;; ---------------------------------------------------------------------------
;; Reading the score
;; ---------------------------------------------------------------------------

(defn sounding
  "The event of part p sounding at time t, or nil."
  [st p t]
  (let [evs (get st p)]
    (loop [i (dec (count evs))]
      (when (>= i 0)
        (let [e (nth evs i)]
          (cond (<= (:on e) t (dec (+ (:on e) (:len e)))) e
                (< (+ (:on e) (:len e)) t) nil
                :else (recur (dec i))))))))

(defn note-at [st p t] (:n (sounding st p t)))

(defn attack? [e] (and (:n e) (not (:tied? e))))

(defn last-attack
  "The last event of p that struck a note (a tie's start), or nil."
  [st p]
  (let [evs (get st p)]
    (loop [i (dec (count evs))]
      (when (>= i 0)
        (let [e (nth evs i)] (if (attack? e) e (recur (dec i))))))))

(defn attacks [st p] (filterv attack? (get st p)))

(defn- kind [ctx p] (get-in ctx [:parts p :kind]))
(defn cantus? [ctx p] (= :cantus (kind ctx p)))

(defn- resting?
  "Part p rests at t: a rest event, or (not placed yet) the half rest a
   2nd/4th/5th-species voice opens with."
  [ctx st p t]
  (if-let [e (sounding st p t)]
    (nil? (:n e))
    (and (< t 4) (contains? #{2 4 5} (kind ctx p)))))

(defn- bass-at
  "The lowest part sounding at t (a part not placed yet counts as
   sounding: voices never cross, so it will be below the ones above)."
  [ctx st t]
  (first (remove #(resting? ctx st % t) (range (dec (:nv ctx)) -1 -1))))

(defn- consonant-pair?
  "Consonant between parts p and q at t: over the bass the fourth is a
   dissonance, between upper voices it is not."
  [ctx st t p a q b]
  (let [bass (bass-at ctx st t)]
    (if (or (= p bass) (= q bass) (= 2 (:nv ctx)))
      (iv/consonant? a b)
      (iv/upper-consonant? a b))))

(defn- prev-sonority-time
  "The last time before t at which p or q struck a note."
  [st p q t]
  (let [last-on (fn [part] (some #(when (and (attack? %) (< (:on %) t)) (:on %)) (rseq (get st part []))))
        a (last-on p) b (last-on q)]
    (when (or a b) (max (or a -1) (or b -1)))))

(defn- turning-point
  "Where the line in `notes` last changed direction, walking back from
   its last note (its first note when it never did)."
  [notes]
  (let [n (count notes)]
    (if (< n 2)
      (first notes)
      (let [dir (compare (:m (peek notes)) (:m (nth notes (- n 2))))]
        (loop [i (- n 2)]
          (if (and (pos? i) (= dir (compare (:m (nth notes i)) (:m (nth notes (dec i))))))
            (recur (dec i))
            (nth notes i)))))))

(def ^:private bad-outline #{:A4 :d5 :m7 :M7 :other})

;; ---------------------------------------------------------------------------
;; Dissonance figures
;; ---------------------------------------------------------------------------

(defn- approach-figures
  "The figures dissonant attack e of part p may be, from its position,
   length and approach; #{} = it can't be a dissonance."
  [st p e]
  (let [a1  (last-attack st p)
        pos (mod (:on e) 8)
        len (:len e)
        s   (when (and a1 (:n e)) (iv/steps (:n a1) (:n e)))
        from-consonance? (and a1 (not (:dis a1)))]
    (if-not (and from-consonance? (= 1 (abs s)))
      #{}
      (cond
        (>= len 6) #{}
        (= len 4)  (if (= pos 4) #{:passing} #{})
        (= len 2)  (case (long pos)
                     0 #{}
                     4 (if (neg? s) #{:passing} #{})             ; accented passing: descending
                     (cond-> #{:passing}
                       (neg? s)   (conj :neighbour)
                       (and (neg? s) (= pos 2)) (conj :cambiata)))
        (= len 1)  (cond-> #{:passing} (neg? s) (conj :neighbour))
        :else      #{}))))

(defn- suspension-ok?
  "A held (tied) note h of part p, dissonant with note x of part q:
   above the other voice a 7th, 4th or 9th; below it a 2nd or 9th."
  [p h q x]
  (let [{:keys [quality octaves]} (iv/interval (if (< p q) x h) (if (< p q) h x))]
    (if (< p q)
      (or (contains? #{:m7 :M7 :P4} quality)
          (and (pos? octaves) (contains? #{:m2 :M2} quality)))
      (contains? #{:m2 :M2} quality))))

(defn- departure-ok?
  "Whether x, the note after dissonance d (figures (:dis d)) in a
   voice, completes one of its figures; a1 is the note before d."
  [a1 d x]
  (let [s1 (iv/steps (:n a1) (:n d))
        s2 (iv/steps (:n d) (:n x))]
    (some (fn [fig]
            (case fig
              :passing    (and (= 1 (abs s2)) (= (Long/signum (long s1)) (Long/signum (long s2))))
              :neighbour  (= (:m (:n x)) (:m (:n a1)))
              :cambiata   (= -2 s2)
              ;; down a step; that it lands on a consonance is checked in
              ;; violations, where x's dissonance is known
              :suspension (= -1 s2)))
          (:dis d))))

;; ---------------------------------------------------------------------------
;; The hard rules
;; ---------------------------------------------------------------------------

(defn- v [rule e & {:as more}] (merge {:rule rule :bar (inc (:bar e)) :on (:on e)} more))

(defn melodic-violations
  "Rules of a single line, for attack e of added part p."
  [ctx st p e]
  (let [prev  (attacks st p)
        a1    (peek prev)
        a0    (when (> (count prev) 1) (nth prev (- (count prev) 2)))
        am1   (when (> (count prev) 2) (nth prev (- (count prev) 3)))
        n     (:n e)
        last? (= (:bar e) (dec (:n ctx)))
        sgn   #(Long/signum (long %))]
    (remove nil?
      [(when (and a1 (not (iv/melodic? (:n a1) n)))
         (v :melodic-intervals e :interval (iv/quality (:n a1) n)))
       ;; a note repeated: only whole against whole, and never three
       (when (and a1 (iv/same? (:n a1) n)
                  (not (and (= 8 (:len e) (:len a1)) (not (and a0 (iv/same? (:n a0) n))))))
         (v :repetition e))
       (when (and a0 a1 (>= (abs (long (iv/steps (:n a0) (:n a1)))) 4)
                  (not (and (iv/step? (:n a1) n)
                            (not= (sgn (iv/steps (:n a0) (:n a1))) (sgn (iv/steps (:n a1) n))))))
         (v :leap-recovery e))
       (when (and a0 a1 (iv/leap? (:n a0) (:n a1)) (iv/leap? (:n a1) n)
                  (= (sgn (iv/steps (:n a0) (:n a1))) (sgn (iv/steps (:n a1) n))))
         (let [s1 (abs (long (iv/steps (:n a0) (:n a1))))
               s2 (abs (long (iv/steps (:n a1) n)))
               up? (pos? (iv/steps (:n a0) (:n a1)))
               third-leap? (and am1 (iv/leap? (:n am1) (:n a0))
                                (= (sgn (iv/steps (:n am1) (:n a0))) (sgn (iv/steps (:n a0) (:n a1)))))]
           (when (or third-leap?
                     (> (+ s1 s2) 7)
                     (not (contains? #{:P5 :P1 :m6 :M6} (iv/quality (:n a0) n)))
                     (if up? (< s1 s2) (> s1 s2)))
             (v :consecutive-leaps e))))
       (when (and a0 a1 (not= (sgn (iv/steps (:n a0) (:n a1))) (sgn (iv/steps (:n a1) n)))
                  (not (zero? (iv/steps (:n a1) n))))
         (let [t (turning-point (mapv :n prev))]
           (when (contains? bad-outline (iv/quality t (:n a1)))
             (v :outlined-dissonance e :span [(:m t) (:m (:n a1))]))))
       (when last?
         (let [t (turning-point (conj (mapv :n prev) n))]
           (when (contains? bad-outline (iv/quality t n))
             (v :outlined-dissonance e :span [(:m t) (:m n)]))))
       (let [ds (map :d (conj (mapv :n prev) n))]
         (when (> (- (apply max ds) (apply min ds)) 9) (v :span e)))
       (when (and (= :cadence (:ficta n)) (not= (:bar e) (- (:n ctx) 2))) (v :ficta e :where :cadence))
       (when (and (= :final (:ficta n)) (not last?)) (v :ficta e :where :final))
       (when (and a1 (= :cadence (:ficta (:n a1)))
                  (not (and (= 1 (iv/steps (:n a1) n)) (= 1 (iv/semis (:n a1) n)))))
         (v :leading-tone e))
       (when (and a1 (or (= 1 (:len e)) (= 1 (:len a1))) (not (iv/step? (:n a1) n)))
         (v :eighths-stepwise e))
       (when (and a1 (:after-cambiata a1) (not= 1 (iv/steps (:n a1) n)))
         (v :cambiata e))
       ;; the last event may be a tied (suspended) note: it is the one
       ;; carrying the dissonance then, prepared by a1
       (let [d (peek (get st p)) before (if (:tied? d) a1 a0)]
         (when (and d (seq (:dis d)) (not (departure-ok? before d e)))
           (v :dissonance-left e :figures (:dis d))))])))

(defn- final-violations
  "The last note of added part p (the final bar) and the cadence."
  [ctx st p e]
  (when (and (= (:bar e) (dec (:n ctx))) (attack? e))
    (let [n      (:n e)
          fpc    (mod (:m (:final ctx)) 12)
          pc     (mod (:m n) 12)
          fifth  (mod (+ fpc 7) 12)
          third? (contains? #{(mod (+ fpc 4) 12)} pc)
          bass?  (= p (dec (:nv ctx)))
          a1     (last-attack st p)
          cf     (first (filter #(cantus? ctx %) (range (:nv ctx))))
          cf-prev (note-at st cf (- (:on e) 8))
          cf-last (note-at st cf (:on e))]
      (remove nil?
        [(when-not (if (or bass? (= 2 (:nv ctx)))
                     (= pc fpc)
                     (or (= pc fpc) (= pc fifth) third?))
           (v :final-chord e))
         (when (and a1 (= pc fpc) (= 1 (iv/steps (:n a1) n))
                    (not= :phrygian (:mode ctx)) (not= 1 (iv/semis (:n a1) n)))
           (v :leading-tone e))
         (when (and (= 2 (:nv ctx)) a1
                    (not (and (iv/step? (:n a1) n)
                              (or (not (iv/step? cf-prev cf-last))
                                  (not= (Long/signum (long (iv/steps cf-prev cf-last)))
                                        (Long/signum (long (iv/steps (:n a1) n))))))))
           (v :cadence e))]))))

(defn- first-violations
  "The first note of added part p, in two voices: a perfect
   consonance; below the cantus only the unison or octave."
  [ctx st p e]
  (when (and (= 2 (:nv ctx)) (attack? e) (nil? (last-attack st p)))
    (let [cf (first (filter #(cantus? ctx %) (range 2)))
          x  (note-at st cf (:on e))
          q  (iv/quality x (:n e))]
      (when (or (not (contains? #{:P1 :P5} q)) (and (> p cf) (= :P5 q)))
        [(v :first-interval e :interval q)]))))

(defn- pair-violations
  "Rules between e (part p) and every decided part, at e's onset:
   crossing, spacing, parallel and hidden perfects, the unison, cross
   relations, the doubled leading tone. Returns [violations marks]:
   marks are dissonances found, keyed by the part whose note makes it."
  [ctx st p e]
  (let [t (:on e) n (:n e) nv (:nv ctx)]
    (reduce
      (fn [[vs marks] q]
        (let [qe (sounding st q t) x (:n qe)]
          (if (or (= q p) (nil? x))
            [vs marks]
            (let [above? (< p q)
                  hi (if above? n x) lo (if above? x n)
                  tp' (prev-sonority-time st p q t)
                  pn' (when tp' (note-at st p tp'))
                  qn' (when tp' (note-at st q tp'))
                  moved? (when (and pn' qn') (or (not (iv/same? pn' n)) (not (iv/same? qn' x))))
                  q-now (iv/quality lo hi)
                  q-then (when (and pn' qn') (iv/quality (if above? qn' pn') (if above? pn' qn')))
                  outer? (= #{p q} #{0 (dec nv)})
                  upper-leaps? (let [u (if above? [pn' n] [qn' x])] (and (first u) (iv/leap? (first u) (second u))))
                  similar? (when (and pn' qn')
                             (let [dp (iv/semis pn' n) dq (iv/semis qn' x)]
                               (and (not (zero? dp)) (not (zero? dq)) (= (Long/signum dp) (Long/signum dq)))))
                  db-then (fn [dt] (let [a (note-at st p (- t dt)) b (note-at st q (- t dt))]
                                     (when (and a b) [a b (iv/quality (if above? b a) (if above? a b))])))
                  vs (cond-> vs
                       (< (:m hi) (:m lo)) (conj (v :voice-crossing e :with q))
                       (and (= 1 (abs (- p q)))
                            (> (- (:m hi) (:m lo)) (if (or (= 2 nv) (= (max p q) (dec nv))) 19 12)))
                       (conj (v :spacing e :with q))
                       (and moved? (iv/perfect q-now) (= q-now q-then))
                       (conj (v :parallel-perfects e :with q :interval q-now))
                       (and similar? (iv/perfect q-now) (not= q-now q-then)
                            (or (= 2 nv) (and outer? upper-leaps?)))
                       (conj (v :hidden-perfects e :with q :interval q-now))
                       (and (= 2 nv) (= :P1 q-now) (zero? (:octaves (iv/interval lo hi)))
                            (zero? (mod t 8)) (not (contains? #{0 (dec (:n ctx))} (:bar e))))
                       (conj (v :unison e :with q))
                       (and (= (mod (:d n) 7) (mod (:d x) 7)) (not= (mod (:m n) 12) (mod (:m x) 12)))
                       (conj (v :cross-relation e :with q))
                       (= (:lt ctx) (mod (:m n) 12) (mod (:m x) 12))
                       (conj (v :doubled-leading-tone e :with q))
                       (and (= (:bar e) (dec (:n ctx)))
                            (= (mod (+ 4 (:m (:final ctx))) 12) (mod (:m n) 12) (mod (:m x) 12)))
                       (conj (v :final-chord e :with q :doubled :third))
                       (and (attack? e) (zero? (mod t 8)) (contains? #{2 3 5} (kind ctx p)) (pos? (:bar e)))
                       (into (when-let [[a b qq] (db-then 8)]
                               (when (and (iv/perfect qq) (= qq q-now) (not (and (iv/same? a n) (iv/same? b x))))
                                 [(v :downbeat-parallels e :with q :interval q-now)])))
                       (and (attack? e) (= 4 (mod t 8)) (= 4 (kind ctx p)))
                       (into (when-let [[a b qq] (db-then 8)]
                               (when (and (iv/perfect qq) (= qq q-now) (not (and (iv/same? a n) (iv/same? b x))))
                                 [(v :syncopation-parallels e :with q :interval q-now)]))))
                  dissonant? (not (consonant-pair? ctx st t p n q x))
                  marks (if-not dissonant?
                          marks
                          (cond
                            (:tied? e)                     (update marks p (fnil conj []) [:suspension q x])
                            (and (= t (:on qe)) (:tied? qe)) (update marks q (fnil conj []) [:suspension p n])
                            (< (:on qe) t)                 (update marks p (fnil conj []) [:attack q x])
                            :else                          (update marks :both (fnil conj []) [q x])))]
              [vs marks]))))
      [[] {}]
      (range nv))))

(defn violations
  "The hard rules event e of part p breaks, placed into st, and the
   state with e placed (dissonances marked as figures to complete).
   With all? false it stops at the first group that breaks one (the
   search only needs to know whether any does)."
  ([ctx st p e] (violations ctx st p e true))
  ([ctx st p e all?]
  (if (or (cantus? ctx p) (nil? (:n e)))
    [[] (update st p (fnil conj []) e)]
    (let [[pvs marks] (pair-violations ctx st p e)]
     (if (and (not all?) (seq pvs))
      [pvs st]
     (let [mine (get marks p)
          figs (when (seq mine)
                 (if (:tied? e)
                   (let [prep (last-attack st p)]
                     (when (and prep (not (:dis prep)) (zero? (mod (:on e) 8))
                                (every? (fn [[_ q x]] (suspension-ok? p (:n e) q x)) mine))
                       #{:suspension}))
                   (not-empty (approach-figures st p e))))
          e' (cond-> e figs (assoc :dis figs))
          a1 (last-attack st p)
          e' (cond-> e' (and a1 (attack? e) (contains? (:dis a1) :cambiata) (= -2 (iv/steps (:n a1) (:n e))))
                (assoc :after-cambiata true))
          vs (concat pvs
                     (when (attack? e) (melodic-violations ctx st p e))
                     (final-violations ctx st p e)
                     (first-violations ctx st p e)
                     (when (and (seq mine) (empty? figs))
                       [(v :dissonance e :figure (if (:tied? e) :suspension :attack))])
                     (when (:both marks) [(v :dissonance e :figure :simultaneous)])
                     (when (and (attack? e) (seq mine) (contains? (:dis (peek (get st p))) :suspension))
                       [(v :dissonance-left e :figures #{:suspension} :lands-on :dissonance)])
                     (when (and (= 4 (kind ctx p)) (attack? e) (= 4 (mod (:on e) 8)) (seq mine))
                       [(v :preparation e)])
                     (when (and (seq (:dis e')) (= (:bar e) (dec (:n ctx))))
                       [(v :dissonance e :figure :final)]))
          ;; a decided voice suspended against e: its suspension must
          ;; also be a legal one against e
          vs (concat vs
                     (for [q (keys (dissoc marks p :both))
                           :let [qe (sounding st q (:on e))]
                           :when (not (and (contains? (:dis qe) :suspension)
                                           (suspension-ok? q (:n qe) p (:n e))))]
                       (v :dissonance e :figure :suspension :with q)))
          ;; a decided voice striking inside e (several florid voices):
          ;; its note must be consonant with e, or a figure already
          vs (concat vs
                     (for [q (range (:nv ctx)) :when (not= q p)
                           qa (get st q)
                           :when (and (attack? qa) (< (:on e) (:on qa) (+ (:on e) (:len e))))
                           :when (not (consonant-pair? ctx st (:on qa) p (:n e) q (:n qa)))
                           :when (empty? (:dis qa))]
                       (v :dissonance e :figure :against-later :with q)))]
      [(vec vs) (update st p (fnil conj []) e')]))))))

;; ---------------------------------------------------------------------------
;; The soft rules
;; ---------------------------------------------------------------------------

(defn penalties
  "{rule count} of Jeppesen's preferences a finished score misses."
  [ctx st]
  (let [added (remove #(cantus? ctx %) (range (:nv ctx)))
        inc+  (fn [m k n] (if (pos? n) (update m k (fnil + 0) n) m))
        lines (into {} (for [p added] [p (mapv :n (attacks st p))]))]
    (as-> {} m
      (reduce (fn [m p]
                (let [ns (lines p)
                      pairs (map vector ns (rest ns))
                      top (apply max (map :m ns))]
                  (-> m
                      (inc+ :leaps (count (filter (fn [[a b]] (iv/leap? a b)) pairs)))
                      (inc+ :big-leaps (count (filter (fn [[a b]] (>= (abs (iv/steps a b)) 3)) pairs)))
                      (inc+ :climax (dec (count (filter #(= top (:m %)) ns))))
                      (inc+ :unrecovered-fourth
                            (count (filter (fn [[a b c]] (and (= 3 (abs (iv/steps a b)))
                                                              (not (iv/step? b c))))
                                           (map vector ns (rest ns) (drop 2 ns))))))))
              m added)
      (reduce (fn [m p]
                (let [evs (get st p)]
                  (-> m
                      (inc+ :broken-ties (if (= 4 (kind ctx p))
                                           (count (filter #(and (attack? %) (zero? (mod (:on %) 8))
                                                                (< 0 (:bar %) (dec (:n ctx)))) evs))
                                           0)))))
              m added)
      (inc+ m :perfect-downbeats
            (count (for [b (range 1 (dec (:n ctx))) p (range (:nv ctx)) q (range (inc p) (:nv ctx))
                         :let [a (note-at st p (* 8 b)) c (note-at st q (* 8 b))]
                         :when (and a c (iv/perfect? c a))] 1)))
      (inc+ m :parallel-imperfects
            (count (for [p (range (:nv ctx)) q (range (inc p) (:nv ctx))
                         :let [ivs (for [b (range (:n ctx))
                                         :let [a (note-at st p (* 8 b)) c (note-at st q (* 8 b))]
                                         :when (and a c)]
                                     (iv/quality c a))]
                         run (partition-by identity ivs)
                         :when (and (iv/imperfect (first run)) (> (count run) 3))] 1))))))

(defn penalty
  "One number from penalties: lower is closer to Jeppesen's taste."
  [ctx st]
  (let [w {:leaps 1 :big-leaps 1 :climax 3 :unrecovered-fourth 2 :broken-ties 4
           :perfect-downbeats 1 :parallel-imperfects 2}]
    (reduce-kv (fn [s k n] (+ s (* n (get w k 1)))) 0 (penalties ctx st))))
