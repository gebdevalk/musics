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
   play-node's Bar case), a channel/chan-key pair that tracks whatever
   MIDI channel it's currently holding, and its own :tx -- the tx this
   voice actually reads the repo tree through (see live-repo/fresh-tx).
   A voice's atoms are only forked -- cloned into fresh atoms seeded from
   the parent's current values -- at a :PAR, since that's the only point
   where playback actually diverges into independent timelines.

   :tx is deliberately per-voice, not one shared pointer: core.repo/
   play-tx only ever seeds a brand-new top-level voice's own :tx, once,
   at the moment play/warm-up! creates it (see fresh-tx) -- it is NOT
   re-read continuously the way it used to be. Redirecting a voice that's
   already running is schedule-tx!'s job (below): it resets ONE voice's
   own :tx directly, via :voice carried opaquely through core.conductor's
   signal event (see core.conductor's own docstring -- it never needs to
   know what a voice is; it just hands the whole event back to whatever
   action fired). This is what lets two uneven-length parts each cut
   over on their own boundary without one flipping the other's still-
   playing content early -- the failure mode a single shared pointer
   couldn't avoid.

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
   re-triggering it. A :generation counter distinguishes a play call's
   voices from any still-unwinding voices of a previous one sharing the
   same :state atom, so a fresh play can never be mistaken for -- or
   silently race against -- leftover voices from the call before it.
   Named :generation, not :session, to avoid colliding with musics.clj's
   own unrelated `session` atom ({:auto-ids :var-map}, parse-time
   bookkeeping) -- easy to conflate when jumping between the two files."
  (:require [clojure.core.async :as async :refer [go go-loop <! <!! >! timeout alts! chan mult tap untap]]
            [core.repo :as core-repo]
            [core.conductor :as conductor]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [core.domain.context :as c]
            [core.domain.ornaments :as orn]
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
  (let [ticker-source (chan)]
    {:state          (atom :stopped)
     :generation     (atom 0)
     :channel-claims (atom {})
     ;; Shared 20ms heartbeat every currently-held note taps into (see
     ;; ensure-ticker!/voice-tick-chan/hold-until!) instead of each voice
     ;; creating its own timeout channel every 20ms -- one ticker per
     ;; engine, not one per voice per tick.
     :ticker-source  ticker-source
     :ticker-mult    (mult ticker-source)
     :ticking?       (atom false)
     :repo           repo
     :root-id        root-id
     :fs             fs}))

(defn set-engine!
  "Set the global engine instance. Called once at startup:
     (set-engine! (engine (live/open-receiver) (atom repo) :ROOT))"
  [eng]
  (alter-var-root #'*engine* (constantly eng)))

;; ============================================================
;; Shared ticker -- one 20ms heartbeat per engine, tapped by every
;; currently-held note instead of each one creating its own timeout
;; channel every 20ms (see hold-until!/wait-while-paused! below, and
;; voice-tick-chan/fork-voice for where a voice's own tap is made and
;; torn down).
;; ============================================================

(defn- ensure-ticker!
  "Start eng's shared ticker go-loop if it isn't already running --
   idempotent (compare-and-set!-guarded), called from play/warm-up!
   right alongside setting :state to :playing. Ticks every 20ms by
   putting a value onto :ticker-source (non-blocking from the ticker's
   own perspective -- every tap is a dropping-buffer-1 channel, see
   voice-tick-chan, so a voice that isn't actively reading right this
   instant just misses that one pulse rather than stalling the ticker
   for everyone else) for as long as :state isn't :stopped, ticking
   through :playing AND :paused alike (a paused voice's own
   wait-while-paused!/hold-until! still needs to wake up periodically
   to notice when it's been resumed) -- only actually stopping, and
   resetting :ticking? so a later play/warm-up! can start a fresh one,
   once a session fully ends. Not tied to :generation -- the ticker
   itself carries no session-specific data, it's a bare heartbeat, so
   there's no reason to tear one down and spin up another just because
   a new play call superseded the last one while still actively
   playing."
  [eng]
  (when (compare-and-set! (:ticking? eng) false true)
    (go-loop []
      (if (= @(:state eng) :stopped)
        (reset! (:ticking? eng) false)
        (do (<! (timeout 20))
            (>! (:ticker-source eng) :tick)
            (recur))))))

(defn- voice-tick-chan
  "A fresh tap of voice's own engine's shared ticker -- a dropping-
   buffer-1 channel, so a voice that isn't actively alts!-ing against
   it at the exact instant a tick arrives just misses that one pulse
   (harmless -- ticks are purely 'wake up and re-check', not something
   that needs to be individually accounted for) rather than applying
   backpressure to the shared ticker. Call once per voice, at
   creation, and hang onto the result (see play/warm-up!/fork-voice's
   own :tick field) -- untap it in release-voice! once the voice is
   done, so the mult doesn't accumulate dead taps over a long session."
  [eng]
  (tap (:ticker-mult eng) (chan (async/dropping-buffer 1))))

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
   most notes in a voice do nothing here at all -- checked by comparing
   program/cc against the CURRENT key's own two parts directly, not by
   building a fresh [program cc] vector first just to compare it: most
   notes never touch !i:/!pan: at all, so program/cc stay unchanged
   note to note, and this used to allocate that comparison vector every
   single time regardless, on top of cc itself (built fresh per note by
   resolve-leaf) -- the one allocation that's still unavoidable, since
   the CC map's own contents can legitimately differ note to note via a
   ramp even when this fn's own answer doesn't change."
  [{:keys [eng channel chan-key]} program cc]
  (let [[old-program old-cc] @chan-key]
    (if (and (= program old-program) (= cc old-cc))
      [@channel false]
      (let [claims (:channel-claims eng)
            new-key [program cc]
            [new-channel fresh?] (claim-channel! claims new-key)]
        (when-let [old @channel] (release-channel! claims old))
        (reset! channel new-channel)
        (reset! chan-key new-key)
        [new-channel fresh?]))))

(defn- release-voice!
  "Release whatever channel claim voice is currently holding (called once
   a voice's play-node call has returned for good, win or lose -- normal
   finish, stop, or superseded by a newer play call), so the channel is
   free for reuse by later, non-overlapping playback. Also untaps voice's
   own :tick channel from the engine's shared ticker (see
   ensure-ticker!/voice-tick-chan) -- otherwise the mult would keep
   fanning ticks out to a channel nobody's reading anymore for the rest
   of the session, accumulating one dead tap per voice that's ever played."
  [{:keys [eng channel chan-key tick]}]
  (when-let [ch @channel]
    (release-channel! (:channel-claims eng) ch)
    (reset! channel nil)
    (reset! chan-key nil))
  (untap (:ticker-mult eng) tick))

