(ns musics
  "REPL entry point — access to the complete musics system.

   Quick start:
     (def r (parse \"[verse: !mf c4 d4 e4 f4]\"))
     (commit! (:sid r))
     (play-latest!)   ; committing never moves what's playing on its own
     (connect)
     (play :verse)

   (mu!) drops into a nested REPL for staging several parts in a row
   without the (s! \"...\") wrapper call each time -- a bare (quoted)
   musics string stages itself, (c1!) commits what was just staged,
   everything else evals normally. See (mu!)'s own docstring, and
   doc/startup.md's \"Shortcut: mu!\" section.

   IDs are first-class handles throughout the API.
   Keywords, strings, and composites are all accepted:
     (play :verse)       — registry lookup
     (play \"verse\")      — same
     (play my-composite) — direct

   core.repo (id -> tx -> node) is the one true store. Reading (parse,
   and every inspection fn -- find/ids/children/inspect/ctx/ctx-value/
   locate/describe/print-structure) works against the latest committed tx by
   default, with an optional trailing tx arg to look at any point in
   history instead. Playing (the live engine) reads through each voice's
   own :tx, seeded once from play-tx when that voice is born -- committing
   never moves it, and neither does (play-tx!)/(play-latest!) once a
   voice is already running; those only affect what the *next* (play ...)
   call starts at. Redirecting a voice already in flight is
   (schedule-tx!)'s job -- see core.async-engine's own docstring. session
   only holds the auto-id counters now, not the repo itself. (write
   path)/(load path) persist or replace the whole committed history;
   (reset) starts a brand new one.

   File layout: the functions you reach for constantly -- parse/commit!/
   play and friends -- read top-to-bottom first, right after State/
   Resolution; everything more specialized (generative transforms,
   context-chain internals, conductor scheduling, the algo registry,
   persistence) follows afterward, same shape as input.forth's own
   kernel-first reorganization. If something you expected near the top
   isn't there, it's further down, not missing."
  (:refer-clojure :exclude [find load reverse shuffle])
  (:require [clojure.main :as cmain]
            [clojure.pprint :as pprint]
            [input.grammar-parser :as gp]
            [input.reader.flat-tree-walker :as walker]
            [input.reader.flat-core-builder :as flat]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.conductor :as conductor]
            [core.wall :as wall]
            [core.persist :as persist]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [algo.random :as rnd]
            [core.domain.ornaments :as orn]
            [common.defaults :as defaults]
            [input.lilypond-import :as ly]
            [core.async-engine :as engine]
            [output.midi.midi-live :as live]
            ))



;; ============================================================
;; State
;; ============================================================

;; core.repo (id -> tx -> node) is the one true store now -- session only
;; keeps the auto-id counters and the variable map (name -> {:children
;; :context}), both pure bookkeeping (never touched by tx history) rather
;; than versioned data.
(defonce session (atom {:auto-ids {} :var-map {}}))
(defonce receiver (atom nil))                               ;; MIDI receiver

;; Guarantee a real :ROOT context exists even before the first (parse ...)/
;; (reset) -- the same guarantee flat/empty-session used to give for free.
;; Idempotency-guarded (rather than unconditional) so reloading this ns
;; within the same JVM doesn't stomp on a session already in progress --
;; core.repo's registry is defonce'd too, so it's already there after the
;; first load.
(defonce ^:private _bootstrap
  (when (nil? (repo/current :ROOT))
    (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
    (repo/play-latest!)))

;; ============================================================
;; Resolution — IDs are first-class handles
;; ============================================================

(defn- resolve-id
  "Resolve a handle to a domain object, as of tx (defaults to latest
   committed).
   keyword → look up in the repo    string → keyword (then look up)
   map     → as-is (assumed to be a node map already)"
  ([x] (resolve-id x (repo/latest-tx)))
  ([x tx]
   (cond
     (nil? x) nil
     (keyword? x) (get (repo/view tx) x)
     (string? x) (get (repo/view tx) (keyword x))
     (map? x) x                                            ;; assume it's a node map
     :else (throw (ex-info (str "Cannot resolve: " (pr-str x)) {:arg x})))))

;; ============================================================
;; Parse & staging
;; ============================================================

(defn- root-id-of
  "child (a keyword ref or an inline leaf) -> the id it'd show up under
   in :ROOT's own :children, same resolution root-children uses -- nil
   for an inline child with nothing worth calling an id (a bare Bar has
   no :id field at all)."
  [child]
  (if (keyword? child) child (:id child)))

(defn parse
  "Parse musics text against the session's current *committed* repo (same
   :ROOT, continuing auto-id counters — a later parse can reference an
   earlier one's named parts, as long as that earlier parse was committed
   first). Nothing lands in the session itself yet: every id this call
   introduced or changed is staged under a fresh sid, invisible to
   (inspect), (play), (ctx), (ctx-value), etc. until (commit! sid) is called — same as
   editing an existing id would be. Returns {:sid sid :ids ids}, or nil
   on failure.

   ids is this call's own *top-level* ids only (a direct child of :ROOT
   -- excludes anything only reachable nested inside one of them, even
   though that nested id also changed and got staged same as always),
   as a plain vector, in the order they were written. Computed directly
   from this walk's own freshly-built :ROOT :children (already
   the corrected, deduplicated list a redefinition leaves in place -- see
   flat-core-builder/pop-container), not by a later, indirect round-trip
   through root-children (a session-wide, cross-call view) the way
   play-file! used to work.

   The auto-id counter itself is not part of this staging -- it advances
   immediately so a second (parse ...) before the first is committed
   doesn't generate a colliding id (leaving a gap in numbering if the
   first is ever aborted). Variables (name = [ ... ] / \\name) work the
   same way -- a definition lands in the session's var-map immediately,
   not gated behind (commit! sid), matching how auto-ids already behaves
   (and how the old text-level var-registry always did too). A \\name
   referenced before its own definition, or never defined at all, is a
   walk-time error: this fn catches it and returns nil, same as a
   grammar-level parse failure."
  [text]
  (try
    (if-let [insta-tree (gp/try-parse text)]
      (let [old-repo    (into {} (repo/view (repo/latest-tx)))
            flat-result (walker/walk insta-tree text
                                     {:repo old-repo :auto-ids (:auto-ids @session)
                                      :var-map (:var-map @session)})
            new-repo    (:tree flat-result)
            changed-ids (repo/changed-ids old-repo new-repo)
            sid         (repo/begin-staged-tx!)
            ids         (into [] (comp (map root-id-of) (filter changed-ids))
                              (:children (get new-repo :ROOT)))]
        (repo/stage-many! sid (select-keys new-repo changed-ids))
        (swap! session assoc
               :auto-ids (:auto-ids flat-result)
               :var-map  (:var-map flat-result))
        {:sid sid :ids ids})
      nil)
    (catch clojure.lang.ExceptionInfo e
      (println (.getMessage e))
      nil)))

(defn s! [text]
  (parse text))

