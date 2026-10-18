(ns musics.events
  "What `play` would perform, as data: a lazy, time-ordered sequence of
   events, computed only as far as it is read.

     (events repo form)              ; same Form as musics.engine/play
     (events repo form :algo name)

   Every event carries :t (seconds from the start), :beat (structural
   time, whole notes, exact) and :path (the voice, as play would name it:
   [:TAA], [:TAA :TAB], ...), plus by :kind

     :note :drum :rest  musics.domain.resolve/resolve-event's MidiEvent
                        (:pitches :velocity :dur-secs :dur-played
                        :program :cc :micro :humanization ...); a :note's
                        :channel is nil -- choosing one is the consumer's
                        job, as the engine's channel pool is play's
     :section           :id :type :phase (:enter / :exit)
     :bar               :n, the voice's new bar number, at the end of
                        the note that crossed into it
     :mark              :count (pipes) and :n (this voice's how-many-th)
     :voice             :phase (:start / :end) and :algo -- a voice (a
                        top-level one, or a branch it forks) beginning
                        and finishing

   the same boundaries musics.engine signals to musics.conductor, here
   as data for whoever reads the stream to act on.

   A voice's position (time, bars, marks, algorithm, material) is a
   plain state map threaded through the walk: a :SEQ continues with the state its
   last child left, a :PAR merges its branches' streams by :t and then
   continues where the branch that ends last stopped (the engine's
   continue-after-fork!). Nothing is walked ahead of what is read, so a
   :count :infinite repeat or an endless live tree is fine -- take what
   you need. Each voice's :algo runs where the engine runs it (a
   container's children, then each leaf), so a live musics.algo.tree voice's
   cursor moves as its events are read.

   Nothing here touches core.async, a clock, MIDI or *engine*. repo is
   read once, at the call -- a snapshot, until a :cutover hook swaps it
   (musics.engine/schedule-tx!)."
  (:require [musics.compose :as compose]
            [musics.domain :as d]
            [musics.domain.ornaments :as orn]
            [musics.domain.resolve :as r]
            [musics.wall :as wall]
            [musics.common.music-data :as data]
            [musics.common.music-elements :as el]))

;; A voice's state -- musics.engine's per-voice atoms, as one map:
;; {:repo :path :algo :t :beat :bar :bar-pos :marks :partial-pending?
;;  :cutover}
;; Every walk fn takes it plus a continuation k (state -> seq of what
;; comes after) and returns a lazy seq, so a long piece never grows the
;; stack and an endless one is never walked past what is read.

(defn- at [st kind] {:kind kind :t (:t st) :beat (:beat st) :path (:path st)})

(defn- boundary
  "st as it continues past boundary [id phase] -- the :cutover hook's
   chance to swap the voice's :repo (musics.engine/schedule-tx!). Called
   only once the boundary's own event is out, so a reader deciding when
   to read on (the engine's lookahead) decides when this runs."
  [st id phase]
  (if-let [f (:cutover st)] (f st id phase) st))

(defn- voice-event [st phase] (assoc (at st :voice) :phase phase :algo (:algo st)))

(defn- branch
  "One voice's stream: a :voice :start, its walk (walk st k), a :voice
   :end, then the ::end sentinel merge-branches continues from."
  [walk st]
  (cons (voice-event st :start)
        (lazy-seq (walk st (fn [st'] (list (voice-event st' :end) {:kind ::end :t (:t st') :state st'}))))))

(defn- apply-wall
  "The voice's algorithm over nodes -- musics.events/apply-wall."
  [st chain nodes]
  (wall/apply-algo (wall/algo (:algo st)) chain {:path (:path st) :algo (:algo st)} nodes))

(defn- advance-bar
  "[state' bar-events] -- musics.events/advance-bar, emitting a
   :bar event per crossing at the note's end (st is already past it);
   the crossings' boundaries are left to the caller."
  [st dur meter partial]
  (let [len (el/meter-bar-length meter)
        pos (cond-> (:bar-pos st)
              (and (:partial-pending? st) partial) (+ (- len partial))
              true (+ dur))]
    (loop [st (assoc st :partial-pending? false) pos pos evs []]
      (if (>= pos len)
        (let [st (update st :bar inc)]
          (recur st (- pos len) (conj evs (assoc (at st :bar) :n (:bar st)))))
        [(assoc st :bar-pos pos) evs]))))

(defn- swing-warp
  "Position x (whole notes from a bar's start) as swung: in each pair of
   units of a bar the first unit lasts ratio of the pair, the second the
   rest. Each bar starts its pairs anew; a unit left over at a bar's end
   (three eighths in 3/8) isn't swung."
  [x unit ratio bar]
  (let [pair  (* 2 unit)
        k     (Math/floor (/ x bar))
        y     (- x (* k bar))
        whole (* pair (Math/floor (/ bar pair)))
        n     (Math/floor (/ y pair))
        z     (- y (* n pair))]
    (+ (* k bar)
       (if (>= y whole)
         y
         (+ (* n pair) (if (<= z unit) (* z 2 ratio) (+ (* pair ratio) (* (- z unit) 2 (- 1 ratio)))))))))

(defn- swung
  "midi with its swing folded in: a note's start and end moved by
   swing-warp from where it sits in its bar (pos), so an off-beat starts
   later and the note before it lasts longer. The ratio is kept to the
   :swing quantity's range; a unit above 1 is a note value (8, 16.0: an
   eighth, a sixteenth)."
  [midi pos dur]
  (let [{lo :min hi :max} (data/quantity :swing)
        ratio (-> (double (or (:swing midi) 0.5)) (max lo) (min hi))
        unit  (let [u (or (:swing-unit midi) 1/8)] (if (> u 1) (/ 1 (rationalize u)) u))
        bar   (el/meter-bar-length (:meter midi))]
    (if (or (== ratio 0.5) (not (pos? dur)) (not (pos? unit)))
      midi
      (let [secs  (/ (:dur-secs midi) dur)
            start (- (swing-warp pos unit ratio bar) pos)
            end   (- (swing-warp (+ pos dur) unit ratio bar) (+ pos dur))]
        (-> midi
            (update :micro #(+ (or % 0.0) (* secs start)))
            (update :dur-played * (/ (+ dur (- end start)) dur)))))))

(defn- grooved
  "midi with its groove folded into :micro: laid back (+) or pushed (-)
   by the weakness of its place in the bar -- the meter's weight at a
   sixteenth's resolution -- up to a 32nd at 1.0, so the downbeat
   stays and a weak sixteenth moves furthest."
  [midi pos dur]
  (let [g (-> (double (or (:groove midi) 0.0)) (max -1.0) (min 1.0))]
    (if (or (zero? g) (not (pos? dur)))
      midi
      (let [meter (or (:meter midi) (el/make-meter 4 4))
            ws    (el/metric-weights meter 1/16)
            w     (if ws (nth ws (mod (long (Math/floor (* pos 16))) (count ws))) 0.0)
            secs  (/ (:dur-secs midi) dur)]
        (update midi :micro #(+ (or % 0.0) (* secs g (- 1.0 w) 1/32)))))))

(defn- felt
  "midi as heard: its swing and its groove folded into :micro and
   :dur-played. The voice's own time, its bars and signals stay
   straight."
  [midi pos dur]
  (-> midi (swung pos dur) (grooved pos dur)))

(defn- fire
  "One leaf/rest/drum: its event, the voice moved past it, its bars."
  [part chain st k]
  (if-let [midi (r/resolve-event {:part part :ctx-chain chain} nil (:t st) (:beat st))]
    (let [dur       (d/part-duration part)
          pos       (cond-> (:bar-pos st)
                      (and (:partial-pending? st) (:partial midi))
                      (+ (- (el/meter-bar-length (:meter midi)) (:partial midi))))
          [st' bars] (advance-bar (-> st (update :t + (:dur-secs midi)) (update :beat + dur))
                                  dur (:meter midi) (:partial midi))]
      (cons (merge (felt midi pos dur) (at st (cond (d/leaf? part) :note (and (d/drum? part) (not (:held part))) :drum :else :rest)))
            (lazy-seq (concat bars (lazy-seq (k (reduce #(boundary %1 (:n %2) :enter) st' bars)))))))
    (k st)))

(defn- fire-all [parts chain st k]
  (if-let [[p & more] (seq parts)]
    (fire p chain st #(fire-all more chain % k))
    (k st)))

(defn- merge-branches
  "The branches' streams (each ending in one ::end event carrying its
   final state), merged by :t -- ties go to the earlier branch -- then
   (k st) with st moved to where the branch that ends last stopped."
  [st streams k]
  (letfn [(step [ss last-out]
            (lazy-seq
             (let [ss (mapv seq ss)
                   i  (reduce (fn [best i]
                                (if (and (ss i) (or (nil? best) (< (:t (first (ss i))) (:t (first (ss best))))))
                                  i best))
                              nil (range (count ss)))]
               (cond
                 (nil? i)
                 (k (if last-out
                      (merge st (select-keys last-out [:t :beat :bar :bar-pos :marks :partial-pending?]))
                      st))

                 (= ::end (:kind (first (ss i))))
                 (let [out (:state (first (ss i)))]
                   (step (assoc ss i nil)
                         (if (or (nil? last-out) (> (:t out) (:t last-out))) out last-out)))

                 :else (cons (first (ss i)) (step (update ss i rest) last-out))))))]
    (step (vec streams) nil)))

(declare walk-node walk-form)

(defn- walk-children
  "nodes in turn; an override marker (a context written in the sequence)
   goes in front of the chain for everything after it."
  [nodes chain st k]
  (if-let [[n & more] (seq nodes)]
    (if-let [over (compose/override-of (:repo st) n)]
      (walk-children more (into [over] chain) st k)
      (walk-node n chain st #(walk-children more chain % k)))
    (k st)))

(defn- walk-seq
  "A sequence's children in the order they play (d/play-segments); a
   pass jumped back to re-enters the container's context where its
   first note is written."
  [part children outer chain st k]
  (let [kids (vec children)
        segs (d/play-segments kids)]
    (if (next segs)
      ((fn step [[[a b] & more] st]
         (if a
           (walk-children (subvec kids a b)
                          (if (zero? a)
                            chain
                            (compose/build-chain part outer (- (:beat st) (d/written-position (:repo st) kids a))))
                          st #(step more %))
           (k st)))
       segs st)
      (walk-children kids chain st k))))

(defn- walk-par
  "A :PAR container's children -- musics.events/walk-par."
  [children chain st k]
  (let [segs (compose/rank-segments compose/mean-pitch-rank children)]
    (merge-branches st
                    (map-indexed (fn [i child]
                                   (branch #(walk-node child chain %1 %2)
                                           (assoc st :path (conj (:path st) (segs i)))))
                                 children)
                    k)))

(defn- walk-iterator
  "musics.events/walk-iterator: source on every pass, each followed by
   its volta ending, LilyPond's rule (d/volta-endings, d/ending-for)."
  [iter chain st k]
  (let [{n :count :or {n 1}} (:params iter)
        infinite? (= n :infinite)
        ends      (if infinite? [] (d/volta-endings (:repo st) iter))
        chain     (compose/build-chain iter chain (:beat st))]
    (letfn [(pass [i st]
              (if (or infinite? (< i n))
                (walk-node (:source iter) chain st
                           (fn [st]
                             (if (seq ends)
                               (walk-node (nth ends (d/ending-for (count ends) n i)) chain st #(pass (inc i) %))
                               (pass (inc i) st))))
                (k st)))]
      (pass 0 st))))

(defn- walk-node
  "musics.events/walk-node."
  [part chain st k]
  (cond
    (d/leaf? part)
    (let [part (r/rekey part chain (:beat st))]
      (fire-all (mapcat #(orn/expand % chain) (apply-wall st chain [part])) chain st k))

    (or (d/rest? part) (d/drum? part))
    (fire-all (apply-wall st chain [part]) chain st k)

    (d/iterator? part)
    (walk-iterator part chain st k)

    (d/bar? part)
    (let [c  (:count part)
          st (update-in st [:marks c] (fnil inc 0))
          n  (get-in st [:marks c])]
      (cons (assoc (at st :mark) :count c :n n)
            (lazy-seq (k (boundary st [:mark c n] :enter)))))

    (d/container? part)
    (let [id      (:id part)
          section (fn [st phase] (assoc (at st :section) :id id :type (:type part) :phase phase))
          k'      (fn [st'] (cons (section st' :exit) (lazy-seq (k (boundary st' id :exit)))))]
      (cons (section st :enter)
            (lazy-seq (let [st       (boundary st id :enter)
                            outer    chain
                            chain    (compose/build-chain part chain (:beat st))
                            n        (get-in st [:entered id] 0)
                            st       (cond-> st (or (:rotate part) (:alternate part)) (assoc-in [:entered id] (inc n)))
                            children (apply-wall st chain (compose/play-order part (d/children (:repo st) part) n))]
                        (if (= :PAR (:type part))
                          (walk-par children chain st k')
                          (walk-seq part children outer chain st k'))))))

    :else (k st)))

;; ------------------------------------------------------------
;; Forms -- musics.events/walk-form and friends
;; ------------------------------------------------------------

(defn- walk-form-par
  "A #{}/(par ...) group: each branch its own voice, a branch's own tag
   winning over the group's (outer-algo), else the voice's algo carries on."
  [forms chain st k outer-algo]
  (let [resolved (mapv #(compose/resolve-form-tag % outer-algo) forms)
        segs     (compose/rank-segments #(compose/mean-pitch-rank (compose/form-pitch-source (:repo st) %))
                                        (map first resolved))]
    (merge-branches st
                    (map-indexed (fn [i [f a]]
                                   (branch #(walk-form f chain %1 %2)
                                           (cond-> (assoc st :path (conj (:path st) (segs i)))
                                             a (assoc :algo a))))
                                 resolved)
                    k)))

(defn- walk-forms [forms chain st k]
  (if-let [[f & more] (seq forms)]
    (walk-form f chain st #(walk-forms more chain % k))
    (k st)))

(defn- walk-form [form chain st k]
  (cond
    (keyword? form)
    (walk-node (get (:repo st) form) chain st k)

    (d/part? form)
    (walk-node form chain st k)

    (compose/tagged-form? form)
    (let [[inner name] (compose/split-tag form)]
      (if (compose/par-form? inner)
        (walk-form-par (seq inner) chain st k name)
        (walk-form inner chain (assoc st :algo name) #(k (assoc % :algo (:algo st))))))

    (or (set? form) (sequential? form))
    (let [[tag items]      (compose/form-tag+items form)
          [material chain] (compose/peel-group-contexts (:repo st) tag items chain)]
      (if (= tag :par)
        (walk-form-par material chain st k nil)
        (walk-forms material chain st k)))

    (nil? form)
    (throw (ex-info "events: don't know how to play nil -- expected a part id, a group, or material from sq"
                    {:form form}))

    :else (k st)))

(defn voice-events
  "One top-level voice's lazy stream -- form walked as the voice at path
   (default [:TAA]) with algo, from :t 0 -- every voice it forks
   included. cutover (st id phase) -> st', if given, sees every
   boundary as it is computed: a section's :enter/:exit (id its id), a
   bar (id its number, :enter), a mark (id [:mark count n], :enter)."
  [repo form & {:keys [path algo cutover] :or {path [:TAA]}}]
  (let [repo  (compose/live-repo repo)
        chain (if-let [root (:context (get repo :ROOT))] [root] [])
        st    {:repo repo :path path :algo algo :cutover cutover
               :t 0.0 :beat 0 :bar 1 :bar-pos 0 :marks {} :partial-pending? true}]
    (cons (voice-event st :start)
          (lazy-seq (walk-form form chain st (fn [st'] (list (voice-event st' :end))))))))

(defn events
  "The lazy, time-ordered events (play form) or (play form :algo name)
   would perform against repo (a map, or an atom such as
   (musics.repo/registry)) -- see the ns docstring."
  [repo form & {:keys [algo]}]
  (let [repo (compose/live-repo repo)]
    (merge-branches {:t 0.0}
                    (map (fn [id [f a]] (voice-events repo f :path [id] :algo a))
                         (compose/track-ids)
                         (compose/top-level-voices repo form algo))
                    (constantly nil))))
