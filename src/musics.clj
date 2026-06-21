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
  (:require [input.reader.grammar-parser :as gp]
            [input.reader.parser.vars :as vars]
            [core.domain.music-domain :as d]
            [output.ornaments :as orn]
            [output.midi.engine :as engine]
            [output.midi.midi-live :as live]))

;; ============================================================
;; State
;; ============================================================

(defonce registry (atom {}))                                ;; keyword → Composite
(defonce book (atom []))                                    ;; ordered list of parsed Scores
(defonce receiver (atom nil))                               ;; MIDI receiver (nil = disconnected)

;; ============================================================
;; Resolution — IDs are first-class handles
;; ============================================================

(defn- resolve-id
  "Resolve a handle to a domain object.
   keyword → registry by name    string → registry
   integer → nth score in book   part   → as-is"
  [x]
  (cond
    (nil? x) nil
    (keyword? x) (clojure.core/get @registry x)
    (string? x) (clojure.core/get @registry (keyword x))
    (integer? x) (clojure.core/get @book x)
    (d/composite? x) x
    (d/leaf? x) x
    (d/rest? x) x
    (d/drum? x) x
    (d/iterator? x) x
    :else (throw (ex-info (str "Cannot resolve: " (pr-str x)) {:arg x}))))

;; ============================================================
;; Parse
;; ============================================================

(defn- register-tree
  "Walk a composite tree and register all named composites."
  [c]
  (when (d/composite? c)
    (when-let [id (:id c)]
      (when (and (seq id) (not= id "score"))
        (swap! registry assoc (keyword id) c)))
    (doseq [ch (d/composite-children c)]
      (register-tree ch))))

(defn- first-named
  "Return the keyword id of the first named composite in the tree."
  [c]
  (when (d/composite? c)
    (if (and (seq (:id c)) (not= (:id c) "score"))
      (keyword (:id c))
      (some first-named (d/composite-children c)))))

(defn parse
  "Parse musics text, register all named composites.
   Returns the first named composite's keyword ID, or nil."
  [text]
  (try
    (let [result (gp/parse-domain text)
          s (:score result)]
      (swap! book conj s)
      (register-tree s)
      (first-named s))
    (catch clojure.lang.ExceptionInfo e
      (println (.getMessage e))
      nil)))

(defn try-parse
  "Parse and return the raw instaparse tree (for debugging grammar).
   Prints a formatted error on failure, returns nil."
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
;; Registry
;; ============================================================

(defn find
  "Look up a registered composite by id (keyword or string)."
  [id]
  (resolve-id id))

(defn ids
  "List all registered composite IDs."
  []
  (sort (keys @registry)))

;; ============================================================
;; Book
;; ============================================================

(defn scores
  "All parsed scores."
  [] @book)

(defn score-count
  "Number of parsed scores."
  [] (count @book))

;; ============================================================
;; Inspection
;; ============================================================

(defn children
  "Children of a composite (by id, index, or directly)."
  [x]
  (let [c (resolve-id x)]
    (when (d/composite? c)
      (d/composite-children c))))

(defn leaves
  "Leaf children (notes/chords) of a composite."
  [x]
  (let [c (resolve-id x)]
    (when (d/composite? c)
      (filter d/leaf? (d/composite-children c)))))

(defn inspect
  "Print structure.
   (inspect)        — book and registry overview
   (inspect :verse) — children of a specific part"
  ([]
   (println "Book:" (count @book) "score(s)")
   (doseq [i (range (count @book))]
     (let [s (clojure.core/get @book i)]
       (println (str "  [" i "] " (:id s) " — "
                     (d/composite-count s) " children"))))
   (when (seq @registry)
     (println "Registry:" (count @registry) "id(s)")
     (doseq [id (sort (keys @registry))]
       (let [c (clojure.core/get @registry id)]
         (println (str "  " id " (" (name (:type c)) " "
                       (d/composite-count c) " children)"))))))
  ([x]
   (let [c (resolve-id x)]
     (cond
       (d/composite? c)
       (do (println (str (name (:type c)) " \"" (:id c) "\""
                         " — " (d/composite-count c) " children"
                         " — dur " (d/composite-duration c)))
           (doseq [ch (d/composite-children c)]
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
  (let [part (resolve-id x)]
    (when-let [c (:context part)]
      (d/ctx-value c key time))))

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
  "Open a MIDI receiver for live playback."
  []
  (reset! receiver (live/open-receiver))
  (println "[musics] Connected."))

(defn disconnect
  "Close the MIDI receiver."
  []
  (when @receiver
    (reset! receiver nil))
  (println "[musics] Disconnected."))

(defn play
  "Play through MIDI.
   (play)              — play last parsed score
   (play :verse)       — play by registry id
   (play 0)            — play nth score
   (play :verse :channel 1) — on specific channel"
  ([] (play (last @book)))
  ([x & {:keys [channel] :or {channel 0}}]
   (let [target (resolve-id x)]
     (if (nil? target)
       (println "[musics] Nothing to play.")
       (if-let [rcv @receiver]
         (do (println "[musics] Playing...")
             (engine/play-live rcv target :channel channel)
             (println "[musics] Done."))
         (println "[musics] Not connected. Run (connect) first."))))))

(defn collect
  "Collect MIDI notes offline (no playback). Returns note vector."
  ([] (collect (last @book)))
  ([x] (engine/collect-notes (resolve-id x))))

(defn play-note
  "Send a single MIDI note-on."
  ([pitch] (play-note 0 pitch 80))
  ([channel pitch velocity]
   (when-let [rcv @receiver]
     (live/note-on rcv channel pitch velocity))
   pitch))

(defn stop-note
  "Send a single MIDI note-off."
  ([pitch] (stop-note 0 pitch))
  ([channel pitch]
   (when-let [rcv @receiver]
     (live/note-off rcv channel pitch))))

(defn all-notes-off
  "Silence all channels."
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
;; Reset
;; ============================================================

(defn reset
  "Clear everything — book, registry, variables, MIDI."
  []
  (clojure.core/reset! book [])
  (clojure.core/reset! registry {})
  (vars/clear-vars!)
  (disconnect)
  (println "[musics] Reset."))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; --- Session example ---
  (parse "{verse: !mf c4 d4 e4 f4 | g4 a4 b4 c'4}")
  (parse "{chorus: !ff g4 g4 a4 a4 | b4 b4 c'2}")
  (ids)                                                     ;; => (:chorus :verse)
  (inspect)                                                 ;; book + registry overview
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