;; ============================================================
;; Context-chain / repo helpers
;; ============================================================

(defn- live-repo
  "Turn whatever `repo` handle a voice holds (normally its own :tx, see
   fresh-tx) into something get-able. An IDeref holding an integer
   (normally a voice's own :tx, seeded once from core.repo/play-tx --
   see fresh-tx) is resolved through core.repo/view, so a (schedule-tx!
   ...) redirect of THIS voice is picked up the moment the traversal
   visits its next not-yet-read node. An IDeref holding a plain map
   (e.g. a standalone (atom repo) in tests/the REPL smoke-test below,
   with no core.repo involved) is just dereferenced. Anything else (a
   plain map, or already a core.repo/view) is returned as-is."
  [repo]
  (if (instance? clojure.lang.IDeref repo)
    (let [v @repo]
      (if (integer? v) (core-repo/view v) v))
    repo))

(defn- fresh-tx
  "The atom a voice's own :tx should hold, derived from source (either
   eng's :repo at voice creation, or a parent voice's own :tx at a :PAR
   fork): a NEW, independent atom seeded with source's CURRENT value if
   it's tx-indexed (an atom holding an integer -- the real core.repo/
   play-tx case), or source itself, unchanged, if it holds a plain map
   (tests/warm-up! -- see live-repo) -- there's no tx to make independent
   there, so this preserves that case's existing shared-atom behavior
   exactly. Used both at initial voice creation and at every :PAR fork,
   same as :clock/:structural/:bar are -- seeded from the current value,
   never derived by incrementing anything."
  [source]
  (let [v @source]
    (if (integer? v) (atom v) source)))

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
  "False once this voice's generation has been superseded by a newer play
   call (even if :state was flipped back to :playing already) or the
   engine has been stopped outright."
  [{:keys [eng generation]}]
  (and (= @(:generation eng) generation)
       (not= @(:state eng) :stopped)))

(defn- voice-paused? [{:keys [eng]}] (= @(:state eng) :paused))

(defn- wait-while-paused!
  "Park, waking on voice's own shared-ticker tap (see voice-tick-chan)
   every ~20ms, while paused, so a resume is noticed promptly without
   creating a fresh timeout channel of its own to do it. Returns as
   soon as unpaused, stopped, or superseded."
  [voice]
  (go-loop []
    (when (and (voice-paused? voice) (voice-active? voice))
      (<! (:tick voice))
      (recur))))

