(ns musics
  "REPL entry point — access to the complete musics system.

   Quick start:
     (def r (parse \"{verse: !mf c4 d4 e4 f4}\"))
     (commit! (:sid r))
     (connect)
     (play :verse)

   IDs are first-class handles throughout the API.
   Keywords, strings, and composites are all accepted:
     (play :verse)       — registry lookup
     (play \"verse\")      — same
     (play my-composite) — direct

   State is a single accumulating session (repo + auto-id counters), not a
   vector of independent parses — every (parse ...) call builds onto the
   same repo, so ids introduced by one call can be referenced by a later
   one (e.g. (parse \"{song: :verse :chorus}\") after parsing verse/chorus
   separately actually resolves them) -- once committed, that is; see
   (parse ...)'s docstring. (write path)/(load path) persist or replace the
   whole session; (reset) starts a brand new one."
  (:refer-clojure :exclude [find load])
  (:require [input.reader.parser.grammar-parser :as gp]
            [input.reader.parser.vars :as vars]
            [input.reader.flat-tree-walker :as walker]
            [input.reader.flat-core-builder :as flat]
            [core.repo :as repo]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [core.domain.persist :as persist]
            [input.reader.lilypond-import :as ly]
            [output.ornaments :as orn]
            [core.engine.async-engine :as engine]
            [output.midi.midi-live :as live]
            ))



;; ============================================================
;; State
;; ============================================================

;; {:repo :auto-ids} -- always has a real :ROOT context, constructed at
;; session-start by flat/empty-session. Never nil, so nothing downstream
;; needs a "session not started yet" fallback.
(defonce session (atom (flat/empty-session)))
(defonce receiver (atom nil))                               ;; MIDI receiver

;; async-engine wants a bare repo atom (not the {:repo :auto-ids} session
;; map), kept in sync via a watch so live edits from later (parse ...)
;; calls reach playback already in progress.
(defonce repo-atom (atom (:repo @session)))
(add-watch session :repo-atom (fn [_ _ _ new-session] (reset! repo-atom (:repo new-session))))

;; ============================================================
;; Resolution — IDs are first-class handles
;; ============================================================

(defn- resolve-id
  "Resolve a handle to a domain object.
   keyword → look up in the session repo    string → keyword (then look up)
   map     → as-is (assumed to be a node map already)"
  [x]
  (cond
    (nil? x) nil
    (keyword? x) (get (:repo @session) x)
    (string? x) (get (:repo @session) (keyword x))
    (map? x) x                                              ;; assume it's a node map
    :else (throw (ex-info (str "Cannot resolve: " (pr-str x)) {:arg x}))))

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
   first is ever aborted)."
  [text]
  (try
    (if-let [[insta-tree processed-text] (gp/try-parse-with-input text)]
      (let [old-repo    (:repo @session)
            flat-result (walker/walk insta-tree processed-text @session)
            new-repo    (:tree flat-result)
            changed-ids (into #{}
                              (keep (fn [[id node]]
                                      (when (not= node (get old-repo id))
                                        id)))
                              new-repo)
            sid         (repo/begin-staged-tx!)]
        (doseq [id changed-ids]
          (repo/stage! sid id (get new-repo id)))
        (swap! session assoc :auto-ids (:auto-ids flat-result))
        {:sid sid :ids (disj changed-ids :ROOT)})
      nil)
    (catch clojure.lang.ExceptionInfo e
      (println (.getMessage e))
      nil)))

(defn commit!
  "Make a pending (parse ...) or staged edit visible: folds every edit
   staged under `sid` into core.repo as one atomic tx, then refreshes the
   session's repo from that tx (the materialized view async-engine reads
   via repo-atom). Returns the new tx, or nil if `sid` has no staged
   edits (already committed, aborted, or unknown)."
  [sid]
  (when-let [tx (repo/commit-staged! sid)]
    (swap! session assoc :repo (repo/snapshot tx))
    tx))

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
  "Look up a registered composite by id (keyword or string)."
  [id]
  (resolve-id id))

