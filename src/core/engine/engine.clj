(ns core.engine.engine
  "Real-time playback engine.

   The engine owns the full pipeline from repo to sound:
     repo (atom) → form-unroll → tracks → tick → Fluidsynth

   Engine holds repo and root-id as state, so play/stop/pause need no
   external arguments. The root context is never passed in separately --
   repo's own :ROOT container always carries one (constructed at session
   start, see flat-core-builder/initial-state), so it's derived from repo,
   not supplied. A dynamic var *engine* allows REPL usage without
   threading the engine reference everywhere:

     (set-engine! (engine fs repo :ROOT))
     (play [:seq.1 :seq.2])
     (stop!)

   Two playback modes:
     play  -- eager form-unroll, for finite pieces
     play! -- lazy form-unroll, for infinite/live patterns

   Just-in-time resolution: resolve-event is called at tick time with
   structural-time (beats consumed so far). No pre-computed offsets.

   Per-track state:
     cursor-atom     -- remaining events (lazy or eager seq)
     clock-atom      -- wall-clock seconds elapsed
     structural-atom -- beats consumed (for context envelope sampling)
     future-atom     -- pending ScheduledFuture (for pause/cancel)"

  (:require [clojure.string :as str]
            [core.domain.resolve :as r]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c])
  (:import [java.util.concurrent
            Executors
            ScheduledExecutorService
            ScheduledFuture
            TimeUnit]))

;; ============================================================
;; Dynamic engine var
;; ============================================================

(def ^:dynamic *engine* nil)