(defn- hold-until!
  "Wait until wall-clock time reaches target-nanos (System/nanoTime
   units), waking on voice's own shared-ticker tap (see voice-tick-chan)
   at least every ~20ms so pause/stop/supersede is noticed promptly,
   freezing (not consuming) the remaining time while paused. Returns
   nil if the voice stopped being active before reaching the target (a
   nil return means \"don't advance this voice's clock/structural
   atoms, the note was cut short\"), or the FINAL target-nanos actually
   reached on normal completion -- which may be LATER than the
   target-nanos passed in, by however much total pause time this call
   absorbed. Callers with a SECOND target further down the same
   timeline (play-event! below, played-target then full-target) MUST
   use that returned value as the new basis for it, not the original
   target-nanos they called this with -- a pause pushes every
   subsequent target on the same voice/note forward by the same
   amount, and this is the only place that pushed-forward amount is
   ever known. This is a real, pre-existing bug this return-value
   change fixes, not a hypothetical one: play-event! used to compute
   both played-target and full-target ONCE, upfront, from the same
   fixed origin-nanos, and pass each independently into its own
   hold-until! call -- a pause during the FIRST hold correctly pushed
   THAT call's own internal deadline forward, but full-target, used by
   the SECOND call, never learned about it, so it stayed stale (already
   in the past, off by roughly the pause duration) by the time the
   second hold ran -- confirmed live: a note's own silent tail after
   note-off (the gap between dur-played and dur-secs, from articulation
   under 1.0) came out roughly `pause-duration` shorter than it should
   have, every time, across repeated trials, whenever a pause happened
   to land during the first (sounding) hold.

   Self-correcting, unlike the relative-ms `hold!` this replaced: the
   ACTUAL remaining gap is measured against a fixed target instead of
   decrementing a fixed budget, so a wake-up that arrives a few ms late
   (ordinary core.async/JVM scheduling or GC jitter, always possible,
   never eliminated) doesn't compound into the next wait. That
   compounding was confirmed to be real and audible: a :PAR whose
   voices have very different note densities (e.g. algo.common.split's
   own canon -- a slow voice with a handful of whole notes next to a
   fast voice with dozens of thirty-second notes) gives the busy voice
   far more independent hold! calls than the slow one, so their two
   totally uncorrelated jitter budgets drift apart over the piece even
   though neither voice's own intended timing ever changes.
   target-nanos is computed fresh from the voice's own :origin-nanos (a
   fixed real instant, shared by every voice forked from the same play
   call -- see fork-voice) plus its exact logical :clock position, in
   play-event! below, rather than accumulated from a chain of previous
   waits -- that's what makes each note's deadline independent of how
   any earlier wait actually went.

   ONE timeout channel per hold, not one per ~20ms tick: target-chan is
   created ONCE, sized to the full remaining gap, then raced against
   voice's own shared tick (alts!) as many times as it takes to
   actually win -- alts! on a channel that loses a race doesn't consume
   or otherwise disturb it, so re-racing the SAME target-chan on every
   tick that arrives first is exactly as correct as racing a fresh one
   each time, just without allocating N of them. The only time
   target-chan gets discarded and rebuilt is coming out of a pause:
   a real-time timeout channel can't be retroactively pushed later once
   created, so a pause abandons whatever target-chan was pending
   (harmless -- it fires into the void later and is GC'd, same as any
   unused channel) and lets the next active+unpaused step build a fresh
   one sized against the correctly-pushed-forward target instead."
  [voice target-nanos]
  (go-loop [target target-nanos, target-chan nil]
    (cond
      (not (voice-active? voice)) nil

      (voice-paused? voice)
      (let [before (System/nanoTime)]
        (<! (:tick voice))
        (recur (+ target (- (System/nanoTime) before)) nil))

      (nil? target-chan)
      (let [remaining-ms (/ (- target (System/nanoTime)) 1e6)]
        (if (<= remaining-ms 0)
          target
          (recur target (timeout (long (Math/ceil remaining-ms))))))

      :else
      (let [[_ ch] (alts! [target-chan (:tick voice)])]
        (if (identical? ch target-chan)
          target
          (recur target target-chan))))))

;; ============================================================
;; Bar/mark tracking (per voice, no central authority -- see ns docstring)
;; ============================================================

(defn- bar-length
  "Bar length in the same duration units as a leaf's own :duration (a
   whole note = 1). meter is the ALREADY-RESOLVED Meter for the note
   that just fired -- resolve-event samples it in the very same
   c/sample-many pass it already uses for tempo/volume/etc (see that
   ns's own docstring on :meter), so there's no separate ctx-chain walk
   here anymore. Falls back to 1 (a bare 4/4 bar) if meter is nil (no
   Meter set anywhere in the chain)."
  [meter]
  (if meter (/ (:num meter) (:den meter)) 1))