(defn ids
  "List all registered IDs in the session (excluding :ROOT)."
  []
  (->> (:repo @session)
       keys
       (remove #{:ROOT})
       (sort)))

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
   at the top level) show up as nil."
  []
  (mapv (fn [child] (if (keyword? child) child (:id child)))
        (:children (get (:repo @session) :ROOT))))

;; ============================================================
;; Inspection
;; ============================================================

(defn children
  "Children of a composite — resolves keywords within a single tree.
   Without tree, returns raw :children (keywords left as-is).
   With tree, resolves keyword children via (get tree child)."
  ([x]
   (children nil x))
  ([tree x]
   (let [c (resolve-id x)]
     (when (d/container? c)
       (mapv (fn [child]
               (if (keyword? child)
                 (when tree (get tree child))
                 child))
             (:children c))))))

(defn leaves
  "Leaf children (notes/chords) of a composite.
   Without tree, ignores keyword children (they become nil).
   With tree, resolves keyword children within that tree."
  ([x]
   (leaves nil x))
  ([tree x]
   (let [c (resolve-id x)]
     (when (d/container? c)
       (filter d/leaf? (children tree c))))))

(defn inspect
  "Print structure.
   (inspect)        — session overview
   (inspect :verse) — children of a specific part"
  ([]
   (let [repo (:repo @session)]
     (println "Session:" (count repo) "node(s), ids:" (or (seq (ids)) "(none)")))
   (println))
  ([x]
   (let [c (resolve-id x)]
     (cond
       (d/container? c)
       (do (println (str (name (:type c)) " \"" (:id c) "\""
                         " — " (count (:children c)) " children"
                         " — dur " (reduce + (map #(or (:duration %) 0) (children c)))))
           (doseq [ch (:children c)]
             (println (str "  " (pr-str ch)))))
       (some? c) (println (pr-str c))
       :else (println "Not found:" (pr-str x))))))

;; ============================================================
;; Context query
;; ============================================================

(defn ctx
  "Query a context value from a part at a given time.
   (ctx :verse :tempo 0.0) → 120
   (ctx leaf :volume 0.5)  → interpolated value"
  [x key time]
  (let [part (resolve-id x)
        root-ctx (:context (get (:repo @session) :ROOT))]
    (when-let [ctx (:context part)]
      (c/ctx-value-chain [ctx root-ctx] key time))))

;; ============================================================
;; Navigation
;; ============================================================

(defn locate
  "Navigate to a location in the session, starting from any registered id
   (not just :ROOT) -- no repo argument needed, it's the session's own.
   (locate :verse [0 1]) -- path selectors are index or id, see
   core.domain.resolve/locate. Returns nil for an invalid path."
  [id path]
  (r/locate (:repo @session) (if (string? id) (keyword id) id) path))

(defn describe
  "Abbreviated structural report from a registered id -- containers and
   iterators only, leaves/rests/drums counted not listed. No repo argument
   needed. See core.domain.flat-domain/describe."
  ([] (describe :ROOT))
  ([id] (d/describe (:repo @session) (if (string? id) (keyword id) id))))

(defn print-structure
  "Pretty-print (describe id) as an indented tree using the surface
   grammar's brackets. No repo argument needed.
   (print-structure)        -- whole session, from :ROOT
   (print-structure :verse) -- just that part"
  ([] (print-structure :ROOT))
  ([id] (d/print-structure (:repo @session) (if (string? id) (keyword id) id))))

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
   core.engine.async-engine) against the session's repo. Safe to call
   more than once -- just re-opens the receiver and re-binds *engine*.
   Blocks briefly (~1/3s) on a near-silent warm-up burst first -- see
   engine/warm-up! -- to avoid an audio crackle on the very first real
   note of the session."
  []
  (reset! receiver (live/open-receiver))
  (let [eng (engine/engine @receiver repo-atom :ROOT)]
    (engine/set-engine! eng)
    (engine/warm-up! eng))
  (println "[musics] Connected."))

(defn warm-up!
  "Play a short burst of near-silent notes through the current engine
   (see core.engine.async-engine/warm-up!) -- (connect) already does this
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
   Args are core.engine.async-engine/play's mini-language:
     (play :verse)                    -- single part
     (play :verse1 :verse2)           -- sequentially
     (play [:par :melody :bass])      -- polyphony, forked onto separate
                                          MIDI channels
   See core.engine.async-engine/play's docstring for the full grammar
   (context-refs, nested [:seq ...]/[:par ...] groups)."
  [& args]
  (when (nil? @receiver) (connect))
  (apply engine/play args))

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

(defn def-vars
  "Extract and register variable definitions without parsing.
   Returns the cleaned text."
  [text]
  (first (vars/extract-vars text)))

(defn clear-vars
  "Clear all registered variables."
  []
  (vars/clear-vars!)
  (println "[musics] Variables cleared."))

;; ============================================================
;; Persistence
;; ============================================================

(defn write
  "Write the whole session (repo + auto-ids) to path as EDN."
  [path]
  (spit path (persist/repo->edn (:repo @session) (:auto-ids @session)))
  (println "[musics] Session written to" path))

(defn load
  "Load a session from path, REPLACING the current one wholesale --
   this becomes the new base every subsequent parse builds onto, it is
   not merged with whatever was already in the session. Also re-seeds
   core.repo's history with this as a fresh baseline commit (discarding
   any prior history), so a later (commit! ...) doesn't clobber it with
   a snapshot that never knew about this data."
  [path]
  (let [loaded (persist/edn->repo (slurp path))]
    (repo/seed! (:repo loaded))
    (reset! session loaded))
  (println "[musics] Session loaded from" path))

(defn from-ly-to-me
  "Best-effort convert a LilyPond .ly file to musics DSL text and write
   it back next to the source as a sibling <name>.mus file. Doesn't touch
   the current session -- load the result yourself, e.g.:
     (parse (slurp (from-ly-to-me \"/path/to/piece.ly\")))
   See input.reader.lilypond-import for what's handled and what's known
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
   core.repo history. Starts a brand new session."
  []
  (repo/reset-all!)
  (reset! session (flat/empty-session))
  (vars/clear-vars!)
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
  (inspect)                                                 ;; session overview
  (inspect :verse)                                          ;; children of verse
  (children :verse)                                         ;; => [Leaf Leaf ...]
  (leaves :verse)                                           ;; => only pitched leaves
  (ctx :verse :volume 0.0)                                  ;; => mf value

  ;; Build on previous parts -- only resolves once verse/chorus are
  ;; committed, since parse walks against the session's committed repo
  (def r3 (parse "{song: :verse :chorus :verse}"))
  (commit! (:sid r3))
  (play :song)

  ;; Inspect or discard a pending parse before committing
  (def r4 (parse "{oops: c4}"))
  (pending (:sid r4))                                       ;; => {:oops #Leaf{...} ...}
  (abort! (:sid r4))                                         ;; never becomes visible

  ;; History / time-travel (read-only, per id)
  (history :verse)                                          ;; => ([tx node] ...)
  (as-of :verse 1)                                          ;; => value right after its first commit

  ;; MIDI
  (connect)
  (play :verse)
  (play [:par :verse :chorus])
  (all-notes-off)
  (disconnect)

  ;; Variables
  (def-vars "motif = c4 d4 e4")
  (parse "{melody: \\motif f4 g4}")

  ;; Persistence -- write/load the whole session
  (write "session.edn")
  (reset)
  (load "session.edn")                                      ;; replaces session wholesale

  ;; Reset everything
  (reset)
  )