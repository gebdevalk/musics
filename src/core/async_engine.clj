(ns core.async-engine
  "Real-time playback engine built on core.async goroutines -- ported from
   a Kotlin-coroutine sketch, wired to the actual flat-domain model, and
   the sole playback engine now (replaces the old ScheduledExecutorService-
   based core.engine.engine, which pre-flattened the whole piece into a
   fixed vector of tracks -- via an eager/lazy pair of whole-tree-walk
   functions that have since been removed from core.domain.resolve, once
   this engine stopped calling them -- before a ScheduledExecutorService
   per track ticked through it).

   This engine walks the repo tree directly and just-in-time instead --
   play-node/play-seq/play-par/play-iterator do that walk's job inline
   rather than building a flattened structure first: :SEQ (and other
   sequential containers) run their children one after another inside a
   single go-block (one voice), :PAR forks each child into a sibling
   go-block the parent awaits on, context chaining is a local build-chain
   applied as the walk descends, and each leaf is actualized via
   core.domain.resolve/resolve-event right at fire-time. Since nothing is
   pre-flattened:

     - live REPL edits to repo (an atom) are picked up the moment a
       not-yet-visited container is descended into -- no separate lazy
       walk needed for that.
     - :count :infinite Iterators just loop -- no separate infinite-vs-
       finite walk needed either.

   Each voice owns a wall-clock atom and a structural-time atom (beats
   consumed, for context envelope sampling), a bar/bar-pos pair (this
   voice's own running position against whatever Meter is in scope --
   see advance-bar!), a marks counter (per-strength counts of BarLine
   markers -- | / || / ||| / |||| -- this voice has crossed, see
   play-node's Bar case), plus a channel/chan-key pair that tracks
   whatever MIDI channel it's currently holding. A voice's atoms are only
   forked -- cloned into fresh atoms seeded from the parent's current
   values -- at a :PAR, since that's the only point where playback
   actually diverges into independent timelines.

   There is deliberately no central/shared notion of \"the current bar\"
   (or \"the Nth mark\") -- each voice tracks its own against whatever
   Meter its own ctx-chain currently has in scope, the same way tempo
   already is. Two voices in different meters (or one that free-runs an
   :infinite Iterator longer than its sibling) simply reach their own
   \"bar 8\" at different times; nothing tries to reconcile that into one
   global bar count.

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
            [core.repo :as core-repo]
            [core.conductor :as conductor]
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
   repo should normally be core.repo/play-tx (an atom holding the tx to
   read through -- see live-repo/core.repo/view): a (play-tx! ...) call
   is then picked up live, as soon as playback reaches a not-yet-read
   node, without committing ever moving it on its own. A plain map works
   too (tests, warm-up!) -- it just means nothing is live.
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
  "Turn whatever `repo` handle the engine holds into something get-able.
   An IDeref holding an integer (normally core.repo/play-tx, the tx to
   read through -- see musics.clj/connect) is resolved through
   core.repo/view, so a live (play-tx! ...) repoint is picked up the
   moment the traversal visits its next not-yet-read node. An IDeref
   holding a plain map (e.g. a standalone (atom repo) in tests/the REPL
   smoke-test below, with no core.repo involved) is just dereferenced.
   Anything else (a plain map, or already a core.repo/view) is returned
   as-is."
  [repo]
  (if (instance? clojure.lang.IDeref repo)
    (let [v @repo]
      (if (integer? v) (core-repo/view v) v))
    repo))

(defn- build-chain
  "Prepend part's own context onto ctx-chain, rebased (core.domain.context/
   ctx-shift) into the same absolute timeline structural-time is already
   in -- part's own envelope was built locally-authored, zero-based (see
   ctx-shift's own docstring for why that rebase has to happen here, at
   play time, rather than once at build time)."
  [part ctx-chain structural-time]
  (if-let [own-ctx (:context part)]
    (into [(c/ctx-shift own-ctx structural-time)] ctx-chain)
    ctx-chain))

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
;; Bar/mark tracking (per voice, no central authority -- see ns docstring)
;; ============================================================