(defn usages
  "Every id whose CURRENT content directly references id as one of its
   own :children -- i.e., who else would be affected if you re-parse/
   re-commit id right now. Direct references only, not transitive (a
   grandparent reaching id only through an intermediate parent isn't
   included) -- that's what 'my next edit to id affects these' actually
   means. Read-only, as of tx (defaults to latest committed) -- a plain
   scan over the current view, same cost/shape as (ids)/root-children,
   nothing about core.repo's own versioning changes.

   Exists because sharing a container across multiple parents (the same
   id in more than one :children vector -- deliberate, cheap DAG reuse,
   see the domain model's own \"no parent pointer\" design) has a real,
   easy-to-miss consequence: core.repo versions by id alone, so
   redefining id under one parent's own name silently redefines it for
   EVERY parent that references it, with nothing anywhere flagging that
   before it happens. This doesn't change that behavior (still correct,
   still how content-addressed reuse is supposed to work) -- it just
   makes the non-local effect discoverable before you commit, instead
   of only after. commit! (below) calls this automatically and warns
   (doesn't block -- deliberately sharing material this way is common
   and legitimate) when a staged edit reaches somewhere the same commit
   didn't already account for. See also children, this fn's own
   reverse (id -> what it references, rather than who references it)."
  ([id] (usages id (repo/latest-tx)))
  ([id tx]
   (let [view (repo/view tx)]
     (into #{}
           (keep (fn [[candidate-id node]]
                   (when (and (d/container? node) (some #{id} (:children node)))
                     candidate-id)))
           (seq view)))))

(defn commit!
  "Fold every edit staged under `sid` into core.repo as one atomic tx.
   Returns the new tx, or nil if `sid` has no staged edits (already
   committed, aborted, or unknown). Committing never moves what's
   currently playing -- see (play-tx!)/(play-latest!) for that.

   Prints a warning (never blocks -- shared material is common and
   legitimate) for any staged id that's also directly referenced by a
   container NOT part of this same sid's own batch (usages, above) --
   e.g. redefining a shared :motif also used by :verseB, when only
   :verseA was actually intended, would otherwise commit silently.
   :ROOT itself is excluded from this check -- it references every
   top-level id by construction (that's what 'top-level' means here),
   so flagging it would fire on every ordinary redefinition and tell
   the composer nothing they don't already know; usages itself still
   reports :ROOT accurately for anyone inspecting id's referrers directly."
  [sid]
  (doseq [[id _] (repo/staged-edits sid)]
    (let [staged-ids (set (keys (repo/staged-edits sid)))
          affected   (remove (into staged-ids #{:ROOT}) (usages id))]
      (when (seq affected)
        (println "[musics] Redefining" id "also affects" (vec affected)
                  "-- give it a new id instead if that's not intended."))))
  (repo/commit-staged! sid))

(defn c! [sid]
  (commit! sid))

(defn sc! [text]
  (let [sid (:sid (parse text))]
    (commit! sid)))

(defn abort!
  "Discard every edit staged under `sid` without ever making it visible."
  [sid]
  (repo/abort-staged! sid)
  nil)

(defn pending
  "The {id -> node} map a sid would apply if committed -- what a pending
   (parse ...) or edit is staged to change. nil if sid is unknown, already
   committed, or aborted."
  [sid]
  (repo/staged-edits sid))

(defn parse-file
  "Read musics text from a file at path and parse it into the session
   (see parse)."
  [path]
  (parse (slurp path)))

(defn try-parse
  "Parse and return the raw instaparse tree (for debugging grammar).
   Prints a formatted error on failure, returns nil.
   Useful for inspecting the parse tree before walking."
  [text]
  (gp/try-parse text))

;; ============================================================
;; Playback / transport
;; ============================================================

(defn play-tx!
  "Point the NEXT (play ...) call at `tx` explicitly -- decoupled from
   committing; (commit! ...) never moves this on its own. Each voice
   reads its own :tx, seeded once when it's born, so this only affects a
   voice not yet created -- it does not redirect anything already
   playing (that's (schedule-tx!)'s job)."
  [tx]
  (repo/play-tx! tx))

(defn play-latest!
  "Point the NEXT (play ...) call at whatever is currently the latest
   committed tx -- see (play-tx!)'s docstring on why this doesn't affect
   voices already playing."
  []
  (repo/play-latest!))

(defn connect
  "Open a MIDI receiver and wire up the live playback engine (see
   core.async-engine) against core.repo/play-tx -- each new (play ...)
   call seeds its own top-level voice from whatever tx (play-tx!)/
   (play-latest!) currently points at, not necessarily the latest
   commit; that voice's own :tx from then on is what actually plays (see
   core.async-engine's own docstring). Safe to call more than once --
   just re-opens the receiver and re-binds *engine*.
   Blocks briefly (~1/3s) on a near-silent warm-up burst first -- see
   engine/warm-up! -- to avoid an audio crackle on the very first real
   note of the session."
  []
  (reset! receiver (live/open-receiver))
  (let [eng (engine/engine @receiver repo/play-tx :ROOT)]
    (engine/set-engine! eng)
    (engine/warm-up! eng))
  (println "[musics] Connected."))

(defn warm-up!
  "Play a short burst of near-silent notes through the current engine
   (see core.async-engine/warm-up!) -- (connect) already does this
   once automatically, but this is here to re-run it standalone (e.g. to
   check whether a crackle is a JIT/GC warm-up effect or something else).
   Blocks until done.
   (warm-up!)             -- default: 16 notes, 20ms each (~1/3s)
   (warm-up! n note-ms)   -- e.g. (warm-up! 40 50) for a longer, easier-
                             to-listen-to burst (~2s)"
  ([] (engine/warm-up! engine/*engine*))
  ([n note-ms] (engine/warm-up! engine/*engine* n note-ms)))

(defn disconnect
  "Forget the MIDI receiver. Does not stop anything already playing --
   call (stop!) first if needed."
  []
  (reset! receiver nil)
  (println "[musics] Disconnected."))

(defn gui
  "Launch the cljfx GUI (gui.lib.core) -- a state window (transport +
   watch control, always open), a dedicated :ROOT window (session-wide
   live-editable defaults, opened from the state window's own 'Root
   panel...' button), and one context window per watched container,
   opened/closed automatically as you watch/unwatch it. Every slider/
   dropdown writes straight through to the real, live Context this
   session is already playing from -- see gui.lib.state's own
   docstring.
   theme is :dark (default, (gui) with no args) or :light -- see
   gui.lib.theme -- applied to every window; also switchable live
   afterward from the state window's own toggle button.
     (gui)        -- dark
     (gui :dark)
     (gui :light)
   Requires gui.lib.core via requiring-resolve rather than a top-level
   :require, so an ordinary (require 'musics) -- e.g. every test run
   -- never pulls in cljfx/JavaFX on a headless box just to load this
   ns; the cost of that require is only paid the first time (gui) is
   actually called.
   Needs a real display (X11/Wayland/macOS) -- safe to call more than
   once, it mounts idempotently."
  ([] (gui :dark))
  ([theme]
   ((requiring-resolve 'gui.lib.core/launch!) theme)))

(defn par
  "A parallel group of Forms, usable anywhere #{...} is -- (par :melody
   :bass) means the same thing as #{:melody :bass}, except it also
   accepts the SAME Form more than once: (par :melody :melody), or
   (par [:melody :algo :phaseShift] [:melody :algo :phaseShift]) for
   two copies running the same algorithm. Both are a reader error as a
   literal #{...} -- a Clojure set can't hold two equal values at all,
   so even two identically-tagged branches collide, not just two bare
   ids written twice -- both fine here: the underlying collection is an
   ordinary vector (never restricted on duplicate values), tagged
   :parallel? in its own metadata, the exact mechanism sq already uses
   to mark an extracted :PAR container's own children -- see core.async-
   engine/par for the full reasoning. #{...} keeps working exactly as
   before for its own common case (branches that are naturally already
   distinct); this only exists for the one shape #{} structurally can't
   express, not as a replacement for it.
     (play (par :melody :melody))
     (play (par [:s1 :algo :a] [:s1 :algo :b]))   ; = #{[:s1 :algo :a] [:s1 :algo :b]}
     (play (par [:s1 :algo :canon] [:s1 :algo :canon]))  ; same algo, twice -- #{} can't do this at all"
  [& forms]
  (apply engine/par forms))

(defn play
  "Play a structure of registered parts through MIDI, connecting
   automatically if (connect) hasn't been called yet. Flushes
   EVERYTHING first -- every voice anywhere, at any path, however it
   got there -- replacing whatever's currently playing (see play-add to
   join instead, play-change to supersede one chosen path by hand).
   Exactly one Form, plus an OPTIONAL trailing :algo name --
   core.async-engine/play's mini-language:
     (play :verse)                    -- single part
     (play [:verse1 :verse2])         -- sequentially -- [] is ALWAYS
                                          sequential, same duality
                                          musics.ebnf's own [ ] Sequence
                                          vs #{ } Parallel brackets have
     (play #{:melody :bass})          -- polyphony, forked onto separate
                                          MIDI channels -- #{} is ALWAYS
                                          parallel
     (play :melody :algo my-algo)     -- an OPTIONAL algorithm (a
                                          walls-registered name, or nil)
     (play #{[:a :algo :x] [:b :algo :y]}) -- each branch its own algo
     (play (par :melody :melody))     -- the SAME part twice in parallel
                                          -- illegal as a literal #{...}
                                          (see par, above)
   See core.async-engine/play's docstring for the full grammar
   (context-refs, [Form :algo Name] tags anywhere in the tree, and the
   #{}-mirroring return shape).
   Returns the id/path this voice was registered under -- a single
   keyword, or (recursively) a #{} of ids for a #{} Form, e.g. (play
   #{:melody :bass}) -> #{:TAA :TAB} -- pass any of these straight back
   into assign-algo!/voice-at/play-change/play-add to keep controlling
   that specific voice."
  [& args]
  (when (nil? @receiver) (connect))
  (apply engine/play args))

(defn play-file!
  "Read, commit, and play a musics file in one step -- (parse-file path),
   (commit! sid), (play-latest!), then (play (vec ids)) -- a single []
   Form, so play's own single-Form call shape still gets exactly one
   argument -- of whatever top-level part(s) this specific call just
   introduced, in the order they're written ([] is always sequential).
   Uses parse's own :ids directly (already this call's own top-level ids,
   in written order -- see parse's docstring) rather than root-children,
   which would need filtering down from every top-level id this whole
   session has ever seen, not just this file's.
   If the file failed to parse, ids is nil, so this ends in a (play [])
   call -- (vec nil) is [] -- still flushes everything and returns a
   fresh track id (see play's own docstring), just with no material of
   its own to play. Not a reliable failure signal on its own; parse
   itself already printed the error, and (parse-file path)/(commit! sid)
   still return their own nil on failure if you need to check
   explicitly."
  [path]
  (let [{:keys [sid ids]} (parse-file path)]
    (commit! sid)
    (play-latest!)
    (play (vec ids))))

(defn play!
  "Stage, commit, and play musics TEXT in one step -- play-file!'s own
   recipe (parse/commit!/play-latest!/(play (vec ids))), starting from a
   string instead of a file path. Mirrors input.forth's own PLAY! word
   exactly (same recipe, same starting-from-text shape) -- this was the
   one gap where Forth had a one-step stage+commit+play word and plain
   Clojure didn't.
   If text failed to parse, ids is nil, so this ends in a (play [])
   call -- same as play-file!'s own failure path, see its docstring."
  [text]
  (let [{:keys [sid ids]} (parse text)]
    (commit! sid)
    (play-latest!)
    (play (vec ids))))

(defn p!
  "Short name for play! -- same relationship s! has to parse."
  [text]
  (play! text))

(defn- round-for-display
  "x rounded to 4 decimal places (0.1ms precision -- plenty to read,
   nowhere near what's needed for audio timing) if it's a double, else x
   unchanged. Display-only: :onset/:dur-secs/:dur-played are doubles by
   deliberate design (see core.domain.resolve/musical->seconds' own
   docstring on why real-world seconds are an unavoidable float
   boundary), and stay full-precision doubles in whatever this fn's
   caller actually returns -- this only shortens what gets PRINTED,
   trading exactness nobody can read (0.6521739130434783) for exactness
   nobody can hear the difference from (0.6522)."
  [x]
  (if (double? x)
    (/ (Math/round (* x 1e4)) 1e4)
    x))

(defn- round-step-for-display
  "One display step, timing fields rounded for printing -- see
   round-for-display. Recurses into a :PAR marker's own nested
   {:voices [steps ...]}; a :mark marker and anything else pass through
   unchanged (no timing fields of their own to round)."
  [step]
  (cond
    (:voices step)
    (update step :voices (fn [vs] (mapv #(mapv round-step-for-display %) vs)))

    (:kind step)
    step

    (map? step)
    (-> step
        (update :onset round-for-display)
        (update :dur-secs round-for-display)
        (update :dur-played round-for-display))

    :else step))

(defn display
  "Like play, but fully synchronous and greedy, for debugging: resolves
   the exact same play-arg mini-language against whatever tx play-tx
   currently points at (no connect/live engine needed), turning every
   leaf it would have played into a MidiEvent via
   core.domain.resolve/resolve-event instead of scheduling/sending it --
   no core.async, no waiting, no MIDI I/O. Pretty-prints the whole
   realized structure and returns it too, for further inspection.

   The PRINTED copy has :onset/:dur-secs/:dur-played rounded to 4
   decimal places (see round-for-display) -- readability only; the
   RETURNED value keeps full double precision throughout, unrounded, so
   programmatic inspection/further computation never loses anything.

   Returns a flat vector of steps: most are resolved MidiEvent maps; a
   :PAR contributes exactly one {:kind :par :voices [steps ...]} marker
   (a single timeline can't literally fork on paper the way it does live,
   so each simultaneous branch gets its own nested step list); a bar line
   contributes a {:kind :mark :count n} marker. See
   core.async-engine/display's docstring for one behavior this
   deliberately reproduces as-is rather than correcting: a :SEQ sibling
   placed right after a :PAR currently starts back at the same onset the
   :PAR's children did, not after them, matching play-par's actual
   current behavior.

   Throws if it hits a :count :infinite Iterator -- greedy realization of
   a genuinely open-ended pattern can never terminate."
  [& args]
  (let [result (apply engine/display repo/play-tx args)]
    (pprint/pprint (mapv round-step-for-display result))
    result))

(defn stop!
  "Halt playback."
  []
  (engine/stop!))

(defn pause!
  "Pause playback -- a sounding note is held in place, not re-triggered."
  []
  (engine/pause!))

(defn resume!
  "Resume playback from exactly where it was paused."
  []
  (engine/resume!))

(defn all-notes-off
  "Silence all MIDI channels."
  []
  (when-let [rcv @receiver]
    (doseq [ch (range 16)]
      (live/all-notes-off rcv ch))))

;; ============================================================
;; mu! -- nested REPL for musics text
;; ============================================================

(defn music-eval
  "clojure.main/repl :eval hook -- a bare string is treated as musics text
   and staged via (s!), everything else evals normally. The reader is
   never touched (only :eval is hooked), so every other Clojure form --
   def, let, require, macros, whatever -- works exactly as it would at
   the ordinary REPL. A string that's already inside some other form
   (an argument to a function call, say) is untouched too, since only
   the top-level read form is checked here."
  [form]
  (if (string? form)
    (s! form)
    (eval form)))

(defn music-read
  "clojure.main/repl :read hook for (mu!) -- reads a form exactly as the
   stock repl-read does (same EOF handling and everything), except
   (exit)/(quit)/:repl/quit are recognized and turned into request-exit
   before they'd ever reach music-eval. Needed because reply's own
   (exit)/(quit) (the ones lein repl's own banner advertises) are handled
   client-side, entirely outside clojure.main/repl's read/eval loop -- a
   nested loop like (mu!) never sees that handling, so without this hook
   typing (exit) here just fails with an unresolved-symbol error instead
   of leaving."
  [request-prompt request-exit]
  (let [form (cmain/repl-read request-prompt request-exit)]
    (if ('#{(exit) (quit) :repl/quit} form) request-exit form)))

(defn mu!
  "Drop into a nested REPL where a bare (quoted) musics string stages
   itself -- (mu!) then \"[verse: !mf c4 d4]\" instead of
   (s! \"[verse: !mf c4 d4]\"). The quotes are still required (only
   :eval is hooked, not :read -- see (music-eval)); this removes the
   wrapper call, not the string literal. (exit), (quit), :repl/quit, or
   EOF (Ctrl+D) all return to the enclosing REPL -- see (music-read).
   (c1!) commits whatever was just staged."
  []
  (cmain/repl :eval music-eval :read music-read :prompt #(print "mu=> ")))

(defn c1!
  "Commit whatever the previous (mu!) form staged -- shorthand for
   (c! (:sid *1)) right after a bare musics-text entry."
  []
  (c! (:sid *1)))

;; ============================================================
;; Reset
;; ============================================================

(defn reset
  "Clear everything — session, variables, MIDI, all committed/staged
   core.repo history, every registered wall algorithm, and every
   conductor action/schedule entry. Starts a brand new session, with a
   fresh :ROOT committed as tx 1 and playback pointed at it.

   Used to only clear core.repo's own history and session -- wall
   registrations and conductor schedules silently survived a (reset),
   despite this fn's own docstring already claiming 'Clear everything'.
   (reg/reset-all!) closes that gap -- see core.registries' own
   docstring for exactly what it covers (everything except play-tx,
   which repo/reset-all! still handles directly)."
  []
  (repo/reset-all!)
  (reg/reset-all!)
  (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
  (repo/play-latest!)
  (reset! session {:auto-ids {} :var-map {}})
  (disconnect)
  (println "[musics] Reset."))

;; ============================================================
;; Registry & inspection
;; ============================================================

(defn find
  "Look up a registered composite by id (keyword or string), as of tx
   (defaults to the latest committed tx)."
  ([id] (find id (repo/latest-tx)))
  ([id tx] (resolve-id id tx)))

(defn ids
  "List all registered IDs (excluding :ROOT), as of tx (defaults to the
   latest committed tx)."
  ([] (ids (repo/latest-tx)))
  ([tx]
   (->> (repo/view tx)
        keys
        (remove #{:ROOT})
        (sort))))

(defn root-children
  "List the ids of :ROOT's own direct children, in parse order.

   This is every top-level parse this session has ever seen, not just
   the most recent one -- :ROOT accumulates across (parse ...) calls
   rather than replacing (see the :ROOT-as-container discussion: a
   named container is registered here purely because it happened to
   be declared at nesting-depth 0, not because it's semantically part
   of some single root piece). Distinct from (ids), which lists every
   id registered anywhere in the repo, not just what :ROOT points to
   directly. Anonymous/inline children (no :id, e.g. a leaf typed bare
   at the top level) show up as nil. As of tx (defaults to latest
   committed tx)."
  ([] (root-children (repo/latest-tx)))
  ([tx]
   (mapv (fn [child] (if (keyword? child) child (:id child)))
         (:children (get (repo/view tx) :ROOT)))))

(defn children
  "Children of a composite, as of tx (defaults to the latest committed
   tx) -- keyword children are resolved into their actual node values.
   See also usages (defined earlier, near commit! which depends on it)
   -- this fn's reverse: id -> what it references, rather than who
   references it."
  ([x] (children x (repo/latest-tx)))
  ([x tx]
   (let [view (repo/view tx)
         c    (resolve-id x tx)]
     (when (d/container? c)
       (mapv (fn [child] (if (keyword? child) (get view child) child))
             (:children c))))))

(defn leaves
  "Leaf children (notes/chords) of a composite, as of tx (defaults to
   the latest committed tx)."
  ([x] (leaves x (repo/latest-tx)))
  ([x tx]
   (let [c (resolve-id x tx)]
     (when (d/container? c)
       (filter d/leaf? (children x tx))))))

(defn sq
  "Children of a composite as a real Clojure seq, tagged with metadata
   ({:parallel? bool :id id :tx tx}) so ordinary seq functions (cycle,
   take, map, filter, ...) work directly on it -- the result stays
   directly playable via `play`. :parallel? is the only part of :type
   that's behaviorally relevant past the grammar stage: core.async-
   engine's play-form/realize-form (form-tag+items) read it straight
   off this seq's own metadata to decide :par vs :seq dispatch, since
   flattening a container into a bare seq leaves no data-level place
   left to carry that tag the way a literal #{...} group has one.
   (duration/part-duration are a different case, not a second consumer
   of this same metadata -- they read :type directly off a still-intact
   container, before it's ever turned into a seq via sq, so they never
   need this tag at all.) metadata isn't preserved across most seq
   transforms, which is fine here -- a reshaped result no longer claims
   to *be* the original container, just material to play, so it's
   expected (and, once transformed, correct) to fall back to plain :seq
   dispatch from that point on.
   As of tx (defaults to the latest committed tx).

     (play (take 5 (cycle (sq :par1))))

   The result is a frozen, point-in-time snapshot, same as any ordinary
   Clojure value derived from a mutable source: each leaf carries its
   own baked ctx-chain (see core.domain.resolve/chain-links), captured
   from whatever :verse's own :context WAS at extraction time -- editing
   :verse afterward (a re-parse/re-commit under the same id) never
   retroactively updates a result you already captured and held onto
   (e.g. (def m (sq :verse)), used later). This is only a real
   consideration if you actually DO hold onto an extracted result
   across a later edit to its source -- every example in this codebase
   calls sq and consumes the result in the same expression
   ((play (times N (sq :verse)))), which is never stale, since sq reads
   whatever's current at the moment IT'S called, not once, permanently,
   at container-definition time. See stale? (below) if you do need to
   check whether a held-onto result still matches its source's current
   state."
  ([x] (sq x (repo/latest-tx)))
  ([x tx]
   (let [c (resolve-id x tx)]
     (when (d/container? c)
       (with-meta (children x tx) {:parallel? (= :PAR (:type c)) :id (:id c) :tx tx})))))

(defn stale?
  "True if extracted (sq's own output, tagged with {:id :tx} metadata --
   see sq) was captured from a source id that's since been re-committed
   at a later tx. sq's result is a frozen snapshot, not a live view (see
   sq's own docstring) -- this is how to actually find out a held-onto
   result no longer matches its source, rather than discovering it by
   ear during playback.
   false (not an error, and not a guess) for anything that isn't sq's
   own output, or a transform of it that happened to drop the metadata
   (map/filter/cycle/etc. don't preserve it once material is genuinely
   reshaped -- same reasoning sq's own docstring already gives for
   :parallel? falling back to plain :seq dispatch past that point) --
   there's no source id left to compare against once the metadata is
   gone, so 'staleness' simply isn't knowable anymore, not wrongly
   reported as true or false either way."
  [extracted]
  (boolean
    (when-let [{:keys [id tx]} (meta extracted)]
      (when (and id tx)
        (some #(> % tx) (map first (repo/history id)))))))

(defn play-xf
  "Like play, but with an extra: a transform fn xf inserted between
   lookup and playback for each BARE KEYWORD id in args -- (xf (sq id))
   instead of id directly, so you don't have to write (sq ...) yourself
   every time you want to reshape what's played. Wraps the (possibly
   transformed) args into a single [] Form before handing it to play --
   play's own single-Form call shape no longer accepts several
   top-level forms directly, and [] is always sequential, so this
   preserves the same 'in written order' behavior play's own docstring
   still describes.
     (play-xf #(take 5 (cycle %)) :verse1 :verse2)
     ;; same as (play [(take 5 (cycle (sq :verse1)))
     ;;                (take 5 (cycle (sq :verse2)))])
   Anything that ISN'T a bare keyword -- a #{...}/[...] group, a
   context-ref -- passes straight through to play unchanged: the engine
   already knows how to play those directly, no sq/xf detour needed
   there (and reaching *inside* a group to transform its own members
   individually would mean re-parsing play's own context-ref-vs-part
   distinction here too, not just a keyword check)."
  [xf & args]
  (play (vec (map (fn [a] (if (keyword? a) (xf (sq a)) a)) args))))

(defn inspect
  "Print structure.
   (inspect)           — session overview, latest committed tx
   (inspect :verse)    — children of a specific part, latest committed tx
   (inspect :verse tx) — same, as of tx"
  ([]
   (let [view (repo/view (repo/latest-tx))]
     (println "Session:" (count view) "node(s), ids:" (or (seq (ids)) "(none)")))
   (println))
  ([x] (inspect x (repo/latest-tx)))
  ([x tx]
   (let [c (resolve-id x tx)]
     (cond
       (d/container? c)
       (do (println (str (name (:type c)) " \"" (:id c) "\""
                         " — " (count (:children c)) " children"
                         " — dur " (reduce + (map #(or (:duration %) 0) (children x tx)))))
           (doseq [ch (:children c)]
             (println (str "  " (pr-str ch)))))
       (some? c) (println (pr-str c))
       :else (println "Not found:" (pr-str x))))))

(defn history
  "All [tx node] pairs ever committed for id, oldest first."
  [id]
  (repo/history id))

(defn as-of
  "The committed value of id as of tx (inclusive), or nil if it didn't
   exist yet."
  [id tx]
  (repo/as-of id tx))

(defn latest-tx
  "The most recently committed tx."
  []
  (repo/latest-tx))

;; ============================================================
;; Generative transforms -- times/transpose/invert/scale/reverse/
;; shuffle/thread/tonal-*, all pure over already-materialized material
;; ============================================================

;; times/transpose/invert/scale/reverse/shuffle/thread/tonal-* below are
;; deliberately, uniformly pure: every one of them takes and returns
;; material -- a real, already-materialized seq -- never a bare id and
;; never a tx. Fetching material FROM core.repo (a keyword/string/node-
;; map id, at a chosen point in history) is sq's job alone; tx has no
;; business anywhere past that point, since a realized seq no longer has
;; any connection to the versioned store it came from. This used to be
;; blurred -- every one of these took an id-or-seq plus an optional tx
;; via a shared playable-seq helper, which meant tx was silently ignored
;; whenever material had already been resolved, and forced invert's own
;; 2-arg form into a genuinely ambiguous (axis x) vs (x tx) sniff. That
;; ambiguity is simply gone now: invert's arities are just [material]
;; and [axis material], nothing to disambiguate. Compose by nesting
;; sq at the one point tx ever matters:
;;   (play (transpose 7 (times 2 (sq :verse))))
;;   (play (transpose 7 (times 2 (sq :verse tx))))   ; explicit history

(defn times
  "n full passes of material, as a flat seq directly playable via play
   -- (play (times 4 (sq :verse))). Whichever seq material is, the
   WHOLE thing repeats n times, not just its first n elements (take
   alone counts elements, not passes -- (take 4 (cycle (sq :verse)))
   on a 5-child :verse stops mid-phrase, not after one full repeat).
   Unrelated to core.domain.flat-domain/times (a duration-scaling fn
   for the grammar's own (times ...)/(tuplet ...), never exposed here)
   despite the shared name --
   and deliberately not named `repeat`, which would shadow
   clojure.core/repeat the same way sq/parse's own `load`/`find`
   already do for their own core names, one shadow warning being
   enough. material itself must be finite -- passing an already-
   cycled/infinite seq in will hang counting it."
  [n material]
  (let [c (count material)]
    (take (* n c) (cycle material))))

(defn transpose
  "material, every pitch shifted by semitones -- (play (transpose 7
   (sq :verse))). Non-pitched items (an inline instruction marker,
   say) pass through unchanged, same as core.domain.flat-domain/
   transpose (the per-part fn this maps across material) already does
   on its own.
   NOT the same operation as the grammar's own (transpose ...)
   (`(transpose from-pitch to-pitch [...])`, which derives an interval
   from two written pitches and is key-aware/respells accidentals) --
   this is the simpler semitone-count sibling
   (core.domain.flat-domain/transpose), matching the shape of the
   example that motivated adding it. A REPL-level equivalent of the
   grammar's own two-pitch form doesn't exist yet.
   ([semitones]) alone returns a transducer instead of applying directly
   -- (sequence (transpose 7) (sq :verse)), or composed with other
   transducer-shaped combinators here via comp: (sequence (comp
   (transpose 7) (scale 2)) (sq :verse)) runs both in one pass rather
   than nesting (transpose 7 (scale 2 (sq :verse)))."
  ([semitones] (map (d/transpose semitones)))
  ([semitones material] (map (d/transpose semitones) material)))

(defn invert
  "material, pitches mirrored around axis (new = 2*axis - old) -- or,
   called without axis, each part mirrored around its OWN pitch mean
   instead (a chord folds around its own center; a single-pitch leaf
   is unchanged) -- core.domain.flat-domain/invert's own default.
   ([]) alone (zero args) returns a transducer for the no-axis/own-mean
   form -- (sequence (invert) (sq :verse)), composable via comp same as
   transpose/scale above. There's deliberately NO one-arg transducer
   form for the explicit-axis case: material's own [material] arity
   already occupies one argument, and letting a single argument mean
   either \"this is axis, hand back a transducer\" or \"this is
   material, apply directly\" is exactly the arity-sniffing ambiguity
   invert's own arities were redesigned to remove in the first place
   (see the comment above times). Use (map (d/invert axis)) directly if
   you need an explicit-axis transducer -- d/invert is the same
   per-part fn this maps across material either way."
  ([] (map (d/invert)))
  ([material] (map (d/invert) material))
  ([axis material] (map (d/invert axis) material)))

(defn- scale-value
  "factor * x -- x's own duration scaled if it's a part (a map with a
   numeric :duration), the product directly if x is itself a bare
   number, x unchanged otherwise (an inline instruction marker, say --
   same pass-through policy transpose/invert already use for anything
   without the field they touch)."
  [factor x]
  (cond
    (number? x)   (* factor x)
    (:duration x) (update x :duration #(* factor %))
    :else         x))

(defn scale
  "material, duration scaled by factor -- (play (scale 2/3 (sq :verse)))
   for a tuplet-style speedup, (play (scale 2 (sq :verse))) to double
   every duration -- this is the grammar's own (times ...)/(tuplet ...)
   operation (core.domain.flat-domain/times, the duration-multiplier
   both compile down to), named scale here instead to avoid colliding
   with musics.clj's own times, which already means \"repeat n passes\"
   -- one name, one meaning, in this namespace.
   Unlike transpose/invert, scale-value (the per-element fn this maps
   across material) is generic past musical parts -- it scales a bare
   number directly too, so this composes with plain Clojure seqs of
   numbers the same way it does with sq's own output:
   (scale 2 [1/4 1/8 1/2]) => (1/2 1/4 1).
   ([factor]) alone returns a transducer, composable via comp same as
   transpose above -- (sequence (scale 2) (sq :verse))."
  ([factor] (map (partial scale-value factor)))
  ([factor material] (map (partial scale-value factor) material)))

(defn reverse
  "material, in reverse order -- (play (reverse (sq :verse))) plays
   the phrase backwards. Order only: each part's own pitches/duration/
   timing are untouched, just the sequence they come in.
   Shadows clojure.core/reverse in this namespace (excluded up in ns,
   same as load/find already were) -- qualify as clojure.core/reverse
   if you need the plain seq version here.
   NOT the same operation as core.domain.context/env-reverse, which
   swaps envelope/ramp interpolation direction for genuinely
   time-reversed playback (a crescendo becomes a decrescendo) -- this
   is just note order, not a REPL wrapper for that."
  [material]
  (clojure.core/reverse material))

(defn shuffle
  "material, randomly reordered -- (play (shuffle (sq :verse))). Built
   on algo.random/shuffle rather than clojure.core/shuffle (also
   shadowed in this namespace, same precedent as reverse/load/find
   above) specifically so a whole generative run -- including this --
   can be pinned to a fixed, reproducible sequence via
   algo.random.core/with-seed:
   (algo.random.core/with-seed 42 (shuffle (sq :verse))).
   Wrapped in `seq`, not returned as algo.random/shuffle's own raw
   vector -- a real, confirmed bug: core.async-engine's form-tag+items
   defaults an untagged bare VECTOR to :par (for a hand-typed group like
   [:melody :bass]), and shuffle's own reordering already strips sq's
   :parallel? metadata the same way every other transform does, so
   (play (shuffle (sq :verse))) silently played as one simultaneous
   chord instead of the shuffled sequence -- confirmed live. `seq`
   turns the result into the same non-vector sequential shape times/
   map/filter/etc. already produce, which correctly keeps defaulting
   to :seq instead."
  [material]
  (seq (rnd/shuffle material)))

(defn thread
  "material, passed through f -- for composing ANY seq-in/seq-out
   transform into a play pipeline, not just the ones with a dedicated
   wrapper above (times/transpose/invert/scale/reverse/shuffle). The
   main use case: algo.random's own discrete/collection fns (choose-n,
   deep-shuffle, choose-from, weighted-choose, only, sputter) and
   anything else shaped the same way -- there are too many of those,
   too situational, to justify a dedicated wrapper apiece; thread is
   the one door that reaches all of them uniformly instead:
     (play (thread #(algo.random/choose-n 4 %) (sq :verse)))
     (play (thread algo.random/deep-shuffle (sq :verse)))
     (play (thread algo.random/choose-from (sq :verse)))
   (weighted-choose/choose return a single element, not a reshaped seq,
   so they don't fit thread's own seq-in/seq-out contract -- call those
   directly instead.)
   f is applied to material and the result passed through `seq` before
   being handed back -- NOT used raw, unlike an early version of this
   fn. A real, confirmed bug otherwise: all three of this docstring's
   own example fns (choose-n, deep-shuffle, choose-from) return a plain
   Clojure vector, not a lazy seq, and core.async-engine's form-tag+
   items defaults an untagged bare VECTOR with no :parallel? metadata
   to :par (for a hand-typed group like [:melody :bass]) -- so every
   one of those endorsed examples silently played as one simultaneous
   chord instead of the reshaped sequence, the exact same failure mode
   musics.clj/shuffle itself had (see its own docstring). `seq` turns
   f's result into the same non-vector sequential shape times/map/
   filter/etc. already produce, which correctly keeps defaulting to
   :seq instead -- f still just needs to return something sequential?,
   that part of the contract is unchanged.
   Kept as its own fn for pipeline symmetry with times/transpose/etc.
   above, and because input.forth's own THREAD word needs a real
   primitive to apply an execution token to, not just direct
   application."
  [f material]
  (seq (f material)))

;; ============================================================
;; Context query
;; ============================================================

(defn- ctx-ref->part [view child]
  (if (keyword? child) (get view child) child))

(defn- ancestor-path
  "Path of nodes from :ROOT down to (and including) target itself, found
   by searching the tree once -- there's no parent pointer on Context
   (see core.domain.context), so this is the only way to recover it for
   a bare id or value. Matches by value equality against target (the
   already-resolved part, e.g. from resolve-id), not by :id text -- a
   leaf's :id is just its display token and can collide (two identical
   notes in the same sequence both print \"c4\"), so it's not a safe
   search key on its own. nil if target isn't reachable from :ROOT at
   all (e.g. a hand-built value never actually parsed into this tree).
   Picks the first matching path found (a DAG-shaped repo, via a :name
   reference, can in principle have more than one)."
  [view target]
  (letfn [(search [part trail]
            (cond
              (nil? part) nil
              (= part target) (conj trail part)
              (d/iterator? part)
              (search (ctx-ref->part view (:source part)) (conj trail part))
              (d/container? part)
              (some #(search (ctx-ref->part view %) (conj trail part)) (:children part))
              :else nil))]
    (search (get view :ROOT) [])))

(defn- full-ctx-chain
  "Nearest-first vector of every reachable ancestor's Context, from part
   itself up through :ROOT inclusive (a context-less node, like a Unit,
   contributes nothing and is skipped) -- built by walking the real tree
   once (ancestor-path), not a [part's own context, :ROOT's context]
   shortcut, which would miss anything authored on an intermediate
   container in between. nil if part isn't reachable from :ROOT at all."
  [view part]
  (when-let [nodes (ancestor-path view part)]
    (->> nodes reverse (keep :context))))

(defn- fmt-point [[time [value ip]]]
  (str (pr-str value) "@" time (when-not (= ip :fixed) (str "/" (name ip)))))

(defn- fmt-context
  "One-line summary of a Context's own envelope points, or nil if it
   has none of its own (nothing authored directly on that node)."
  [ctx]
  (let [envs @(:envelopes-atom ctx)]
    (when (seq envs)
      (apply str
             (interpose "  "
               (for [[k env] (sort-by key envs)]
                 (str k "=" (apply str (interpose ", " (map fmt-point @(:points-atom env)))))))))))

(defn ctx
  "Show a part's context chain: every ancestor's own authored context
   values, nearest first, as of tx (defaults to the latest committed
   tx). :ROOT's own (huge, all-defaults) context is deliberately left
   out -- it's the same for everything and just noise here; a value
   lookup (see ctx-value) still falls through to it as normal, this is
   a display convenience only.
   (ctx :verse)     — latest committed tx
   (ctx :verse tx)  — as of tx"
  ([x] (ctx x (repo/latest-tx)))
  ([x tx]
   (let [part  (resolve-id x tx)
         nodes (when part (ancestor-path (repo/view tx) part))]
     (cond
       (nil? part)
       (println "Not found:" (pr-str x))

       (nil? nodes)
       (do (println "(not reachable from :ROOT — anonymous/detached; own context only)")
           (println (str "  " (or (some-> part :context fmt-context) "(empty)"))))

       :else
       (let [chain (->> nodes reverse (remove #(= (:id %) :ROOT)))]
         (if (empty? chain)
           (println (pr-str (:id part)) "— no context chain (only :ROOT)")
           (doseq [c chain]
             (println (str (:id c) ": " (or (some-> c :context fmt-context) "(empty)"))))))))))

(defn ctx-value
  "Query a context value from a part at a given time, as of tx (defaults
   to the latest committed tx). key is canonicalized through
   common.defaults/canonical-key first, same as a write does (e.g.
   :tempo/:T -> :Tempo, :vol/:v -> :volume), so any alias reads back
   the same envelope it was written under, not just its canonical
   spelling. Samples the part's *complete* ancestor chain (see
   full-ctx-chain) -- a value authored on any intermediate container,
   not just the part's own immediate context or :ROOT, is found.
   (ctx-value :verse :tempo 0.0) → 120
   (ctx-value leaf :volume 0.5)  → interpolated value"
  ([x key time] (ctx-value x key time (repo/latest-tx)))
  ([x key time tx]
   (let [part  (resolve-id x tx)
         view  (repo/view tx)
         chain (or (full-ctx-chain view part)
                   ;; part isn't reachable from :ROOT at all (e.g. a
                   ;; hand-built value never actually parsed into this
                   ;; tree, same case ctx's "detached" branch handles) --
                   ;; fall back to just its own context plus :ROOT's,
                   ;; rather than sampling nothing.
                   (keep :context [part (get view :ROOT)]))]
     (when (seq chain)
       (c/ctx-value-chain chain (defaults/canonical-key key) time)))))

(defn active-key
  "The resolved Key (common.music-elements) in effect for x at its own
   start (time 0), as of tx (defaults to latest committed) -- whatever
   !key: last set on x's own ctx-chain, or C major if nothing ever was.
   An input-phase fn, like sq: x must be a real id/string/node map
   (whatever resolve-id/ctx-value accept), read from core.repo at a
   chosen point in history -- not an already-built seq, which has no
   single context of its own to sample and no tx of its own either.
   Feeds ks into the tonal-* fns below, e.g. (tonal-transpose
   (active-key :verse) 1 (sq :verse))."
  ([x] (active-key x (repo/latest-tx)))
  ([x tx] (ctx-value x :key 0.0 tx)))

(defn tonal-transpose
  "material, transposed by steps SCALE DEGREES (diatonic transposition,
   not semitones -- see core.domain.flat-domain/tonal-transpose and
   contrast plain transpose above) against ks (a common.music-elements
   Key -- (active-key :verse) for whatever !key: is active there, or
   any other Key to transpose against something material's own source
   doesn't have).
   ([ks steps]) alone returns a transducer, composable via comp same as
   transpose above."
  ([ks steps] (map (d/tonal-transpose ks steps)))
  ([ks steps material] (map (d/tonal-transpose ks steps) material)))

(defn tonal-invert
  "material, mirrored around axis (a MIDI pitch) in SCALE STEPS within
   ks -- see core.domain.flat-domain/tonal-invert.
   ([ks axis]) alone returns a transducer, composable via comp same as
   transpose above."
  ([ks axis] (map (d/tonal-invert ks axis)))
  ([ks axis material] (map (d/tonal-invert ks axis) material)))

(defn snap-to-scale
  "material, every pitch quantized onto ks's scale -- a pitch already
   on the scale is unchanged, one that isn't snaps up to the nearest
   scale tone. Useful straight after a chromatic transpose/invert to
   pull the result back onto the key.
   ([ks]) alone returns a transducer, composable via comp same as
   transpose above."
  ([ks] (map (d/snap-to-scale ks)))
  ([ks material] (map (d/snap-to-scale ks) material)))

(defn tonal-harmonize
  "material, each pitch gains a scale-relative harmony pitch (steps
   scale degrees above, or below for negative steps) within ks --
   thickens each note into a dyad rather than moving it (contrast
   tonal-transpose, which moves pitches instead of adding to them).
   ([ks steps]) alone returns a transducer, composable via comp same as
   transpose above."
  ([ks steps] (map (d/tonal-harmonize ks steps)))
  ([ks steps material] (map (d/tonal-harmonize ks steps) material)))

;; ============================================================
;; Navigation
;; ============================================================

(defn locate
  "Navigate to a location in the repo, starting from any registered id
   (not just :ROOT), as of tx (defaults to the latest committed tx).
   (locate :verse [0 1]) -- path selectors are index or id, see
   core.domain.resolve/locate. Returns nil for an invalid path."
  ([id path] (locate id path (repo/latest-tx)))
  ([id path tx]
   (r/locate (repo/view tx) (if (string? id) (keyword id) id) path)))

(defn describe
  "Abbreviated structural report from a registered id -- containers and
   iterators only, leaves/rests/drums counted not listed, as of tx
   (defaults to the latest committed tx). See core.domain.flat-domain/describe."
  ([] (describe :ROOT (repo/latest-tx)))
  ([id] (describe id (repo/latest-tx)))
  ([id tx] (d/describe (repo/view tx) (if (string? id) (keyword id) id))))

(defn print-structure
  "Pretty-print (describe id) as an indented tree using the surface
   grammar's brackets, as of tx (defaults to the latest committed tx).
   (print-structure)        -- whole session, from :ROOT
   (print-structure :verse) -- just that part"
  ([] (print-structure :ROOT (repo/latest-tx)))
  ([id] (print-structure id (repo/latest-tx)))
  ([id tx] (d/print-structure (repo/view tx) (if (string? id) (keyword id) id))))

;; ============================================================
;; Expand (ornaments, tremolo, grace)
;; ============================================================

(defn expand
  "Expand a leaf's modifiers (ornament, tremolo, grace) into sub-leaves,
   as of tx (defaults to the latest committed tx). Builds the leaf's
   real, complete ancestor ctx-chain first (same as ctx-value -- see
   full-ctx-chain), so an ornament's :key is sampled from wherever it's
   actually set in the tree, not just [leaf's own context, :ROOT].
   Returns [leaf] unchanged if no expandable modifier is present."
  ([leaf] (expand leaf (repo/latest-tx)))
  ([leaf tx]
   (orn/expand leaf (full-ctx-chain (repo/view tx) leaf))))

;; ============================================================
;; Conductor & scheduling -- named actions, triggered by section
;; boundaries or a voice's own bar/mark crossing
;; ============================================================

(defn register-action!
  "Park f under id, callable later via (trigger! id & args) -- either
   directly (from here, the REPL) or indirectly (a section boundary
   whose (schedule! ...) names this id)."
  [id f]
  (conductor/register-action! id f))

(defn unregister-action!
  "Forget id's parked action."
  [id]
  (conductor/unregister-action! id))

(defn trigger!
  "Apply the action registered under id to args, if one is registered."
  [id & args]
  (apply conductor/trigger! id args))

(defn schedule!
  "Fire action-id the next time a section identified by id crosses phase
   (:enter or :exit), e.g. (schedule! :verse :exit :my-action) -- one-shot,
   consumed the moment it fires; re-schedule for a repeat visit."
  [id phase action-id]
  (conductor/schedule! id phase action-id))

(defn unschedule!
  "Cancel a pending (schedule! ...) entry without ever triggering it."
  [id phase]
  (conductor/unschedule! id phase))

(defn scheduled
  "The pending {[id phase] -> action-id} ONE-SHOT schedule table (see
   schedule!), or just the action-id pending for [id phase] if given.
   schedule-tx! doesn't show up here -- it's armed on the separate,
   non-consuming repeating table instead, see scheduled-repeating below."
  ([] (conductor/scheduled))
  ([id phase] (conductor/scheduled id phase)))

(defn scheduled-repeating
  "The pending {[id phase] -> action-id} REPEATING table (see
   schedule-tx!'s own docstring for why it's a separate, non-consuming
   mechanism from schedule!/scheduled above), or just the action-id armed
   for [id phase] if given."
  ([] (conductor/scheduled-repeating))
  ([id phase] (conductor/scheduled-repeating id phase)))

(defn unschedule-repeating!
  "Cancel an armed schedule-tx! (or any other repeating-table entry)
   without waiting for a voice to trigger it -- no further [id phase]
   signal redirects anything after this."
  [id phase]
  (conductor/unschedule-repeating! id phase))

(defn schedule-tx!
  "Cut EVERY voice over to target-tx, each the next time ITS OWN crossing
   of a section identified by id, at phase, signals -- e.g.
   (schedule-tx! :verse :exit 8) jumps every voice whose own :verse
   section exits to tx 8, each at its own exit, not just whichever one
   gets there first (see core.async-engine/schedule-tx!'s own docstring
   for why a plain one-shot schedule entry isn't enough here). target-tx
   may also be :latest, resolved at the moment EACH redirect actually
   fires rather than when it was scheduled -- for \"commit now, cut over
   whenever we get there\" instead of a tx number fixed in advance.
   Stays armed until explicitly cancelled with unschedule-repeating!."
  [id phase target-tx]
  (engine/schedule-tx! id phase target-tx))

;; ============================================================
;; Wall -- pluggable per-voice playback transforms
;; ============================================================

(defn register-wall!
  "Park f under name (a string or keyword), usable thereafter as a
   voice's assigned algorithm (see assign-algo!/play's own :algo tag)
   -- e.g. (register-wall! :retrograde my-ns/my-fn). f is
   always called as
   (f nodes ctx-chain voice) -> nodes', nodes always a real seq: either
   the full sibling list of a container's children, or a singleton
   wrapping one already-ornament-expanded leaf/rest/drum -- f never
   declares which one it 'acts on', it just always receives a seq (see
   core.wall's own docstring). doc (a plain string, optional) is shown
   by (walls)/(walls name).
   f can instead be a FACTORY -- (fn [arg1 arg2 ...] -> wall-fn) -- if
   you want name usable with parameters, either inline in a play call's
   own :algo tag ([Form :algo [name arg1 arg2 ...]]) or via
   configure-wall! below. Nothing here detects which shape f is by
   default -- kind (also optional, :fn or :factory) lets you say so
   explicitly: a mismatch between how name is later used and its
   declared kind then gets a specific error ('that's a plain fn, not a
   factory' or vice versa) instead of a bare arity exception, or --
   worse, for a factory referenced bare without this -- the raw,
   unapplied factory closure being silently used as if it were the
   resolved algorithm itself. Omitting kind (the default) behaves
   exactly as before this option existed."
  ([name f] (register-wall! name f nil nil))
  ([name f doc] (register-wall! name f doc nil))
  ([name f doc kind] (wall/register-wall! name f doc kind)))

(defn unregister-wall!
  "Forget name's parked wall fn. Any path already assigned to it (via
   assign-algo!, or play/play-add's own :algo tag) keeps running
   whatever fn it already resolved to -- only a later (assign-algo!
   ... name) lookup is affected."
  [name]
  (wall/unregister-wall! name))

(defn walls
  "List registered algorithms.
   (walls)        -- every registered name with its doc
   (walls name)   -- name's full doc"
  ([] (wall/walls))
  ([name] (wall/walls name)))

(defn wall-kind
  "name's declared :kind (:fn, :factory, or nil if either unregistered or
   registered without ever declaring one via register-wall!'s optional
   4th arg -- see that fn's own docstring)."
  [name]
  (wall/wall-kind name))

(defn configure-wall!
  "Feed location's currently-registered factory args, and re-register
   the resolved wall fn back under that same name -- 'install once
   (register-wall! a factory under a stable name, ahead of time),
   configure later (this call, any time, any number of times,
   independent of any play call)'. location's own doc (if any) is
   preserved across the reconfigure. Returns location.
   ONE store, the same one register-wall!/wall-fn/assign-algo! already
   read -- not a second place holding 'the current configuration'
   separately from 'the original factory'. The real tradeoff that buys:
   after this runs once, location holds a concrete fn, not the factory
   anymore -- reconfiguring it AGAIN needs the factory re-registered
   under location first. A location used this way shouldn't also be
   used for inline args (assign-algo!/a play call's own [name arg...]
   tag) with a DIFFERENT parameter set at the same time -- register the
   factory under two distinct names if both usages are wanted at once.
   An unregistered location, a factory that throws, or a factory whose
   result isn't itself a fn all print a console warning and leave
   location's own registration untouched, same as an inline [name
   arg...] tag's own failure handling (see core.wall/apply-factory).
     (register-wall! :verseColor (fn [talea color] (fn [nodes ctx voice] ...)))
     (configure-wall! :verseColor talea1 color1)
     (play :verse :algo :verseColor)"
  [location & args]
  (apply wall/configure-wall! location args))

(defn register-preset!
  "Park an already-resolved fn f under name -- a SEPARATE store from
   register-wall!/configure-wall! above (see core.wall/configure-
   preset!'s own docstring for why). configure-preset! below is the
   usual way to get here; this fn is for when you already have a
   concrete wall fn in hand and just want to give it a switchable name."
  ([name f] (wall/register-preset! name f))
  ([name f doc] (wall/register-preset! name f doc)))

(defn unregister-preset!
  "Forget name's parked preset. Any path already assigned to it (via
   assign-algo!, or play/play-add's own :algo tag) keeps running
   whatever fn it already resolved to -- only a later reference to name
   is affected."
  [name]
  (wall/unregister-preset! name))

(defn presets
  "List registered presets.
   (presets)      -- every registered preset name with its doc
   (presets name) -- name's full doc"
  ([] (wall/presets))
  ([name] (wall/presets name)))

(defn configure-preset!
  "Build ONE named preset -- apply factory-name's own currently-
   registered FACTORY (register-wall! it there first, same as
   configure-wall! requires) to args, and park the RESOLVED result
   under preset-name in a SEPARATE store from wall-registry. Unlike
   configure-wall!, factory-name's own entry is only ever read here,
   never overwritten -- call this any number of times, under any
   number of different preset-name values, off the SAME factory-name,
   to build that many independent, coexisting, switchable presets:
     (register-wall! :colorTalea (fn [color talea] (fn [nodes ctx voice] ...)) nil :factory)
     (configure-preset! :bright :colorTalea [60 64 67] [1/8])
     (configure-preset! :dark   :colorTalea [48 51 55] [1/2])
     (play :melody :algo :bright)
     (assign-algo! :melody :dark)   ; one name in, switched

   args can be anything play's own Form mini-language accepts -- a bare
   keyword resolves as a real repo reference (a :DATA container's own
   committed values, e.g. a talea authored as '[ /4 /8 /8 /4 ]), and
   [Form+]/#{Form+} groups resolve recursively -- but the result never
   has to be a sequence the way a play argument does; a literal value
   (or a plain Clojure collection with nothing keyword-shaped in it)
   passes straight through unchanged. Resolved against the latest
   committed repo ONCE, right now -- not re-read later, same invariant
   assign-algo!/configure-wall! already have. Returns preset-name."
  [preset-name factory-name & args]
  (apply wall/configure-preset! preset-name factory-name args))

(defn assign-algo!
  "Wire path (a voice's own registry path -- see voice-at/play-change --
   or a bare keyword for a single-segment path, e.g. a play-minted
   short track id) to name's registered algorithm, or back to a no-op if
   name is nil. name can also be [registered-name arg1 arg2 ...] to feed
   a registered FACTORY concrete params right here, inline -- see
   register-wall!'s own note on the factory shape, and configure-wall!
   above for a different way to get a parameterized algorithm going
   (install a factory under a fixed name ahead of time, feed it args
   independently of any assign-algo!/play call, then just reference
   that plain name here). An unregistered name (bare or inside a
   [name...] vector), a factory that throws, or a factory whose result
   isn't itself a fn all print a console warning and fall back to a
   no-op rather than erroring.
   Takes effect immediately, mid-performance, for whichever
   voice is currently registered at path -- the fn is re-read fresh on
   every single node a voice visits, never cached at the voice's own
   creation time.
   A direct, tangible association: assign an algorithm to the actual
   voice playing there (a play-change/play-add path you picked
   yourself, or a mean-pitch-ranked :TAA/:TAB/... :PAR-fork segment, or
   a play/play-add-minted top-level track id), not an abstract slot
   number -- there is no separate index space at all, path IS the
   address, the same one eng's :voices registry uses.
   play/play-add's own optional :algo tag calls this itself, implicitly,
   at the moment either one starts a new voice -- this fn stays the one
   for RE-assigning an already-playing voice's algorithm without
   restarting it."
  [path name]
  (engine/assign-algo! path name))

(defn algo-assignments
  "*engine*'s current algorithm configuration -- a map, path ->
   registered name (or nil for an unassigned/identity path)."
  []
  (engine/algo-assignments))

(defn voice-at
  "The voice map currently registered at path (a vector, or a bare
   keyword for a single-segment path), or nil if nothing is. A
   permanent, always-queryable live-voice handle -- unlike a
   core.conductor scheduled action's own :voice, which only exists for
   the instant it fires, this can be read at any moment a voice happens
   to be active there. Mostly of interest for direct atom access
   (:clock/:structural/:tx/etc.) -- e.g. real-time GUI inspection of
   whichever voice is currently sounding at a given path."
  [path]
  (engine/voice-at path))

(defn play-change
  "Like play, but supersedes only whichever voice is CURRENTLY
   registered at path (a vector, or a bare keyword) -- every other path
   keeps playing untouched. See core.async-engine/play-change's own
   docstring for the mechanism."
  [path & args]
  (apply engine/play-change path args))

(defn play-add
  "Like play, but never flushes -- joins whatever's already sounding,
   at one or more freshly-minted short track ids (:TAA, :TAB, ... :TZZ),
   instead of replacing everything (see play for 'replace'; see
   play-change to supersede one chosen path by hand instead of
   auto-picking one). Same single-Form-plus-optional-trailing-:algo
   call shape as play -- see play's own docstring for the full
   mini-language and return shape.
   Returns the id/path this voice was registered under -- a single
   keyword, or (recursively) a #{} of ids for a #{} Form -- pass any of
   these straight back into assign-algo!/voice-at/play-change/play-add
   to keep controlling that specific voice.
     (play-add :verse)
     (play-add [:melody :bass] :algo :retrograde)
   Connects automatically, same as play."
  [& args]
  (when (nil? @receiver) (connect))
  (apply engine/play-add args))

;; ============================================================
;; Help
;; ============================================================

(defn help
  "List available commands.
   (help)          — list all
   (help \"parse\")   — full doc for a specific command"
  ([] (println "\n--- musics ---\n")
   (doseq [[n v] (sort-by first (ns-publics (the-ns 'musics)))]
     (when-let [d (:doc (meta v))]
       (println (format "  %-15s  %s" n (first (.split d "\n"))))))
   (println))
  ([name]
   (if-let [v (ns-resolve (the-ns 'musics) (symbol name))]
     (println (or (:doc (meta v)) "(no docstring)"))
     (println "Unknown command:" name))))

;; ============================================================
;; Variables
;; ============================================================

;; Variables (name = [ ... ] / \name) are real grammar constructs now,
;; resolved as part of an ordinary (parse ...) call -- there's no
;; separate "just register the definitions" step anymore (that was
;; def-vars, now gone): (parse "motif = [c4 d4 e4]") registers :motif in
;; the session's var-map exactly the same way a piece with more content
;; alongside it would, whether or not that piece is ever committed.

(defn clear-vars
  "Clear all registered variables."
  []
  (swap! session assoc :var-map {})
  (println "[musics] Variables cleared."))

;; ============================================================
;; Persistence
;; ============================================================

(defn write
  "Write the repo (as of tx, defaults to the latest committed tx) plus
   auto-ids to path as EDN."
  ([path] (write path (repo/latest-tx)))
  ([path tx]
   (spit path (persist/repo->edn (into {} (repo/view tx)) (:auto-ids @session)))
   (println "[musics] Session written to" path)))

(defn load
  "Load a session from path, REPLACING all committed history wholesale --
   re-seeds core.repo with this as a fresh baseline commit (discarding
   any prior history) and points playback at it, so subsequent (parse ...)/
   (commit! ...) calls build on real history instead of a stale snapshot."
  [path]
  (let [loaded (persist/edn->repo (slurp path))]
    (repo/seed! (:repo loaded))
    (repo/play-latest!)
    (swap! session assoc :auto-ids (:auto-ids loaded)))
  (println "[musics] Session loaded from" path))

(defn persist-session
  "Like write, but also captures the current engine's algo-assignments
   (path -> Name, the composer-typed :algo tag/assign-algo! argument --
   see core.async-engine/assign-algo!'s own docstring) alongside the
   repo + auto-ids, so a voice's algorithm survives the round-trip too,
   not just the material it plays. No engine created yet persists an
   empty algo-assignments table, not an error.

   What this deliberately does NOT capture -- review.txt point 11's own
   fuller diagnosis, kept honest rather than silently declared 'solved':
   - core.wall/configure-wall!'s own last-applied factory+args -- once
     resolved, the factory identity is gone by design ('one store, not
     two', see core.wall's own docstring), so there's nothing left to
     read back out.
   - core.conductor's schedule/repeating tables -- pending cues in ONE
     specific live performance, not composed material (closer to a
     paused breakpoint than a saved document).
   - Any wall registration itself (register-wall!/register-action!) --
     code, always the user's own job to re-run
     (e.g. re-require a setup namespace), same as any other Clojure fn
     definition never round-tripping through a data file."
  ([path] (persist-session path (repo/latest-tx)))
  ([path tx]
   (spit path (persist/session->edn (into {} (repo/view tx)) (:auto-ids @session)
                                     (engine/algo-assignments)))
   (println "[musics] Session persisted to" path)))

(defn restore-session
  "Like load, but also replays a persist-session-captured
   algo-assignments table (path -> Name) via assign-algo!, after
   re-seeding the repo -- reading a plain write-produced file works
   too, it just has nothing to replay. Ensures an engine exists first
   (creating a minimal, receiver-less one -- no MIDI, no sound, same as
   engine/engine's own nil-fs test path -- if (connect) hasn't been
   called yet), since assign-algo! is pure bookkeeping and doesn't need
   real audio wired up to do its job.
   A replayed Name that fails to resolve (its wall algorithm not yet
   re-registered in THIS process) degrades to identity-wall with a
   console warning, same as assign-algo! always has -- restore-session
   doesn't make that any louder.
   NOTE: (connect) always mints a brand-new engine, discarding whatever
   engine (and its algo-assignments) existed before -- true of ANY live
   session already, restored or not, not a new limitation. Call
   (restore-session ...) AFTER (connect), or call it again afterward,
   if you need both real sound and the restored assignments together."
  [path]
  (let [{:keys [repo auto-ids algo-assignments]} (persist/edn->session (slurp path))]
    (repo/seed! repo)
    (repo/play-latest!)
    (swap! session assoc :auto-ids auto-ids)
    (when (seq algo-assignments)
      (when-not engine/*engine*
        (engine/set-engine! (engine/engine nil repo/play-tx :ROOT)))
      (doseq [[voice-path name] algo-assignments]
        (engine/assign-algo! voice-path name))))
  (println "[musics] Session restored from" path))

(defn ly-to-mus
  "Best-effort convert a LilyPond .ly file to musics DSL text and write
   it back next to the source as a sibling <name>.mus file. Doesn't touch
   the current session -- load the result yourself, e.g.:
     (parse (slurp (from-ly-to-mus \"/path/to/piece.ly\")))
   See input.lilypond-import for what's handled and what's known
   to be out of scope (markup, lyrics, engraving overrides, ...)."
  [ly-path]
  (let [mus-path (ly/from-ly-to-mus ly-path)]
    (println "[musics] Converted" ly-path "->" mus-path)
    mus-path))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; --- Session example ---
  ;; Every (parse ...) is staged, not applied -- commit! (or abort!) it.
  (def r1 (parse "[verse: !mf c4 d4 e4 f4 | g4 a4 b4 c'4]"))
  (commit! (:sid r1))
  (def r2 (parse "[chorus: !ff g4 g4 a4 a4 | b4 b4 c'2]"))
  (commit! (:sid r2))
  (ids)                                                     ;; => (:chorus :verse)
  (inspect)                                                 ;; session overview, latest tx
  (inspect :verse)                                          ;; children of verse
  (children :verse)                                         ;; => [Leaf Leaf ...]
  (leaves :verse)                                           ;; => only pitched leaves
  (ctx-value :verse :volume 0.0)                            ;; => mf value
  (ctx :verse)                                              ;; => context chain, short form

  ;; Build on previous parts -- only resolves once verse/chorus are
  ;; committed, since parse walks against the latest committed repo
  (def r3 (parse "[song: :verse :chorus :verse]"))
  (commit! (:sid r3))

  ;; Committing never moves what's playing -- point playback explicitly.
  (play-latest!)
  (play :song)

  ;; Inspect or discard a pending parse before committing
  (def r4 (parse "[oops: c4]"))
  (pending (:sid r4))                                       ;; => {:oops #Leaf{...} ...}
  (abort! (:sid r4))                                         ;; never becomes visible

  ;; History / time-travel (read-only, per id, or across the whole repo
  ;; via the optional trailing tx on any inspection fn)
  (history :verse)                                          ;; => ([tx node] ...)
  (as-of :verse 1)                                          ;; => value right after its first commit
  (ids 1)                                                   ;; => ids as of tx 1 only

  ;; Live edit that doesn't disturb what's sounding: stage + commit a
  ;; change, keep whatever's already playing exactly as it is (each
  ;; voice reads its own :tx, seeded once at birth -- see
  ;; core.async-engine's own docstring), then choose how the edit takes
  ;; effect:
  (def r5 (parse "[verse: !mf c4 d4 e4 f4 g4]"))
  (commit! (:sid r5))            ;; new tx exists now, but playback is unaffected
  ;; (a) a brand new play call picks it up automatically:
  (play-tx! (latest-tx))         ;; seeds the NEXT (play ...) call, not anything already running
  (play :verse)                  ;; this pass performs the new tx
  ;; (b) redirect a voice that's ALREADY playing, at a chosen boundary:
  (schedule-tx! :verse :exit :latest)   ;; fires once :verse's own :exit is reached

  ;; MIDI
  (connect)
  (play :verse)
  (play #{:verse :chorus})
  (all-notes-off)
  (disconnect)

  ;; Variables -- must be defined before referenced, in the same call or
  ;; an earlier one; the value is always a Sequence (braced)
  (parse "motif = {c4 d4 e4}\n{melody: \\motif f4 g4}")

  ;; Persistence -- write/load the whole committed history
  (write "session.edn")
  (reset)
  (load "session.edn")                                      ;; replaces history wholesale

  ;; Reset everything
  (reset)
  )
