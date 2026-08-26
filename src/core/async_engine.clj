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
   re-triggering it. Distinguishing a play call's voices from any still-
   unwinding voices of a previous, now-superseded one sharing the same
   :state atom used to be a single engine-wide :generation counter --
   that's gone now, replaced by eng's own :voices registry (path ->
   voice) and each voice's own :root-path/:birth-token (see voice-
   active?, and play/play-change/play-add's own docstrings): a fresh
   play call can still never be mistaken for -- or silently race
   against -- leftover voices from before it, but the check is now
   per-path, which is what makes play-change/play-add able to supersede
   ONE path without touching any other voice anywhere else.

   :active-voices (another engine-instance field, same reasoning as
   :channel-claims -- not a single global atom, since tests spin up
   throwaway engines constantly) is a live id -> voice-count registry,
   maintained by play-node's container branch right alongside its
   existing :section :enter/:exit conductor signal (see track-enter!/
   track-exit!/playing-ids below) -- 'what's actually playing right
   now', for a GUI/REPL consumer, as opposed to core.repo's own
   root-children, 'what's committed and COULD be played but currently
   isn't'. This distinction is the one a Forth-hosted predecessor of
   this app didn't need to make explicitly -- it modeled a fixed set of
   named voices rather than an arbitrarily nested :SEQ/:PAR tree, so
   'is voice N currently sounding' was just that voice's own on/off
   flag. Once :PAR made the tree shape arbitrary, 'is this id currently
   playing' stopped being answerable without a real registry, which is
   what this is."
  (:require [clojure.core.async :as async :refer [go go-loop <! <!! >! timeout alts! chan mult tap untap]]
            [core.repo :as core-repo]
            [core.conductor :as conductor]
            [core.wall :as wall]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [core.domain.context :as c]
            [core.domain.ornaments :as orn]
            [common.music-elements :as el]
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
     :channel-claims (atom {})
     :active-voices  (atom {})
     ;; :voices -- path -> voice, the one general, always-queryable
     ;; live-voice registry (voice-at) AND the mechanism play/play-
     ;; change/play-add all supersede/coexist through (see voice-active?
     ;; and each of those fns' own docstrings) -- a plain map, no fixed
     ;; size, no ordering: a voice's path is added at fork/creation,
     ;; removed by the voice itself (release-voice!) as its very last
     ;; act, guarded so a superseded voice's own delayed cleanup can
     ;; never clobber a newer voice that's since reclaimed the same
     ;; path. Unlike a core.conductor boundary signal's own :voice
     ;; (which only exists transiently, inside a fired action), any
     ;; entry here is a permanent handle for as long as that path is
     ;; actually occupied.
     :voices         (atom {})
     ;; :algo-assignments -- path -> concrete algorithm fn, resolved
     ;; ONCE at assignment time (musics.clj/assign-algo!), not re-
     ;; looked-up by name later -- unregistering that name afterward
     ;; doesn't retroactively change an already-assigned path. Default
     ;; (path absent) is core.wall/identity-wall, a no-op. Voices are
     ;; addressed by the exact same path :voices uses -- there is no
     ;; separate numeric slot space at all; "which algorithm does
     ;; this voice run through" is just a lookup on its own real id,
     ;; set explicitly, never derived from its content.
     :algo-assignments (atom {})
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
   once a session fully ends. Not tied to any one voice/path's own
   liveness -- the ticker itself carries no session-specific data, it's
   a bare heartbeat, so there's no reason to tear one down and spin up
   another just because a play call superseded a path while still
   actively playing."
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
   of the session, accumulating one dead tap per voice that's ever played.
   Removes voice's own entry from eng's :voices registry too, but ONLY if
   it's STILL the current occupant of its own :path -- a plain (dissoc)
   would be wrong the moment a newer voice has since reclaimed the same
   path (play-change, or a :PAR-fork's path being reused across separate
   play calls): this voice's own cleanup running late must never clobber
   that newer voice's registration. A voice with no :path at all (none
   built through fork-voice/play -- warm-up!'s own throwaway literal
   registers its own single fixed path directly, see warm-up!) simply
   has nothing to remove."
  [{:keys [eng channel chan-key tick path] :as voice}]
  (when-let [ch @channel]
    (release-channel! (:channel-claims eng) ch)
    (reset! channel nil)
    (reset! chan-key nil))
  (when path
    (swap! (:voices eng) (fn [m] (if (identical? (get m path) voice) (dissoc m path) m))))
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
  "Prepend part's own context onto ctx-chain, as a [ctx offset] pair --
   NOT eagerly rebased via core.domain.context/ctx-shift anymore. part's
   own envelope was built locally-authored, zero-based, so it still
   needs rebasing into the same absolute timeline structural-time is
   already in before any of its points mean anything relative to the
   rest of the chain -- but core.domain.context/sample-many's own
   link->ctx+offset already normalizes a [ctx offset] pair exactly the
   same way core.domain.resolve/chain-links does for extracted (sq/
   times/cycle) material, shifting the QUERY time backward by offset
   right at the point of touching THIS ancestor's own points, never the
   points themselves (see sample-many's own docstring). This used to
   call ctx-shift here instead -- eagerly copying and shifting every
   point of every key this container's context happens to hold, on
   EVERY container descent, for ordinary (non-extracted) playback: the
   overwhelming majority of notes fired, unlike the extracted path that
   already got this same optimization. ctx-shift itself is unchanged
   and still exported (still directly useful, still tested) -- this is
   just no longer its own hot-path caller.
   The only other place a live ctx-chain's own elements get read
   directly rather than through sample-many/link->ctx+offset was
   core.domain.ornaments/expand's :key lookup, updated alongside this
   change to go through sample-many too."
  [part ctx-chain structural-time]
  (if-let [own-ctx (:context part)]
    (into [[own-ctx structural-time]] ctx-chain)
    ctx-chain))

;; ============================================================
;; Voice paths -- every voice's own real, always-addressable id: a
;; vector, root-first, one segment per level of forking. A :PAR/
;; play-arg-group fork's own children are relabeled :TAA/:TAB/... by
;; ASCENDING MEAN PITCH (see mean-pitch-rank/rank-segments) -- "lowest
;; voice lands in slot 0", the mixing-desk convention this project has
;; always used for :PAR ordering, restored here now that every voice
;; has a real, stable short id to hang it on (this used to be a
;; fixed-size array indexed by mean-pitch rank; a plain path segment,
;; the exact same alphabet play mints top-level track ids from,
;; carries the same information without a separate index space).
;; Deliberately NOT a child's own container id/name (:melody, :bass,
;; ...) -- purely pitch content, uniform across every :PAR fork,
;; regardless of whether some children happen to have one and others
;; don't. This is what both eng's :voices registry (general
;; addressability, voice-at) AND :algo-assignments (which algorithm
;; this voice runs through) are keyed by -- the SAME id, not two
;; separate index spaces.
;; ============================================================

