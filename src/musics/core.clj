(ns musics.core
  "REPL entry point — access to the complete musics system. Nested
   under musics/ (not a bare top-level musics.clj) specifically so it
   compiles to a real, named Java package rather than the default
   (unnamed) one -- a bare, dot-free namespace name has no practical
   consequence for a purely REPL-driven project with no :aot/:main/
   :uberjar, but this project won't stay that way forever, and the
   idiomatic Clojure convention (mirroring what `lein new app` itself
   generates) is to avoid the default package from the start rather
   than retrofit it once it actually matters. Renamed 2026-09-15 from
   plain `musics` -- every :require across the project, dev/user.clj's
   own :refer :all, and the two internal (the-ns 'musics) self-
   references below were updated in the same pass.

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
  (:refer-clojure :exclude [find load reverse shuffle repeat])
  (:require [clojure.main :as cmain]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [input.grammar-parser :as gp]
            [input.reader.flat-tree-walker :as walker]
            [input.reader.flat-core-builder :as flat]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.conductor :as conductor]
            [core.wall :as wall]
            [core.adviser :as adviser]
            [core.persist :as persist]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [common.music-elements :as el]
            [algo.random :as rnd]
            [core.domain.ornaments :as orn]
            [common.defaults :as defaults]
            [input.lilypond-import :as ly]
            [core.async-engine :as engine]
            [core.compose :as compose]
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
        (adviser/log-activity! :parse {:sid sid :ids ids})
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
  (let [tx (repo/commit-staged! sid)]
    (adviser/log-activity! :commit! {:sid sid :tx tx})
    tx))

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
  (adviser/log-activity! :play-tx! {:tx tx})
  (repo/play-tx! tx))

(defn play-latest!
  "Point the NEXT (play ...) call at whatever is currently the latest
   committed tx -- see (play-tx!)'s docstring on why this doesn't affect
   voices already playing."
  []
  (adviser/log-activity! :play-latest!)
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
  (adviser/log-activity! :connect)
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
   :require, so an ordinary (require 'musics.core) -- e.g. every test run
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
   to mark an extracted :PAR container's own children -- see core.
   compose/par for the full reasoning. #{...} keeps working exactly as
   before for its own common case (branches that are naturally already
   distinct); this only exists for the one shape #{} structurally can't
   express, not as a replacement for it.
     (play (par :melody :melody))
     (play (par [:s1 :algo :a] [:s1 :algo :b]))   ; = #{[:s1 :algo :a] [:s1 :algo :b]}
     (play (par [:s1 :algo :canon] [:s1 :algo :canon]))  ; same algo, twice -- #{} can't do this at all"
  [& forms]
  (apply compose/par forms))

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
                                          algos-registered name, or nil)
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
  (let [result (apply engine/play args)]
    (adviser/log-activity! :play {:args args :result result})
    result))

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
   core.compose/display's docstring for one behavior this
   deliberately reproduces as-is rather than correcting: a :SEQ sibling
   placed right after a :PAR currently starts back at the same onset the
   :PAR's children did, not after them, matching play-par's actual
   current behavior.

   Throws if it hits a :count :infinite Iterator -- greedy realization of
   a genuinely open-ended pattern can never terminate."
  [& args]
  (let [result (apply compose/display repo/play-tx args)]
    (pprint/pprint (mapv round-step-for-display result))
    result))

(defn stop!
  "Halt playback."
  []
  (adviser/log-activity! :stop!)
  (engine/stop!))

(defn pause!
  "Pause playback -- a sounding note is held in place, not re-triggered."
  []
  (adviser/log-activity! :pause!)
  (engine/pause!))

(defn resume!
  "Resume playback from exactly where it was paused."
  []
  (adviser/log-activity! :resume!)
  (engine/resume!))

(defn all-notes-off
  "Silence all MIDI channels."
  []
  (when-let [rcv @receiver]
    (doseq [ch (range 16)]
      (live/all-notes-off rcv ch))))

;; ============================================================
;; Adviser -- uh?/advise
;; ============================================================

