(ns core.engine.async-engine
  "Real-time playback engine built on core.async goroutines -- ported from
   a Kotlin-coroutine sketch, wired to the actual flat-domain model, and
   the sole playback engine now (replaces the old ScheduledExecutorService-
   based core.engine.engine, which pre-flattened the whole piece into a
   fixed vector of tracks via core.domain.resolve/form-unroll -- or
   form-unroll-lazy for infinite/live-mutable pieces -- before a
   ScheduledExecutorService per track ticked through it).

   This engine doesn't call form-unroll at all -- play-node/play-seq/
   play-par/play-iterator walk the repo tree directly and just-in-time,
   doing form-unroll's job inline instead of building a flattened structure
   first: :SEQ (and other sequential containers) run their children one
   after another inside a single go-block (one voice), :PAR forks each
   child into a sibling go-block the parent awaits on, context chaining
   is a local build-chain applied as the walk descends, and each leaf is
   actualized via core.domain.resolve/resolve-event right at fire-time.
   Since nothing is pre-flattened:

     - live REPL edits to repo (an atom) are picked up the moment a
       not-yet-visited container is descended into -- no separate lazy
       walk needed for that, unlike form-unroll vs. form-unroll-lazy.
     - :count :infinite Iterators just loop -- no separate infinite-vs-
       finite walk needed either.

   Each voice owns a MIDI channel plus a wall-clock atom and a
   structural-time atom (beats consumed, for context envelope sampling).
   A voice's atoms are only forked -- cloned into fresh atoms seeded from
   the parent's current values -- at a :PAR, since that's the only point
   where playback actually diverges into independent timelines; a shared
   channel-counter atom hands out a fresh channel number to each forked
   voice so nested :PAR's don't collide with their siblings.

   Transport (pause!/resume!/stop!) is a single :state atom on the engine,
   shared by every voice in the current play session, checked between
   events and in small increments *during* a held note -- so stop is at
   most ~20ms late and pause freezes a sounding note in place (holding the
   remaining duration exactly, then continuing it on resume) rather than
   re-triggering it. A :session counter distinguishes a play call's voices
   from any still-unwinding voices of a previous one sharing the same
   :state atom, so a fresh play can never be mistaken for -- or silently
   race against -- leftover voices from the call before it."
  (:require [clojure.core.async :refer [go go-loop <! timeout]]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]
            [core.domain.context :as c]
            [output.midi.midi-live :as live]))

;; ============================================================
;; Dynamic engine var
;; ============================================================

(def ^:dynamic *engine* nil)

(defn engine
  "Create a new engine holding repo and root-id.
   fs is a MIDI Receiver (see output.midi.midi-live/open-receiver) -- nil
   is fine too, playback just sends no MIDI (useful for tests).
   repo should be an atom for live mutation so edits take effect as soon
   as playback reaches the container they touch. A plain map works too --
   it just means nothing is live.
   Does not start playback -- call play after creation."
  [fs repo root-id]
  {:state    (atom :stopped)
   :session  (atom 0)
   :repo     repo
   :root-id  root-id
   :fs       fs})