(defn set-engine!
  "Set the global engine instance. Called once at startup:
     (set-engine! (engine fs repo :ROOT))"
  [eng]
  (alter-var-root #'*engine* (constantly eng)))

;; ============================================================
;; Fluidsynth dispatch
;; ============================================================

(defn- note-on!
  "Send note-on for all pitches. Skips if no channel (Rest)."
  [_fs {:keys [channel pitches velocity program cc]}]
  (when channel
    (when (pos? program)
      ;; (fs/program-change fs channel program)
      (println (format "PROG-CHG ch=%d prog=%d" channel program)))
    (doseq [[cc-num cc-val] cc]
      ;; (fs/control-change fs channel cc-num cc-val)
      (println (format "CC       ch=%d cc=%d val=%d" channel cc-num cc-val)))
    (doseq [pitch pitches]
      ;; (fs/note-on fs channel pitch velocity)
      (println (format "NOTE-ON  ch=%d pitch=%d vel=%d" channel pitch velocity)))))

(defn- schedule-note-off!
  "Schedule note-off after dur-played seconds on the track's own executor.
   Same executor as tick loop -- note-offs serialized with note-ons,
   no races within a voice. Tied notes suppress note-off."
  [_fs ^ScheduledExecutorService executor {:keys [channel pitches dur-played tied]}]
  (when (and channel (not tied) (seq pitches))
    (.schedule executor
               ^Runnable
               (fn []
                 (doseq [pitch pitches]
                   ;; (fs/note-off fs channel pitch)
                   (println (format "NOTE-OFF ch=%d pitch=%d" channel pitch))))
               (long (* dur-played 1000))
               TimeUnit/MILLISECONDS)))

;; ============================================================
;; Per-track tick loop
;; ============================================================

(defn- tick-track!
  "Process the next event on one track.
   Resolves just-in-time using current structural-time (beats consumed).
   Advances clock-atom (seconds) and structural-atom (beats) after firing."
  [fs state channel
   cursor-atom clock-atom structural-atom
   ^ScheduledExecutorService executor future-atom]
  (when (= @state :playing)
    (if-let [event (first @cursor-atom)]
      (let [onset           @clock-atom
            structural-time @structural-atom
            midi            (r/resolve-event event channel onset structural-time)
            dur-secs        (:dur-secs midi)
            dur-ms          (long (* dur-secs 1000))]
        (note-on! fs midi)
        (schedule-note-off! fs executor midi)
        (swap! cursor-atom  rest)
        (swap! clock-atom      + dur-secs)
        (swap! structural-atom + (d/part-duration (:part event)))
        (reset! future-atom
                (.schedule executor
                           ^Runnable
                           #(tick-track! fs state channel
                                         cursor-atom clock-atom structural-atom
                                         executor future-atom)
                           dur-ms
                           TimeUnit/MILLISECONDS)))
      (println (format "Track ch=%d finished." channel)))))

;; ============================================================
;; Engine construction
;; ============================================================

(defn engine
  "Create a new engine holding repo and root-id.
   repo should be an atom for lazy/live mode so edits take effect
   on the next lazy iteration. A plain map works for eager mode.
   Does not start playback -- call play or play! after creation.

     (set-engine! (engine fs (atom repo) :ROOT))"
  [fs repo root-id]
  {:state       (atom :stopped)
   :repo        repo          ;; atom for live mutation, plain map for eager
   :root-id     root-id
   :contexts    (atom {})     ;; named contexts: keyword -> Context
   :tracks      (atom [])
   :cursors     (atom [])
   :clocks      (atom [])
   :structurals (atom [])
   :executors   (atom [])
   :futures     (atom [])
   :fs          fs})

;; ============================================================
;; Internal helpers
;; ============================================================

(defn- shutdown-all!
  [{:keys [futures executors]}]
  (doseq [f-atom @futures]
    (when-let [^ScheduledFuture f @f-atom]
      (.cancel f false)))
  (doseq [^ScheduledExecutorService ex @executors]
    (.shutdownNow ex))
  (reset! futures   [])
  (reset! executors []))

(defn- make-cursors     [tracks] (mapv #(atom (seq %)) tracks))
(defn- make-clocks      [tracks] (mapv (fn [_] (atom 0.0)) tracks))
(defn- make-structurals [tracks] (mapv (fn [_] (atom 0))   tracks))

(defn- live-repo
  "Dereference repo if it's an atom, return as-is if plain map."
  [repo]
  (if (instance? clojure.lang.IDeref repo) @repo repo))

(defn- build-play-root
  "Build a transient :ROOT container from play args.
   Each arg is either:
     string   -- context instructions, parsed into a fresh Context
     keyword  -- single part reference
     vector   -- sequential grouping of keyword refs
     [vector] -- nested vector = parallel grouping
   The outer level is always sequential.

   The new wrapper's :context is repo's real :ROOT context, carried
   forward -- it's the one true root context (see resolve/root-seed),
   never rebuilt or supplied separately."
  [repo args]
  (let [children (mapv (fn [arg]
                         (cond
                           ;; bare keyword -- direct reference
                           (keyword? arg) arg
                           ;; vector -- sequential or parallel grouping
                           (vector? arg)
                           (let [type (if (vector? (first arg)) :PAR :SEQ)
                                 ids  (if (= type :PAR)
                                        (mapv first arg)
                                        arg)]
                             {:type     type
                              :id       (keyword (gensym "play."))
                              :context  (c/context)
                              :children (vec ids)})
                           :else nil))
                       (filter (complement string?) args))]
    {:type     :ROOT
     :id       :ROOT
     :context  (:context (get repo :ROOT))
     :children (filterv some? children)}))

(defn- start-tracks!
  "Internal: load tracks into engine state and start executors."
  [{:keys [state tracks cursors clocks structurals
           executors futures fs]}]
  (reset! cursors     (make-cursors     @tracks))
  (reset! clocks      (make-clocks      @tracks))
  (reset! structurals (make-structurals @tracks))
  (let [channels (range (count @tracks))
        exs      (mapv (fn [_] (Executors/newSingleThreadScheduledExecutor))
                       @tracks)
        fat      (mapv (fn [_] (atom nil)) @tracks)]
    (reset! executors exs)
    (reset! futures   fat)
    (reset! state :playing)
    (doseq [[channel cursor-atom clock-atom structural-atom ex future-atom]
            (map vector channels @cursors @clocks @structurals exs fat)]
      (reset! future-atom
              (.schedule ^ScheduledExecutorService ex
                         ^Runnable
                         #(tick-track! fs state channel
                                       cursor-atom clock-atom structural-atom
                                       ex future-atom)
                         0
                         TimeUnit/MILLISECONDS)))))

;; ============================================================
;; Transport
;; ============================================================

(defn stop!
  "Stop all tracks. Resets all per-track state to start.
   For lazy/infinite tracks the lazy tail is dropped and GC'd.
   Uses *engine* if no arg supplied."
  ([]    (stop! *engine*))
  ([eng]
   (reset! (:state eng) :stopped)
   (shutdown-all! eng)
   (reset! (:cursors     eng) [])
   (reset! (:clocks      eng) [])
   (reset! (:structurals eng) [])
   eng))

(defn pause!
  "Pause all tracks. All per-track positions preserved."
  ([]    (pause! *engine*))
  ([eng]
   (when (= @(:state eng) :playing)
     (reset! (:state eng) :paused)
     (doseq [f-atom @(:futures eng)]
       (when-let [^ScheduledFuture f @f-atom]
         (.cancel f false))))
   eng))

(defn resume!
  "Resume all tracks from current positions."
  ([]    (resume! *engine*))
  ([eng]
   (when (= @(:state eng) :paused)
     (let [{:keys [state tracks cursors clocks structurals
                   executors futures fs]} eng
           channels (range (count @tracks))
           exs      (mapv (fn [_] (Executors/newSingleThreadScheduledExecutor))
                          @tracks)
           fat      (mapv (fn [_] (atom nil)) @tracks)]
       (reset! executors exs)
       (reset! futures   fat)
       (reset! state :playing)
       (doseq [[channel cursor-atom clock-atom structural-atom ex future-atom]
               (map vector channels @cursors @clocks @structurals exs fat)]
         (reset! future-atom
                 (.schedule ^ScheduledExecutorService ex
                            ^Runnable
                            #(tick-track! fs state channel
                                          cursor-atom clock-atom structural-atom
                                          ex future-atom)
                            0
                            TimeUnit/MILLISECONDS)))))
   eng))

;; ============================================================
;; Named context registry
;; ============================================================

(defn register-context!
  "Register a named context in the engine's context registry.
   Referenced by keyword in play args:
     (register-context! :forte-allegro ctx)
     (play \":forte-allegro\" [:seq.1])"
  ([name ctx]       (register-context! *engine* name ctx))
  ([eng name ctx]
   (swap! (:contexts eng) assoc name ctx)
   eng))

(defn- resolve-context-args
  "Extract named context references from play args.
   String args like \":forte-allegro\" are looked up in the context registry.
   Returns [ctx-chain remaining-args]."
  [eng args]
  (let [ctx-registry @(:contexts eng)
        ctx-args     (filter string? args)
        struct-args  (remove string? args)
        extra-ctxs   (mapcat (fn [s]
                               (keep #(get ctx-registry (keyword (str/trim %)))
                                     (str/split s #"\s+")))
                             ctx-args)]
    [extra-ctxs struct-args]))

;; ============================================================
;; Play API
;; ============================================================

(defn play
  "Compose and eagerly play a structure from pre-defined repo parts.
   Uses *engine* -- call set-engine! first.

   Args are a mix of:
     string   -- named context(s) to apply: \":forte-allegro :build\"
     keyword  -- single part reference: :seq.1
     vector   -- sequential group:  [:seq.1 :seq.2]
     [[...]]  -- parallel group:    [[:par.1] [:par.2]]

   Examples:
     (play [:seq.1 :seq.2])
     (play \":forte-allegro\" [:intro [:par.1 :par.2] :outro])
     (play [:seq.1] [:par.1 :par.2])    ;; seq.1 then par.1+par.2 together"
  [& args]
  (let [eng              *engine*
        repo             (live-repo (:repo eng))
        [_extra-ctxs
         struct-args]    (resolve-context-args eng args)
        root             (build-play-root repo struct-args)
        play-repo        (assoc repo :ROOT root)
        tracks           (r/form-unroll play-repo :ROOT)]
    (stop! eng)
    (reset! (:tracks eng) tracks)
    (start-tracks! eng))
  *engine*)

(defn play!
  "Compose and lazily play a structure from pre-defined repo parts.
   Same args as play but uses lazy form-unroll.
   Best for infinite/open-ended patterns and live REPL mutation --
   structural changes to repo take effect on the next lazy iteration.

   Examples:
     (play! [:seq.1])                   ;; loop seq.1 if :count :infinite
     (play! \":build\" [:verse :chorus]) ;; with named context"
  [& args]
  (let [eng              *engine*
        repo             (live-repo (:repo eng))
        [_extra-ctxs
         struct-args]    (resolve-context-args eng args)
        root             (build-play-root repo struct-args)
        play-repo        (assoc repo :ROOT root)
        tracks           (r/form-unroll-lazy play-repo :ROOT)]
    (stop! eng)
    (reset! (:tracks eng) tracks)
    (start-tracks! eng))
  *engine*)

;; ============================================================
;; State predicates
;; ============================================================

(defn playing? ([] (playing? *engine*)) ([eng] (= @(:state eng) :playing)))
(defn paused?  ([] (paused?  *engine*)) ([eng] (= @(:state eng) :paused)))
(defn stopped? ([] (stopped? *engine*)) ([eng] (= @(:state eng) :stopped)))

#_:clj-kondo/ignore
(comment
  ;; --- REPL usage ---

  ;; 1. Startup
  (set-engine! (engine fs (atom repo) :ROOT))

  ;; 2. Register named contexts
  ;; (register-context! :forte-allegro some-ctx)

  ;; 3. Play -- no eng arg needed anywhere
  (play [:seq.1 :seq.2])
  (play [:intro [:par.1 :par.2] :outro])
  (play ":forte-allegro" [:verse.1 :chorus.1])

  ;; 4. Lazy / infinite
  (play! [:seq.1])   ;; loops if seq.1 has :count :infinite iterator

  ;; 5. Transport
  (pause!)
  (resume!)
  (stop!)

  ;; 6. Live edit (lazy mode -- takes effect next iteration)
  ;; (swap! (:repo *engine*) assoc-in [:SEQ.1 :children] new-children)
  )