(ns musics
  "REPL entry point — access to the complete musics system.

   Quick start:
     (def r (parse \"{verse: !mf c4 d4 e4 f4}\"))
     (commit! (:sid r))
     (play-latest!)   ; committing never moves what's playing on its own
     (connect)
     (play :verse)

   IDs are first-class handles throughout the API.
   Keywords, strings, and composites are all accepted:
     (play :verse)       — registry lookup
     (play \"verse\")      — same
     (play my-composite) — direct

   core.repo (id -> tx -> node) is the one true store. Reading (parse,
   and every inspection fn -- find/ids/children/inspect/ctx/locate/
   describe/print-structure) works against the latest committed tx by
   default, with an optional trailing tx arg to look at any point in
   history instead. Playing (the live engine) reads through a separate,
   sticky play-tx pointer that committing never moves on its own --
   (play-tx!)/(play-latest!) repoint it explicitly, taking effect at the
   next node the engine visits. session only holds the auto-id counters
   now, not the repo itself. (write path)/(load path) persist or replace
   the whole committed history; (reset) starts a brand new one."
  (:refer-clojure :exclude [find load])
  (:require [clojure.pprint :as pprint]
            [input.grammar-parser :as gp]
            [input.reader.flat-tree-walker :as walker]
            [input.reader.flat-core-builder :as flat]
            [core.repo :as repo]
            [core.conductor :as conductor]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [core.domain.persist :as persist]
            [core.domain.ornaments :as orn]
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
;; Parse
;; ============================================================

(defn parse
  "Parse musics text against the session's current *committed* repo (same
   :ROOT, continuing auto-id counters — a later parse can reference an
   earlier one's named parts, as long as that earlier parse was committed
   first). Nothing lands in the session itself yet: every id this call
   introduced or changed is staged under a fresh sid, invisible to
   (inspect), (play), (ctx), etc. until (commit! sid) is called — same as
   editing an existing id would be. Returns {:sid sid :ids ids} (ids new
   or changed by this call, :ROOT excluded), or nil on failure.

   The auto-id counter itself is not part of this staging -- it advances
   immediately so a second (parse ...) before the first is committed
   doesn't generate a colliding id (leaving a gap in numbering if the
   first is ever aborted). Variables (name = { ... } / \\name) work the
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
            sid         (repo/begin-staged-tx!)]
        (repo/stage-many! sid (select-keys new-repo changed-ids))
        (swap! session assoc
               :auto-ids (:auto-ids flat-result)
               :var-map  (:var-map flat-result))
        {:sid sid :ids (disj changed-ids :ROOT)})
      nil)
    (catch clojure.lang.ExceptionInfo e
      (println (.getMessage e))
      nil)))

(defn commit!
  "Fold every edit staged under `sid` into core.repo as one atomic tx.
   Returns the new tx, or nil if `sid` has no staged edits (already
   committed, aborted, or unknown). Committing never moves what's
   currently playing -- see (play-tx!)/(play-latest!) for that."
  [sid]
  (repo/commit-staged! sid))

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

(defn play-tx!
  "Point live playback at `tx` explicitly -- decoupled from committing;
   (commit! ...) never moves this on its own. Takes effect at the next
   node the engine visits (no phrase/bar-boundary awareness yet)."
  [tx]
  (repo/play-tx! tx))

(defn play-latest!
  "Point live playback at whatever is currently the latest committed tx."
  []
  (repo/play-latest!))

;; ============================================================
;; Conductor -- named actions, triggered by section boundaries
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
  "The pending {[id phase] -> action-id} schedule table, or just the
   action-id pending for [id phase] if given."
  ([] (conductor/scheduled))
  ([id phase] (conductor/scheduled id phase)))

(defn schedule-tx!
  "Cut playback over to target-tx the next time a section identified by
   id crosses phase -- e.g. (schedule-tx! :verse :exit 8) jumps playback
   to tx 8 right as the :verse section finishes playing. target-tx may
   also be :latest, resolved at the moment this actually fires rather
   than when it was scheduled -- for \"commit now, cut over whenever we
   get there\" instead of a tx number fixed in advance."
  [id phase target-tx]
  (conductor/schedule-tx! id phase target-tx))

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
;; Registry (dynamic scanning)
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

;; ============================================================
;; Inspection
;; ============================================================

(defn children
  "Children of a composite, as of tx (defaults to the latest committed
   tx) -- keyword children are resolved into their actual node values."
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

;; ============================================================
;; Context query
;; ============================================================

(defn ctx
  "Query a context value from a part at a given time, as of tx (defaults
   to the latest committed tx).
   (ctx :verse :tempo 0.0) → 120
   (ctx leaf :volume 0.5)  → interpolated value"
  ([x key time] (ctx x key time (repo/latest-tx)))
  ([x key time tx]
   (let [part (resolve-id x tx)
         root-ctx (:context (get (repo/view tx) :ROOT))]
     (when-let [ctx (:context part)]
       (c/ctx-value-chain [ctx root-ctx] key time)))))

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
  "Expand a leaf's modifiers (ornament, tremolo, grace) into sub-leaves.
   Returns [leaf] unchanged if no expandable modifier is present."
  [leaf]
  (orn/expand leaf))

