;; logistic.clj
;; Clojure port of kotlin-reference/jl/bifurcation.jl and verhulst.jl --
;; identical files under two different names for the same logistic map
;; (x = r*x*(1-x)). Their coroutine rewrite, resumable_bifurcation.jl,
;; was left unported:
;; it destructures three return values from a Julia @resumable generator
;; that only ever yields one, so it doesn't actually run as written.

(ns algo.random.logistic
  (:require [core.wall :as wall]))

(defn logistic-function
  "A logistic map generator -- the classic discrete chaotic system
   x = r*x*(1-x), iterated one step per call. Returns a map of three
   closures sharing private r/x atoms: :value (0-arg -- advances the
   map one step and returns the new x), :r! (resets the growth-rate
   parameter r), :x! (reseeds the current state x directly, without
   advancing).

   r controls the map's own long-term character, not just its speed:
   below ~3.0 it settles to a fixed point (boring, not useful as a
   random source), 3.0 to ~3.57 cycles through a growing sequence of
   periodic orbits (period-doubling), and beyond ~3.57 (up to 4.0,
   past which x can escape (0,1) entirely) it's genuinely chaotic --
   aperiodic, sensitive to x's exact starting value -- which is the
   actual reason this exists as a generative source rather than a
   plain uniform PRNG.

   This file's OWN r/x defaults (3.0, an arbitrary-looking mid-range
   seed) are NOT chaotic, despite first appearances -- confirmed
   empirically, not assumed: r = 3.0 is exactly the first
   period-doubling bifurcation point (where the fixed point splits into
   a 2-cycle), so :value there settles into a slow, barely-moving
   oscillation between two nearby values (~0.649/~0.684 from this
   file's own seed), still visibly drifting rather than repeating even
   after 20 calls -- nowhere near the rich, unpredictable variation the
   chaotic regime gives. For output that's actually musically
   interesting in that sense, pick r well inside 3.57-4.0 instead --
   r = 3.8 and r = 3.9 were both checked live and gave properly
   aperiodic, wide-ranging sequences from the first call on.

   (def lg (logistic-function 3.8 0.5))
   ((:value lg))  ;; advance one step, get the next x, in (0,1)"
  ([] (logistic-function 3.0 0.6486168175923613))
  ([r x]
   (let [r (atom r)
         x (atom x)]
     {:r!    (fn [f] (reset! r f))
      :x!    (fn [f] (reset! x f))
      :value (fn []
               (reset! x (* @r @x (- 1 @x))))})))

(defn logistic-algo
  "A core.wall FACTORY -- (fn [name params] -> name), params a map --
   built on top of core.wall/stateful-generator, the shared boilerplate
   every generator wall fn needs (idempotency tagging under core.wall's
   own double-call contract, non-leaf/rest/drum passthrough) -- wrapping
   logistic-function as a live generator, built and stored under name
   (see core.wall/build-algo!, this factory's own last step): the wall
   fn ignores its own placeholder nodes and substitutes the logistic
   map's own next x, mapped through :render-fn, in their place instead.
   next-fn is (:value (logistic-function r x)) directly -- logistic-
   function's own :value closure already IS the 0-arg 'advance and
   return the next raw value' shape stateful-generator expects, no
   adapting needed.

   params keys: :r/:x (required, mean exactly what logistic-function's
   own docstring says -- r well inside 3.57-4.0 for genuinely chaotic,
   musically interesting output, not this file's own non-chaotic (3.0)
   default), :render-fn (optional, raw x in (0,1) -> {:pitches [...]
   :duration r}, defaults to a plain 2-octave linear scale, MIDI 48-72,
   x=0 -> 48, x=1 -> 72, at a fixed 1/8 duration), :r-key (optional, a
   context key or nil).

   :r-key lets r itself be driven LIVE by a committed context envelope
   instead of staying fixed for the whole voice -- built on core.wall/
   context-params-pre-step-fn, sampling r-key against ctx-chain at the
   voice's own real elapsed structural time, once per generated step,
   and pushing the result into logistic-function's own :r! setter. x is
   never context-driven this way (it's the map's own running STATE,
   re-seeding it every step would just discard the chaotic trajectory,
   not modulate it) -- only r, the map's fixed parameter, is a sensible
   target here. Omitting :r-key (or passing nil) leaves r exactly the
   fixed value this factory was called with:
     '[ float 3.6 3.7 3.8 3.7 ]  ; committed as :chaosR, an authored ramp
     (logistic-algo :logisticPitch {:r 3.0 :x 0.5 :r-key :chaosR})
     (play :verse :algo :logisticPitch)"
  [name {:keys [r x render-fn r-key]
         :or {render-fn (fn [xv] {:pitches [(+ 48 (int (* xv 24)))] :duration 1/8})}}]
  (let [gen (logistic-function r x)]
    (wall/build-algo! name
      (wall/stateful-generator
        (:value gen)
        render-fn
        (when r-key
          (wall/context-params-pre-step-fn {:r r-key} (fn [m] ((:r! gen) (:r m)))))))))