(defn set-engine!
  "Set the global engine instance. Called once at startup:
     (set-engine! (engine (live/open-receiver) (atom repo) :ROOT))"
  [eng]
  (alter-var-root #'*engine* (constantly eng)))

;; ============================================================
;; MIDI primitive
;; ============================================================

(defn- send-midi-on! [fs {:keys [channel program cc pitches velocity]}]
  (when (and fs channel)
    (when (pos? program)
      (live/program-change fs channel program))
    (doseq [[cc-num cc-val] cc]
      (live/control-change fs channel cc-num cc-val))
    (doseq [pitch pitches]
      (live/note-on fs channel pitch velocity))))

(defn- send-midi-off! [fs {:keys [channel pitches tied]}]
  (when (and fs channel (not tied))
    (doseq [pitch pitches]
      (live/note-off fs channel pitch))))

;; ============================================================
;; Context-chain / repo helpers
;; ============================================================

(defn- live-repo
  "Dereference repo if it's an atom, return as-is if plain map."
  [repo]
  (if (instance? clojure.lang.IDeref repo) @repo repo))

(defn- build-chain
  [part ctx-chain]
  (if-let [own-ctx (:context part)] (into [own-ctx] ctx-chain) ctx-chain))

;; ============================================================
;; Voice: everything one line of playback needs, bundled so forking at
;; :PAR is just `assoc`-ing in a fresh channel/clock/structural triple.
;; ============================================================

(defn- voice-active?
  "False once this voice's session has been superseded by a newer play
   call (even if :state was flipped back to :playing already) or the
   engine has been stopped outright."
  [{:keys [eng session]}]
  (and (= @(:session eng) session)
       (not= @(:state eng) :stopped)))

(defn- voice-paused? [{:keys [eng]}] (= @(:state eng) :paused))

(defn- wait-while-paused!
  "Park in 20ms increments while paused, so a resume is noticed promptly.
   Returns as soon as unpaused, stopped, or superseded."
  [voice]
  (go-loop []
    (when (and (voice-paused? voice) (voice-active? voice))
      (<! (timeout 20))
      (recur))))

(defn- hold!
  "Wait out ms milliseconds in small steps, freezing (not consuming) the
   remaining time while paused, and returning early -- true -- if the
   voice stops being active. A truthy return means \"don't advance this
   voice's clock/structural atoms, the note was cut short.\""
  [voice ms]
  (go-loop [remaining ms]
    (cond
      (not (voice-active? voice)) true
      (voice-paused? voice)       (do (<! (timeout 20)) (recur remaining))
      (<= remaining 0)            false
      :else (let [step (min 20 remaining)]
              (<! (timeout step))
              (recur (- remaining step))))))

;; ============================================================
;; Dispatcher
;; ============================================================

(declare play-node)

(defn- play-event!
  "Fire one leaf/rest/drum: resolve against the voice's current wall-clock/
   structural-time, send note-on, hold for the played duration, send
   note-off (always -- even if cut short, so nothing is left stuck
   sounding), then advance the voice's clock/structural atoms unless the
   hold was cut short."
  [voice part ctx-chain]
  (go
    (<! (wait-while-paused! voice))
    (when (voice-active? voice)
      (let [{:keys [eng channel clock structural]} voice
            fs               (:fs eng)
            onset            @clock
            structural-time  @structural
            midi             (r/resolve-event {:part part :ctx-chain ctx-chain}
                                               channel onset structural-time)]
        (send-midi-on! fs midi)
        (let [cut-short? (<! (hold! voice (long (* (:dur-played midi) 1000))))]
          (send-midi-off! fs midi)
          (when-not cut-short?
            (swap! clock      + (:dur-secs midi))
            (swap! structural + (d/part-duration part))))))
    nil))

(defn- play-iterator
  "Expand an Iterator's :count passes in place, one after another -- an
   Iterator is a single voice's repeated material, never a fork.
   :count :infinite loops until the voice is paused-out/stopped/superseded."
  [voice repo iter ctx-chain]
  (go
    (let [source    (:source iter)
          params    (:params iter)
          n         (get params :count 1)
          infinite? (= n :infinite)
          volta?    (= (:repeat-type params) :volta)
          alt       (:alternative params)
          chain     (build-chain iter ctx-chain)]
      (loop [i 0]
        (when (and (voice-active? voice) (or infinite? (< i n)))
          (let [use-alt? (and (not infinite?) volta? alt (= i (dec n)))
                node     (if use-alt? alt source)]
            (<! (play-node voice repo node chain))
            (recur (inc i))))))))

(defn- play-seq
  [voice repo children ctx-chain]
  (go
    (loop [cs children]
      (when (and (seq cs) (voice-active? voice))
        (<! (play-node voice repo (first cs) ctx-chain))
        (recur (rest cs))))))

(defn- play-par
  "Fork each child into its own voice: a fresh channel, and clock/
   structural atoms cloned from the parent's *current* values (siblings
   start at the same wall-clock/structural offset since :PAR children
   are simultaneous), then await all of them."
  [voice repo children ctx-chain]
  (go
    (when (voice-active? voice)
      (let [start-clock      @(:clock voice)
            start-structural @(:structural voice)
            voices (mapv (fn [child]
                            (play-node (assoc voice
                                               :channel (swap! (:channel-counter voice) inc)
                                               :clock (atom start-clock)
                                               :structural (atom start-structural))
                                       repo child ctx-chain))
                          children)]
        (doseq [v voices] (<! v))))))

(defn- play-node
  [voice repo part ctx-chain]
  (cond
    (or (d/leaf? part) (d/rest? part) (d/drum? part))
    (play-event! voice part ctx-chain)

    (d/iterator? part)
    (play-iterator voice repo part ctx-chain)

    (d/container? part)
    (let [chain    (build-chain part ctx-chain)
          children (d/children (live-repo repo) part)]
      (case (:type part)
        :PAR (play-par voice repo children chain)
        (play-seq voice repo children chain)))

    :else (go nil)))

;; ============================================================
;; Transport
;; ============================================================

(defn stop!
  "Halt playback. Current voices notice within ~20ms (or immediately
   between events) and unwind, sending note-off for anything sounding."
  ([]    (stop! *engine*))
  ([eng] (reset! (:state eng) :stopped) eng))

