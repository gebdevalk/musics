(ns core.engine.async-engine
  "Real-time playback engine built on core.async goroutines -- ported from
   a Kotlin-coroutine sketch, wired to the actual flat-domain model, and
   the sole playback engine now (replaces the old ScheduledExecutorService-
   based core.engine.engine, which pre-flattened the whole piece into a
   fixed vector of tracks via core.domain.resolve/form-unroll -- or
   form-unroll-lazy for infinite/live-mutable pieces -- before a
   ScheduledExecutorService per track ticked through it).

   This engine doesn't call form-unroll at all -- play-node/play-seq/
   play-par/play-iterator walk the repo tree directly and just-in-time,
   doing form-unroll's job inline instead of building a flattened structure
   first: :SEQ (and other sequential containers) run their children one
   after another inside a single go-block (one voice), :PAR forks each
   child into a sibling go-block the parent awaits on, context chaining
   is a local build-chain applied as the walk descends, and each leaf is
   actualized via core.domain.resolve/resolve-event right at fire-time.
   Since nothing is pre-flattened:

     - live REPL edits to repo (an atom) are picked up the moment a
       not-yet-visited container is descended into -- no separate lazy
       walk needed for that, unlike form-unroll vs. form-unroll-lazy.
     - :count :infinite Iterators just loop -- no separate infinite-vs-
       finite walk needed either.

   Each voice owns a wall-clock atom and a structural-time atom (beats
   consumed, for context envelope sampling), plus a channel/chan-key pair
   that tracks whatever MIDI channel it's currently holding. A voice's
   atoms are only forked -- cloned into fresh atoms seeded from the
   parent's current values -- at a :PAR, since that's the only point
   where playback actually diverges into independent timelines.

   MIDI channels are a shared, refcounted pool on the engine (0-15,
   excluding 9 -- reserved for GM percussion), not handed out one-per-
   voice: a channel is polyphonic, so several simultaneous voices sharing
   the same chan-key -- [program cc], i.e. instrument *and* panning/any
   other channel-wide CC state -- share one channel instead of each
   burning a fresh one, and a voice only actually claims/releases a
   channel when its resolved chan-key changes (including its very first
   note) -- see resolve-voice-channel!/release-voice!. Only program and
   CC state are ever shared this way -- per-note fields like velocity
   never factor in, since they don't persist on the channel past one
   note. (core.domain.resolve's drum channel, always 9, sits outside
   this pool entirely and is never claimed/shared through it.)

   Transport (pause!/resume!/stop!) is a single :state atom on the engine,
   shared by every voice in the current play session, checked between
   events and in small increments *during* a held note -- so stop is at
   most ~20ms late and pause freezes a sounding note in place (holding the
   remaining duration exactly, then continuing it on resume) rather than
   re-triggering it. A :session counter distinguishes a play call's voices
   from any still-unwinding voices of a previous one sharing the same
   :state atom, so a fresh play can never be mistaken for -- or silently
   race against -- leftover voices from the call before it."
  (:require [clojure.core.async :refer [go go-loop <! <!! timeout]]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [core.domain.context :as c]
            [output.midi.midi-live :as live]))

;; ============================================================
;; Dynamic engine var
;; ============================================================

(def ^:dynamic *engine* nil)

(defn engine
  "Create a new engine holding repo and root-id.
   fs is a MIDI Receiver (see output.midi.midi-live/open-receiver) -- nil
   is fine too, playback just sends no MIDI (useful for tests).
   repo should be an atom for live mutation so edits take effect as soon
   as playback reaches the container they touch. A plain map works too --
   it just means nothing is live.
   Does not start playback -- call play after creation."
  [fs repo root-id]
  {:state          (atom :stopped)
   :session        (atom 0)
   :channel-claims (atom {})
   :repo           repo
   :root-id        root-id
   :fs             fs})