(defn- print-suggestions!
  "Prints suggestions and returns nil, NOT suggestions itself -- at a
   REPL, returning the vector too meant it got printed a SECOND time
   (once here, formatted, then again as the call's own raw echoed
   return value) -- confirmed live, not a hypothetical: a real session
   showed both. Same reasoning clojure.repl/doc prints and returns nil
   rather than the docstring it just printed. Anything that needs the
   suggestions as DATA rather than a printed side effect should call
   core.adviser/what-next directly -- that one still returns the
   vector, untouched."
  [suggestions]
  (doseq [s suggestions] (println "-" s))
  (when (nil? @receiver)
    (println "  (also: not connected to MIDI yet -- (connect) when you're ready to hear playback)"))
  nil)

(defn uh?
  "Suggests up to n (default 3) sensible next REPL calls, most relevant
   first, given the current session state (uncommitted staged edits,
   whether anything's played yet, wall algorithms/factories registered
   but never built or assigned, ...). Prints each suggestion on its own line;
   returns nil, not the list (see print-suggestions!'s own docstring
   for why -- call core.adviser/what-next directly for the data). See
   (advise ...) for the same thing with a bias toward one particular
   intent."
  ([] (uh? 3))
  ([n] (print-suggestions! (adviser/what-next n))))

(defn advise
  "Like (uh?), but with an OPTIONAL intent argument -- (advise) or
   (advise :parse/:stage/:commit/:configure/:conductor/:play), or the
   same phase by its 1-based position instead of its keyword (advise 4)
   == (advise :configure) -- see core.adviser/intents' own ordered
   list -- to bias the suggestions toward what's relevant to that one
   phase of the pipeline you're currently in (see assist.txt for the
   full phase-by-phase command reference). Biasing toward one doesn't
   hide the others, it just reorders which surface first -- see
   core.adviser/what-next's own docstring for the exact priority.
   Nothing here is stored anywhere -- purely a one-off argument to this
   one call, not a mode you declare ahead of time and forget about;
   (advise) with no argument is identical to (uh?). Throws a clear
   error, showing the numbered list, for an unrecognized intent or an
   out-of-range number. Returns nil, not the suggestions -- same
   reasoning as (uh?)'s own docstring."
  ([] (uh?))
  ([intent] (print-suggestions! (adviser/what-next 3 intent))))