(defn- bar-length
  "Bar length in the same duration units as a leaf's own :duration (a
   whole note = 1), sampled from ctx-chain at structural-time -- same
   mechanism resolve.clj already uses for tempo. Falls back to 1 (a bare
   4/4 bar) if no Meter is set anywhere in the chain."
  [ctx-chain structural-time]
  (if-let [m (c/ctx-value-chain ctx-chain :Meter (double structural-time))]
    (/ (:num m) (:den m))
    1))

(defn- advance-bar!
  "Bump this voice's own bar position by dur (a just-played leaf/rest/
   drum's duration), crossing as many bar boundaries as dur spans (a
   single long tied note can cross more than one) and firing a :bar
   section signal at each crossing -- see core.conductor/signal!. The
   :id is a bare integer (this voice's own new bar number), disjoint
   from every :section signal's keyword container ids, so both kinds
   share one schedule table with no collision risk. Re-samples the
   meter at every crossing, so a mid-piece meter change (or a mid-piece
   :PAR sibling in a different meter) takes effect from the very next
   bar rather than needing a restart."
  [voice dur ctx-chain]
  (let [{:keys [bar bar-pos structural]} voice]
    (swap! bar-pos + dur)
    (loop []
      (let [len (bar-length ctx-chain @structural)]
        (when (>= @bar-pos len)
          (swap! bar-pos - len)
          (conductor/signal! {:kind :bar :id (swap! bar inc) :phase :enter})
          (recur))))))

(defn- mark!
  "Fire a :mark signal for a BarLine (| / || / ||| / ||||) this voice just
   hit -- see core.conductor/signal!. count is the number of pipes (1-4,
   see core.domain.flat-domain/Bar), an author-placed extra cue layered on
   top of the automatic :section/:bar signals, not a replacement for them
   (a BarLine has zero duration and never advances bar-pos/structural-time
   on its own). :id is [:mark count n] -- n is this voice's own running
   count of markers *at that same strength* (a bare :mark keyword would
   collide across strengths; a bare integer would collide with :bar's own
   ids), so e.g. (schedule! [:mark 2 1] :enter ...) means \"this voice's
   first double bar-line\"."
  [voice count]
  (let [n (get (swap! (:marks voice) update count (fnil inc 0)) count)]
    (conductor/signal! {:kind :mark :id [:mark count n] :phase :enter :count count})))

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
                (swap! structural + (d/part-duration part))
                (advance-bar! voice (d/part-duration part) ctx-chain)))))))
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
          chain     (build-chain iter ctx-chain @(:structural voice))]
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
   see resolve-voice-channel!) and clock/structural/bar atoms cloned from
   the parent's *current* values (siblings start at the same wall-clock/
   structural/bar offset since :PAR children are simultaneous, then
   immediately diverge -- see the ns docstring on why bar tracking has no
   central authority), then await all of them, releasing each child's
   channel claim as it finishes."
  [voice repo children ctx-chain]
  (go
    (when (voice-active? voice)
      (let [start-clock      @(:clock voice)
            start-structural @(:structural voice)
            start-bar        @(:bar voice)
            start-bar-pos    @(:bar-pos voice)
            start-marks      @(:marks voice)
            voices (mapv (fn [child]
                            (let [child-voice (assoc voice
                                                      :clock (atom start-clock)
                                                      :structural (atom start-structural)
                                                      :bar (atom start-bar)
                                                      :bar-pos (atom start-bar-pos)
                                                      :marks (atom start-marks)
                                                      :channel (atom nil)
                                                      :chan-key (atom nil))]
                              (go (<! (play-node child-voice repo child ctx-chain))
                                  (release-voice! child-voice))))
                          children)]
        (doseq [v voices] (<! v))))))