(defn set-engine!
  "Set the global engine instance. Called once at startup:
     (set-engine! (engine (live/open-receiver) (atom repo) :ROOT))"
  [eng]
  (alter-var-root #'*engine* (constantly eng)))

;; ============================================================
;; MIDI primitive
;; ============================================================

(defn- send-midi-on!
  "send-channel-state? is true only when this event's channel was just
   freshly claimed from the pool (see resolve-voice-channel!) -- a shared
   channel already running the right program/CC state was set up by
   whichever voice claimed it first, so resending program-change/CC here
   would be redundant (and, worse, would stomp on that voice's still-
   sounding notes if we ever shared a channel across mismatched state --
   see resolve-voice-channel!, which is exactly why sharing requires an
   exact match, not just the same program)."
  [fs {:keys [channel program cc pitches velocity]} send-channel-state?]
  (when (and fs channel)
    (when send-channel-state?
      (live/program-change fs channel program)
      (doseq [[cc-num cc-val] cc]
        (live/control-change fs channel cc-num cc-val)))
    (doseq [pitch pitches]
      (live/note-on fs channel pitch velocity))))

(defn- send-midi-off! [fs {:keys [channel pitches tied]}]
  (when (and fs channel (not tied))
    (doseq [pitch pitches]
      (live/note-off fs channel pitch))))

;; ============================================================
;; MIDI channel pool -- shared across all voices via the engine, since a
;; channel is polyphonic and can be reused by any voice currently playing
;; the same program *and* the same channel-wide CC state (panning, and
;; anything else resolve-event ever adds to :cc); channel 9 is excluded
;; (reserved for GM percussion -- core.domain.resolve's Drum handling
;; always uses it directly, outside this pool).
;;
;; A "chan-key" is [program cc] -- program plus the whole resolved CC map
;; -- the complete set of persistent, per-channel (not per-note) MIDI
;; state a note's channel carries after it fires. Two voices may only
;; share a channel when their chan-keys are =, not just their program:
;; panning (or any other CC) is exactly as "sticky" on a channel as
;; program is, so two simultaneous voices with the same instrument but
;; different panning must NOT share a channel -- whichever one's note
;; fired last would silently override the channel's CC state out from
;; under the other voice's still-sounding note.
;; ============================================================

(def ^:private channel-pool (vec (remove #{9} (range 16))))

(defn- free-channel
  "First pool channel with no current claim, or nil if all are taken."
  [claims]
  (some (fn [c] (when-not (contains? claims c) c)) channel-pool))

(defn- claim-for-key
  "Channel already claimed for chan-key, if any."
  [claims chan-key]
  (some (fn [[c {:keys [key]}]] (when (= key chan-key) c)) claims))

(defn- claim-channel!
  "Share an existing channel already running chan-key, or claim a fresh
   one from the pool. Returns [channel fresh?] -- fresh? true means the
   channel's actual MIDI program/CC state isn't set yet (caller must send
   it explicitly); false means some other still-active voice already has
   it running that exact state, so it's already correct.
   Falls back to forcing channel 0 (fresh? true) if the pool is exhausted
   -- a 16th simultaneous non-percussion chan-key is an edge case this
   degrades on rather than crashing playback over."
  [claims-atom chan-key]
  (loop []
    (let [claims @claims-atom]
      (if-let [shared (claim-for-key claims chan-key)]
        (if (compare-and-set! claims-atom claims
                               (update claims shared update :refcount inc))
          [shared false]
          (recur))
        (if-let [fresh (free-channel claims)]
          (if (compare-and-set! claims-atom claims
                                 (assoc claims fresh {:key chan-key :refcount 1}))
            [fresh true]
            (recur))
          [0 true])))))

(defn- release-channel!
  "Drop one voice's hold on channel; frees it back to the pool once no
   voice is left using it."
  [claims-atom channel]
  (when channel
    (swap! claims-atom
           (fn [claims]
             (if-let [{:keys [refcount]} (get claims channel)]
               (if (<= refcount 1)
                 (dissoc claims channel)
                 (update claims channel update :refcount dec))
               claims)))))

(defn- resolve-voice-channel!
  "Ensure voice is on a channel appropriate for [program cc] (its
   chan-key), claiming/sharing/releasing through eng's channel-claims
   pool as that key changes over the voice's life (including its very
   first note, where the voice holds nothing yet). Returns
   [channel needs-channel-state-resend?]. A no-op (just returns the
   already-held channel) when the key hasn't changed since last time, so
   most notes in a voice do nothing here at all."
  [{:keys [eng channel chan-key]} program cc]
  (let [new-key [program cc]]
    (if (= new-key @chan-key)
      [@channel false]
      (let [claims (:channel-claims eng)
            [new-channel fresh?] (claim-channel! claims new-key)]
        (when-let [old @channel] (release-channel! claims old))
        (reset! channel new-channel)
        (reset! chan-key new-key)
        [new-channel fresh?]))))

(defn- release-voice!
  "Release whatever channel claim voice is currently holding (called once
   a voice's play-node call has returned for good, win or lose -- normal
   finish, stop, or superseded by a newer play call), so the channel is
   free for reuse by later, non-overlapping playback."
  [{:keys [eng channel chan-key]}]
  (when-let [ch @channel]
    (release-channel! (:channel-claims eng) ch)
    (reset! channel nil)
    (reset! chan-key nil)))

;; ============================================================
;; Context-chain / repo helpers
;; ============================================================

(defn- live-repo
  "Dereference repo if it's an atom, return as-is if plain map."
  [repo]
  (if (instance? clojure.lang.IDeref repo) @repo repo))

(defn- build-chain
  [part ctx-chain]
  (if-let [own-ctx (:context part)] (into [own-ctx] ctx-chain) ctx-chain))

;; ============================================================
;; Voice: everything one line of playback needs, bundled so forking at
;; :PAR is just `assoc`-ing in a fresh channel/clock/structural triple.
;; ============================================================

(defn- voice-active?
  "False once this voice's session has been superseded by a newer play
   call (even if :state was flipped back to :playing already) or the
   engine has been stopped outright."
  [{:keys [eng session]}]
  (and (= @(:session eng) session)
       (not= @(:state eng) :stopped)))

(defn- voice-paused? [{:keys [eng]}] (= @(:state eng) :paused))

(defn- wait-while-paused!
  "Park in 20ms increments while paused, so a resume is noticed promptly.
   Returns as soon as unpaused, stopped, or superseded."
  [voice]
  (go-loop []
    (when (and (voice-paused? voice) (voice-active? voice))
      (<! (timeout 20))
      (recur))))

(defn- hold!
  "Wait out ms milliseconds in small steps, freezing (not consuming) the
   remaining time while paused, and returning early -- true -- if the
   voice stops being active. A truthy return means \"don't advance this
   voice's clock/structural atoms, the note was cut short.\""
  [voice ms]
  (go-loop [remaining ms]
    (cond
      (not (voice-active? voice)) true
      (voice-paused? voice)       (do (<! (timeout 20)) (recur remaining))
      (<= remaining 0)            false
      :else (let [step (min 20 remaining)]
              (<! (timeout step))
              (recur (- remaining step))))))

;; ============================================================
;; Dispatcher
;; ============================================================

(declare play-node)

(defn- play-event!
  "Fire one leaf/rest/drum: resolve against the voice's current wall-clock/
   structural-time, send note-on, hold for the played duration, send
   note-off, then (if there's any left) hold out the rest of the FULL
   note value (dur-secs) as silence before advancing the voice's clock/
   structural atoms and letting it move to the next event. Onset-to-onset
   spacing always ends up matching the score's dur-secs regardless of
   articulation, so independent voices in a :PAR (e.g. a canon) can't
   drift apart from each other just because they carry different
   articulation -- but note-off is still strictly sequenced *before* the
   voice can proceed to the next note-on (both holds run in this same
   go-block, not a detached one), so a fast-articulated repeated pitch
   can never have its note-on race a still-in-flight note-off for the
   previous note and get clipped by it.
   Always sends note-off, even if cut short (stopped/superseded)
   mid-note, so nothing is left stuck sounding."
  [voice part ctx-chain]
  (go
    (<! (wait-while-paused! voice))
    (when (voice-active? voice)
      (let [{:keys [eng clock structural]} voice
            fs               (:fs eng)
            onset            @clock
            structural-time  @structural
            ;; channel param nil here -- only resolve-leaf's :program/:cc
            ;; matter before we know which real channel to use; Rest/Drum
            ;; already ignore the channel arg (Drum hardcodes 9).
            midi0            (r/resolve-event {:part part :ctx-chain ctx-chain}
                                               nil onset structural-time)
            leaf?            (d/leaf? part)
            [channel fresh?] (if leaf?
                                (resolve-voice-channel! voice (:program midi0) (:cc midi0))
                                [(:channel midi0) false])
            midi             (cond-> midi0 leaf? (assoc :channel channel))
            played-ms        (long (* (:dur-played midi) 1000))
            full-ms          (long (* (:dur-secs   midi) 1000))]
        (send-midi-on! fs midi fresh?)
        (let [cut-short? (<! (hold! voice played-ms))]
          (send-midi-off! fs midi)
          (when-not cut-short?
            (let [remaining   (- full-ms played-ms)
                  cut-short2? (if (pos? remaining) (<! (hold! voice remaining)) false)]
              (when-not cut-short2?
                (swap! clock      + (:dur-secs midi))
                (swap! structural + (d/part-duration part))))))))
    nil))

(defn- play-iterator
  "Expand an Iterator's :count passes in place, one after another -- an
   Iterator is a single voice's repeated material, never a fork.
   :count :infinite loops until the voice is paused-out/stopped/superseded."
  [voice repo iter ctx-chain]
  (go
    (let [source    (:source iter)
          params    (:params iter)
          n         (get params :count 1)
          infinite? (= n :infinite)
          volta?    (= (:repeat-type params) :volta)
          alt       (:alternative params)
          chain     (build-chain iter ctx-chain)]
      (loop [i 0]
        (when (and (voice-active? voice) (or infinite? (< i n)))
          (let [use-alt? (and (not infinite?) volta? alt (= i (dec n)))
                node     (if use-alt? alt source)]
            (<! (play-node voice repo node chain))
            (recur (inc i))))))))

(defn- play-seq
  [voice repo children ctx-chain]
  (go
    (loop [cs children]
      (when (and (seq cs) (voice-active? voice))
        (<! (play-node voice repo (first cs) ctx-chain))
        (recur (rest cs))))))

(defn- play-par
  "Fork each child into its own voice: fresh channel/program (unclaimed --
   see resolve-voice-channel!) and clock/structural atoms cloned from the
   parent's *current* values (siblings start at the same wall-clock/
   structural offset since :PAR children are simultaneous), then await
   all of them, releasing each child's channel claim as it finishes."
  [voice repo children ctx-chain]
  (go
    (when (voice-active? voice)
      (let [start-clock      @(:clock voice)
            start-structural @(:structural voice)
            voices (mapv (fn [child]
                            (let [child-voice (assoc voice
                                                      :clock (atom start-clock)
                                                      :structural (atom start-structural)
                                                      :channel (atom nil)
                                                      :chan-key (atom nil))]
                              (go (<! (play-node child-voice repo child ctx-chain))
                                  (release-voice! child-voice))))
                          children)]
        (doseq [v voices] (<! v))))))

(defn- play-node
  [voice repo part ctx-chain]
  (cond
    (or (d/leaf? part) (d/rest? part) (d/drum? part))
    (play-event! voice part ctx-chain)

    (d/iterator? part)
    (play-iterator voice repo part ctx-chain)

    (d/container? part)
    (let [chain    (build-chain part ctx-chain)
          children (d/children (live-repo repo) part)]
      (case (:type part)
        :PAR (play-par voice repo children chain)
        (play-seq voice repo children chain)))

    :else (go nil)))

;; ============================================================
;; Transport
;; ============================================================

(defn stop!
  "Halt playback. Current voices notice within ~20ms (or immediately
   between events) and unwind, sending note-off for anything sounding."
  ([]    (stop! *engine*))
  ([eng] (reset! (:state eng) :stopped) nil))

