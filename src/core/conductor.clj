(ns core.conductor
  "Bridges the engine's structural boundaries to arbitrary, named, reusable
   actions -- registered once, triggered by id from anywhere (a boundary
   signal from the engine, or a human at the REPL), fully decoupled from
   *when* they fire.

   async-engine depends on this namespace (calls signal! directly, a plain
   function call -- see core.async-engine/play-node); this namespace
   never depends back on async-engine, only on core.repo (for the primary
   use case: cutting playback over to a newly-committed tx at a chosen
   boundary).

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
  (:require [core.repo :as repo]))

;; ---------------------------------------------------------------------
;; Action registry -- a parked toolbox, independent of any boundary
;; ---------------------------------------------------------------------

(defonce action-registry (atom {}))

(defn register-action!
  "Park f under id, callable later via (trigger! id & args) -- either
   directly (a human, the REPL) or indirectly (a boundary signal whose
   schedule entry names this id)."
  [id f]
  (swap! action-registry assoc id f)
  nil)

(defn unregister-action!
  "Forget id's parked action."
  [id]
  (swap! action-registry dissoc id)
  nil)

(defn trigger!
  "Apply the action registered under id to args, if one is registered.
   A no-op (returns nil) if id isn't registered."
  [id & args]
  (when-let [f (get @action-registry id)]
    (apply f args)))

;; ---------------------------------------------------------------------
;; Schedule -- [id phase] -> action-id, one-shot
;; ---------------------------------------------------------------------

(defonce schedule (atom {}))

(defn schedule!
  "Fire action-id the next time [id phase] is signaled, e.g.
   (schedule! :verse :exit :cut-over) -- consumed on trigger (one-shot),
   so it needs re-scheduling for a repeat visit to the same section."
  [id phase action-id]
  (swap! schedule assoc [id phase] action-id)
  nil)

(defn unschedule!
  "Cancel a pending schedule entry without ever triggering it."
  [id phase]
  (swap! schedule dissoc [id phase])
  nil)

(defn scheduled
  "The pending {[id phase] -> action-id} schedule table, or just the
   action-id pending for [id phase] if given."
  ([] @schedule)
  ([id phase] (get @schedule [id phase])))

;; ---------------------------------------------------------------------
;; Signal -- the engine's single entry point, every boundary kind
;; ---------------------------------------------------------------------

(defn signal!
  "The engine's single entry point for every boundary kind (just :section
   for now). event is a plain map, e.g.
     {:kind :section :id :verse :type :SEQ :phase :enter}
   If action-id is scheduled for [id phase], triggers it (passing event)
   and consumes the schedule entry. Always safe to call even when nothing
   is scheduled there -- a no-op."
  [{:keys [id phase] :as event}]
  (when-let [action-id (get @schedule [id phase])]
    (swap! schedule dissoc [id phase])
    (trigger! action-id event)))

;; ---------------------------------------------------------------------
;; Primary use case: cut playback over to a tx at a chosen boundary
;; ---------------------------------------------------------------------

(defn schedule-tx!
  "Cut playback over to target-tx the next time [id phase] is signaled,
   e.g. (schedule-tx! :verse :exit 8) to jump playback to tx 8 right as
   the :verse section finishes. target-tx may also be :latest, resolved
   to whatever is the latest committed tx at the moment this actually
   fires (not when it was scheduled) -- for \"commit now, cut over
   whenever we get there\" rather than a tx number fixed in advance.
   Returns the generated action-id (e.g. to unregister-action! later)."
  [id phase target-tx]
  (let [action-id (gensym "cut-over")]
    (register-action! action-id
                       (fn [_event]
                         (repo/play-tx! (if (= target-tx :latest)
                                          (repo/latest-tx)
                                          target-tx))))
    (schedule! id phase action-id)
    action-id))

(comment
  ;; Register a reusable, general-purpose action -- no boundary involved.
  (register-action! :fade-out (fn [voice] (println "fading" voice)))
  (trigger! :fade-out :voice-2)

  ;; Primary use case: prepare an edit, commit it, then cut playback over
  ;; to it right as a chosen section finishes.
  (schedule-tx! :verse :exit :latest)
  ;; ... later, from anywhere: (parse ...) + (commit! sid) ...
  ;; the next time :verse's :SEQ container exits during playback, play-tx
  ;; jumps to whatever was latest at that moment.
  )