(defn- advance-bar!
  "Bump this voice's own bar position by dur (a just-played leaf/rest/
   drum's duration), crossing as many bar boundaries as dur spans (a
   single long tied note can cross more than one) and firing a :bar
   section signal at each crossing -- see core.conductor/signal!. The
   :id is a bare integer (this voice's own new bar number), disjoint
   from every :section signal's keyword container ids, so both kinds
   share one schedule table with no collision risk.

   meter is that SAME note's own already-resolved Meter (see
   bar-length), computed ONCE here and reused for every boundary this
   one note's duration happens to cross -- correct, not just cheaper,
   since meter can't change mid-note (it was fixed back when
   resolve-event sampled it, before this function is ever called): an
   earlier version re-sampled the chain on every single crossing
   inside this same loop, which could never actually observe a
   different value there even when it was cheap to ask, and would mean
   a second full ctx-chain walk per crossing now that asking is no
   longer free. A genuine mid-piece meter change still takes effect
   from the very next bar exactly as before -- that happens between
   DIFFERENT notes' own advance-bar! calls (each with its own freshly
   resolved meter), never within one."
  [voice dur meter]
  (let [{:keys [bar bar-pos]} voice
        len (bar-length meter)]
    (swap! bar-pos + dur)
    (loop []
      (when (>= @bar-pos len)
        (swap! bar-pos - len)
        (conductor/signal! {:kind :bar :id (swap! bar inc) :phase :enter :voice voice})
        (recur)))))

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
    (conductor/signal! {:kind :mark :id [:mark count n] :phase :enter :count count :voice voice})))

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
   mid-note, so nothing is left stuck sounding.

   Both holds wait for an ABSOLUTE wall-clock target (origin-nanos +
   this voice's own exact logical clock position), not a relative
   millisecond count accumulated from wherever the previous wait
   happened to actually finish -- see hold-until!'s own docstring for
   why: it's what stops independent voices' scheduling jitter from
   compounding into audible drift over a long piece, especially one
   like a canon where sibling voices have very different note
   densities."
  [voice part ctx-chain]
  (go
    (<! (wait-while-paused! voice))
    (when (voice-active? voice)
      (let [{:keys [eng clock structural origin-nanos]} voice
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
            played-target    (+ origin-nanos (long (* (+ onset (:dur-played midi)) 1e9)))
            full-target      (+ origin-nanos (long (* (+ onset (:dur-secs   midi)) 1e9)))]
        (send-midi-on! fs midi fresh?)
        ;; full-target is re-based from played-reached, the ACTUAL wall-
        ;; clock instant the first hold ended at (which can be LATER than
        ;; played-target itself, if any pause happened during it), not
        ;; from the original, now possibly-stale full-target computed
        ;; before either hold ran -- see hold-until!'s own docstring for
        ;; why using the original full-target here would silently
        ;; truncate this note's own silent tail by roughly however long
        ;; any pause during the first hold lasted.
        (let [played-reached (<! (hold-until! voice played-target))]
          (send-midi-off! fs midi)
          (when played-reached
            (let [full-target' (+ played-reached (- full-target played-target))
                  full-reached (if (> full-target' played-reached)
                                 (<! (hold-until! voice full-target'))
                                 played-reached)]
              (when full-reached
                (swap! clock + (:dur-secs midi))
                (swap! structural + (d/part-duration part))
                (advance-bar! voice (d/part-duration part) (:meter midi))))))))
    nil))

(defn- play-iterator
  "Expand an Iterator's :count passes in place, one after another -- an
   Iterator is a single voice's repeated material, never a fork.
   :count :infinite loops until the voice is paused-out/stopped/superseded.
   source itself plays on EVERY pass, same as a plain (non-volta) repeat --
   a volta :alternative is a SUFFIX appended after source on the final
   pass only, never a replacement for it: real repeat/volta notation
   still repeats the shared body the full :count times, only the tail
   after it differs on the last time through. An earlier version played
   alt INSTEAD of source on the last pass, which silently dropped source's
   own final playthrough entirely -- confirmed live (\\repeat volta 2 {c4
   d4} \\alternative {e4 f4} played only C4 D4 E4 F4, not C4 D4 C4 D4 E4
   F4), and the very asymmetry a real piece's own voices don't share (main
   bodies of very different lengths across a canon's several parts) is
   exactly what turned a silently-dropped repeat into audible desync
   between voices, not just a locally short bar."
  [voice iter ctx-chain]
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
          (<! (play-node voice source chain))
          (when (and (voice-active? voice) (not infinite?) volta? alt (= i (dec n)))
            (<! (play-node voice alt chain)))
          (recur (inc i)))))))

(defn- play-seq
  [voice children ctx-chain]
  (go
    (loop [cs children]
      (when (and (seq cs) (voice-active? voice))
        (<! (play-node voice (first cs) ctx-chain))
        (recur (rest cs))))))