(defn pause!
  "Pause all voices. Sounding notes are held in place, not re-triggered."
  ([]    (pause! *engine*))
  ([eng]
   (when (= @(:state eng) :playing) (reset! (:state eng) :paused))
   nil))

(defn resume!
  "Resume all voices from exactly where they were paused."
  ([]    (resume! *engine*))
  ([eng]
   (when (= @(:state eng) :paused) (reset! (:state eng) :playing))
   nil))

(defn playing? ([] (playing? *engine*)) ([eng] (= @(:state eng) :playing)))
(defn paused?  ([] (paused?  *engine*)) ([eng] (= @(:state eng) :paused)))
(defn stopped? ([] (stopped? *engine*)) ([eng] (= @(:state eng) :stopped)))

;; ============================================================
;; Play-arg mini-language
;;
;; A play-arg is either a bare keyword (a repo reference) or a group
;; vector. A group optionally starts with an explicit :par/:seq tag
;; (defaults to :seq if the first element isn't literally one of those).
;; Among a group's remaining items, a *leading* run that resolves (via a
;; fresh repo lookup, so live edits apply) to :CONTEXT containers is
;; peeled off -- the first non-context item ends the run, so contexts
;; must come first, same as the repo build already requires. Each is
;; pushed onto the ctx-chain nearest-first, in listed order, ahead of
;; this group's own fresh Context -- so a referenced context partly
;; overrides the group's own, exactly like build-chain pushes any
;; container's own :context ahead of its ctx-chain, just for possibly
;; more than one context at once here.
;;
;; Remaining material is played sequentially or forked, each item
;; recursively parsed by this same rule. A bare keyword bottoms out by
;; resolving it against the live repo and handing the real part off to
;; play-node -- from there it's ordinary domain content, using the exact
;; same machinery as everything above.
;; ============================================================