(defn advise!
  "Interactive: prints the numbered phase list, blocks on a single
   (read-line) for you to type either a number or a phase keyword name
   (with or without the leading colon -- \"configure\" and \":configure\"
   both work), then calls (advise ...) with whatever you chose. Blank
   input (just Enter) means no bias, same as (advise)/(uh?). A typo'd
   phase name or an out-of-range number surfaces advise's own clear
   error, same as calling it directly would."
  []
  (println "Which phase?")
  (println (adviser/numbered-intents))
  (print "> ") (flush)
  (let [input (str/trim (or (read-line) ""))]
    (cond
      (str/blank? input) (advise)
      (re-matches #"\d+" input) (advise (Integer/parseInt input))
      :else (advise (keyword (str/replace input #"^:" ""))))))

(defn wipe-adviser!
  "Reset ONLY the adviser's own state -- the recent-activity log --
   without touching the repo, session, engine, or wall's own
   factory/algo registries. Not a substitute for (reset)."
  []
  (adviser/wipe!))

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

(defn repeat
  "n passes of an existing container id's own material, as a real,
   lazily-expanded Iterator -- (play (repeat :verse 4 :unfold)) --
   unlike `times` above (n EAGER, already-flattened passes of already-
   extracted material), this defers expansion to playback time,
   matching the grammar's own (repeat unfold/volta/tremolo N [body])
   exactly: :count :infinite works here too, for the same reason
   async-engine's own docstring gives Iterators generally (no eager
   flattening, so it falls out for free).
   repeat-type is :unfold, :volta (an optional :alternative id, played
   on the LAST pass instead of id's own body), or :tremolo (a measured
   tremolo -- alternates rather than repeating verbatim). id (and
   :alternative, if given) must already be a real, committed container
   id -- an Iterator's own :source needs a real container VALUE (with
   its own :context), not sq's already-flattened seq, so passing
   already-extracted material here doesn't work.
   As of tx (defaults to the latest committed tx), same as sq.
   Shadows clojure.core/repeat in this namespace (excluded up in ns,
   same as load/find/reverse/shuffle already are)."
  [id count-val repeat-type & {:keys [alternative tx]}]
  (let [tx        (or tx (repo/latest-tx))
        source    (resolve-id id tx)
        alt-node  (when alternative (resolve-id alternative tx))
        iter-type (if (= repeat-type :tremolo) :TREMOLO :REPEAT)
        ids-atom  (atom (:auto-ids @session))
        iter-id   (flat/next-auto-id {:auto-ids ids-atom} iter-type)
        _         (swap! session assoc :auto-ids @ids-atom)
        params    (cond-> {:count count-val}
                    (not= repeat-type :tremolo) (assoc :repeat-type repeat-type)
                    alt-node (assoc :alternative alt-node))]
    (d/iterator iter-type iter-id (c/context) source params)))

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
   (active-key :verse) 1 (sq :verse)).
   KNOWN GAP, confirmed live, not just suspected: this samples x's
   context chain via full-ctx-chain, a STRUCTURAL search from :ROOT
   down by value equality (ancestor-path) -- NOT the leaf-level baked
   :ctx-chain core.domain.resolve/effective-chain uses for playback.
   For a leaf still sitting untouched in the tree this finds the same
   chain playback would; for one that's been extracted-and-transformed
   (sq, times, transpose, an ornament-expanded sub-leaf, anything
   algo-registry-generated) it's no longer value-equal to anything in
   the tree, ancestor-path returns nil, and this silently falls back to
   just [x's own :context, :ROOT's] -- missing any !key:/etc. authored
   on an intermediate container in between. The same class of bug the
   Leaf-level ctx-chain project fixed for playback, left open here."
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

(defn transpose-key
  "ks (a common.music-elements Key) transposed by semitones -- the SAME
   scale/mode, just its tonic shifted along the circle of fifths. The
   natural partner to transpose/tonal-transpose ABOVE, on material
   itself: transposing a passage without also transposing whatever Key
   it's read against leaves note-name (below) spelling against the
   ORIGINAL key, not the transposed passage's own new tonal center.
     (def new-key (transpose-key (active-key :verse) 2))
     (note-name some-leaf new-key)"
  [ks semitones]
  (el/transpose-key ks semitones))

(defn note-name
  "The correctly-spelled note name(s) for leaf's own :pitches, spelled
   against ks (a common.music-elements Key) -- a vector, one name per
   pitch (a chord spells every tone), via el/key-pitch-name: a pitch
   that's actually one of ks's own diatonic degrees is spelled with
   THAT degree's own letter, never a coincidentally-different
   enharmonic spelling of the same pitch class; a chromatic passing
   tone falls back to ks's own sharp/flat signature bias.
   ([leaf]) alone derives ks itself via (active-key leaf) -- see that
   fn's own docstring for a real, confirmed gap this inherits: correct
   for a leaf still untouched in the tree, unreliable (silently falls
   back toward C major) for one that's been extracted/transposed/
   ornament-expanded. Pass ks explicitly (e.g. from transpose-key
   above, after transposing the same material) whenever leaf isn't a
   plain, untouched tree member."
  ([leaf] (note-name leaf (active-key leaf)))
  ([leaf ks] (mapv #(el/key-pitch-name ks %) (:pitches leaf))))

(defn transpose-part
  "Commit a transposed copy of source (an id/string/node, whatever
   active-key/sq accept), in ONE step: source's own material transposed
   by semitones (plain transpose above), AND source's own active-key
   transposed by the SAME amount (transpose-key above), set as the new
   container's own !key: -- so note-name/active-key on the RESULT
   reflect its own new tonal center automatically, rather than still
   reading source's original key the way two separate manual steps
   would leave it unless you remembered to wire the second one in
   yourself.
   This is specifically for a genuine MODULATION -- see transpose-key's
   own docstring on why this is deliberately a separate, explicit
   choice, never something plain transpose/tonal-transpose do on their
   own: a transposed RESTATEMENT that should stay conceptually in
   source's own original key (a sequence, borrowed material) should
   just call (transpose semitones (sq source)) directly and commit
   that plainly instead, key untouched.
   id (optional) is the new container's own id -- omit it for a fresh
   auto-generated :s<N>, same numbering space/mechanism ordinary
   parsing mints ids from (flat-core-builder/next-auto-id against this
   session's own :auto-ids), so it can never collide with one a real
   [name: ...] parse would also pick.
   Only :key is set on the new container's own context -- nothing else
   (tempo/dynamics/etc.) is copied forward from source; add further
   (c/ctx-append ...) calls yourself if you want more than that.
   note-name's own 1-arg auto-lookup form will NOT find the RESULT's
   new key on its own children, confirmed live, not hypothetical: plain
   transpose only ever touches :pitches, so each transposed leaf still
   carries its ORIGINAL :context/:ctx-chain -- active-key's own
   structural search on one of these children finds source's original
   key, not this fn's own transposed one (see active-key's own
   docstring for the general mechanism). Always pass the key
   explicitly instead: (note-name leaf (active-key result-id)).
   Returns the new container's own id."
  ([source semitones]
   (let [ids-atom (atom (:auto-ids @session))
         id       (flat/next-auto-id {:auto-ids ids-atom} :SEQ)]
     (swap! session assoc :auto-ids @ids-atom)
     (transpose-part id source semitones)))
  ([id source semitones]
   (let [material (transpose semitones (sq source))
         new-key  (transpose-key (active-key source) semitones)
         ctx      (c/context)]
     (c/ctx-append ctx :key 0.0 new-key :fixed)
     (repo/commit-node! id {:type :SEQ :id id :context ctx :children (vec material)})
     id)))

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

(defn reg-action! [id f] (register-action! id f))

(defn unregister-action!
  "Forget id's parked action."
  [id]
  (conductor/unregister-action! id))

(defn unreg-action! [id] (unregister-action! id))

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

(defn register-factory!
  "Park f, PERMANENTLY, under factory-name (a string or keyword) --
   usable thereafter to build any number of independently-named,
   independently-hot-swappable cooked algos off of (build!/calling f
   directly) -- e.g. (register-factory! :slonimsky
   algo.melodic.slonimsky/mixed-polations-algo). f is ALWAYS
   (fn [name params] -> name), params ALWAYS a plain map: name is f's
   OWN first argument -- the name f's own result gets stored under, via
   build-algo!, as f's own last step, never a separate wrapper's
   concern. doc (a plain string, optional) is shown by (factories)/
   (factories factory-name)."
  ([factory-name f] (register-factory! factory-name f nil))
  ([factory-name f doc]
   (adviser/log-activity! :register-factory! {:factory-name factory-name})
   (wall/register-factory! factory-name f doc)))

(defn reg-factory!
  ([factory-name f] (register-factory! factory-name f))
  ([factory-name f doc] (register-factory! factory-name f doc)))

(defn unregister-factory!
  "Forget factory-name's parked factory. Factories are meant to be
   permanent -- this exists for cleanup/test isolation, not routine
   use. Any algo already built from factory-name keeps running
   unaffected; only a LATER reference to factory-name is affected."
  [factory-name]
  (wall/unregister-factory! factory-name))

(defn unreg-factory! [factory-name] (unregister-factory! factory-name))

(defn factories
  "List registered factories.
   (factories)              -- every registered factory-name with its doc
   (factories factory-name) -- factory-name's full doc"
  ([] (wall/factories))
  ([factory-name] (wall/factories factory-name)))

(defn build!
  "Look up factory-name's registered factory and call it with
   (name resolved-params) -- params ALWAYS a plain map, {param-key
   value} -- builds a cooked, ready-to-play algo and stores it under
   name, ready to be pointed at via assign-algo!/a play call's own
   :algo tag, and HOT-SWAPPABLE thereafter: call build! again with the
   SAME name (the same factory-name, or a different one) to rebuild it
   in place -- every voice/track currently pointing at name picks up
   the change on its very next node, with no separate assign-algo! call
   needed.
   Each VALUE in params can be anything play's own Form mini-language
   accepts -- a bare keyword resolves as a real repo reference (a
   :DATA container's own committed values, e.g. a talea authored as
   '[ /4 /8 /8 /4 ]), and [Form+]/#{Form+} groups resolve recursively --
   but the result never has to be a sequence the way a play argument
   does; a literal value (or a plain Clojure collection with nothing
   keyword-shaped in it) passes straight through unchanged. Resolved
   against the latest committed repo ONCE, right now, not re-read
   later.
   An unregistered factory-name, or a factory that throws applying
   params, prints a console warning and builds identity-algo under name
   instead of erroring. On success, name's own (registered name) entry
   also remembers :factory-name/:params -- the recipe, not just the
   resolved fn (see core.wall/build!'s own docstring).
     (register-factory! :colorTalea
       (fn [name {:keys [color talea]}] (build-algo! name (fn [nodes ctx voice] ...))))
     (build! :bright :colorTalea {:color [60 64 67] :talea [1/8]})
     (build! :dark   :colorTalea {:color [48 51 55] :talea [1/2]})
     (play :melody :algo :bright)
     (build! :bright :colorTalea {:color [62 65 69] :talea [1/4]})   ; hot-swap :bright
                                                       ; in place -- :melody picks it
                                                       ; up on its very next node"
  [name factory-name params]
  (adviser/log-activity! :build! {:name name :factory-name factory-name})
  (wall/build! name factory-name params))

(defn bld! [name factory-name params] (build! name factory-name params))

(defn build-algo!
  "Store an already-resolved wall fn f under name -- the direct,
   low-level counterpart to build!/register-factory! above, for when
   you already have a concrete wall fn in hand (typically: called from
   INSIDE a factory you're writing, as its own last step -- see
   build!'s own example) rather than a registered factory to apply args
   to. doc (optional) is shown by (algos)/(algos name)."
  ([name f] (build-algo! name f nil))
  ([name f doc]
   (adviser/log-activity! :build-algo! {:name name})
   (wall/build-algo! name f doc)))

(defn unregister-algo!
  "Forget name's parked cooked algo. A voice/track pointing at name now
   sees identity starting its very next node -- NOT frozen at whatever
   it last resolved to, since nothing about a voice's own assignment
   ever held a copy of the fn itself."
  [name]
  (wall/unregister-algo! name))

(defn unreg-algo! [name] (unregister-algo! name))

(defn algos
  "List registered (cooked, ready-to-play) algorithms.
   (algos)      -- every registered name with its doc
   (algos name) -- name's full doc"
  ([] (wall/algos))
  ([name] (wall/algos name)))

(defn registered
  "The raw {name -> {:fn f :doc doc ...}} cooked-algo registry map --
   unlike algos above (doc-only), this surfaces the FULL entry for
   every built algo, including :factory-name/:params for anything built
   via build! (see core.wall/build!'s own docstring) -- the recipe, not
   just the resolved fn. Useful for a caller that wants to introspect or
   re-derive a built algo (a GUI re-opening its own build form, a
   composer checking what actually built :bright)."
  [] (wall/registered))

(defn register-distribution!
  "Park f (a plain (lo hi) -> value sampler -- e.g. algo.random/lo-emph/
   mean-emph/hi-emph/uniform) under name -- a SEPARATE store from
   register-factory!/build-algo! above, for a composite wall-fn
   FACTORY that accepts a distribution BY NAME as one of its own args
   (see algo.common.reshape/weighted-shuffle-algo for the first one).
   doc (optional) is shown by (distributions)/(distributions name)."
  ([name f] (wall/register-distribution! name f))
  ([name f doc] (wall/register-distribution! name f doc)))

(defn unregister-distribution!
  "Forget name's parked distribution. Anything that already resolved it
   keeps whatever fn it already resolved to -- only a later reference
   to name is affected."
  [name]
  (wall/unregister-distribution! name))

(defn distributions
  "List registered distributions.
   (distributions)      -- every registered name with its doc
   (distributions name) -- name's full doc"
  ([] (wall/distributions))
  ([name] (wall/distributions name)))

(defn register-criterion!
  "Park f (a FACTORY, (fn [args...] -> select-fn)) under name -- a
   SEPARATE store from register-factory!/build-algo! above, usable
   thereafter by algo.common.gate/gate-algo, e.g. [:lo 67] resolving
   name :lo and applying 67 to its own registered factory. doc
   (optional) is shown by (criteria)/(criteria name)."
  ([name f] (wall/register-criterion! name f))
  ([name f doc] (wall/register-criterion! name f doc)))

(defn unregister-criterion!
  "Forget name's parked criterion. Anything that already resolved it
   keeps whatever select-fn it already resolved to -- only a later
   reference to name is affected."
  [name]
  (wall/unregister-criterion! name))

(defn criteria
  "List registered criteria.
   (criteria)      -- every registered name with its doc
   (criteria name) -- name's full doc"
  ([] (wall/criteria))
  ([name] (wall/criteria name)))

(defn assign-algo!
  "Prepare path (a voice's own registry path -- see voice-at/play-change
   -- or a bare keyword for a single-segment path, e.g. a play-minted
   short track id) so that the NEXT voice minted there (a play-change
   call with no :algo of its own, or a play/play-add call that happens
   to auto-mint into path) picks up name's registered algorithm, or nil
   to clear a prepared entry. name doesn't have to already be built --
   an unregistered name is just stored as-is (nothing resolves it here);
   resolving it later degrades to a console warning + identity, same
   'degrade and warn, never throw' policy the rest of this mechanism
   has.
   Does NOT reach an already-live voice: as of the 2026-09-10 redesign,
   a voice's own algorithm is a plain, immutable value baked in once at
   mint time -- the only way to change what an ALREADY-PLAYING voice
   sounds like is build!/build-algo! rebuilding what its name resolves
   to. This fn is for preparing a track before you start it:
     (assign-algo! :myTrack :bright)
     (play-change :myTrack :melody)                  ; picks :bright up,
                                                       ; no :algo of its own
   or, more directly, just pass :algo straight to the call that starts
   the track -- (play-change :myTrack :melody :algo :bright) -- which
   needs no separate assign-algo! step at all."
  [path name]
  (adviser/log-activity! :assign-algo! {:path path :name name})
  (engine/assign-algo! path name))

(defn algo-assignments
  "*engine*'s currently PREPARED algorithm table -- a map, path ->
   registered name (or nil), exactly what assign-algo! was called with.
   Reflects what a FUTURE, untagged mint at a given path will pick up,
   NOT what any currently-live voice is actually running -- see
   (:algo (voice-at path)) for that instead."
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
  (let [result (apply engine/play-change path args)]
    (adviser/log-activity! :play-change {:path path :args args})
    result))

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
  (let [result (apply engine/play-add args)]
    (adviser/log-activity! :play-add {:args args :result result})
    result))

;; ============================================================
;; Help
;; ============================================================

(defn help
  "List available commands.
   (help)          — list all
   (help \"parse\")   — full doc for a specific command"
  ([] (println "\n--- musics ---\n")
   (doseq [[n v] (sort-by first (ns-publics (the-ns 'musics.core)))]
     (when-let [d (:doc (meta v))]
       (println (format "  %-15s  %s" n (first (.split d "\n"))))))
   (println))
  ([name]
   (if-let [v (ns-resolve (the-ns 'musics.core) (symbol name))]
     (println (or (:doc (meta v)) "(no docstring)"))
     (println "Unknown command:" name))))

(defn- algo-ns-syms
  "Every namespace symbol under algo/ on the classpath, derived by
   walking the actual directory tree -- never a hand-maintained list
   (the exact class of staleness a 2026-09-10 audit found doc/
   algorithms.md's own file index had drifted into before that pass).
   Each .clj file's path becomes its namespace the same way Clojure
   itself derives one: algo/common/gate.clj -> algo.common.gate,
   algo/random.clj -> algo.random (a bare top-level file, no
   subdirectory of its own), underscores in a filename becoming
   hyphens in the namespace segment."
  []
  (let [root      (io/file (io/resource "algo"))
        root-path (.getPath root)]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
         (map (fn [f]
                (let [rel (subs (.getPath ^java.io.File f) (inc (count root-path)))
                      path (subs rel 0 (- (count rel) 4))] ;; strip ".clj"
                  (symbol (str "algo." (-> path
                                            (str/replace "/" ".")
                                            (str/replace "_" "-")))))))
         sort)))

(defn- algo-category
  "The category segment of an algo.* namespace symbol -- the first
   segment after algo., e.g. algo.rhythmic.rhythm -> \"rhythmic\",
   algo.random -> \"random\" (a namespace with no subdirectory of its
   own still counts as its own category, alongside algo/random/'s
   other namespaces -- see algo-ns-syms)."
  [ns-sym]
  (second (str/split (str ns-sym) #"\." 3)))

(defn- algo-tree
  "{category -> {algo-name -> doc}} for every public, documented var
   across every algo.* namespace on the classpath. Built fresh every
   call, straight off the real code (ns-publics/docstrings) -- never a
   hand-maintained catalog that could drift from it, same reasoning as
   help's own (ns-publics (the-ns 'musics.core))."
  []
  (doseq [ns-sym (algo-ns-syms)] (require ns-sym))
  (reduce (fn [tree ns-sym]
            (reduce (fn [tree [n v]]
                      (if-let [d (:doc (meta v))]
                        (assoc-in tree [(algo-category ns-sym) (name n)] d)
                        tree))
                    tree
                    (ns-publics (the-ns ns-sym))))
          {}
          (algo-ns-syms)))

(defn show-algos
  "Browse the algo/ catalog -- root (\"algorithms\") -> category
   (rhythmic/melodic/common/random/indisp/metric, one per algo/
   subdirectory) -> algo name -> documentation. Built fresh every call,
   straight off the real algo.* namespaces (ns-publics/docstrings) --
   never a hand-maintained list that could drift from the actual code.
   (show-algos)                                    -- every category,
                                                       every algo name,
                                                       one-line gloss each
   (show-algos \"rhythmic\")                         -- just that category
   (show-algos \"rhythmic\" \"euclidean-rhythm\")      -- that ONE algo's
                                                       full documentation"
  ([]
   (let [tree (algo-tree)]
     (doseq [cat (sort (keys tree))]
       (println (str "\n--- " cat " ---"))
       (doseq [[n d] (sort-by first (get tree cat))]
         (println (format "  %-28s  %s" n (first (str/split-lines d))))))
     (println)))
  ([category]
   (let [tree (algo-tree)]
     (if-let [algos (get tree category)]
       (do (println (str "\n--- " category " ---"))
           (doseq [[n d] (sort-by first algos)]
             (println (format "  %-28s  %s" n (first (str/split-lines d)))))
           (println))
       (println "Unknown category:" category "-- known:" (vec (sort (keys tree)))))))
  ([category name]
   (let [tree (algo-tree)]
     (if-let [d (get-in tree [category name])]
       (println d)
       (println "Unknown algo:" (str category "/" name))))))

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
  "Like write, but also captures whatever's CURRENTLY LIVE right now --
   path -> Name for every actually-sounding voice (engine/live-algos,
   read straight off each voice's own immutable :algo field) -- alongside
   the repo + auto-ids, so a voice's algorithm survives the round-trip
   too, not just the material it plays. This is deliberately live-voice
   state, not the (usually near-empty, prepare-ahead-only) prep table
   assign-algo! writes to -- an ordinary :algo-tagged play/play-add call
   never touches that table at all, only the voice it mints. No engine
   created yet, or nothing currently playing, persists an empty table,
   not an error.

   What this deliberately does NOT capture -- review.txt point 11's own
   fuller diagnosis, kept honest rather than silently declared 'solved':
   - which factory+params built a given *algo-registry* entry -- as of
     the 2026-09-11 params-map redesign, core.wall/build! DOES stamp
     :factory-name/:params onto that entry now (see core.wall/build!'s
     own docstring), closing this gap for anything built THROUGH
     build! -- but persist-session doesn't read that back out and write
     it to path yet, so the recipe still doesn't survive THIS
     round-trip, only the live in-process registry. A factory called
     directly (bypassing build!) still stamps nothing at all, same as
     before.
   - core.conductor's schedule/repeating tables -- pending cues in ONE
     specific live performance, not composed material (closer to a
     paused breakpoint than a saved document).
   - Any wall registration itself (register-factory!/register-action!) --
     code, always the user's own job to re-run
     (e.g. re-require a setup namespace), same as any other Clojure fn
     definition never round-tripping through a data file."
  ([path] (persist-session path (repo/latest-tx)))
  ([path tx]
   (spit path (persist/session->edn (into {} (repo/view tx)) (:auto-ids @session)
                                     (engine/live-algos)))
   (println "[musics] Session persisted to" path)))

(defn restore-session
  "Like load, but also replays a persist-session-captured snapshot
   (path -> Name, whatever was actually live at persist-session time)
   via assign-algo! -- into the PREP table, after re-seeding the repo --
   reading a plain write-produced file works too, it just has nothing
   to replay. Ensures an engine exists first (creating a minimal,
   receiver-less one -- no MIDI, no sound, same as engine/engine's own
   nil-fs test path -- if (connect) hasn't been called yet), since
   assign-algo! is pure bookkeeping and doesn't need real audio wired up
   to do its job.
   Restoring never recreates any live voices itself (nothing here calls
   play/play-change) -- it only prepares each captured path so that
   YOUR OWN next untagged play/play-change call at that same path picks
   the algorithm back up automatically, without retyping it.
   A replayed Name that fails to resolve (its wall algorithm not yet
   re-registered in THIS process) degrades to identity-algo with a
   console warning, same as assign-algo! always has -- restore-session
   doesn't make that any louder.
   NOTE: (connect) always mints a brand-new engine, discarding whatever
   engine (and its prepared algorithms) existed before -- true of ANY
   live session already, restored or not, not a new limitation. Call
   (restore-session ...) AFTER (connect), or call it again afterward,
   if you need both real sound and the restored preparation together."
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