(defn- fork-voice
  "A child voice at a :PAR/[:par ...] fork: fresh channel/program
   (unclaimed -- see resolve-voice-channel!), fresh clock/structural/bar/
   tx atoms cloned from the parent's *current* values (siblings start at
   the same wall-clock/structural/bar/tx offset since :PAR children are
   simultaneous, then immediately diverge -- see the ns docstring on why
   bar tracking, and now tx, have no central authority). :tx is forked
   via fresh-tx, same seeded-not-incremented rule as the others.
   :origin-nanos is deliberately NOT touched here -- assoc leaves it as
   whatever the top-level play/warm-up! call already set, so every
   voice descended from one play call, no matter how many :PAR forks
   deep, keeps measuring its own hold-until! deadlines against the same
   fixed real-time origin. That's what keeps siblings' schedules
   independent of each other's actual jitter (see hold-until!'s own
   docstring) rather than each fork re-anchoring to whatever real time
   it happened to run at.
   :tick is a FRESH tap too, not inherited from the parent -- a tapped
   channel only ever delivers each pulse to ONE reader, so two sibling
   voices sharing one tap would race each other for every tick and
   starve whichever one keeps losing; each voice, forked or not, needs
   its own independent tap of the engine's shared ticker (see
   voice-tick-chan). The parent's own tap stays valid and keeps
   receiving ticks (harmlessly unread, dropping-buffer) until the
   parent's own release-voice! runs, same as always."
  [voice start-clock start-structural start-bar start-bar-pos start-marks]
  (assoc voice
         :clock (atom start-clock)
         :structural (atom start-structural)
         :bar (atom start-bar)
         :bar-pos (atom start-bar-pos)
         :marks (atom start-marks)
         :tx (fresh-tx (:tx voice))
         :channel (atom nil)
         :chan-key (atom nil)
         :tick (voice-tick-chan (:eng voice))))