;; ============================================================
;; MIDI live
;; ============================================================

(defn connect
  "Open a MIDI receiver and wire up the live playback engine (see
   core.async-engine) against core.repo/play-tx -- playback always
   reads whatever tx (play-tx!)/(play-latest!) currently points at, not
   necessarily the latest commit. Safe to call more than once -- just
   re-opens the receiver and re-binds *engine*.
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

(defn play
  "Play one or more registered parts through MIDI, connecting
   automatically if (connect) hasn't been called yet.
   Args are core.async-engine/play's mini-language:
     (play :verse)                    -- single part
     (play :verse1 :verse2)           -- sequentially
     (play [:par :melody :bass])      -- polyphony, forked onto separate
                                          MIDI channels
   See core.async-engine/play's docstring for the full grammar
   (context-refs, nested [:seq ...]/[:par ...] groups)."
  [& args]
  (when (nil? @receiver) (connect))
  (apply engine/play args))

(defn display
  "Like play, but fully synchronous and greedy, for debugging: resolves
   the exact same play-arg mini-language against whatever tx play-tx
   currently points at (no connect/live engine needed), turning every
   leaf it would have played into a MidiEvent via
   core.domain.resolve/resolve-event instead of scheduling/sending it --
   no core.async, no waiting, no MIDI I/O. Pretty-prints the whole
   realized structure and returns it too, for further inspection.

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
    (pprint/pprint result)
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
;; Variables
;; ============================================================

;; Variables (name = { ... } / \name) are real grammar constructs now,
;; resolved as part of an ordinary (parse ...) call -- there's no
;; separate "just register the definitions" step anymore (that was
;; def-vars, now gone): (parse "motif = {c4 d4 e4}") registers :motif in
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

(defn from-ly-to-me
  "Best-effort convert a LilyPond .ly file to musics DSL text and write
   it back next to the source as a sibling <name>.mus file. Doesn't touch
   the current session -- load the result yourself, e.g.:
     (parse (slurp (from-ly-to-me \"/path/to/piece.ly\")))
   See input.lilypond-import for what's handled and what's known
   to be out of scope (markup, lyrics, engraving overrides, ...)."
  [ly-path]
  (let [mus-path (ly/from-ly-to-me ly-path)]
    (println "[musics] Converted" ly-path "->" mus-path)
    mus-path))

;; ============================================================
;; Reset
;; ============================================================

(defn reset
  "Clear everything — session, variables, MIDI, and all committed/staged
   core.repo history. Starts a brand new session, with a fresh :ROOT
   committed as tx 1 and playback pointed at it."
  []
  (repo/reset-all!)
  (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
  (repo/play-latest!)
  (reset! session {:auto-ids {} :var-map {}})
  (disconnect)
  (println "[musics] Reset."))

;; ============================================================
;; REPL smoke-test
;; ============================================================

#_:clj-kondo/ignore
(comment
  ;; --- Session example ---
  ;; Every (parse ...) is staged, not applied -- commit! (or abort!) it.
  (def r1 (parse "{verse: !mf c4 d4 e4 f4 | g4 a4 b4 c'4}"))
  (commit! (:sid r1))
  (def r2 (parse "{chorus: !ff g4 g4 a4 a4 | b4 b4 c'2}"))
  (commit! (:sid r2))
  (ids)                                                     ;; => (:chorus :verse)
  (inspect)                                                 ;; session overview, latest tx
  (inspect :verse)                                          ;; children of verse
  (children :verse)                                         ;; => [Leaf Leaf ...]
  (leaves :verse)                                           ;; => only pitched leaves
  (ctx :verse :volume 0.0)                                  ;; => mf value

  ;; Build on previous parts -- only resolves once verse/chorus are
  ;; committed, since parse walks against the latest committed repo
  (def r3 (parse "{song: :verse :chorus :verse}"))
  (commit! (:sid r3))

  ;; Committing never moves what's playing -- point playback explicitly.
  (play-latest!)
  (play :song)

  ;; Inspect or discard a pending parse before committing
  (def r4 (parse "{oops: c4}"))
  (pending (:sid r4))                                       ;; => {:oops #Leaf{...} ...}
  (abort! (:sid r4))                                         ;; never becomes visible

  ;; History / time-travel (read-only, per id, or across the whole repo
  ;; via the optional trailing tx on any inspection fn)
  (history :verse)                                          ;; => ([tx node] ...)
  (as-of :verse 1)                                          ;; => value right after its first commit
  (ids 1)                                                   ;; => ids as of tx 1 only

  ;; Live edit that doesn't (yet) disturb what's sounding: stage + commit
  ;; a change, keep playing the old tx, then cut over explicitly whenever
  ;; you're ready -- takes effect at the next node the engine visits.
  (def r5 (parse "{verse: !mf c4 d4 e4 f4 g4}"))
  (commit! (:sid r5))            ;; new tx exists now, but playback is unaffected
  (play-tx! (latest-tx))         ;; now cut over (same as play-latest!)

  ;; MIDI
  (connect)
  (play :verse)
  (play [:par :verse :chorus])
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