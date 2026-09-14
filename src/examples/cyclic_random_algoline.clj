(ns examples.cyclic-random-algoline
  "A second implementation of algo.toolkit/cyclic-random (itself a thin
   forward of algo.random/cyclic-random) built on
   algoline-intercepted.core instead of a plain closure-over-an-atom --
   written to get a genuine feel for the one real capability algoline
   adds that the tree mechanism (the `algo` branch) explicitly leaves
   unsolved: a place for EVOLVING state to live that isn't hidden
   inside an opaque closure.

   algo.random/cyclic-random (and every other 0-arg-fn generator in
   that ns -- random-walk/biased-walk/smooth-walk/markov-chain) all
   share the same shape: (let [state (atom ...)] (fn [] ... swap!
   state ...)) -- real, running state, but INVISIBLE from outside the
   closure. You can't peek at it, patch one field of it without
   reconstructing the whole thing, or let a GUI bind a control to just
   one of its moving parts.

   cyclic-random-step/cyclic-random' (below) are the exact same
   algorithm, expressed as an algoline-intercepted.core/step instead:
   :pool/:idx live in `state`, a plain map, visible and patchable from
   outside the moment it's attach!'d (algoline-intercepted.core/
   patch-active!, or just @state-atom) -- no atom hidden inside a
   returned closure.

   This also happens to be a direct, deliberate test of whether
   algoline's own execution shape is naturally immune to the exact bug
   class just fixed in algo.random/cyclic-random (src/algo/random.clj,
   2026-09-12): reading {:keys [pool idx]} BEFORE checking/performing
   an exhaustion reset, then using those stale, pre-reset locals for
   the actual item lookup. See cyclic-random-step's own docstring for
   why the answer is yes here, structurally, not just by coincidence.

   (Ported 2026-09-14 from the original algoline.core -- since removed
   entirely, superseded by algoline-intercepted.core -- onto the
   surviving mechanism; model-step's own pair-return shape is now just
   step's pair-return shape, and dstep/dref collapsed into ref.)"
  (:require [algoline-intercepted.core :as a]
            [algo.random :as random]))

(defn cyclic-random-step
  "An algoline step running THE SAME algorithm as algo.random/
   cyclic-random, with :pool/:idx threaded through `state` instead of a
   closure's own private atom. Every part of the exhaustion check, the
   reshuffle-on-exhaustion, the item lookup, and the index increment
   happens inside this ONE function call -- the same reason algo.
   random/cyclic-random's own fix works (folding everything into one
   atomic swap!): there is no window here where a stale, pre-reset
   :pool/:idx could be read, because `state` is a plain, immutable
   value for the entire duration of this one call, never a live atom
   multiple separate statements could read out of sequence against.

   coll must be non-empty -- same reasoning as algo.toolkit/
   cycle-shuffle's own guard: an empty coll can never produce a first
   item, so this fails immediately and loudly rather than looping
   forever or (worse) silently returning nil the way the pre-fix
   algo.random/cyclic-random once did at its own exhaustion boundary."
  [coll]
  (when (empty? coll)
    (throw (ex-info "cyclic-random-step: coll must not be empty" {:coll coll})))
  (a/step
    (fn [_value state]
      (let [pool       (or (:pool state) (random/shuffle coll))
            idx        (or (:idx state) 0)
            [pool idx] (if (= idx (count pool))
                         [(random/shuffle coll) 0]
                         [pool idx])]
        [(nth pool idx) (assoc state :pool pool :idx (inc idx))]))))

(defn cyclic-random'
  "An Algoline wrapping cyclic-random-step -- the direct algoline
   counterpart to algo.random/cyclic-random (and algo.toolkit's own
   thin forward of it). Unlike that closure-returning version, this
   returns a composable interceptor: run it with algoline-intercepted.
   core/run-with-state against your own state atom, or attach!/
   run-active! for a live, per-path instance a GUI could bind controls
   to. Its own output is a bare drawn item, not resolved-leaf shape --
   see cyclic-random-leaf' below for a root?-eligible version."
  [coll]
  (a/algoline (cyclic-random-step coll)))

(defn cyclic-random-leaf'
  "cyclic-random' wired into resolved-leaf shape ({:pitches [pitch]
   :duration dur}, dur read from state via ref) -- root?-eligible, so
   (unlike a bare cyclic-random') this one is directly attach!-able as
   a live, per-path instance (see algoline-intercepted.core/attach!/
   validate-root!). Caller supplies :dur in attach!'s own
   initial-state (or a static state map passed to run/run-with-state)."
  [coll]
  (a/algoline
    (cyclic-random-step coll)
    (a/step (fn [pitch state] {:pitches [pitch] :duration (a/ref state :dur pitch)}))))

(comment
  ;; Threading your own state atom, call by call -- the direct
  ;; algoline equivalent of holding onto the closure
  ;; algo.random/cyclic-random returns and calling it repeatedly.
  (def st (atom {}))
  (def gen (cyclic-random' [60 62 64]))
  (a/run-with-state gen nil st)  ;; => one of 60/62/64
  (a/run-with-state gen nil st)  ;; => a different one (draws without
                                   ;;    replacement from the CURRENT pool)
  @st
  ;; => {:pool [...] :idx 1} -- visible, inspectable state, unlike the
  ;;    plain closure version: you can read exactly where this
  ;;    generator is in its current pass, or patch it directly.

  ;; Live, per-path, GUI-bindable version -- see test/
  ;; cyclic_random_algoline_test.clj for this run for real, not just
  ;; reasoned about:
  (def leaf-gen (cyclic-random-leaf' [60 62 64]))
  (a/attach! [:demo] leaf-gen 60 {:dur 1/4})
  (a/run-active! [:demo] 60)
  ;; => {:pitches [<one of 60/62/64>], :duration 1/4}
  (a/patch-active! [:demo] {:dur 1/8})
  ;; ^ a GUI "change the duration this generator draws at" control,
  ;; straight into the live state atom -- no equivalent exists for
  ;; the plain closure version at all, short of throwing it away and
  ;; building a new one from scratch.
  (a/run-active! [:demo] 60)
  ;; => {:pitches [...], :duration 1/8} -- the patch took effect on
  ;; the very next draw.
  )
