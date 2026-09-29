(ns core.events
  "What `play` would perform, as data: a lazy, time-ordered sequence of
   events, computed only as far as it is read.

     (events repo form)              ; same Form as core.async-engine/play
     (events repo form :algo name)

   Every event carries :t (seconds from the start), :beat (structural
   time, whole notes, exact) and :path (the voice, as play would name it:
   [:TAA], [:TAA :TAB], ...), plus by :kind

     :note :drum :rest  core.domain.resolve/resolve-event's MidiEvent
                        (:pitches :velocity :dur-secs :dur-played
                        :program :cc :micro :humanization ...); a :note's
                        :channel is nil -- choosing one is the consumer's
                        job, as the engine's channel pool is play's
     :section           :id :type :phase (:enter / :exit)
     :bar               :n, the voice's new bar number, at the end of
                        the note that crossed into it
     :mark              :count (pipes) and :n (this voice's how-many-th)

   the same boundaries core.async-engine signals to core.conductor, here
   as data for whoever reads the stream to act on.

   The walk mirrors core.async-engine's play-node/play-form family, with
   the voice's atoms as plain values: a :SEQ continues with the state its
   last child left, a :PAR merges its branches' streams by :t and then
   continues where the branch that ends last stopped (the engine's
   continue-after-fork!). Nothing is walked ahead of what is read, so a
   :count :infinite repeat or an endless live tree is fine -- take what
   you need. Each voice's :algo runs where the engine runs it (a
   container's children, then each leaf), so a live algo.tree voice's
   cursor moves as its events are read.

   Nothing here touches core.async, a clock, MIDI or *engine*. repo is
   read once, at the call (a voice's :view is a snapshot too)."
  (:require [core.compose :as compose]
            [core.domain.flat-domain :as d]
            [core.domain.ornaments :as orn]
            [core.domain.resolve :as r]
            [core.wall :as wall]
            [common.music-elements :as el]))

;; A voice's state -- core.async-engine's per-voice atoms, as one map:
;; {:repo :path :algo :t :beat :bar :bar-pos :marks :partial-pending?}
;; Every walk fn takes it plus a continuation k (state -> seq of what
;; comes after) and returns a lazy seq, so a long piece never grows the
;; stack and an endless one is never walked past what is read.

(defn- at [st kind] {:kind kind :t (:t st) :beat (:beat st) :path (:path st)})

(defn- apply-wall
  "The voice's algorithm over nodes -- core.async-engine/resolve-algo."
  [st chain nodes]
  (wall/apply-algo (wall/algo (:algo st)) chain {:path (:path st) :algo (:algo st)} nodes))

(defn- advance-bar
  "[state' bar-events] -- core.async-engine/advance-bar!, emitting a
   :bar event per crossing at the note's end (st is already past it)."
  [st dur meter partial]
  (let [len (el/meter-bar-length meter)
        pos (cond-> (:bar-pos st)
              (and (:partial-pending? st) partial) (+ (- len partial))
              true (+ dur))]
    (loop [pos pos bar (:bar st) evs []]
      (if (>= pos len)
        (recur (- pos len) (inc bar) (conj evs (assoc (at st :bar) :n (inc bar))))
        [(assoc st :bar-pos pos :bar bar :partial-pending? false) evs]))))

(defn- fire
  "One leaf/rest/drum: its event, the voice moved past it, its bars."
  [part chain st k]
  (if-let [midi (r/resolve-event {:part part :ctx-chain chain} nil (:t st) (:beat st))]
    (let [dur       (d/part-duration part)
          [st' bars] (advance-bar (-> st (update :t + (:dur-secs midi)) (update :beat + dur))
                                  dur (:meter midi) (:partial midi))]
      (cons (merge midi (at st (cond (d/leaf? part) :note (d/drum? part) :drum :else :rest)))
            (lazy-seq (concat bars (k st')))))
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

(defn- branch-end [st] (list {:kind ::end :t (:t st) :state st}))

(declare walk-node walk-form)

(defn- walk-children [nodes chain st k]
  (if-let [[n & more] (seq nodes)]
    (walk-node n chain st #(walk-children more chain % k))
    (k st)))

(defn- walk-par
  "A :PAR container's children -- core.async-engine/play-par."
  [children chain st k]
  (let [segs (compose/rank-segments compose/mean-pitch-rank children)]
    (merge-branches st
                    (map-indexed (fn [i child]
                                   (walk-node child chain
                                              (assoc st :path (conj (:path st) (segs i)) :partial-pending? true)
                                              branch-end))
                                 children)
                    k)))

(defn- walk-iterator
  "core.async-engine/play-iterator: source on every pass, a volta's
   alternative after it on the last."
  [iter chain st k]
  (let [{n :count :keys [repeat-type alternative] :or {n 1}} (:params iter)
        infinite? (= n :infinite)
        chain     (compose/build-chain iter chain (:beat st))]
    (letfn [(pass [i st]
              (if (or infinite? (< i n))
                (walk-node (:source iter) chain st
                           (fn [st]
                             (if (and (not infinite?) (= repeat-type :volta) alternative (= i (dec n)))
                               (walk-node alternative chain st #(pass (inc i) %))
                               (pass (inc i) st))))
                (k st)))]
      (pass 0 st))))

(defn- walk-node
  "core.async-engine/play-node."
  [part chain st k]
  (cond
    (d/leaf? part)
    (fire-all (mapcat #(orn/expand % chain) (apply-wall st chain [part])) chain st k)

    (or (d/rest? part) (d/drum? part))
    (fire-all (apply-wall st chain [part]) chain st k)

    (d/iterator? part)
    (walk-iterator part chain st k)

    (d/bar? part)
    (let [c  (:count part)
          st (update-in st [:marks c] (fnil inc 0))]
      (cons (assoc (at st :mark) :count c :n (get-in st [:marks c]))
            (lazy-seq (k st))))

    (d/container? part)
    (let [chain    (compose/build-chain part chain (:beat st))
          children (apply-wall st chain (d/children (:repo st) part))
          section  (fn [st phase] (assoc (at st :section) :id (:id part) :type (:type part) :phase phase))
          k'       (fn [st'] (cons (section st' :exit) (lazy-seq (k st'))))]
      (cons (section st :enter)
            (lazy-seq (if (= :PAR (:type part))
                        (walk-par children chain st k')
                        (walk-children children chain st k')))))

    :else (k st)))

;; ------------------------------------------------------------
;; Forms -- core.async-engine/play-form and friends
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
                                   (walk-form f chain
                                              (cond-> (assoc st :path (conj (:path st) (segs i)) :partial-pending? true)
                                                a (assoc :algo a))
                                              branch-end))
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

(defn- top-level-voices
  "[[form algo] ...] -- the top-level voices play would mint for form
   (core.async-engine/mint-branches!): a #{} at the top becomes one voice
   per branch, recursively, in mean-pitch order."
  [repo form algo]
  (if (compose/par-form? form)
    (->> (seq form)
         (map #(compose/resolve-form-tag % algo))
         (map-indexed (fn [i [f a]] [i f a]))
         (sort-by (fn [[i f _]] [(compose/mean-pitch-rank (compose/form-pitch-source repo f)) i]))
         (mapcat (fn [[_ f a]] (top-level-voices repo f a))))
    [[form algo]]))

(defn events
  "The lazy, time-ordered events (play form) or (play form :algo name)
   would perform against repo (a map, or an atom such as
   (core.repo/registry)) -- see the ns docstring."
  [repo form & {:keys [algo]}]
  (let [repo  (compose/live-repo repo)
        chain (if-let [root (:context (get repo :ROOT))] [root] [])
        st0   {:repo repo :t 0.0 :beat 0 :bar 1 :bar-pos 0 :marks {} :partial-pending? true}]
    (merge-branches st0
                    (map (fn [id [f a]]
                           (walk-form f chain (assoc st0 :path [id] :algo a) branch-end))
                         (compose/track-ids)
                         (top-level-voices repo form algo))
                    (constantly nil))))
