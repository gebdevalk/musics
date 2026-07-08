(ns musics
  "REPL entry point — access to the complete musics system.

   Quick start:
     (parse \"{verse: !mf c4 d4 e4 f4}\")
     (connect)
     (play :verse)

   IDs are first-class handles throughout the API.
   Keywords, strings, integers, and composites are all accepted:
     (play :verse)       — registry lookup
     (play \"verse\")      — same
     (play 0)            — nth score in book
     (play my-composite) — direct"
  (:refer-clojure :exclude [find])
  (:require [common.data.defaults :as defaults]
            [input.reader.parser.grammar-parser :as gp]
            [input.reader.parser.vars :as vars]
            [input.reader.flat-tree-walker :as walker]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]
            [output.ornaments :as orn]
    ;[output.midi.engine :as engine]
    ;[output.midi.midi-live :as live]
            ))

;; ============================================================
;; State
;; ============================================================

(defrecord Score [id context tree root-id])

(defonce book (atom []))                                    ;; vector of Score records
(defonce receiver (atom nil))                               ;; MIDI receiver

;; ============================================================
;; Resolution — IDs are first-class handles
;; ============================================================

(defn- find-in-book
  "Look up a keyword in all scores' trees."
  [kw]
  (some (fn [score]
          (when-let [node (get (:tree score) kw)]
            node))
        @book))

(defn- resolve-id
  "Resolve a handle to a domain object.
   keyword → scan all scores' trees    string → keyword (then scan)
   integer → root of nth score        map    → as-is"
  [x]
  (cond
    (nil? x) nil
    (keyword? x) (find-in-book x)
    (string? x) (find-in-book (keyword x))
    (integer? x) (when-let [score (get @book x)]
                   (get (:tree score) (:root-id score)))
    (map? x) x                                              ;; assume it's a node map
    :else (throw (ex-info (str "Cannot resolve: " (pr-str x)) {:arg x}))))

;; ============================================================
;; Parse
;; ============================================================

(defn- first-named
  "Return the keyword id of the first named composite in the tree (depth-first)."
  [tree-map root]
  (if (and (:id root) (not= (:id root) :ROOT))
    (:id root)
    (some (fn [child]
            (if (keyword? child)
              (let [child-node (get tree-map child)]
                (when child-node
                  (first-named tree-map child-node)))
              nil))
          (:children root))))

(defn parse
  "Parse musics text, create a Score, and add it to the book.
   Returns the Score record, or nil on failure."
  [text]
  (try
    (if-let [insta-tree (gp/try-parse text)]
      (let [flat-result (walker/walk insta-tree text)       ;; flat tree + root-id
            tree-map (:tree flat-result)
            root-id (:root-id flat-result)
            root (get tree-map root-id)
            score-id (or (first-named tree-map root)
                         (keyword (str "score." (count @book))))
            context (:context root)
            score (->Score score-id context tree-map root-id)]
        (swap! book conj score)
        score)
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
  "List all registered IDs across all scores (excluding :ROOT)."
  []
  (->> @book
       (mapcat (comp keys :tree))
       (remove #{:ROOT})
       (sort)))

;; ============================================================
;; Book
;; ============================================================

(defn scores
  "All parsed Score records."
  [] @book)

(defn score-count
  "Number of parsed scores."
  [] (count @book))

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
   (inspect)        — book overview
   (inspect :verse) — children of a specific part"
  ([]
   (println "Book:" (count @book) "score(s)")
   (doseq [i (range (count @book))]
     (let [score (get @book i)]
       (println (str "  [" i "] " (:id score) " — "
                     (count (:tree score)) " nodes"
                     ", root: " (:root-id score)))))
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
(def root-ctx (c/context-root (defaults/root-defaults)))

(defn ctx
  "Query a context value from a part at a given time.
   (ctx :verse :tempo 0.0) → 120
   (ctx leaf :volume 0.5)  → interpolated value"
  [x key time]
  (let [part (resolve-id x)]
    (when-let [ctx (:context part)]
      (c/ctx-value-chain [ctx root-ctx] key time))))

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
;   (play)              — play last parsed score
;   (play :verse)       — play by registry id
;   (play 0)            — play nth score
;   (play :verse :channel 1) — on specific channel"
;  ([] (play (last @book)))
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
;  ([] (collect (last @book)))
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
;; Reset
;; ============================================================

(defn reset
  "Clear everything — book, variables, MIDI."
  []
  (reset! book [])
  (vars/clear-vars!)
  ;(disconnect)
  (println "[musics] Reset."))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; --- Session example ---
  (parse "{verse: !mf c4 d4 e4 f4 | g4 a4 b4 c'4}")
  (parse "{chorus: !ff g4 g4 a4 a4 | b4 b4 c'2}")
  (ids)                                                     ;; => (:chorus :verse)
  (inspect)                                                 ;; book overview
  (inspect :verse)                                          ;; children of verse
  (children :verse)                                         ;; => [Leaf Leaf ...]
  (leaves :verse)                                           ;; => only pitched leaves
  (ctx :verse :volume 0.0)                                  ;; => mf value

  ;; Build on previous parts
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

  ;; Reset everything
  (reset)
  )