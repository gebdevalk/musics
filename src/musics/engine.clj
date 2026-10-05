(ns musics.engine
  "Live playback. Every top-level voice is a musics.events stream; one
   sender thread per engine reads each stream a lookahead (default
   100 ms) ahead of the clock, turns what it reads into timed actions --
   note-on, note-off, a conductor signal, a voice starting or ending --
   and performs each at its moment. Nothing waits inside a voice: the
   whole performance is one time-ordered queue.

     play / play-add / play-change   start voices (see play)
     stop! / pause! / resume!        transport
     schedule-tx!                    cut voices over at a boundary
     voice-at / playing-ids / live-algos / assign-algo!

   A voice's material, algorithm and position live in its stream, so a
   change is heard once the sender reads that far: a live musics.algo.tree
   voice's new settings, or a schedule-tx! cutover (decided as the
   boundary is computed, a lookahead before it sounds -- one armed
   later than that catches the voice's next crossing). A note's :micro
   may move it early, by at most the lookahead.

   The sender holds :lock while it reads streams and performs actions;
   every public fn here takes the same lock, so the REPL and the sender
   never see each other half-way. An action (a conductor action, a wall
   fn) runs on the sender thread; one that throws is reported and, for a
   stream, ends that voice -- it never stops the others."
  (:require [musics.repo :as core-repo]
            [musics.conductor :as conductor]
            [musics.wall :as wall]
            [musics.compose :as compose]
            [musics.events :as ev]
            [musics.domain :as d]
            [musics.domain.context :as c]
            [musics.domain.resolve :as resolve]
            [musics.common.music-data :as data]
            [musics.midi.live :as live])
  (:import [java.util PriorityQueue Comparator]
           [java.util.concurrent.locks LockSupport]))

(def ^:dynamic *engine* nil)

(def ^:private action-order
  "By time; at the same moment a note-off first (a repeated pitch is
   released before it sounds again), then signals, then note-ons; then
   in the order scheduled."
  (reify Comparator
    (compare [_ a b]
      (let [c (Long/compare (:at a) (:at b))]
        (if-not (zero? c)
          c
          (let [c (Long/compare (:prio a) (:prio b))]
            (if-not (zero? c) c (Long/compare (:n a) (:n b)))))))))

(defn engine
  "An engine playing from repo (normally (musics.repo/registry)) through fs,
   a MIDI Receiver (musics.midi.live/open-receiver) -- nil plays
   silently, for tests. lookahead-ms: how far ahead the sender reads."
  ([fs repo root-id] (engine fs repo root-id 100))
  ([fs repo root-id lookahead-ms]
   {:fs fs :repo repo :root-id root-id
    :lookahead-ns   (long (* lookahead-ms 1e6))
    :lock           (Object.)
    :state          (atom :stopped)     ; :playing :paused :stopped
    :running?       (atom false)        ; a sender thread is looping
    :paused-at      (atom nil)
    :streams        (atom {})           ; top path -> {:events :origin}
    :queue          (PriorityQueue. 64 ^Comparator action-order)
    :n              (atom 0)
    :sounding       (atom {})           ; top path -> {[channel pitch] count}
    :voices         (atom {})           ; path -> {:path :root-path :algo :t :beat}
    :active-voices  (atom {})           ; top path -> {id count}
    :algo-prepared  (atom {})           ; path -> name, read when a voice starts
    :channel-claims (atom {})           ; channel -> {:key [program cc] :refcount}
    :voice-channels (atom {})}))        ; path -> [channel key]