(defn- play-node
  "Container visits bracket a :section signal (see core.conductor/signal!)
   around the child playback -- :enter before descending, :exit once every
   child has finished, unconditionally (even if cut short by stop!/a newer
   play superseding this one), matching play-event!'s own always-send-
   note-off symmetry. Signaling is a plain, synchronous function call
   straight into core.conductor -- the engine depends on the conductor,
   never the other way around (see that namespace's docstring)."
  [voice repo part ctx-chain]
  (cond
    (or (d/leaf? part) (d/rest? part) (d/drum? part))
    (play-event! voice part ctx-chain)

    (d/iterator? part)
    (play-iterator voice repo part ctx-chain)

    (d/bar? part)
    (go (mark! voice (:count part)) nil)

    (d/container? part)
    (let [chain    (build-chain part ctx-chain @(:structural voice))
          children (d/children (live-repo repo) part)
          id       (:id part)
          type     (:type part)]
      (go
        (conductor/signal! {:kind :section :id id :type type :phase :enter})
        (<! (case type
              :PAR (play-par voice repo children chain)
              (play-seq voice repo children chain)))
        (conductor/signal! {:kind :section :id id :type type :phase :exit})))

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
            start-bar        @(:bar voice)
            start-bar-pos    @(:bar-pos voice)
            start-marks      @(:marks voice)
            voices (mapv (fn [f]
                            (let [child-voice (assoc voice
                                                      :clock (atom start-clock)
                                                      :structural (atom start-structural)
                                                      :bar (atom start-bar)
                                                      :bar-pos (atom start-bar-pos)
                                                      :marks (atom start-marks)
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

    (d/part? form)
    (play-node voice repo form ctx-chain)

    (sequential? form)
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
                   :bar (atom 1) :bar-pos (atom 0) :marks (atom {})
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
                  :bar (atom 1) :bar-pos (atom 0) :marks (atom {})
                  :channel (atom nil) :chan-key (atom nil)}]
    (reset! (:state eng) :playing)
    (let [done (play-form-group voice repo :seq args (if root-ctx [root-ctx] []))]
      (go (<! done) (release-voice! voice))))
  nil)

;; ============================================================
;; Display -- greedy, synchronous realization (debugging)
;; ============================================================

;; Mirrors play-node/play-seq/play-par/play-iterator/play-form* exactly,
;; but purely functionally: no core.async, no voice/atoms, no MIDI, no
;; *engine* -- just (clock, structural) threaded as plain values through
;; the same recursive shape, resolving every leaf via resolve-event
;; instead of scheduling and sending it. A :SEQ (or Iterator, or a plain
;; container) contributes a flat run of steps, since nothing about them
;; forks the timeline; a :PAR contributes exactly one {:kind :par :voices
;; [steps ...]} step, since that's the one place a single timeline
;; genuinely forks into several simultaneous ones. Deliberately matches
;; play-par's actual current behavior, quirks included: the parent's own
;; (clock, structural) are NOT advanced past whatever the forked children
;; took (play-par never touches the parent voice's own atoms either --
;; see play-par above), so a :SEQ sibling placed right after a :PAR
;; currently starts back at the SAME onset the :PAR's children did, not
;; after them. That looks like a real gap in the live engine, not
;; something worth quietly correcting here -- display is meant to show
;; you what play would actually do, warts included.

(declare realize-node realize-form)

(defn- realize-iterator
  [repo iter ctx-chain clock structural]
  (let [source (:source iter)
        params (:params iter)
        n      (get params :count 1)
        volta? (= (:repeat-type params) :volta)
        alt    (:alternative params)
        chain  (build-chain iter ctx-chain structural)]
    (when (= n :infinite)
      (throw (ex-info (str "display can't greedily realize a :count :infinite "
                          "Iterator -- it would never terminate.")
                      {:iterator iter})))
    (loop [i 0 steps [] clock clock structural structural]
      (if (>= i n)
        [steps clock structural]
        (let [use-alt? (and volta? alt (= i (dec n)))
              node     (if use-alt? alt source)
              [child-steps clock' structural'] (realize-node repo node chain clock structural)]
          (recur (inc i) (into steps child-steps) clock' structural'))))))

(defn- realize-node
  "Eagerly resolve part into [steps new-clock new-structural]."
  [repo part ctx-chain clock structural]
  (cond
    (or (d/leaf? part) (d/rest? part) (d/drum? part))
    (let [midi (r/resolve-event {:part part :ctx-chain ctx-chain} nil clock structural)]
      [[midi] (+ clock (:dur-secs midi)) (+ structural (d/part-duration part))])

    (d/bar? part)
    [[{:kind :mark :count (:count part)}] clock structural]

    (d/iterator? part)
    (realize-iterator repo part ctx-chain clock structural)

    (d/container? part)
    (let [chain    (build-chain part ctx-chain structural)
          children (d/children (live-repo repo) part)]
      (if (= (:type part) :PAR)
        (let [voices (mapv (fn [child]
                              (first (realize-node repo child chain clock structural)))
                            children)]
          [[{:kind :par :voices voices}] clock structural])
        (loop [cs children steps [] clock clock structural structural]
          (if (empty? cs)
            [steps clock structural]
            (let [[child-steps clock' structural'] (realize-node repo (first cs) chain clock structural)]
              (recur (rest cs) (into steps child-steps) clock' structural'))))))

    :else [[] clock structural]))

(defn- realize-form-seq
  [repo forms ctx-chain clock structural]
  (loop [fs forms steps [] clock clock structural structural]
    (if (empty? fs)
      [steps clock structural]
      (let [[form-steps clock' structural'] (realize-form repo (first fs) ctx-chain clock structural)]
        (recur (rest fs) (into steps form-steps) clock' structural')))))

(defn- realize-form-par
  [repo forms ctx-chain clock structural]
  (let [voices (mapv (fn [f] (first (realize-form repo f ctx-chain clock structural))) forms)]
    [[{:kind :par :voices voices}] clock structural]))

(defn- realize-form-group
  [repo tag items ctx-chain clock structural]
  (let [repo-now            (live-repo repo)
        [ctx-refs material] (split-leading-contexts repo-now items)
        chain (reduce (fn [chain ctx] (into [ctx] chain))
                       (into [(c/context)] ctx-chain)
                       ctx-refs)]
    (if (= tag :par)
      (realize-form-par repo material chain clock structural)
      (realize-form-seq repo material chain clock structural))))

(defn- realize-form
  [repo form ctx-chain clock structural]
  (cond
    (keyword? form)
    (realize-node repo (get (live-repo repo) form) ctx-chain clock structural)

    (d/part? form)
    (realize-node repo form ctx-chain clock structural)

    (sequential? form)
    (let [tagged? (#{:par :seq} (first form))
          tag     (if tagged? (first form) :seq)
          items   (if tagged? (rest form) form)]
      (realize-form-group repo tag items ctx-chain clock structural))

    :else [[] clock structural]))

(defn display
  "Like play, but fully synchronous and greedy: walks the exact same
   play-arg mini-language against repo (no *engine*/connect needed --
   pass core.repo/play-tx to see exactly what (play ...) would perform
   right now), resolving every leaf into a MidiEvent via
   core.domain.resolve/resolve-event instead of scheduling/sending it,
   and returns the whole thing as one realized, inspectable data
   structure -- no core.async, no waiting, no MIDI I/O.

   Returns a flat vector of steps: most are resolved MidiEvent maps; a
   :PAR contributes exactly one {:kind :par :voices [steps ...]} marker
   (see the ns note above this section for the one behavior this
   deliberately reproduces, not corrects); a BarLine contributes a
   {:kind :mark :count n} marker.

   Throws if it hits a :count :infinite Iterator -- greedy realization of
   a genuinely open-ended pattern can never terminate."
  [repo & args]
  (let [root-ctx (:context (get (live-repo repo) :ROOT))]
    (first (realize-form-seq repo args (if root-ctx [root-ctx] []) 0.0 0))))

;; ============================================================
;; REPL smoke-test
;; ============================================================

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
                               (c/context-root {"Tempo" 120 "volume" 80}) 1/2)
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
