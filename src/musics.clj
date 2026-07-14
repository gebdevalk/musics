(ns musics
  "REPL entry point — access to the complete musics system.

   Quick start:
     (parse \"{verse: !mf c4 d4 e4 f4}\")
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
   separately actually resolves them). (write path)/(load path) persist or
   replace the whole session; (reset) starts a brand new one."
  (:refer-clojure :exclude [find load])
  (:require [clojure.set :as set]
            [input.reader.parser.grammar-parser :as gp]
            [input.reader.parser.vars :as vars]
            [input.reader.flat-tree-walker :as walker]
            [input.reader.flat-core-builder :as flat]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [core.domain.persist :as persist]
            [input.reader.lilypond-import :as ly]
            [output.ornaments :as orn]
    ;[output.midi.engine :as engine]
    ;[output.midi.midi-live :as live]
            ))

;; ============================================================
;; State
;; ============================================================

;; {:repo :auto-ids} -- always has a real :ROOT context, constructed at
;; session-start by flat/empty-session. Never nil, so nothing downstream
;; needs a "session not started yet" fallback.
(defonce session (atom (flat/empty-session)))
(defonce receiver (atom nil))                               ;; MIDI receiver

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
  "Parse musics text and merge it into the session (same repo, same :ROOT,
   continuing auto-id counters — a later parse can reference an earlier
   one's named parts). Returns the set of top-level ids newly introduced
   by this call, or nil on failure."
  [text]
  (try
    (if-let [insta-tree (gp/try-parse text)]
      (let [old-repo    (:repo @session)
            old-ids     (set (keys old-repo))
            flat-result (walker/walk insta-tree text @session)
            new-repo    (:tree flat-result)
            new-ids     (set (keys new-repo))]
        (reset! session {:repo new-repo :auto-ids (:auto-ids flat-result)})
        (disj (set/difference new-ids old-ids) :ROOT))
      nil)
    (catch clojure.lang.ExceptionInfo e
      (println (.getMessage e))
      nil)))

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

;(defn connect
;  "Open a MIDI receiver for live playback."
;  []
;  (reset! receiver (live/open-receiver))
;  (println "[musics] Connected."))
;
;(defn disconnect
;  "Close the MIDI receiver."
;  []
;  (when @receiver
;    (reset! receiver nil))
;  (println "[musics] Disconnected."))
;
;(defn play
;  "Play through MIDI.
;   (play)              — play the whole session (:ROOT)
;   (play :verse)       — play by registry id
;   (play :verse :channel 1) — on specific channel"
;  ([] (play :ROOT))
;  ([x & {:keys [channel] :or {channel 0}}]
;   (let [target (resolve-id x)]
;     (if (nil? target)
;       (println "[musics] Nothing to play.")
;       (if-let [rcv @receiver]
;         (do (println "[musics] Playing...")
;             (engine/play-live rcv target :channel channel)
;             (println "[musics] Done."))
;         (println "[musics] Not connected. Run (connect) first."))))))
;
;(defn collect
;  "Collect MIDI notes offline (no playback). Returns note vector."
;  ([] (collect :ROOT))
;  ([x] (engine/collect-notes (resolve-id x))))
;
;(defn play-note
;  "Send a single MIDI note-on."
;  ([pitch] (play-note 0 pitch 80))
;  ([channel pitch velocity]
;   (when-let [rcv @receiver]
;     (live/note-on rcv channel pitch velocity))
;   pitch))
;
;(defn stop-note
;  "Send a single MIDI note-off."
;  ([pitch] (stop-note 0 pitch))
;  ([channel pitch]
;   (when-let [rcv @receiver]
;     (live/note-off rcv channel pitch))))
;
;(defn all-notes-off
;  "Silence all channels."
;  []
;  (when-let [rcv @receiver]
;    (doseq [ch (range 16)]
;      (live/all-notes-off rcv ch))))

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
   not merged with whatever was already in the session."
  [path]
  (reset! session (persist/edn->repo (slurp path)))
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
  "Clear everything — session, variables, MIDI. Starts a brand new session."
  []
  (reset! session (flat/empty-session))
  (vars/clear-vars!)
  ;(disconnect)
  (println "[musics] Reset."))

;; ============================================================
;; REPL smoke-test
;; ============================================================

#_:clj-kondo/ignore
(comment
  ;; --- Session example ---
  (parse "{verse: !mf c4 d4 e4 f4 | g4 a4 b4 c'4}")
  (parse "{chorus: !ff g4 g4 a4 a4 | b4 b4 c'2}")
  (ids)                                                     ;; => (:chorus :verse)
  (inspect)                                                 ;; session overview
  (inspect :verse)                                          ;; children of verse
  (children :verse)                                         ;; => [Leaf Leaf ...]
  (leaves :verse)                                           ;; => only pitched leaves
  (ctx :verse :volume 0.0)                                  ;; => mf value

  ;; Build on previous parts -- now actually resolves, same session repo
  (parse "{song: :verse :chorus :verse}")
  (play :song)

  ;; MIDI
  (connect)
  (play :verse)
  (play :chorus :channel 1)
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