(defn- resolve-context-ref
  "If item is a keyword resolving (in repo) to a :CONTEXT container,
   return its Context record; else nil."
  [repo item]
  (when (keyword? item)
    (let [resolved (get repo item)]
      (when (= :CONTEXT (:type resolved)) (:context resolved)))))

(defn- split-leading-contexts
  "Split a group's items into [ctx-refs material] -- ctx-refs is the
   leading run of context-ref items (see resolve-context-ref), material
   is everything from the first non-context item on."
  [repo items]
  (loop [items items ctxs []]
    (if-let [ctx (and (seq items) (resolve-context-ref repo (first items)))]
      (recur (rest items) (conj ctxs ctx))
      [ctxs items])))

(declare play-form)

(defn- play-form-seq
  [voice repo forms ctx-chain]
  (go
    (loop [fs forms]
      (when (and (seq fs) (voice-active? voice))
        (<! (play-form voice repo (first fs) ctx-chain))
        (recur (rest fs))))))

(defn- play-form-par
  [voice repo forms ctx-chain]
  (go
    (when (voice-active? voice)
      (let [start-clock      @(:clock voice)
            start-structural @(:structural voice)
            voices (mapv (fn [f]
                            (let [child-voice (assoc voice
                                                      :clock (atom start-clock)
                                                      :structural (atom start-structural)
                                                      :channel (atom nil)
                                                      :chan-key (atom nil))]
                              (go (<! (play-form child-voice repo f ctx-chain))
                                  (release-voice! child-voice))))
                          forms)]
        (doseq [v voices] (<! v))))))

