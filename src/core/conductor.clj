(ns core.conductor
  "Bridges the engine's structural boundaries to arbitrary, named, reusable
   actions -- registered once, triggered by id from anywhere (a boundary
   signal from the engine, or a human at the REPL), fully decoupled from
   *when* they fire.

   async-engine depends on this namespace (calls signal! directly, a plain
   function call -- see core.async-engine/play-node); this namespace
   never depends back on async-engine, and requires nothing else except
   core.registries (a leaf namespace holding this and a few other
   namespaces' mutable state, nothing else -- see its own docstring) --
   still a fully generic dispatcher, no domain/engine logic pulled in. It
   used to also require core.repo, for the primary use case
   (core.async-engine/schedule-tx!, cutting playback over to a newly-
   committed tx at a chosen boundary) living directly in this file; that
   moved to core.async-engine once cutover became per-voice (it needs to
   know what a voice is, which this namespace still never does) --
   schedule-tx! is still built on register-action!/schedule! from here,
   just no longer defined here. signal!'s event map is opaque to every
   function in this file, including a :voice key schedule-tx! now relies
   on -- conductor hands the whole event to whatever's registered without
   ever interpreting it.

   Two independent pieces:
   - action-registry: id -> f, a parked toolbox of reusable actions.
   - schedule: [id phase] -> action-id, filled in by (schedule! ...),
     consumed exactly once (dissoc'd on trigger) by (signal! ...) -- the
     engine's single entry point for every boundary kind. Three kinds
     fire: :section (a :SEQ/:PAR/etc. container's own :enter/:exit, :id a
     keyword), :bar (a voice crossing its own bar boundary -- see
     core.async-engine/advance-bar!, :id a bare integer, that
     voice's new bar number), and :mark (a voice hitting an author-placed
     BarLine -- | / || / ||| / |||| -- see async-engine/mark!, :id a
     [:mark count n] vector, count the pipe-count 1-4 and n that voice's
     own running count of markers at that same strength). The three :id
     spaces are deliberately disjoint (keyword / bare integer / vector)
     so all three share this one schedule table with no collision risk.
     A :mark is a pure author-placed extra layered on top of the
     automatic :section/:bar signals, not a replacement for them -- a
     BarLine has zero duration and never advances bar-pos on its own.
     Bar/mark tracking has no central authority -- each voice counts its
     own against whatever Meter its own ctx-chain has in scope, so
     (schedule! 8 :enter ...) fires on whichever voice reaches its own
     bar 8 *first*, not \"the piece's bar 8\" as a single notion."
  (:require [core.registries :as reg]))

;; ---------------------------------------------------------------------
;; Action registry -- a parked toolbox, independent of any boundary
;; ---------------------------------------------------------------------

(defn register-action!
  "Park f under id, callable later via (trigger! id & args) -- either
   directly (a human, the REPL) or indirectly (a boundary signal whose
   schedule entry names this id)."
  [id f]
  (swap! reg/*conductor-action-registry* assoc id f)
  nil)

(defn unregister-action!
  "Forget id's parked action."
  [id]
  (swap! reg/*conductor-action-registry* dissoc id)
  nil)

(defn trigger!
  "Apply the action registered under id to args, if one is registered.
   A no-op (returns nil) if id isn't registered."
  [id & args]
  (when-let [f (get @reg/*conductor-action-registry* id)]
    (apply f args)))

;; ---------------------------------------------------------------------
;; Schedule -- [id phase] -> action-id, one-shot
;; ---------------------------------------------------------------------

(defn schedule!
  "Fire action-id the next time [id phase] is signaled, e.g.
   (schedule! :verse :exit :cut-over) -- consumed on trigger (one-shot),
   so it needs re-scheduling for a repeat visit to the same section."
  [id phase action-id]
  (swap! reg/*conductor-schedule* assoc [id phase] action-id)
  nil)

(defn unschedule!
  "Cancel a pending schedule entry without ever triggering it."
  [id phase]
  (swap! reg/*conductor-schedule* dissoc [id phase])
  nil)

(defn scheduled
  "The pending {[id phase] -> action-id} schedule table, or just the
   action-id pending for [id phase] if given."
  ([] @reg/*conductor-schedule*)
  ([id phase] (get @reg/*conductor-schedule* [id phase])))

;; ---------------------------------------------------------------------
;; Repeating schedule -- [id phase] -> action-id, NOT consumed on trigger
;; ---------------------------------------------------------------------
;;
;; A genuinely separate table from `schedule` above, not a variant reading
;; of it: `schedule` is one-shot BY REMOVING the entry the instant it
;; fires, which is exactly right for "the next time this happens, do X
;; once" -- but it means the entry is briefly ABSENT between "voice A just
;; consumed it" and "whatever re-schedules it runs again" (see
;; core.async-engine/schedule-tx!'s own docstring for the concrete case
;; this was built for: several independent, concurrent voices -- e.g.
;; :PAR siblings -- each crossing their OWN copy of the same boundary,
;; e.g. the same bar number, where every one of them, not just whichever
;; gets there first, needs to be caught). A "consume, act, then
;; re-register" dance around the one-shot table was tried first and still
;; has exactly that gap: a second voice signaling the SAME [id phase]
;; while the first voice's action is still mid-flight, between its own
;; consume and its own re-register, finds nothing there and is silently
;; dropped -- confirmed live, not hypothetical, by a flaky test that
;; passed or failed depending on how core.async happened to interleave
;; two goroutines that tick. This table sidesteps the gap by never being
;; consumed at all: signal! (below) reads it without ever removing
;; anything from it, so any number of truly concurrent callers all see
;; the same still-present entry and all trigger -- correctness here comes
;; from the action itself being idempotent per-occurrence (schedule-tx!'s
;; own `redirected` set, keyed by voice path), not from the table
;; enforcing exactly-once.

(defn schedule-repeating!
  "Fire action-id every time [id phase] is signaled, by ANY voice, until
   explicitly cancelled with unschedule-repeating! -- never consumed on
   its own. Use this instead of schedule! when more than one concurrent
   occurrence of the same [id phase] is possible and every one of them
   should trigger the action (see the table's own comment above for why
   schedule!'s one-shot semantics can't safely be adapted into this by
   just re-scheduling after each trigger)."
  [id phase action-id]
  (swap! reg/*conductor-repeating* assoc [id phase] action-id)
  nil)

(defn unschedule-repeating!
  "Cancel a pending repeating entry -- no further [id phase] signals
   trigger it after this, regardless of how many already have."
  [id phase]
  (swap! reg/*conductor-repeating* dissoc [id phase])
  nil)

(defn scheduled-repeating
  "The pending {[id phase] -> action-id} repeating table, or just the
   action-id armed for [id phase] if given -- the non-consuming
   counterpart to `scheduled` above."
  ([] @reg/*conductor-repeating*)
  ([id phase] (get @reg/*conductor-repeating* [id phase])))

;; ---------------------------------------------------------------------
;; Signal -- the engine's single entry point, every boundary kind
;; ---------------------------------------------------------------------

(defn signal!
  "The engine's single entry point for every boundary kind (just :section
   for now). event is a plain map, e.g.
     {:kind :section :id :verse :type :SEQ :phase :enter}
   Checks BOTH tables for [id phase]: the one-shot `schedule` (consumed
   the instant it fires) and the non-consuming `repeating` (fires every
   time, until explicitly cancelled -- see that table's own comment on
   why it exists as a genuinely separate mechanism, not a variant of the
   one-shot one). Both can be armed for the same [id phase] at once and
   both will fire. Always safe to call even when nothing is scheduled
   either way -- a no-op.

   The one-shot table's read (is something scheduled here?) and its
   consume (remove it so it only fires once) are done as ONE
   compare-and-set!, not a separate get followed by a swap! -- this
   matters because signal! is genuinely called concurrently from
   independent voices (separate core.async go-blocks/threads, e.g. two
   :PAR siblings each crossing their own bar boundary at close to the
   same wall-clock instant -- see core.async-engine/advance-bar!). A
   get-then-swap! here would let two overlapping calls for the SAME
   [id phase] both read the entry before either one's dissoc landed, so
   both would go on to trigger! -- a scheduled action meant to
   consume-and-fire exactly once could silently double-fire instead. The
   CAS loop makes exactly one caller the one that successfully removes
   the entry; every other concurrent caller for that same [id phase]
   either sees it already gone (a legitimate no-op, same as calling
   signal! when nothing was ever scheduled) or retries against a fresh
   read if some UNRELATED concurrent schedule!/signal! changed the map
   out from under it."
  [{:keys [id phase] :as event}]
  (loop []
    (let [before    @reg/*conductor-schedule*
          action-id (get before [id phase])]
      (when action-id
        (if (compare-and-set! reg/*conductor-schedule* before (dissoc before [id phase]))
          (trigger! action-id event)
          (recur)))))
  (when-let [action-id (get @reg/*conductor-repeating* [id phase])]
    (trigger! action-id event)))

(comment
  ;; Register a reusable, general-purpose action -- no boundary involved.
  (register-action! :fade-out (fn [voice] (println "fading" voice)))
  (trigger! :fade-out :voice-2)

  ;; Primary use case (see core.async-engine/schedule-tx!): prepare an
  ;; edit, commit it, then cut ONE voice's own playback over to it right
  ;; as a chosen section finishes.
  ;; (core.async-engine/schedule-tx! :verse :exit :latest)
  ;; ... later, from anywhere: (parse ...) + (commit! sid) ...
  ;; the next time :verse's :SEQ container exits during playback, that
  ;; ONE voice's own :tx jumps to whatever was latest at that moment.
  )