(def ^:private track-letters "ABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn- track-ids
  "Every short track id -- T + two uppercase letters, :TAA :TAB ..
   :TZZ, 676 total -- in a fixed order. Two independent consumers share
   this one alphabet: play/play-add mint TOP-level ids from it (checked
   against eng's :voices for occupancy, see next-track-id), and rank-segments
   hands out PATH SEGMENTS from it per :PAR fork (unique only within
   that fork's own sibling list, not globally -- the full path is what
   :voices/:algo-assignments actually key on)."
  []
  (for [a track-letters b track-letters] (keyword (str "T" a b))))

(defn- mean-pitch-rank
  "part's own mean-pitch (core.domain.flat-domain/mean-pitch, an O(1)
   read off a container's own baked :pitch-sum/:pitch-n -- see that ns's
   docstring on why this is cheap enough to call at every :PAR fork),
   or Double/MAX_VALUE if part is nil or has no pitched content at all
   (all rests/drums, or unmeasurable -- see form-pitch-source) -- pushes
   anything unmeasurable to the END of the sort rather than crashing or
   arbitrarily landing first."
  [part]
  (or (and part (d/mean-pitch part)) Double/MAX_VALUE))

(defn- form-pitch-source
  "The real node a play-arg form refers to, for mean-pitch-rank's sake
   only -- a bare keyword resolves against repo (a live-repo'd view);
   anything else (a nested group, already-sq'd raw seq material) has no
   single node to measure, so nil (sorts last, same as silent content
   does). Takes repo directly, not a voice -- reused both by
   play-form-par (an already-forked voice's own :tx) and mint-branches!
   (top-level #{} minting, before any voice for that branch exists yet,
   see eng's own :repo)."
  [repo form]
  (when (keyword? form)
    (get repo form)))

(defn- rank-segments
  "items (any seq -- real container children, or play-arg forms) -> a
   vector of :TAA/:TAB/... segments, one per item, in the SAME order as
   items itself. Computed by pairing each item with its own original
   index, sorting ascending by [(pitch-of item) index] (index breaks a
   tie deterministically, by original left-to-right position, rather
   than at sort stability's mercy), handing out track-ids 0,1,2... in
   THAT order, then scattering the results back to each item's own
   original position -- the actual mechanism behind 'lowest mean pitch
   gets the lowest track id'."
  [pitch-of items]
  (let [ranked (->> (map-indexed vector items)
                    (sort-by (fn [[i item]] [(pitch-of item) i])))
        ids    (track-ids)]
    (reduce (fn [acc [rank [orig-i _]]] (assoc acc orig-i (nth ids rank)))
            (vec (repeat (count items) nil))
            (map-indexed vector ranked))))

(defn- register-voice!
  "Add voice into eng's :voices registry under its own :path -- called
   once, at creation (play/play-change/play-add's own top-level voice,
   or every :PAR-fork's child -- see fork-voice), never touched again
   until release-voice! removes it."
  [eng voice]
  (swap! (:voices eng) assoc (:path voice) voice)
  voice)

;; ============================================================
;; Voice: everything one line of playback needs, bundled so forking at
;; :PAR is just `assoc`-ing in a fresh channel/clock/structural triple.
;; ============================================================

(defn- voice-active?
  "False once a newer voice has superseded this one's own top-level
   :root-path (play-change, or a fresh (play ...) flushing everything --
   see each of those fns' own docstrings), or the engine has been
   stopped outright. Checked against eng's :voices registry itself, not
   a separate counter: this voice's own :birth-token (captured once, at
   its top-level ancestor's creation, and inherited unchanged by every
   voice forked from it -- never re-derived per-fork) must still match
   whatever's CURRENTLY registered at :root-path -- if a different voice
   (a different birth-token) has since taken that path, every voice
   descended from the old one reads that mismatch on its own next check
   and winds down."
  [{:keys [eng root-path birth-token]}]
  (and (= (:birth-token (get @(:voices eng) root-path)) birth-token)
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
   Meter set anywhere in the chain) -- see common.music-elements/
   meter-bar-length, the same formula input.reader.flat-tree-walker's
   MultiRest (\\R) needs at walk time, factored out there once."
  [meter]
  (el/meter-bar-length meter))

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
   resolved meter), never within one.

   partial is that SAME note's own already-resolved :Partial (see
   resolve-event/common-keys+defaults) -- consulted only ONCE per
   voice, on whichever leaf this voice happens to resolve first
   (:partial-pending? flips false right here and never fires again for
   this voice), same 'no central authority, each voice on its own'
   philosophy the rest of bar-tracking already has: a \\partial written
   inside one :PAR branch only ever affects that branch's own bar
   count, never a sibling's. Consuming it BEFORE the ordinary
   (swap! bar-pos + dur) below, by adding (len - partial) once, is what
   makes the FIRST :bar crossing land after only partial's own length
   instead of a full bar -- exactly LilyPond's own \\partial semantics,
   just applied lazily against real playback instead of a fixed offset
   computed in advance."
  [voice dur meter partial]
  (let [{:keys [bar bar-pos partial-pending?]} voice
        len (bar-length meter)]
    (when @partial-pending?
      (reset! partial-pending? false)
      (when partial
        (swap! bar-pos + (- len partial))))
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
                (advance-bar! voice (d/part-duration part) (:meter midi) (:partial midi))))))))
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