(defn- play-par
  "Fork each child into its own voice (see fork-voice), then await all of
   them, releasing each child's channel claim as it finishes."
  [voice children ctx-chain]
  (go
    (when (voice-active? voice)
      (let [start-clock      @(:clock voice)
            start-structural @(:structural voice)
            start-bar        @(:bar voice)
            start-bar-pos    @(:bar-pos voice)
            start-marks      @(:marks voice)
            voices (mapv (fn [child]
                            (let [child-voice (fork-voice voice start-clock start-structural
                                                           start-bar start-bar-pos start-marks)]
                              (go (<! (play-node child-voice child ctx-chain))
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
   never the other way around (see that namespace's docstring). :voice
   rides along in the event, opaque to core.conductor itself, so a
   voice-aware action (schedule-tx!) can reach back into THIS voice's own
   :tx once conductor hands the event to whatever fired.

   A Leaf is run through core.domain.ornaments/expand first -- ctx-chain
   here is already the exact nearest-first ancestor chain expand needs to
   sample :key from, since it's the same one threaded down through this
   whole traversal. orn/expand returns [part] unchanged (count 1) when
   there's no ornament/tremolo/grace modifier, which is the common case,
   so an ordinary note takes the exact same play-event! path it always
   did -- expansion only costs the cheap :modifiers check itself, no
   extra go-block layer, for anything that isn't actually decorated. A
   decorated leaf's sub-leaves all carry empty :modifiers (see orn/expand
   plus every ornament fn's own :tied fix), so replaying each one back
   through play-node via play-seq terminates after exactly one level --
   none of them re-triggers expansion again. This was previously entirely
   unwired: core.domain.ornaments existed, was unit-tested on its own,
   and had a REPL-only (musics.clj/expand) introspection helper, but
   nothing in the real play/display pipeline ever called it -- an
   ornament modifier parsed and stored correctly but was silently
   ignored at both resolve-event and here, confirmed live (\\prallmordent
   played as a single plain note, not eight)."
  [voice part ctx-chain]
  (cond
    (d/leaf? part)
    (let [expanded (orn/expand part ctx-chain)]
      (if (= (count expanded) 1)
        (play-event! voice part ctx-chain)
        (play-seq voice expanded ctx-chain)))

    (or (d/rest? part) (d/drum? part))
    (play-event! voice part ctx-chain)

    (d/iterator? part)
    (play-iterator voice part ctx-chain)

    (d/bar? part)
    (go (mark! voice (:count part)) nil)

    (d/container? part)
    (let [chain    (build-chain part ctx-chain @(:structural voice))
          children (d/children (live-repo (:tx voice)) part)
          id       (:id part)
          type     (:type part)]
      (go
        (conductor/signal! {:kind :section :id id :type type :phase :enter :voice voice})
        (<! (case type
              :PAR (play-par voice children chain)
              (play-seq voice children chain)))
        (conductor/signal! {:kind :section :id id :type type :phase :exit :voice voice})))

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
;; vector. A group optionally starts with an explicit :par/:seq tag --
;; without one, a plain hand-typed vector/seq (no :parallel? metadata at
;; all) defaults to :par (simultaneous), not :seq: [:melody :bass] plays
;; both at once, write [:seq :melody :bass] for sequential. Material
;; produced by musics.clj/sq is a separate case -- it always carries its
;; own explicit :parallel? metadata (true or false, read off the real
;; container's own :type), so it's never subject to this default at all;
;; see form-tag+items's own docstring. Among a group's remaining items, a
;; *leading* run that resolves (via a
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

(defn- form-tag+items
  "[tag items] for a sequential play-arg form. A literal leading
   :par/:seq keyword -- the [:par ...]/[:seq ...] mini-language, written
   directly as data -- wins if present. Otherwise falls back to the
   form's own :parallel? seq metadata, which is how musics.clj/sq marks
   a container's :PAR-vs-:SEQ nature once it's been turned into a bare
   seq of children (mapv'd off the container -- there's no data-level
   place left to carry the tag at that point, only metadata): sq ALWAYS
   sets :parallel? explicitly, true or false, for any genuine container
   it was called on, so this branch is really 'trust sq's own answer',
   not a guess.
   A form with NEITHER a literal tag NOR any :parallel? metadata at all
   splits on one more distinction: a genuine vector (a plain hand-typed
   group, e.g. [:melody :bass] typed directly, or sq's own direct,
   untransformed output before metadata is even consulted) defaults to
   :par -- an untagged group of DISTINCT parts read as simultaneous,
   not sequential, write :seq explicitly ([:seq :melody :bass]) for the
   old default. Anything sequential but NOT a vector (a LazySeq/list --
   concretely, whatever musics.clj/times or map/filter/etc. produce
   from sq'd material, which never preserves sq's own metadata) keeps
   defaulting to :seq instead: that shape is already-linear repeated/
   transformed material, not a fresh grouping of separate parts, and
   this is what keeps (play (times 4 (sq :verse))) meaning 'four
   repeats in a row', not 'four copies stacked at once' -- confirmed as
   a real, not hypothetical, break: flipping the default without this
   distinction silently turned (times N (sq :x)) into simultaneous
   chords in four different tests before this split was added.
   contains? (not just a falsy check on :parallel?'s own value) is what
   lets sq's own explicit false survive the metadata branch unchanged --
   an ordinary :SEQ container's own sq'd material must still play
   sequentially, same as it always did, since a missing key and a false
   value need to land on opposite sides of that check."
  [form]
  (if-let [literal (#{:par :seq} (first form))]
    [literal (rest form)]
    (let [m (meta form)]
      [(cond
         (contains? m :parallel?) (if (:parallel? m) :par :seq)
         (vector? form)           :par
         :else                    :seq)
       form])))

(declare play-form)

(defn- play-form-seq
  [voice forms ctx-chain]
  (go
    (loop [fs forms]
      (when (and (seq fs) (voice-active? voice))
        (<! (play-form voice (first fs) ctx-chain))
        (recur (rest fs))))))

(defn- play-form-par
  [voice forms ctx-chain]
  (go
    (when (voice-active? voice)
      (let [start-clock      @(:clock voice)
            start-structural @(:structural voice)
            start-bar        @(:bar voice)
            start-bar-pos    @(:bar-pos voice)
            start-marks      @(:marks voice)
            voices (mapv (fn [f]
                            (let [child-voice (fork-voice voice start-clock start-structural
                                                           start-bar start-bar-pos start-marks)]
                              (go (<! (play-form child-voice f ctx-chain))
                                  (release-voice! child-voice))))
                          forms)]
        (doseq [v voices] (<! v))))))

(defn- play-form-group
  [voice tag items ctx-chain]
  (let [repo-now            (live-repo (:tx voice))
        [ctx-refs material] (split-leading-contexts repo-now items)
        chain (reduce (fn [chain ctx] (into [ctx] chain))
                       (into [(c/context)] ctx-chain)
                       ctx-refs)]
    (if (= tag :par)
      (play-form-par voice material chain)
      (play-form-seq voice material chain))))

(defn- play-form
  [voice form ctx-chain]
  (cond
    (keyword? form)
    (play-node voice (get (live-repo (:tx voice)) form) ctx-chain)

    (d/part? form)
    (play-node voice form ctx-chain)

    (sequential? form)
    (let [[tag items] (form-tag+items form)]
      (play-form-group voice tag items ctx-chain))

    ;; validate-ids! (run synchronously, before any voice starts) is
    ;; what actually rejects a nonsense form -- a throw here wouldn't
    ;; even reach the caller: this runs inside a go block, and an
    ;; exception thrown there is swallowed by core.async's own executor
    ;; (confirmed live -- (<!! ch) on a go block that throws returns nil,
    ;; not the exception), not propagated the way a synchronous throw is.
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
   (let [generation (swap! (:generation eng) inc)
         ctx     (c/context)
         ;; tempo defaults to 120 on an empty ctx-chain (see resolve/sample),
         ;; so dur-secs = duration*2 (musical->seconds: duration*240/120)
         ;; -- pick duration to land on note-ms.
         dur     (/ (/ note-ms 1000.0) 2)
         part    {:type :SEQ :id ::warmup :context ctx
                   :children (vec (repeatedly n #(d/leaf ::warmup ctx dur [1] nil -79 nil false)))}
         voice   {:eng eng :generation generation
                   :tx (fresh-tx (:repo eng))
                   :clock (atom 0.0) :structural (atom 0)
                   :bar (atom 1) :bar-pos (atom 0) :marks (atom {})
                   :channel (atom nil) :chan-key (atom nil)
                   :tick (voice-tick-chan eng)
                   :origin-nanos (System/nanoTime)}]
     (ensure-ticker! eng)
     (reset! (:state eng) :playing)
     (<!! (play-node voice part []))
     (release-voice! voice)
     (reset! (:state eng) :stopped)
     nil)))

;; ============================================================
;; Play API
;; ============================================================

(defn- validate-ids!
  "Walk a play-arg tree (same shape play-form/play-form-group dispatch
   on) and throw a clear ex-info immediately -- before play touches
   :generation or starts any voice -- if a keyword doesn't resolve in
   repo-now, or if a leaf of the tree is nil (concretely: sq returning
   nil for an id that doesn't resolve to a container). Deliberately
   does NOT reject every other unrecognized shape -- an :assignment/
   :BAR/etc. structural node inline in sq'd material is left alone,
   since play-node's own dispatch already silently no-ops on exactly
   that shape during ordinary playback too (an :assignment node's real
   effect already landed on its siblings' shared context back at
   parse/walk time -- confirmed live: (play (times N (sq :verse))) on
   material containing an inline !tempo:/!mf/etc. instruction node
   used to throw here even though (play :verse) directly, no sq
   involved, already relied on play-node tolerating that same node
   shape -- a real regression from an earlier, too-broad version of
   this same guard that rejected anything non-keyword/non-sequential,
   not just nil). Without the nil case caught here, a typo'd/premature
   id (most commonly: forgetting play-tx!/play-latest! after commit!,
   since commit-staged! deliberately never moves play-tx on its own),
   or sq's own nil, either NPE'd inside core.repo/as-of (fixed
   separately, see that ns) or, once that raw crash is gone, would
   silently no-op deep inside an async voice with no sound and no
   error at all -- worse than the NPE it replaces. Runs synchronously
   ahead of everything else so the error surfaces the same way a bad
   call always has: immediately, at the (play ...) call itself, not
   async/invisible inside a go block (play-form's own analogous :else
   branch can't usefully throw for this same reason -- confirmed live,
   a throw inside a go block doesn't propagate to (<!!): the channel
   just closes and returns nil)."
  [repo-now tx form]
  (cond
    (keyword? form)
    (when (nil? (get repo-now form))
      (throw (ex-info (str "No part found for id " form
                            (when (integer? tx) (str " as of tx " tx))
                            " -- check (ids), and (play-tx!)/(play-latest!) if"
                            " it was committed after this tx.")
                       {:id form :tx tx})))

    (sequential? form)
    (let [[_ items] (form-tag+items form)]
      (doseq [item items] (validate-ids! repo-now tx item)))

    ;; Anything else -- an :assignment/:BAR/etc. structural node inline
    ;; in sq'd material included -- is left to play-node's own :else,
    ;; same as it always has been: play-node silently no-ops on any
    ;; child shape it doesn't specifically recognize (an :assignment
    ;; node's real effect already landed on its siblings' shared
    ;; context back at parse/walk time, so there's nothing left for it
    ;; to *do* at play time -- confirmed live: (play :verse) directly,
    ;; with no sq involved, already relies on exactly this tolerance).
    ;; nil is the one real, confirmed exception -- concretely, sq
    ;; returning nil for an id that doesn't resolve to a container --
    ;; which used to silently no-op with no sound and no error at all.
    (nil? form)
    (throw (ex-info (str "play: don't know how to play nil -- expected"
                          " a part id, a group vector, or material from sq")
                     {:form form}))))

(defn play
  "Compose and play a structure from pre-defined repo parts. Uses
   *engine* -- call set-engine! first. One core.async voice per
   independent line; :PAR forks, :SEQ and Iterators (including
   :count :infinite ones) don't.

   Args are play-arg forms (see the mini-language above the play-form*
   fns): a mix of
     keyword  -- single part reference: :verse1
     vector   -- group, tag optional (defaults to :par, NOT :seq):
                   [:melody :bass]          same as  [:par :melody :bass]
                   [:seq :verse1 :verse2]   sequential needs the explicit tag
     context-ref -- a keyword resolving to a repo :CONTEXT, as the
                    leading item(s) of a group: applies nearest, partly
                    overriding that group's own context.

   play's own top-level args are always sequential regardless of this
   default (that's a separate, hardcoded :seq group, not a play-arg
   vector) -- so (play :verse1 [:melody :bass]) plays verse1, THEN
   melody and bass together, mixing both defaults naturally.

   Examples:
     (play :verse1 :verse2)
     (play :verse1 [:melody :bass])
     (play [:context1 :verse1] :verse2)
     (play [:seq :context0 [:seq :verse1] [:seq :context2 :verse2]])"
  [& args]
  (let [eng      *engine*
        repo-now (live-repo (:repo eng))
        tx-val   (let [v @(:repo eng)] (when (integer? v) v))]
    (doseq [a args] (validate-ids! repo-now tx-val a))
    (let [generation (swap! (:generation eng) inc)
          voice      {:eng eng :generation generation
                      :tx (fresh-tx (:repo eng))
                      :clock (atom 0.0) :structural (atom 0)
                      :bar (atom 1) :bar-pos (atom 0) :marks (atom {})
                      :channel (atom nil) :chan-key (atom nil)
                      :tick (voice-tick-chan eng)
                      :origin-nanos (System/nanoTime)}
          root-ctx   (:context (get (live-repo (:tx voice)) :ROOT))]
      (ensure-ticker! eng)
      (reset! (:state eng) :playing)
      (let [done (play-form-group voice :seq args (if root-ctx [root-ctx] []))]
        (go (<! done) (release-voice! voice)))))
  nil)

;; ============================================================
;; Cut-over -- redirect one already-running voice's own tx
;; ============================================================

(defn schedule-tx!
  "Cut ONE voice over to target-tx the next time [id phase] is signaled,
   e.g. (schedule-tx! :verse :exit 8) jumps whichever voice's own :verse
   section next exits to tx 8. target-tx may also be :latest, resolved
   to whatever is the latest committed tx at the moment this actually
   fires (not when it was scheduled) -- for \"commit now, cut over
   whenever we get there\" rather than a tx number fixed in advance.

   Lives here rather than core.conductor because it needs to know what a
   voice is -- it targets the ONE voice whose own boundary crossing
   triggered this, via :voice carried opaquely through the signal event
   (see the ns docstring and play-node). core.repo/play-tx only seeds a
   brand new top-level voice at play/warm-up! time now; it is not what
   this resets. Built on register-action!/schedule! exactly as before --
   only the action's own body changed.
   Returns the generated action-id (e.g. to unregister-action! later)."
  [id phase target-tx]
  (let [action-id (gensym "cut-over")]
    (conductor/register-action!
      action-id
      (fn [event]
        (let [tx (if (= target-tx :latest) (core-repo/latest-tx) target-tx)]
          (reset! (:tx (:voice event)) tx))))
    (conductor/schedule! id phase action-id)
    action-id))

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
  "source realizes on EVERY pass; a volta :alternative is appended as a
   SUFFIX after source on the final pass only, never a substitute for it
   -- see play-iterator's own docstring for why (same bug, same fix,
   mirrored here since display must show what play would actually do)."
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
        (let [[source-steps clock' structural'] (realize-node repo source chain clock structural)
              use-alt? (and volta? alt (= i (dec n)))
              [alt-steps clock'' structural''] (if use-alt?
                                                  (realize-node repo alt chain clock' structural')
                                                  [[] clock' structural'])]
          (recur (inc i) (into steps (into source-steps alt-steps)) clock'' structural''))))))

(defn- realize-node
  "Eagerly resolve part into [steps new-clock new-structural].
   A Leaf goes through core.domain.ornaments/expand first -- see
   play-node's own docstring for why (same fix, mirrored here since
   display must show what play would actually do); [part] unchanged
   (count 1) is the common, no-modifier case and takes the original
   single-resolve-event path directly, no extra looping."
  [repo part ctx-chain clock structural]
  (cond
    (d/leaf? part)
    (let [expanded (orn/expand part ctx-chain)]
      (if (= (count expanded) 1)
        (let [midi (r/resolve-event {:part part :ctx-chain ctx-chain} nil clock structural)]
          [[midi] (+ clock (:dur-secs midi)) (+ structural (d/part-duration part))])
        (loop [ls expanded steps [] clock clock structural structural]
          (if (empty? ls)
            [steps clock structural]
            (let [l    (first ls)
                  midi (r/resolve-event {:part l :ctx-chain ctx-chain} nil clock structural)]
              (recur (rest ls) (conj steps midi)
                     (+ clock (:dur-secs midi)) (+ structural (d/part-duration l))))))))

    (or (d/rest? part) (d/drum? part))
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
    (let [[tag items] (form-tag+items form)]
      (realize-form-group repo tag items ctx-chain clock structural))

    ;; See validate-ids!'s own comment on this same distinction -- an
    ;; :assignment/:BAR/etc. structural node inline in sq'd material
    ;; falls through to realize-node's own :else (unchanged, still a
    ;; silent [[] clock structural] no-op, same tolerance realize-node
    ;; already has for a container's own inline children); nil (sq
    ;; returning nil for an id that doesn't resolve to a container) is
    ;; the one real, confirmed exception.
    (nil? form)
    (throw (ex-info (str "display: don't know how to play nil -- expected"
                          " a part id, a group vector, or material from sq")
                     {:form form}))

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