(defn- play-form-group
  [voice repo tag items ctx-chain]
  (let [repo-now            (live-repo repo)
        [ctx-refs material] (split-leading-contexts repo-now items)
        chain (reduce (fn [chain ctx] (into [ctx] chain))
                       (into [(c/context)] ctx-chain)
                       ctx-refs)]
    (if (= tag :par)
      (play-form-par voice repo material chain)
      (play-form-seq voice repo material chain))))

(defn- play-form
  [voice repo form ctx-chain]
  (cond
    (keyword? form)
    (play-node voice repo (get (live-repo repo) form) ctx-chain)

    (vector? form)
    (let [tagged? (#{:par :seq} (first form))
          tag     (if tagged? (first form) :seq)
          items   (if tagged? (rest form) form)]
      (play-form-group voice repo tag items ctx-chain))

    :else (go nil)))

;; ============================================================
;; Warm-up
;; ============================================================

(defn warm-up!
  "Play a short burst of near-silent notes through eng's fs before real
   playback -- exercises the exact same resolve/timing/MIDI-send code
   path real playback uses (JIT-compiling the hot loop, letting GC
   settle) and gets the receiver's own audio pipeline flowing, before
   anything the listener cares about starts. Mitigates a crackle at the
   very start of a session's first playback, caused by CPU contention
   between the JVM warming up and a real-time software synth's audio
   thread fighting over the CPU -- not a logic bug in this engine, just
   softening a race against JIT/GC timing that only ever shows up once,
   right at the start.
   Blocks (synchronous) until done -- call once, right after opening the
   receiver, before any music that matters. n notes, each note-ms long
   (default 16 x 20ms = ~320ms); velocity ends up at 1 regardless of any
   real session's volume settings, since this uses its own throwaway
   context, not the real repo."
  ([eng] (warm-up! eng 16 20))
  ([eng n note-ms]
   (let [session (swap! (:session eng) inc)
         ctx     (c/context)
         ;; tempo defaults to 120 on an empty ctx-chain (see resolve/sample),
         ;; so dur-secs = duration/2 -- pick duration to land on note-ms.
         dur     (* 2 (/ note-ms 1000.0))
         part    {:type :SEQ :id ::warmup :context ctx
                   :children (vec (repeatedly n #(d/leaf ::warmup ctx dur [1] nil -79 nil false)))}
         voice   {:eng eng :session session
                   :clock (atom 0.0) :structural (atom 0)
                   :channel (atom nil) :chan-key (atom nil)}]
     (reset! (:state eng) :playing)
     (<!! (play-node voice (:repo eng) part []))
     (release-voice! voice)
     (reset! (:state eng) :stopped)
     nil)))

;; ============================================================
;; Play API
;; ============================================================

(defn play
  "Compose and play a structure from pre-defined repo parts. Uses
   *engine* -- call set-engine! first. One core.async voice per
   independent line; :PAR forks, :SEQ and Iterators (including
   :count :infinite ones) don't.

   Args are play-arg forms (see the mini-language above the play-form*
   fns): a mix of
     keyword  -- single part reference: :verse1
     vector   -- group, tag optional (defaults to :seq):
                   [:seq :verse1 :verse2]  same as  [:verse1 :verse2]
                   [:par [:seq :melody] [:seq :bass]]
     context-ref -- a keyword resolving to a repo :CONTEXT, as the
                    leading item(s) of a group: applies nearest, partly
                    overriding that group's own context.

   Examples:
     (play :verse1 :verse2)
     (play [:context1 :verse1] :verse2)
     (play [:par :context0 [:seq :verse1] [:seq :context2 :verse2]])"
  [& args]
  (let [eng      *engine*
        repo     (:repo eng)
        root-ctx (:context (get (live-repo repo) :ROOT))
        session  (swap! (:session eng) inc)
        voice    {:eng eng :session session
                  :clock (atom 0.0) :structural (atom 0)
                  :channel (atom nil) :chan-key (atom nil)}]
    (reset! (:state eng) :playing)
    (let [done (play-form-group voice repo :seq args (if root-ctx [root-ctx] []))]
      (go (<! done) (release-voice! voice))))
  nil)

;; ============================================================
;; REPL smoke-test
;; ============================================================

#_:clj-kondo/ignore
(comment
  ;; --- Standalone (no *engine*), matches the shape before this rewrite ---
  (def n1 (d/leaf :n1 (c/context) 1/4 [60]))
  (def n2 (d/leaf :n2 (c/context) 1/4 [62]))
  (def seq1 {:type :SEQ :id :s1
             :context (c/set-duration (c/context) 1/2)
             :children [n1 n2]})
  (def loud-ctx (c/context-root {"volume" 120}))

  (def repo {:ROOT {:type :ROOT :id :ROOT
                    :context (c/set-duration
                               (c/context-root {"tempo" 120 "volume" 80}) 1/2)
                    :children [:s1]}
             :s1     seq1
             :forte  {:type :CONTEXT :id :forte :context loud-ctx}})

  ;; --- Full engine usage ---

  ;; 1. Startup -- fs nil plays silently (as in the examples below);
  ;;    for real sound, run ./scripts/setup.sh once, then:
  ;;    (set-engine! (engine (live/open-receiver) (atom repo) :ROOT))
  (set-engine! (engine nil (atom repo) :ROOT))

  ;; 2. Play -- no eng arg needed anywhere
  (play :s1)
  (play [:s1] [:s1])                       ;; :s1 then :s1 again -- untagged
                                            ;; groups default to :seq, so
                                            ;; this is NOT parallel
  (play [:par :s1 :s1])                    ;; :s1 against itself -- :par is
                                            ;; obligatory for parallel play
  (play [:forte :s1])                      ;; :s1, but louder
  (play [:par [:seq :s1] [:seq :forte :s1]])   ;; one voice plain, one loud

  ;; 3. Transport
  (pause!)
  (resume!)
  (stop!)

  ;; 4. Live edit -- takes effect as soon as playback reaches :s1 again
  ;; (swap! (:repo *engine*) assoc-in [:s1 :children] new-children)
  )