(defn set-engine!
  "Set the global engine instance -- (set-engine! (engine rcv (musics.repo/registry) :ROOT))."
  [eng]
  (alter-var-root #'*engine* (constantly eng)))

(defn- secs->ns [s] (long (* (double s) 1e9)))

(defn- ->path
  "A path vector, or a bare keyword as a one-segment path."
  [path-or-id]
  (if (vector? path-or-id) path-or-id [path-or-id]))

;; ============================================================
;; MIDI and the channel pool -- a channel carries one [program cc] at
;; a time, shared by every voice playing exactly that; 9 is percussion.
;; ============================================================

(defn- send-midi-on!
  "Program and CC first when the channel was just claimed (fresh?)."
  [fs {:keys [channel program cc pitches velocity]} fresh?]
  (when (and fs channel)
    (when fresh?
      (live/program-change fs channel program)
      (doseq [[n v] cc] (live/control-change fs channel n v)))
    (doseq [p pitches] (live/note-on fs channel p velocity))))

(defn- send-midi-off! [fs {:keys [channel pitches tied]}]
  (when (and fs channel (not tied))
    (doseq [p pitches] (live/note-off fs channel p))))

(def ^:private channel-pool (vec (remove #{9} (range 16))))

(defn- claim-channel!
  "[channel fresh?] -- a channel already playing key, else a free one
   (fresh? true: its program/CC still has to be sent); nil when all 15
   carry other timbres."
  [claims key]
  (let [cs @claims]
    (if-let [shared (some (fn [[ch {k :key}]] (when (= k key) ch)) cs)]
      (do (swap! claims update-in [shared :refcount] inc) [shared false])
      (when-let [free (some #(when-not (contains? cs %) %) channel-pool)]
        (swap! claims assoc free {:key key :refcount 1})
        [free true]))))

(defn- release-channel! [claims ch]
  (when ch
    (swap! claims (fn [cs]
                    (if-let [{:keys [refcount]} (get cs ch)]
                      (if (<= refcount 1) (dissoc cs ch) (update-in cs [ch :refcount] dec))
                      cs)))))

(defn- channel-for!
  "[channel fresh?] for the voice at path playing [program cc] -- its
   channel as long as that stays the same, else claimed anew. A full
   pool drops the note (channel nil) rather than take another voice's."
  [eng path program cc]
  (let [key    [program cc]
        [ch k] (get @(:voice-channels eng) path)]
    (if (and ch (= k key))
      [ch false]
      (let [claimed (claim-channel! (:channel-claims eng) key)]
        (release-channel! (:channel-claims eng) ch)
        (if claimed
          (do (swap! (:voice-channels eng) assoc path [(first claimed) key]) claimed)
          (do (swap! (:voice-channels eng) dissoc path)
              (println "musics.engine: MIDI channel pool exhausted (15 timbres at once) -- dropping a note")
              [nil false]))))))

(defn- release-voice-channel! [eng path]
  (when-let [[ch] (get @(:voice-channels eng) path)]
    (release-channel! (:channel-claims eng) ch)
    (swap! (:voice-channels eng) dissoc path)))

;; ============================================================
;; Actions -- what the sender does, and when
;; ============================================================

(defn- add! [eng action]
  (.add ^PriorityQueue (:queue eng) (assoc action :n (swap! (:n eng) inc))))

(defn- schedule!
  "Queue e, an event of top's stream (origin: when its :t 0 is)."
  [eng top origin e]
  (let [at (+ origin (secs->ns (:t e)))]
    (case (:kind e)
      (:note :drum)
      (let [[offset vel] (resolve/humanize e rand)
            e   (assoc e :velocity vel)
            on  (+ at (secs->ns offset))
            box (volatile! nil)]
        (add! eng {:at on :prio 2 :top top :type :on :e e :box box})
        (when-not (:tied e)
          (add! eng {:at (+ on (secs->ns (:dur-played e))) :prio 0 :top top :type :off :box box})))
      (:section :bar :mark :voice)
      (add! eng {:at at :prio 1 :top top :type (:kind e) :e e})
      nil)))

(defn- signal!
  "musics.conductor/signal!, an action that throws reported, not raised."
  [event]
  (try (conductor/signal! event)
       (catch Throwable t
         (println "musics.engine: a conductor action failed --" (.getMessage t)))))

(defn- note-on! [eng top {:keys [e box]}]
  (let [[ch fresh?] (if (= :drum (:kind e))
                      [(:channel e) false]
                      (channel-for! eng (:path e) (:program e) (:cc e)))
        midi        (assoc e :channel ch)]
    (vreset! box midi)
    (when ch
      (send-midi-on! (:fs eng) midi fresh?)
      (doseq [p (:pitches e)]
        (swap! (:sounding eng) update-in [top [ch p]] (fnil inc 0))))
    (when (contains? @(:voices eng) (:path e))
      (swap! (:voices eng) update (:path e) assoc :t (:t e) :beat (:beat e)))))

(defn- note-off! [eng top {:keys [box]}]
  (when-let [{:keys [channel pitches] :as midi} @box]
    (send-midi-off! (:fs eng) midi)
    (when channel
      (doseq [p pitches]
        (swap! (:sounding eng)
               (fn [s] (let [n (dec (get-in s [top [channel p]] 0))]
                         (if (pos? n) (assoc-in s [top [channel p]] n) (update s top dissoc [channel p])))))))))

(defn- fire!
  "Perform one due action."
  [eng {:keys [type top e] :as action}]
  (let [voice #(get @(:voices eng) (:path e) {:path (:path e) :root-path top})]
    (case type
      :on  (note-on! eng top action)
      :off (note-off! eng top action)
      :voice
      (if (= :start (:phase e))
        (swap! (:voices eng) update (:path e) merge
               {:path (:path e) :root-path top :algo (:algo e) :t (:t e) :beat (:beat e)})
        (do (release-voice-channel! eng (:path e))
            (swap! (:voices eng) dissoc (:path e))))
      :section
      (let [{:keys [id phase]} e]
        (swap! (:active-voices eng) update-in [top id] (fnil (if (= phase :enter) inc dec) 0))
        (signal! {:kind :section :id id :type (:type e) :phase phase :voice (voice)}))
      :bar  (signal! {:kind :bar :id (:n e) :phase :enter :voice (voice)})
      :mark (signal! {:kind :mark :id [:mark (:count e) (:n e)] :phase :enter
                      :count (:count e) :voice (voice)}))))

;; ============================================================
;; Streams
;; ============================================================

(defn- drop-voice!
  "Silence and forget top and everything it forked: its stream, its
   queued actions, its sounding notes (note-off now), its channels, and
   an :exit for every section it is still inside."
  [eng top]
  (swap! (:streams eng) dissoc top)
  (let [^PriorityQueue q (:queue eng)
        keep (remove #(= top (:top %)) (vec q))]
    (.clear q)
    (.addAll q keep))
  (doseq [[[ch p] _] (get @(:sounding eng) top)]
    (send-midi-off! (:fs eng) {:channel ch :pitches [p]}))
  (swap! (:sounding eng) dissoc top)
  (doseq [[path v] @(:voices eng) :when (= top (:root-path v))]
    (release-voice-channel! eng path)
    (swap! (:voices eng) dissoc path))
  (let [open (get @(:active-voices eng) top)]
    (swap! (:active-voices eng) dissoc top)
    (doseq [[id n] open, _ (range n)]
      (signal! {:kind :section :id id :phase :exit :voice {:path top :root-path top}}))))

(defn- drop-all! [eng]
  (doseq [top (distinct (concat (keys @(:streams eng)) (keys @(:sounding eng))
                                (map :root-path (vals @(:voices eng)))))]
    (drop-voice! eng top))
  (.clear ^PriorityQueue (:queue eng)))

(defn- pull!
  "Queue every stream's events that fall within the lookahead of now."
  [eng now]
  (let [horizon (+ now (:lookahead-ns eng))]
    (doseq [top (keys @(:streams eng))]
      (loop []
        (when-let [{:keys [events origin]} (get @(:streams eng) top)]
          (let [s (try (seq events)
                       (catch Throwable t
                         (println "musics.engine: voice" top "stopped --" (.getMessage t))
                         ::failed))]
            (cond
              (= ::failed s) (drop-voice! eng top)
              (nil? s)       (swap! (:streams eng) dissoc top)
              (<= (+ origin (secs->ns (:t (first s)))) horizon)
              (do (schedule! eng top origin (first s))
                  (swap! (:streams eng) assoc-in [top :events] (rest s))
                  (recur)))))))))

(defn- fire-due! [eng now]
  (let [^PriorityQueue q (:queue eng)]
    (loop []
      (when-let [a (.peek q)]
        (when (<= (:at a) now)
          (.poll q)
          (try (fire! eng a)
               (catch Throwable t (println "musics.engine:" (.getMessage t))))
          (recur))))))

(def ^:private max-park-ns 5000000)

(defn- sender-loop [eng]
  (loop []
    (let [park (locking (:lock eng)
                 (let [^PriorityQueue q (:queue eng)
                       idle #(do (reset! (:running? eng) false) nil)]
                   (case @(:state eng)
                     :playing (let [now (System/nanoTime)]
                                (pull! eng now)
                                (fire-due! eng now)
                                (if (and (empty? @(:streams eng)) (.isEmpty q))
                                  (idle)
                                  (let [nxt (some-> (.peek q) :at (- (System/nanoTime)))]
                                    (max 0 (min (or nxt max-park-ns) max-park-ns)))))
                     :paused  max-park-ns
                     (idle))))]
      (when park
        (LockSupport/parkNanos (long park))
        (recur)))))

(defn- ensure-running!
  "Start the sender thread unless one is looping. Call holding :lock."
  [eng]
  (when (compare-and-set! (:running? eng) false true)
    (doto (Thread. ^Runnable (bound-fn [] (sender-loop eng)) "musics-sender")
      (.setDaemon true)
      (.start))))

;; ============================================================
;; Cut-over -- a voice onto what is committed now, at a boundary
;; ============================================================

(def ^:private cutovers
  "schedule-tx! action-id -> atom of the voice paths already cut over."
  (atom {}))

(defn- cutover
  "musics.events' :cutover hook: at a boundary armed by schedule-tx!, the
   voice continues with what is committed now -- once per voice."
  [st id phase]
  (if-let [done (some->> (conductor/scheduled-repeating id phase) (get @cutovers))]
    (if (contains? @done (:path st))
      st
      (do (swap! done conj (:path st))
          (assoc st :repo @(core-repo/registry))))
    st))

(defn schedule-tx!
  "Cut every voice over to what is committed now, each at its own next
   crossing of [id phase] -- (schedule-tx! :verse :exit): each voice
   leaving :verse continues with the latest commit. Decided as the
   boundary is computed (a lookahead before it sounds); each voice is
   cut over once. Stays armed until (musics.conductor/unschedule-repeating!
   id phase). Returns the action-id."
  [id phase]
  (let [action-id (gensym "cut-over")]
    (swap! cutovers assoc action-id (atom #{}))
    (conductor/register-action! action-id (fn [_] nil))
    (conductor/schedule-repeating! id phase action-id)
    action-id))

;; ============================================================
;; Voices -- queries and algorithm assignment
;; ============================================================

(defn playing-ids
  "The ids some voice is inside right now; #{} before any engine."
  ([] (playing-ids *engine*))
  ([eng] (if eng
           (into #{} (for [[_ ids] @(:active-voices eng) [id n] ids :when (pos? n)] id))
           #{})))

(defn voice-at
  "{:path :root-path :algo :t :beat} of the voice at path (a vector, or
   a keyword for a top-level one) -- as of its latest note -- or nil."
  ([path] (voice-at *engine* path))
  ([eng path] (when eng (get @(:voices eng) (->path path)))))

(defn assign-algo!
  "The algorithm the NEXT voice started at path gets when its play call
   names none -- never a voice already playing (whose :algo is fixed).
   Used to prepare a track, and by musics.persist's restore-session."
  ([path name] (assign-algo! *engine* path name))
  ([eng path name]
   (when eng (swap! (:algo-prepared eng) assoc (->path path) name))))

(defn algo-assignments
  "path -> name, as prepared by assign-algo!."
  ([] (algo-assignments *engine*))
  ([eng] (if eng (into {} @(:algo-prepared eng)) {})))

(defn live-algos
  "path -> name for every voice playing now."
  ([] (live-algos *engine*))
  ([eng] (if eng (into {} (map (fn [[p v]] [p (:algo v)])) @(:voices eng)) {})))

;; ============================================================
;; Transport
;; ============================================================

(defn stop!
  "Stop everything: sounding notes get their note-off now."
  ([] (stop! *engine*))
  ([eng]
   (locking (:lock eng)
     (drop-all! eng)
     (reset! (:state eng) :stopped))
   nil))

(defn pause!
  "Hold everything where it is; sounding notes keep sounding."
  ([] (pause! *engine*))
  ([eng]
   (locking (:lock eng)
     (when (= :playing @(:state eng))
       (reset! (:paused-at eng) (System/nanoTime))
       (reset! (:state eng) :paused)))
   nil))

(defn resume!
  "Continue from where pause! held everything."
  ([] (resume! *engine*))
  ([eng]
   (locking (:lock eng)
     (when (= :paused @(:state eng))
       (let [delta (- (System/nanoTime) @(:paused-at eng))
             ^PriorityQueue q (:queue eng)
             shifted (mapv #(update % :at + delta) q)]
         (swap! (:streams eng) update-vals #(update % :origin + delta))
         (.clear q)
         (.addAll q shifted)
         (reset! (:state eng) :playing)
         (ensure-running! eng))))
   nil))

(defn playing? ([] (playing? *engine*)) ([eng] (= :playing @(:state eng))))
(defn paused?  ([] (paused?  *engine*)) ([eng] (= :paused  @(:state eng))))
(defn stopped? ([] (stopped? *engine*)) ([eng] (= :stopped @(:state eng))))

;; ============================================================
;; Play
;; ============================================================

(defn- validate-algo-name! [name]
  (when (and name (not (wall/algo name)))
    (throw (ex-info (str "play: :algo tag references unregistered name " name
                         " -- check (algos), or install it first via musics.algo.tree/live!")
                    {:algo name}))))

(def ^:private validate-prefix 1000)

(defn- validate-ids!
  "Throw on what play can't play: an id that isn't there, an
   unregistered :algo, nil, a bare fn. Anything else it doesn't know
   (an inline :assignment node in sq'd material) plays as nothing.
   An uncounted seq may be endless ((cycle (sq :verse))), so only its
   first validate-prefix items are checked; a bad one later on ends
   that voice when the stream reaches it."
  [repo-now form]
  (cond
    (keyword? form)
    (when (nil? (get repo-now form))
      (throw (ex-info (str "No part found for id " form " -- check (ids); this id likely doesn't exist (a typo?).")
                      {:id form})))

    (compose/tagged-form? form)
    (let [[inner name] (compose/split-tag form)]
      (validate-algo-name! name)
      (validate-ids! repo-now inner))

    (or (set? form) (sequential? form))
    (doseq [item (cond->> (second (compose/form-tag+items form))
                   (not (counted? form)) (take validate-prefix))]
      (validate-ids! repo-now item))

    (nil? form)
    (throw (ex-info "play: don't know how to play nil -- expected a part id, a group vector, or material from sq"
                    {:form form}))

    (fn? form)
    (throw (ex-info (str "play: don't know how to play a bare function -- did you mean play-xf?"
                         " (play-xf f & args) applies f to each keyword id's own (sq id) before"
                         " playing, e.g. (play-xf #(times 5 (shuffle %)) :verse)")
                    {:form form}))))

(defn- validate-args! [eng args]
  (let [repo-now (compose/live-repo (:repo eng))]
    (doseq [a args] (validate-ids! repo-now a))))

(defn- split-call-args
  "[form algo] from (play Form) or (play Form :algo Name)."
  [args]
  (let [n (count args)]
    (cond
      (= n 1) [(first args) nil]
      (and (= n 3) (= :algo (second args))) [(first args) (nth args 2)]
      :else (throw (ex-info (str "play: expected (play Form) or (play Form :algo Name) -- got " n " args")
                            {:args (vec args)})))))

(defn- split-change-args
  "[args algo] -- a trailing :algo Name taken off play-change's args."
  [args]
  (let [n (count args)]
    (if (and (>= n 2) (= :algo (nth args (- n 2))))
      [(vec (take (- n 2) args)) (nth args (dec n))]
      [(vec args) nil])))

(defn- origin-now
  "Where a new voice's :t 0 is: now -- or, while paused, the moment the
   pause began, so resume! moves it on by exactly the pause."
  [eng]
  (if (= :paused @(:state eng)) @(:paused-at eng) (System/nanoTime)))

(defn- start-voice!
  "Start the top-level voice at path -- registered at once, so voice-at
   finds it as soon as play returns."
  [eng path form algo origin]
  (let [algo (or algo (get @(:algo-prepared eng) path))]
    (swap! (:voices eng) assoc path {:path path :root-path path :algo algo :t 0.0 :beat 0})
    (swap! (:streams eng) assoc path
           {:origin origin
            :events (ev/voice-events (:repo eng) form :path path :algo algo :cutover cutover)})))

(defn- occupied? [eng id]
  (or (contains? @(:streams eng) [id])
      (some #(= [id] (:root-path %)) (vals @(:voices eng)))))

(defn- next-track-id [eng]
  (or (some #(when-not (occupied? eng %) %) (compose/track-ids))
      (throw (ex-info "play: no free track id left (all 676 :TAA..:TZZ in use)" {}))))

(defn- mint!
  "Start form's top-level voices; its id, or for a #{}/(par ...) a set
   of (recursively) the same, lowest mean pitch first."
  [eng form algo origin]
  (if (compose/par-form? form)
    (let [repo (compose/live-repo (:repo eng))]
      (->> (seq form)
           (map #(compose/resolve-form-tag % algo))
           (map-indexed (fn [i [f a]] [i f a]))
           (sort-by (fn [[i f _]] [(compose/mean-pitch-rank (compose/form-pitch-source repo f)) i]))
           (map (fn [[_ f a]] (mint! eng f a origin)))
           (into #{})))
    (let [id (next-track-id eng)]
      (start-voice! eng [id] form algo origin)
      id)))

(defn- play-top-level! [eng form algo flush?]
  (validate-args! eng [form])
  (validate-algo-name! algo)
  (locking (:lock eng)
    (when flush? (drop-all! eng))
    (let [origin (origin-now eng)
          ids    (mint! eng form algo origin)]
      (when-not (= :paused @(:state eng)) (reset! (:state eng) :playing))
      (ensure-running! eng)
      ids)))

(defn play
  "Play one Form, replacing whatever is playing -- (play Form) or
   (play Form :algo Name). Returns the voice's id (:TAA), or for a #{} /
   (par ...) at the top a set of ids, nested as written.

     keyword           a committed part: :verse
     [Form+]           one after another
     #{Form+}          at once, each branch its own voice
     (par Form+)       the same, also for the same Form twice
     [Form :algo Name] Form through a registered algorithm (musics.wall)
     a :CONTEXT id     as an item of a group: its settings apply to the
                       group ([]: leading ones only)

     (play :verse)
     (play [:verse :chorus])
     (play #{:melody [:bass :algo :walk]})"
  [& args]
  (let [[form algo] (split-call-args args)]
    (play-top-level! *engine* form algo true)))

(defn play-add
  "Like play, but joins what is already playing."
  [& args]
  (let [[form algo] (split-call-args args)]
    (play-top-level! *engine* form algo false)))

(defn play-change
  "Replace only the voice at path with args (played one after another),
   optionally ending in :algo Name; no args just stops that voice.
     (play-change :TAB :chorus)
     (play-change :myTrack :verse :algo :bright)"
  [path & args]
  (let [eng        *engine*
        path       (->path path)
        [args algo] (split-change-args args)]
    (validate-args! eng args)
    (validate-algo-name! algo)
    (locking (:lock eng)
      (drop-voice! eng path)
      (when (seq args)
        (start-voice! eng path (vec args) algo (origin-now eng)))
      (when-not (= :paused @(:state eng)) (reset! (:state eng) :playing))
      (ensure-running! eng))
    nil))

(defn warm-up!
  "Play n near-silent notes (MIDI velocity 1, pitch 1) of note-ms each
   through eng, blocking until done -- run once after connecting so the
   first real notes don't pay for the JVM warming up."
  ([eng] (warm-up! eng 16 20))
  ([eng n note-ms]
   (let [ctx   (c/context)
         tempo (:default (data/quantity :tempo))
         dur   (/ (* (/ note-ms 1000.0) tempo) 240)
         dyn   (- 1 (:default (data/quantity :volume)))
         part  {:type :SEQ :id ::warmup :context ctx
                :children (vec (repeatedly n #(d/leaf ::warmup ctx dur [1] nil dyn nil false)))}
         path  [::warmup]]
     (locking (:lock eng)
       (swap! (:streams eng) assoc path
              {:origin (System/nanoTime) :events (ev/voice-events {} part :path path)})
       (reset! (:state eng) :playing)
       (ensure-running! eng))
     (loop [waited 0]
       (when (and (< waited 5000)
                  (locking (:lock eng)
                    (or (contains? @(:streams eng) path) (contains? @(:voices eng) path))))
         (Thread/sleep 5)
         (recur (+ waited 5))))
     (locking (:lock eng)
       (drop-voice! eng path)
       (when (empty? @(:streams eng)) (reset! (:state eng) :stopped)))
     nil)))