(defn- play-leaves
  "Play already-finalized leaf/rest/drum records directly, one after
   another via play-event! -- NOT play-seq, which redispatches each one
   through play-node again, i.e. back through ornament expansion AND the
   wall a second time. orn/expand's own sub-leaves happen to be safe
   against that (empty :modifiers, so a second call is a no-op) -- but a
   wall fn has no such self-recognition: a fn that ever expands 1->N,
   redispatched through play-seq instead of this fn, would see its OWN
   >1-count output fed straight back into play-node's leaf branch, which
   calls it again on that same already-expanded material -- and since
   that result is again >1, play-seq would redispatch it once more,
   without any structural reason for that to ever stop. (Traced through
   the code, not run to completion live -- deliberately not induced for
   real, to avoid actually triggering an unbounded goroutine spawn just
   to watch it happen.) play-leaves is what makes it safe for a wall fn
   to expand at all: its output is played directly, never threaded back
   through wall or orn/expand again. Live-verified instead on the
   fixed/current code: calling a doubling wall fn on one leaf inside a
   :SEQ container invokes it exactly 3 times -- once for the container's
   own sibling-list pass (core.wall's 'phase 1'), and once per leaf that
   pass's own doubling produced (phase 2) -- matching the 3 real call
   sites this design intends, not once more per node thereafter."
  [voice xs ctx-chain]
  (go
    (loop [xs xs]
      (when (and (seq xs) (voice-active? voice))
        (<! (play-event! voice (first xs) ctx-chain))
        (recur (rest xs))))))

(defn- fork-voice
  "A child voice at a :PAR/#{...} fork: fresh channel/program
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
   parent's own release-voice! runs, same as always.
   path is this voice's own new registry key (parent's :path plus one
   more segment, see rank-segments) -- fixed for this voice's whole
   life, never reassigned, same 'only forked at :PAR, never resampled
   afterward' rule :tx/:bar/etc. already follow. :root-path/:birth-token
   are NOT rebuilt here -- inherited unchanged from the parent (assoc
   never touches them), since liveness (voice-active?) always refers
   back to the top-level ancestor's own path, never a fork's own.
   Registers this voice into eng's :voices immediately (register-voice!)
   -- release-voice! removes it once this voice is done."
  [voice start-clock start-structural start-bar start-bar-pos start-marks path]
  (let [child (assoc voice
                     :clock (atom start-clock)
                     :structural (atom start-structural)
                     :bar (atom start-bar)
                     :bar-pos (atom start-bar-pos)
                     :marks (atom start-marks)
                     :tx (fresh-tx (:tx voice))
                     :channel (atom nil)
                     :chan-key (atom nil)
                     ;; Fresh true, not inherited from the parent -- a \partial
                     ;; written inside THIS branch only ever affects this branch's
                     ;; own bar count (see advance-bar!'s own comment), so each
                     ;; forked voice gets its own independent chance to apply one
                     ;; against its own first leaf, same as :bar/:bar-pos above are
                     ;; seeded fresh (from the parent's current values) rather than
                     ;; sharing the parent's own atoms.
                     :partial-pending? (atom true)
                     :path path
                     :tick (voice-tick-chan (:eng voice)))]
    (register-voice! (:eng voice) child)))

(defn- play-par
  "Fork each child into its own voice (see fork-voice), then await all of
   them, releasing each child's channel claim as it finishes. Each
   child's own path is the parent's own path plus that child's segment
   -- rank-segments' own mean-pitch-ascending :TAA/:TAB/... labeling,
   computed ONCE for the whole sibling list before any child forks (see
   this file's own 'Voice paths' comment on why: lowest pitch, lowest
   id)."
  [voice children ctx-chain]
  (go
    (when (voice-active? voice)
      (let [start-clock      @(:clock voice)
            start-structural @(:structural voice)
            start-bar        @(:bar voice)
            start-bar-pos    @(:bar-pos voice)
            start-marks      @(:marks voice)
            segments (rank-segments mean-pitch-rank children)
            voices (into []
                         (map-indexed
                          (fn [i child]
                            (let [path (conj (:path voice) (nth segments i))
                                  child-voice (fork-voice voice start-clock start-structural
                                                           start-bar start-bar-pos start-marks path)]
                              (go (<! (play-node child-voice child ctx-chain))
                                  (release-voice! child-voice)))))
                         children)]
        (doseq [v voices] (<! v))))))

;; ============================================================
;; Live voice registry -- id -> how many voices are CURRENTLY inside
;; that container, right now, across every :SEQ/:PAR depth (not just
;; top-level play calls). Answers "what's actually playing" for a GUI/
;; REPL consumer (see playing-ids below) without that consumer having
;; to walk core.conductor's scheduling machinery, which only fires
;; one-shot registered actions, not a general observe-everything feed.
;; >1 only when the same id is legitimately entered by more than one
;; live voice at once (e.g. the same part reused twice under a :PAR).
;; Lives on the engine instance (like :channel-claims), not a single
;; global atom -- tests spin up throwaway engines constantly, and a
;; shared global registry would leak state across them.
;; ============================================================

(defn- track-enter!
  [voice id]
  (swap! (:active-voices (:eng voice)) update id (fnil inc 0)))

(defn- track-exit!
  [voice id]
  (swap! (:active-voices (:eng voice))
         (fn [m]
           (let [n (dec (get m id 1))]
             (if (pos? n) (assoc m id n) (dissoc m id))))))

(defn playing-ids
  "The set of ids currently entered by at least one live voice on eng --
   the 'actually playing' half of a playing-vs-waiting distinction (the
   other half, every other committed/addressable id, is already
   answered by core.repo/musics.clj's own root-children -- this
   namespace has no reason to duplicate that). #{} (not an error) if eng
   is nil -- set-engine! hasn't been called yet -- so a poller can call
   this unconditionally from before the very first (connect!)."
  ([] (playing-ids *engine*))
  ([eng] (if eng (set (keys @(:active-voices eng))) #{})))

(defn- ->path
  "Accept either a real path (a vector) or a bare keyword (wrapped into
   a single-segment path) -- the common case for voice-at/assign-algo!/
   play-change/play-add, matching this project's usual accept-the-
   shorthand convention (resolve-id, etc.)."
  [path-or-id]
  (if (vector? path-or-id) path-or-id [path-or-id]))

(defn voice-at
  "The voice map currently registered at path (a vector, or a bare
   keyword for a single-segment path), or nil if nothing's there right
   now. A permanent, always-queryable handle -- registered at fork-
   voice/play, removed at release-voice! -- for as long as a voice is
   active you can read its own atoms (:clock/:structural/:tx/etc.)
   straight off this, at any moment, with none of a core.conductor
   boundary signal's transience (its own :voice only exists for the
   instant a fired action runs)."
  ([path] (voice-at *engine* path))
  ([eng path] (get @(:voices eng) (->path path))))

(defn- resolve-algo-name
  "name -> a concrete wall fn, for assign-algo!'s own sake -- the one
   place every Name shape in the play-arg mini-language ultimately
   funnels through (play-form-tagged/play-form-par/mint-leaf! all just
   pass whatever Name they parsed straight to assign-algo!, never
   resolve it themselves). Three shapes:
     nil                    -> identity-wall
     [registered-name args] -> wall/apply-factory, falling back to
                                identity-wall (with its own console
                                warning already printed) if that fails
     a bare name            -> wall/wall-fn directly, same as always,
                                except an unregistered name now ALSO
                                prints a console warning before falling
                                back to identity-wall -- previously
                                silent; made consistent with the other
                                two failure cases above rather than
                                leaving this one quietly different."
  [name]
  (cond
    (nil? name) wall/identity-wall
    (vector? name) (let [[n & args] name]
                      (or (wall/apply-factory n args) wall/identity-wall))
    :else (or (wall/wall-fn name)
              (do (println "core.wall: no algorithm registered as" name "-- falling back to identity")
                  nil)
              wall/identity-wall)))

(defn assign-algo!
  "Assign path (a vector, or a bare keyword) the algorithm registered
   under name (core.wall/wall-fn), or clear it back to identity-wall if
   name is nil. name can also be [registered-name arg1 arg2 ...] --
   registered-name must then be a FACTORY, (fn [arg1 arg2 ...] ->
   wall-fn), not a plain 3-arg wall fn -- resolved via
   core.wall/apply-factory, falling back to identity-wall (with a
   console warning) if registered-name isn't registered, its factory
   throws applying the given args, or the result isn't itself a fn.
   An unregistered bare name also now prints a console warning before
   falling back to identity-wall, for the same reason.
   Resolved once, right here -- not re-looked-up by name on
   every node -- so a later (unregister-wall! name) doesn't retroactively
   affect a path already assigned to it. Takes effect immediately,
   mid-performance, for whichever voice currently occupies path:
   voice-wall-slot-fn re-reads eng's :algo-assignments fresh on every
   single node, never once at fork time. A direct, tangible association
   -- the actual voice sounding at path (a play-change id you picked
   yourself, or a mean-pitch-ranked :TAA/:TAB/... :PAR-fork segment, or
   a play/play-add-minted top-level track id -- see rank-segments/
   next-track-id) gets an algorithm, never an arbitrary slot number.
   play/play-add's own optional :algo tag (a [Form :algo Name] anywhere
   in the tree, or a trailing :algo Name on the call itself) calls this
   itself, implicitly -- see play-form-tagged/mint-branches! -- this fn
   stays the one for reassigning an already-playing voice's algorithm
   without restarting it.
   See also core.wall/configure-wall! for a DIFFERENT way to get a
   parameterized algorithm going -- install a factory under a fixed,
   known name ahead of time, feed it args whenever you want (any time,
   independent of any play/assign-algo! call), then just reference that
   plain name here or in a play call's own :algo tag, same as any other
   registered algorithm."
  ([path name] (assign-algo! *engine* path name))
  ([eng path name]
   (swap! (:algo-assignments eng) assoc (->path path) (resolve-algo-name name))
   nil))

(defn algo-assignments
  "eng's current algorithm configuration as a plain map, path ->
   name-or-nil (nil for an identity/unassigned path) -- not the raw fns
   themselves, not meaningfully printable. Best-effort: a path holding
   some other fn entirely (assigned some way other than assign-algo!, or
   whose name was since unregistered) shows as :unknown rather than nil,
   so it still reads as visibly configured."
  ([] (algo-assignments *engine*))
  ([eng]
   (let [name-for-fn (into {} (map (fn [[k v]] [(:fn v) k])) @wall/wall-registry)]
     (into {}
           (map (fn [[path f]]
                  [path (cond
                          (= f wall/identity-wall)  nil
                          (contains? name-for-fn f) (get name-for-fn f)
                          :else                     :unknown)]))
           @(:algo-assignments eng)))))

(defn- voice-wall-slot-fn
  "The concrete algorithm fn assigned to voice's own :path right now, or
   nil if this voice has no :path at all (warm-up!'s own throwaway voice
   literal, deliberately never given one -- see engine's own docstring)
   -- core.wall/apply-wall treats nil the same as an unassigned path's
   own default (identity), so both cases are indistinguishable at the
   call site. Read fresh every time, not cached on the voice -- this is
   what makes a path's fn hot-swappable (musics.clj/assign-algo!)
   mid-performance: the very next node this voice visits picks up
   whatever's now assigned to it."
  [voice]
  (when-let [path (:path voice)]
    (get @(:algo-assignments (:eng voice)) path wall/identity-wall)))

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
   played as a single plain note, not eight).

   core.wall/apply-wall runs BEFORE ornament expansion, once, on the
   ORIGINAL authored leaf/rest/drum -- not after, on whatever orn/expand
   already unfolded it into. This matters for more than ordering: a
   result is played via play-leaves (NOT play-seq) precisely so neither
   wall nor orn/expand ever gets a second, redundant crack at its own
   already-produced output (see play-leaves' own docstring for the
   double-application bug that would otherwise cause) -- running wall
   first, then mapcat-ing orn/expand over whatever it produced, is what
   keeps this a single clean pass: wall transforms the composer's own
   written idea (matching how the container branch, just below,
   necessarily already sees pre-expansion material too, since expansion
   is per-leaf and only happens once a child is individually dispatched
   here), and ornament realization is the last, closest-to-the-speaker
   step, applied fresh to whatever the wall handed it -- not the other
   way around, which would mean transposing/reshaping already-realized
   grace notes as independent events rather than reshaping the note they
   decorate. voice-wall-slot-fn's own nil case (warm-up!'s isolated
   voice) makes an absent slot a pure no-op, same cheap cost orn/expand's
   own common-case check already has. The container branch runs the
   same apply-wall call once, on the whole resolved sibling list, BEFORE
   either play-par or play-seq ever sees it -- see core.wall's own
   docstring for why one fn signature covers both granularities."
  [voice part ctx-chain]
  (cond
    (d/leaf? part)
    (let [slot-fn  (voice-wall-slot-fn voice)
          walled   (wall/apply-wall slot-fn ctx-chain voice [part])
          expanded (mapcat #(orn/expand % ctx-chain) walled)]
      (if (= (count expanded) 1)
        (play-event! voice (first expanded) ctx-chain)
        (play-leaves voice expanded ctx-chain)))

    (or (d/rest? part) (d/drum? part))
    (let [slot-fn  (voice-wall-slot-fn voice)
          expanded (wall/apply-wall slot-fn ctx-chain voice [part])]
      (if (= (count expanded) 1)
        (play-event! voice (first expanded) ctx-chain)
        (play-leaves voice expanded ctx-chain)))

    (d/iterator? part)
    (play-iterator voice part ctx-chain)

    (d/bar? part)
    (go (mark! voice (:count part)) nil)

    (d/container? part)
    (let [chain        (build-chain part ctx-chain @(:structural voice))
          raw-children (d/children (live-repo (:tx voice)) part)
          children     (wall/apply-wall (voice-wall-slot-fn voice) chain voice raw-children)
          id           (:id part)
          type         (:type part)]
      (go
        (track-enter! voice id)
        (conductor/signal! {:kind :section :id id :type type :phase :enter :voice voice})
        (<! (case type
              :PAR (play-par voice children chain)
              (play-seq voice children chain)))
        (conductor/signal! {:kind :section :id id :type type :phase :exit :voice voice})
        (track-exit! voice id)))

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
;; A Form is a bare keyword (a repo reference), a vector [Form+]
;; (sequential -- mirrors { } Sequence in musics.ebnf), a set #{Form+}
;; (parallel -- mirrors << >> Parallel), or a tagged form [Form :algo
;; Name] -- exactly one Form, optionally followed by :algo and a
;; Name, see tagged-form?/split-tag. Vector vs set is now the ONLY
;; thing that decides sequential vs parallel -- there's no more literal
;; :par/:seq leading keyword, and an untagged vector never defaults to
;; parallel the way it used to; see form-tag+items's own docstring for
;; the one case this doesn't apply to (musics.clj/sq's own :parallel?
;; metadata, unchanged).
;;
;; Name is nil, a bare walls-registered name, or [registered-name arg1
;; arg2 ...] to feed that name's own registered FACTORY concrete
;; parameters right here, inline -- resolve-algo-name (used by
;; assign-algo!, which every Name-consuming site below funnels through)
;; is the one place this is resolved; core.wall/apply-factory does the
;; actual lookup+apply, falling back to identity (with a console
;; warning) rather than erroring, same as an unregistered bare name
;; now also does. See core.wall/configure-wall! for the OTHER way to
;; get a parameterized algorithm going: install a factory under a
;; fixed name ahead of time, feed it args independently of any play
;; call (any time, any number of times), then reference that plain
;; name here exactly like any other registered algorithm -- the two
;; approaches (inline args right here vs. a pre-configured name) are
;; deliberately both available, not one replacing the other.
;;
;; Among a group's remaining items (after any tag is stripped), context
;; refs are peeled off before real material: for a [] group this is
;; still a *leading run* (order-dependent, same as always -- the first
;; non-context item ends the run); for a #{} group, which has no
;; "leading" to speak of, EVERY item resolving to a :CONTEXT is pulled
;; out regardless of position (split-contexts-unordered). Each is pushed
;; onto the ctx-chain nearest-first, in listed order, ahead of this
;; group's own fresh Context -- so a referenced context partly overrides
;; the group's own, exactly like build-chain pushes any container's own
;; :context ahead of its ctx-chain, just for possibly more than one
;; context at once here.
;;
;; A tag's algorithm is applied through the exact same mechanism every
;; voice already goes through for real containers -- :algo-assignments
;; + assign-algo! + voice-wall-slot-fn, nothing bespoke -- in one of two
;; temporal patterns (see play-form-tagged/play-form-par):
;;   - permanent, for the entire remaining lifetime of a voice that's
;;     being freshly minted/forked right here (play/play-add's own
;;     top-level tag -- see mint-branches! -- and each #{} branch's own
;;     tag);
;;   - temporary push/pop on the CURRENT voice's own path, restoring
;;     whatever was there before, for a tag sitting inside an ongoing []
;;     walk where the same voice continues on to more material
;;     afterward (play-form-tagged's non-#{} branch).
;; A #{} tagged as a whole (play-form-tagged's #{} branch, or
;; mint-branches!'s own outer-algo threading) applies its algorithm to
;; every branch as that branch's OWN default -- an individual branch's
;; own closer tag still wins (resolve-form-tag).
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
   is everything from the first non-context item on. Order-dependent --
   used for [] groups only; see split-contexts-unordered for #{}."
  [repo items]
  (loop [items items ctxs []]
    (if-let [ctx (and (seq items) (resolve-context-ref repo (first items)))]
      (recur (rest items) (conj ctxs ctx))
      [ctxs items])))

(defn- split-contexts-unordered
  "Like split-leading-contexts, but order-independent: every item
   resolving to a :CONTEXT is pulled out regardless of position, not
   just a leading run. Used for #{} groups, which have no 'leading' at
   all -- a set can't promise an order for a run to even be defined
   against."
  [repo items]
  (reduce (fn [[ctxs material] item]
            (if-let [ctx (resolve-context-ref repo item)]
              [(conj ctxs ctx) material]
              [ctxs (conj material item)]))
          [[] []] items))

(defn- tagged-form?
  "true for a play-arg form that's specifically [Form :algo Name] --
   exactly 3 elements, :algo at index 1 -- never for an ordinary 3-item
   [] group. :algo is reserved here the same way it always has been."
  [x]
  (and (vector? x) (= 3 (count x)) (= :algo (nth x 1))))

(defn- split-tag
  "[inner-form algo-name] for a tagged-form? x."
  [x]
  [(nth x 0) (nth x 2)])

(defn- resolve-form-tag
  "[inner-form algo] for form -- form's OWN tag wins if it has one
   (tagged-form?); otherwise inner-form is form itself, unchanged, and
   algo is whatever outer-algo was inherited from an enclosing #{}'s own
   whole-group tag (nil if there wasn't one). Used wherever a #{}'s
   branches are resolved -- mint-branches! (top-level) and play-form-par
   (nested) both share this, so a branch's own tag always takes
   precedence over an inherited one, consistently either way."
  [form outer-algo]
  (if (tagged-form? form)
    (split-tag form)
    [form outer-algo]))

(defn- par-form?
  [form]
  (set? form))

(defn- form-tag+items
  "[tag items] for a play-arg form that isn't itself a tagged-form? (see
   play-form/realize-form/validate-ids!, which check that shape first).
   sq's own :parallel? seq metadata -- how musics.clj/sq marks a
   container's :PAR-vs-:SEQ nature once it's been turned into a bare seq
   of children (mapv'd off the container -- there's no data-level place
   left to carry that at that point, only metadata) -- wins first if
   present: sq ALWAYS sets :parallel? explicitly, true or false, for any
   genuine container it was called on, so this branch is really 'trust
   sq's own answer', not a guess, and it's untouched by the vector/set
   split below.
   Otherwise the collection's own literal type IS the tag now -- no more
   :par/:seq leading keyword, no more untagged-vector-defaults-to-:par:
   a set is always :par, a vector is always :seq. Anything else
   sequential but neither (a LazySeq/list -- concretely, whatever
   musics.clj/times or map/filter/etc. produce from sq'd material, which
   never preserves sq's own metadata) still defaults to :seq: that shape
   is already-linear repeated/transformed material, not a fresh grouping
   of separate parts, and this is what keeps (play (times 4 (sq
   :verse))) meaning 'four repeats in a row', not 'four copies stacked
   at once' -- confirmed as a real, not hypothetical, break historically.
   contains? (not just a falsy check on :parallel?'s own value) is what
   lets sq's own explicit false survive the metadata branch unchanged --
   an ordinary :SEQ container's own sq'd material must still play
   sequentially, same as it always did, since a missing key and a false
   value need to land on opposite sides of that check."
  [form]
  (let [m (meta form)]
    [(cond
       (contains? m :parallel?) (if (:parallel? m) :par :seq)
       (set? form)               :par
       :else                     :seq)
     (if (set? form) (seq form) form)]))

(declare play-form)

(defn- play-form-seq
  [voice forms ctx-chain]
  (go
    (loop [fs forms]
      (when (and (seq fs) (voice-active? voice))
        (<! (play-form voice (first fs) ctx-chain))
        (recur (rest fs))))))

(defn- play-form-par
  "Same mean-pitch-ranked path-building play-par's own :PAR children
   get -- see rank-segments -- applied to a play-arg #{...} group's own
   material instead of a container's :children. A form's own 'pitch',
   for ranking purposes, is form-pitch-source's own best effort -- a
   bare keyword resolves against the live repo, anything else sorts
   last (see that fn's own docstring).
   Each child is first run through resolve-form-tag against outer-algo
   (the whole #{}'s own tag, if play-form-tagged handed one down; nil
   otherwise) -- a child's own closer tag wins, else it inherits
   outer-algo. A resolved algo is assign-algo!'d onto that child's own
   freshly-forked path BEFORE play-form ever walks it (permanent for
   that child voice's whole life, same mechanism/timing play/play-add's
   own top-level tag uses -- see mint-branches!). Nested #{}-branches
   still fork a genuinely nested child voice here (unlike a bare
   top-level #{}, see mint-branches!'s own docstring for why that case
   is different) -- this parent voice already exists for real material
   of its own, so there's no wasted go-block to avoid."
  ([voice forms ctx-chain] (play-form-par voice forms ctx-chain nil))
  ([voice forms ctx-chain outer-algo]
   (go
     (when (voice-active? voice)
       (let [start-clock      @(:clock voice)
             start-structural @(:structural voice)
             start-bar        @(:bar voice)
             start-bar-pos    @(:bar-pos voice)
             start-marks      @(:marks voice)
             resolved (mapv #(resolve-form-tag % outer-algo) forms)
             segments (rank-segments #(mean-pitch-rank (form-pitch-source (live-repo (:tx voice)) %))
                                      (map first resolved))
             voices (into []
                          (map-indexed
                           (fn [i [f a]]
                             (let [path (conj (:path voice) (nth segments i))
                                   child-voice (fork-voice voice start-clock start-structural
                                                            start-bar start-bar-pos start-marks path)]
                               (when a (assign-algo! (:eng voice) path a))
                               (go (<! (play-form child-voice f ctx-chain))
                                   (release-voice! child-voice)))))
                          resolved)]
         (doseq [v voices] (<! v)))))))

(defn- play-form-tagged
  "form is tagged-form? -- apply its algorithm through the SAME
   :algo-assignments/assign-algo!/voice-wall-slot-fn mechanism every
   voice already goes through, no separate one-shot path. If the inner
   form is itself #{} (par-form?), this whole tagged group's algorithm
   is each branch's own default (play-form-par's outer-algo, a branch's
   own closer tag still winning) -- there's no single existing voice a
   push/pop would even reach, since forking mints brand new paths.
   Otherwise (inner is a keyword/[]/d/part?), no forking happens here --
   the CURRENT voice's own path is temporarily reassigned for exactly
   the span of playing inner, then restored to whatever was there
   before (not unconditionally to identity), so nesting composes: a tag
   nested inside an already-tagged outer span correctly falls back to
   the OUTER tag afterward, not identity. voice-wall-slot-fn re-reads
   :algo-assignments fresh on every node, so this reaches every node
   inner touches -- nested containers/groups included -- with no
   separate resolve-material/apply-wall-directly step needed. Safe
   without locking: play-form-seq (the only caller that reaches a
   tagged form still nested inside ongoing material) walks one child at
   a time inside one go-block, so nothing else touches this voice's own
   path between the assign and the restore."
  [voice form ctx-chain]
  (let [[inner name] (split-tag form)]
    (if (par-form? inner)
      (play-form-par voice (seq inner) ctx-chain name)
      (let [eng   (:eng voice)
            path  (:path voice)
            prior (get @(:algo-assignments eng) path wall/identity-wall)]
        (assign-algo! eng path name)
        (go
          (<! (play-form voice inner ctx-chain))
          (swap! (:algo-assignments eng) assoc path prior))))))

(defn- play-form-group
  [voice tag items ctx-chain]
  (let [repo-now            (live-repo (:tx voice))
        [ctx-refs material] (if (= tag :par)
                               (split-contexts-unordered repo-now items)
                               (split-leading-contexts repo-now items))
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

    (tagged-form? form)
    (play-form-tagged voice form ctx-chain)

    (or (set? form) (sequential? form))
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
   (let [ctx     (c/context)
         ;; tempo defaults to 120 on an empty ctx-chain (see resolve/sample),
         ;; so dur-secs = duration*2 (musical->seconds: duration*240/120)
         ;; -- pick duration to land on note-ms.
         dur     (/ (/ note-ms 1000.0) 2)
         part    {:type :SEQ :id ::warmup :context ctx
                   :children (vec (repeatedly n #(d/leaf ::warmup ctx dur [1] nil -79 nil false)))}
         path    [::warmup]
         voice   {:eng eng :path path :root-path path :birth-token (gensym "warmup")
                   :tx (fresh-tx (:repo eng))
                   :clock (atom 0.0) :structural (atom 0)
                   :bar (atom 1) :bar-pos (atom 0) :marks (atom {})
                   :channel (atom nil) :chan-key (atom nil)
                   :partial-pending? (atom true)
                   :tick (voice-tick-chan eng)
                   :origin-nanos (System/nanoTime)}]
     (register-voice! eng voice)
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
   on) and throw a clear ex-info immediately -- before play/play-change/
   play-add touch eng's :voices registry or start any voice -- if a
   keyword doesn't resolve in
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

    (tagged-form? form)
    (validate-ids! repo-now tx (first (split-tag form)))

    (or (set? form) (sequential? form))
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
    ;; nil is one real, confirmed exception -- concretely, sq
    ;; returning nil for an id that doesn't resolve to a container --
    ;; which used to silently no-op with no sound and no error at all.
    (nil? form)
    (throw (ex-info (str "play: don't know how to play nil -- expected"
                          " a part id, a group vector, or material from sq")
                     {:form form}))

    ;; A bare fn is the other real, confirmed exception -- user-reported
    ;; live: (play #(times 5 (shuffle %)) :verse) silently "worked" (no
    ;; error) but only ever played :verse plain, once -- the fn arg fell
    ;; through this same cond with no matching clause (not a keyword,
    ;; not sequential?, not nil), so it silently no-op'd exactly like an
    ;; :assignment/:BAR node does, then play's own top-level args are
    ;; always sequential regardless, so :verse played normally right
    ;; after it -- no crash, no sign anything was wrong, just quietly
    ;; not doing what was asked. play-xf is the actual entry point for
    ;; this shape (a transform fn applied to a keyword id's own (sq id)
    ;; before playing) -- caught here and pointed at it directly rather
    ;; than left to silently do nothing.
    (fn? form)
    (throw (ex-info (str "play: don't know how to play a bare function --"
                          " did you mean play-xf? (play-xf f & args)"
                          " applies f to each keyword id's own (sq id)"
                          " before playing, e.g. (play-xf #(times 5"
                          " (shuffle %)) :verse)")
                     {:form form}))))

(defn- validate-args!
  "validate-ids! every one of args against eng's own live repo -- the
   one place that owns building repo-now/tx-val for it. Used both by
   start-top-level-voice! (play-change's own path) and play-top-level!
   (play/play-add's own single-Form call shape, via [form] -- see
   play-top-level!'s own docstring for why this has to run BEFORE
   pre-fn/mint-branches! ever mutate anything)."
  [eng args]
  (let [repo-now (live-repo (:repo eng))
        tx-val   (let [v @(:repo eng)] (when (integer? v) v))]
    (doseq [a args] (validate-ids! repo-now tx-val a))))

(defn- start-top-level-voice!
  "Shared construction behind play/play-change/play-add: validate args
   FIRST -- before pre-fn runs, before anything about :voices is
   touched, so a rejected/typo'd call can never disturb what's already
   playing (a real, previously-tested invariant: validate-ids! used to
   run before play bumped its own :generation; the equivalent guarantee
   now is that it runs before pre-fn, which is where play's own 'wipe
   everything' lives) -- then run pre-fn (0-arg, side-effecting; play's
   own is '(reset! (:voices eng) {})', play-change/play-add's own is a
   no-op), then build a fresh top-level voice at path (its own :path AND
   :root-path -- a top-level voice always governs its own liveness,
   never an ancestor's), register it into eng's :voices (unconditionally
   overwriting whatever was there -- that's the whole supersede
   mechanism: an old occupant's own next voice-active? check reads a
   different :birth-token there now and winds down), and kick off
   play-form-group."
  [eng path args pre-fn]
  (validate-args! eng args)
  (pre-fn)
  (let [voice    {:eng eng :path path :root-path path :birth-token (gensym)
                   :tx (fresh-tx (:repo eng))
                   :clock (atom 0.0) :structural (atom 0)
                   :bar (atom 1) :bar-pos (atom 0) :marks (atom {})
                   :channel (atom nil) :chan-key (atom nil)
                   :partial-pending? (atom true)
                   :tick (voice-tick-chan eng)
                   :origin-nanos (System/nanoTime)}
        root-ctx (:context (get (live-repo (:tx voice)) :ROOT))]
    (register-voice! eng voice)
    (ensure-ticker! eng)
    (reset! (:state eng) :playing)
    (let [done (play-form-group voice :seq args (if root-ctx [root-ctx] []))]
      (go (<! done) (release-voice! voice)))))

;; ============================================================
;; play/play-add -- start one or more top-level voices at real,
;; addressable short track ids (see next-track-id) instead of an
;; explicit or internal-sentinel path, from a SINGLE Form plus an
;; OPTIONAL trailing :algo name -- (play Form) or (play Form :algo
;; Name), never play's older variadic multi-form shape. play flushes
;; EVERYTHING first, replacing whatever's currently playing; play-add
;; never does, joining whatever is already sounding instead
;; (play-change, unchanged, is still the one for superseding a single
;; chosen path by hand, and keeps its own older explicit-path/variadic-
;; args shape -- it targets one already-known path, so none of the
;; #{}-minting below applies to it). track-ids itself lives earlier in
;; this file (with rank-segments -- the OTHER consumer of the same
;; alphabet, for mean-pitch-ranked :PAR path segments nested INSIDE an
;; already-existing voice, as opposed to top-level minting here).
;; ============================================================

(defn- next-track-id
  "The first track-ids entry NOT currently occupied in eng's :voices.
   play always calls this right after flushing eng's :voices, so in
   practice a solo (non-#{}) call deterministically gets :TAA every
   time. mint-branches! calls this once per LEAF voice it mints (a #{}
   form mints one per branch, recursively) -- occupancy only matters at
   all within one call because of that, not because two separate play
   calls could otherwise collide. Only throws if genuinely every one of
   the 676 is occupied at once, an extreme edge case, not something
   normal use could hit."
  [eng]
  (let [occupied? (fn [id] (contains? @(:voices eng) [id]))]
    (or (some (fn [id] (when-not (occupied? id) id)) (track-ids))
        (throw (ex-info "play: no free track id left (all 676 :TAA..:TZZ in use)" {})))))

(defn- split-call-args
  "[form algo-name] from play/play-add's own & args -- exactly (form)
   or (form :algo name), matching [Form :algo Name]'s own shape one
   level up (a call's own trailing args aren't wrapped in a vector the
   way a nested tag is, since there's nothing here to wrap -- but the
   :algo-at-position-1 discipline is identical). Anything else (0 args,
   2, 4+, or 3 with :algo not in the middle) is a clear ex-info, not a
   silent misparse."
  [args]
  (let [n (count args)]
    (cond
      (= n 1) [(first args) nil]
      (and (= n 3) (= :algo (second args))) [(first args) (nth args 2)]
      :else (throw (ex-info
                     (str "play: expected (play Form) or (play Form :algo"
                          " Name) -- got " n " args") {:args (vec args)})))))

(declare mint-branches!)

(defn- mint-leaf!
  "Mint ONE real, addressable top-level voice for form (a keyword, [],
   or already tagged-form?-stripped -- never itself a #{}, see
   mint-branches!) plus its own resolved algo (nil for none), exactly
   the construction start-top-level-voice! used to do inline before
   play/play-add could mint more than one voice per call. Returns the
   new id."
  [eng form algo]
  (let [id   (next-track-id eng)
        path [id]]
    (assign-algo! eng path algo)
    (let [voice    {:eng eng :path path :root-path path :birth-token (gensym)
                     :tx (fresh-tx (:repo eng))
                     :clock (atom 0.0) :structural (atom 0)
                     :bar (atom 1) :bar-pos (atom 0) :marks (atom {})
                     :channel (atom nil) :chan-key (atom nil)
                     :partial-pending? (atom true)
                     :tick (voice-tick-chan eng)
                     :origin-nanos (System/nanoTime)}
          root-ctx (:context (get (live-repo (:tx voice)) :ROOT))]
      (register-voice! eng voice)
      (ensure-ticker! eng)
      (reset! (:state eng) :playing)
      (let [done (play-form voice form (if root-ctx [root-ctx] []))]
        (go (<! done) (release-voice! voice))))
    id))

(defn- mint-branches!
  "form (already tag-stripped one level up by play/play-add's own
   split-call-args) plus algo (that same call's own trailing :algo, or
   nil) -> the id/path return-shape: a single id for a leaf (keyword/[]
   /tagged-non-#{}), or a #{} of (recursively) this same shape for a
   #{} form, mirroring exactly wherever #{} appears in what was typed --
   never for a []'s own internal structure, which is always one voice
   regardless of nesting (see mint-leaf!).
   A #{}'s own DIRECT children are ranked among themselves by pitch
   (form-pitch-source/mean-pitch-rank, subtree/non-keyword children
   sorting last -- same convention play-form-par already uses for a
   real :PAR fork's own siblings) before minting, so lowest pitch lands
   on the lowest id, same mixing-desk convention as everywhere else in
   this file. Each child is first resolve-form-tag'd against algo (this
   #{}'s own tag, if any -- a child's own closer tag still wins) BEFORE
   ranking/minting.
   A branch whose own (tag-resolved) content is IMMEDIATELY just
   another #{}, with nothing else of its own to play, never gets an
   intermediate wrapping voice for that fact alone -- it recurses
   straight into ITS OWN children instead, which pull their ids from
   the exact same shared, occupancy-checked next-track-id pool the
   outer level does (not a fresh/independent range), so no go-block is
   ever spent purely on forking with no material of its own -- 'every
   voice/track gets an id, not subparts.'"
  [eng form algo]
  (if (par-form? form)
    (let [resolved (->> (seq form)
                         (map #(resolve-form-tag % algo))
                         (map-indexed (fn [i [f a]] [i f a]))
                         (sort-by (fn [[i f _]] [(mean-pitch-rank (form-pitch-source (live-repo (:repo eng)) f)) i])))]
      (into #{} (map (fn [[_ f a]] (mint-branches! eng f a))) resolved))
    (mint-leaf! eng form algo)))

(defn- play-top-level!
  "Shared body behind play/play-add's own new single-Form call shape:
   validate the (already tag-stripped) form, run pre-fn (play's own
   flush / play-add's own no-op) exactly once regardless of how many
   voices form ends up minting, then mint-branches!."
  [eng form algo pre-fn]
  (validate-args! eng [form])
  (pre-fn)
  (mint-branches! eng form algo))

(defn play
  "Compose and play a structure from pre-defined repo parts, at one or
   more real, addressable short track ids (see next-track-id/
   mint-branches!) instead of an internal sentinel path. Uses *engine*
   -- call set-engine! first. One core.async voice per independent
   line; #{} forks, [] and Iterators (including :count :infinite ones)
   don't.

   Exactly one Form, plus an OPTIONAL trailing :algo name -- see the
   Form grammar in the play-arg mini-language comment above the
   play-form* fns:
     keyword           -- single part reference: :verse1
     [Form+]           -- sequential group, ALWAYS -- mirrors { }
                          Sequence; no more :par-by-default guessing
     #{Form+}          -- parallel group, ALWAYS -- mirrors << >>
                          Parallel; each branch forks its own voice
     [Form :algo Name] -- tag Form with an algorithm -- Name is nil, a
                          walls-registered name, or [name arg1 arg2
                          ...] to feed a registered FACTORY concrete
                          params inline (see resolve-algo-name/
                          core.wall/apply-factory) -- see tagged-form?/
                          play-form-tagged for the full mechanism
     :algo Name        -- OPTIONAL, trailing, at the CALL level itself
                          (split-call-args) -- same idea one level up,
                          applied as every top-level branch's own
                          default (mint-branches!)
     context-ref        -- a keyword resolving to a repo :CONTEXT, as
                          an item of a group: applies nearest, partly
                          overriding that group's own context ([]:
                          leading run only; #{}: any position, see
                          split-contexts-unordered)

   play no longer accepts several top-level forms implicitly
   sequenced -- (play :verse1 :verse2) is now (play [:verse1 :verse2]),
   matching the same one-Form discipline every nested level already has.

   Examples:
     (play :verse1)
     (play [:verse1 :verse2])
     (play #{:melody :bass})
     (play [:context1 :verse1])
     (play :melody :algo :retrograde)
     (play #{[:a :algo :x] [:b :algo :y]})
     (play :melody :algo [:transpose 5])            ; inline params --
                                                      ; :transpose must be
                                                      ; registered as a
                                                      ; factory, not a
                                                      ; plain wall fn
     (play :melody :algo :myLocation)                ; a name previously
                                                      ; fed via
                                                      ; core.wall/
                                                      ; configure-wall!

   Flushes EVERYTHING -- every voice anywhere, at any path, however it
   got there (a previous play, play-change, or play-add) -- by wiping
   eng's whole :voices registry before starting: every one of them
   reads that on its own very next check and winds down. See
   play-change/play-add for the narrower, single-path versions of this.
   Validated (validate-args!) BEFORE the flush -- a rejected/typo'd
   call can never disturb what's already playing.

   Returns the id/path return-shape mint-branches! produces: a single
   keyword for a plain Form, or a #{} of ids (recursively, matching
   wherever #{} was written) for a #{} Form -- every entry is a real,
   directly usable top-level path on its own, no reconstruction needed,
   e.g. (play #{:melody :bass}) -> #{:TAA :TAB}, (play #{:melody #{:a
   :b}}) -> #{:TAA #{:TAB :TAC}}. Pass any of these straight back into
   assign-algo!/voice-at/play-change/play-add to keep controlling that
   specific voice."
  [& args]
  (let [eng         *engine*
        [form algo] (split-call-args args)]
    (play-top-level! eng form algo #(reset! (:voices eng) {}))))

(defn play-change
  "Like play, but supersedes only whichever voice is CURRENTLY
   registered at path (a vector, or a bare keyword) -- every other path
   is untouched. The old occupant's own next liveness check
   (voice-active?) fails the moment this one takes its place (a fresh
   :birth-token at the same path), and it winds down normally (note-off
   sent, release-voice! runs -- its own conditional removal from
   :voices is correctly a no-op by then, since it's no longer path's
   current occupant). Keeps its own older variadic-args/implicit-:seq
   shape (via start-top-level-voice!) rather than play/play-add's newer
   single-Form-plus-:algo one -- it always targets exactly one already-
   known path, so mint-branches!'s #{}-minting/return-shape machinery
   (built for 'how many voices, and which ids, does this call need to
   invent') doesn't apply here at all."
  [path & args]
  (start-top-level-voice! *engine* (->path path) args (constantly nil))
  nil)

(defn play-add
  "Like play, but never flushes -- joins whatever's already sounding,
   at one or more freshly-minted short track ids (see next-track-id/
   mint-branches!), instead of replacing everything (see play for
   'replace'; see play-change to supersede one chosen path by hand
   instead of auto-picking one). Same single-Form-plus-optional-:algo
   call shape as play -- see play's own docstring for the full
   mini-language, examples, and return-shape.
   Returns the id/path return-shape mint-branches! produces -- pass it
   straight back into assign-algo!/voice-at/play-change/play-add
   afterward to keep controlling those specific voices.
     (play-add :verse)
     (play-add #{:melody :bass} :algo :retrograde)"
  [& args]
  (let [eng         *engine*
        [form algo] (split-call-args args)]
    (play-top-level! eng form algo (constantly nil))))

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
  "voices in the SAME mean-pitch-ranked order play-form-par's own real
   voices end up in -- required now that #{} (unordered) is the only
   spelling for parallel material: forms arrives as (seq of a set), with
   no reliable order of its own to just mapv over the way a literal,
   ordered [:par ...] vector used to provide for free. Ranking here
   keeps display showing voices in the same order play would actually
   assign them to :TAA/:TAB/..., not an arbitrary hash order."
  [repo forms ctx-chain clock structural]
  (let [ranked (->> (map-indexed vector forms)
                    (sort-by (fn [[i f]] [(mean-pitch-rank (form-pitch-source (live-repo repo) f)) i])))
        voices (mapv (fn [[_ f]] (first (realize-form repo f ctx-chain clock structural))) ranked)]
    [[{:kind :par :voices voices}] clock structural]))

(defn- realize-form-group
  [repo tag items ctx-chain clock structural]
  (let [repo-now            (live-repo repo)
        [ctx-refs material] (if (= tag :par)
                               (split-contexts-unordered repo-now items)
                               (split-leading-contexts repo-now items))
        chain (reduce (fn [chain ctx] (into [ctx] chain))
                       (into [(c/context)] ctx-chain)
                       ctx-refs)]
    (if (= tag :par)
      (realize-form-par repo material chain clock structural)
      (realize-form-seq repo material chain clock structural))))

(defn- realize-form
  "Mirrors play-form's own dispatch (see that fn/the mini-language
   comment above it), with one deliberate simplification: display is
   purely structural/timing preview, with no *engine*/:algo-assignments
   at all, so a tagged-form? here just unwraps and realizes inner --
   the algorithm itself has no visible effect on display's own output,
   same as it always implicitly did before tags existed (display never
   modeled wall transforms)."
  [repo form ctx-chain clock structural]
  (cond
    (keyword? form)
    (realize-node repo (get (live-repo repo) form) ctx-chain clock structural)

    (d/part? form)
    (realize-node repo form ctx-chain clock structural)

    (tagged-form? form)
    (realize-form repo (first (split-tag form)) ctx-chain clock structural)

    (or (set? form) (sequential? form))
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

    ;; See validate-ids!'s own comment on this same case -- a bare fn
    ;; used to silently fall through to the :else no-op below instead of
    ;; ever reaching play-xf, the actual entry point for this shape.
    (fn? form)
    (throw (ex-info (str "display: don't know how to play a bare function"
                          " -- did you mean play-xf? (play-xf f & args)"
                          " applies f to each keyword id's own (sq id)"
                          " before playing, e.g. (play-xf #(times 5"
                          " (shuffle %)) :verse)")
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

  ;; 2. Play -- no eng arg needed anywhere, always exactly one Form
  (play :s1)
  (play [:s1 :s1])                         ;; :s1 then :s1 again -- [] is
                                            ;; always sequential now
  (play #{:s1 :forte})                     ;; #{} is always parallel, forks
                                            ;; a voice per branch -- a
                                            ;; literal Clojure set can't
                                            ;; hold the same value twice,
                                            ;; so ":s1 against itself" needs
                                            ;; two distinguishable branches,
                                            ;; e.g. differently tagged
  (play [:forte :s1])                      ;; :s1, but louder
  (play #{[:s1] [:forte :s1]})             ;; one voice plain, one loud

  ;; 3. Transport
  (pause!)
  (resume!)
  (stop!)

  ;; 4. Live edit -- takes effect as soon as playback reaches :s1 again
  ;; (swap! (:repo *engine*) assoc-in [:s1 :children] new-children)
  )