(defn pause!
  "Pause all voices. Sounding notes are held in place, not re-triggered."
  ([]    (pause! *engine*))
  ([eng]
   (when (= @(:state eng) :playing) (reset! (:state eng) :paused))
   eng))

(defn resume!
  "Resume all voices from exactly where they were paused."
  ([]    (resume! *engine*))
  ([eng]
   (when (= @(:state eng) :paused) (reset! (:state eng) :playing))
   eng))

(defn playing? ([] (playing? *engine*)) ([eng] (= @(:state eng) :playing)))
(defn paused?  ([] (paused?  *engine*)) ([eng] (= @(:state eng) :paused)))
(defn stopped? ([] (stopped? *engine*)) ([eng] (= @(:state eng) :stopped)))

;; ============================================================
;; Play-arg mini-language
;;
;; A play-arg is either a bare keyword (a repo reference) or a group
;; vector. A group optionally starts with an explicit :par/:seq tag
;; (defaults to :seq if the first element isn't literally one of those).
;; Among a group's remaining items, a *leading* run that resolves (via a
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

(declare play-form)

(defn- play-form-seq
  [voice repo forms ctx-chain]
  (go
    (loop [fs forms]
      (when (and (seq fs) (voice-active? voice))
        (<! (play-form voice repo (first fs) ctx-chain))
        (recur (rest fs))))))

(defn- play-form-par
  [voice repo forms ctx-chain]
  (go
    (when (voice-active? voice)
      (let [start-clock      @(:clock voice)
            start-structural @(:structural voice)
            voices (mapv (fn [f]
                            (play-form (assoc voice
                                               :channel (swap! (:channel-counter voice) inc)
                                               :clock (atom start-clock)
                                               :structural (atom start-structural))
                                       repo f ctx-chain))
                          forms)]
        (doseq [v voices] (<! v))))))

(defn- play-form-group
  [voice repo tag items ctx-chain]
  (let [repo-now            (live-repo repo)
        [ctx-refs material] (split-leading-contexts repo-now items)
        chain (reduce (fn [chain ctx] (into [ctx] chain))
                       (into [(c/context)] ctx-chain)
                       ctx-refs)]
    (if (= tag :par)
      (play-form-par voice repo material chain)
      (play-form-seq voice repo material chain))))

(defn- play-form
  [voice repo form ctx-chain]
  (cond
    (keyword? form)
    (play-node voice repo (get (live-repo repo) form) ctx-chain)

    (vector? form)
    (let [tagged? (#{:par :seq} (first form))
          tag     (if tagged? (first form) :seq)
          items   (if tagged? (rest form) form)]
      (play-form-group voice repo tag items ctx-chain))

    :else (go nil)))

;; ============================================================
;; Play API
;; ============================================================

(defn play
  "Compose and play a structure from pre-defined repo parts. Uses
   *engine* -- call set-engine! first. One core.async voice per
   independent line; :PAR forks, :SEQ and Iterators (including
   :count :infinite ones) don't.

   Args are play-arg forms (see the mini-language above the play-form*
   fns): a mix of
     keyword  -- single part reference: :verse1
     vector   -- group, tag optional (defaults to :seq):
                   [:seq :verse1 :verse2]  same as  [:verse1 :verse2]
                   [:par [:seq :melody] [:seq :bass]]
     context-ref -- a keyword resolving to a repo :CONTEXT, as the
                    leading item(s) of a group: applies nearest, partly
                    overriding that group's own context.

   Examples:
     (play :verse1 :verse2)
     (play [:context1 :verse1] :verse2)
     (play [:par :context0 [:seq :verse1] [:seq :context2 :verse2]])"
  [& args]
  (let [eng      *engine*
        repo     (:repo eng)
        root-ctx (:context (get (live-repo repo) :ROOT))
        session  (swap! (:session eng) inc)
        voice    {:eng eng :session session :channel 0
                  :channel-counter (atom 0)
                  :clock (atom 0.0) :structural (atom 0)}]
    (reset! (:state eng) :playing)
    (play-form-group voice repo :seq args (if root-ctx [root-ctx] [])))
  *engine*)

;; ============================================================
;; REPL smoke-test
;; ============================================================

#_:clj-kondo/ignore
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
                               (c/context-root {"tempo" 120 "volume" 80}) 1/2)
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
