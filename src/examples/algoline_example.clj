(ns examples.algoline-example
  "A worked, REPL-runnable example of the algoline mechanism
   (algoline.core/step/dstep/dref/aref/model-step/safe/run-with-model)
   -- see algoline.core's own ns docstring for the full design. Nothing
   in this file runs on load; every form lives inside the trailing
   `(comment ...)` block below, same convention common/music_elements.clj's
   own smoke test and examples/chain_example.clj (the algo-tree branch's
   own worked example) already use.

   Walks through: a plain step; a dstep reading a named, live value out
   of a model atom (dref); a dstep referencing a NAMED step by name
   (aref) -- including the real, confirmed subtlety that a referenced
   step runs against the SAME current value, not a detached input, so a
   value-transforming step used this way double-counts unless it's
   written to ignore its own value argument; a model-step tracking how
   many times the algoline has run; and safe, wrapping one step so a
   broken dynamic degrades gracefully instead of taking down the whole
   line. Every result shown below was run for real, not just reasoned
   about -- verify against your own REPL if anything here ever looks
   stale."
  (:require [algoline.core :as a]))

(comment
  ;; A fixed, value-INDEPENDENT contribution -- ignores its own value
  ;; argument entirely, which matters once it's used via aref below.
  (def octave-up (a/step (constantly 12)))

  ;; A small pitch-shaping algoline: up a fifth (always), up by a
  ;; live, named amount (dref), up an octave (a named step, aref),
  ;; wrapped `safe` so a broken :octave doesn't abort the whole line --
  ;; then a model-step tracking how many times it's actually run.
  (def melody-line
    (a/algoline
      (a/step #(+ % 7))
      (a/dstep + (a/dref :amount))
      (a/safe (a/dstep + (a/aref :octave)))
      (a/model-step (fn [v dyns] [v (update dyns :play-count (fnil inc 0))]))))

  (def model (atom {:amount 3 :octave octave-up}))

  (a/run-with-model melody-line 60 model)
  ;; => 82   (60 + 7 + 3 + 12)

  (a/run-with-model melody-line 60 model)
  ;; => 82   -- same result, model is otherwise unchanged
  (:play-count @model)
  ;; => 2

  ;; aref's own real subtlety, confirmed live: a referenced step runs
  ;; against the SAME current value the calling dstep already has, not
  ;; a detached computation -- so a step that TRANSFORMS its own value
  ;; (rather than ignoring it, like octave-up above) double-counts:
  (def transforms-value (a/step #(+ % 12)))
  (a/run (a/dstep + (a/aref :octave)) 70 {:octave transforms-value})
  ;; => 152   -- 70 combined with (+ 70 12)=82 via +, NOT the clean 82
  ;;             a fixed contribution gives (see octave-up above)

  ;; Now break :octave on purpose -- safe degrades just that ONE step
  ;; (a console warning, [value dynamics] passed through unchanged at
  ;; the point of failure), not the whole melody-line.
  (swap! model assoc :octave :not-a-step)
  (a/run-with-model melody-line 60 model)
  ;; => 70   (60 + 7 + 3 -- the safe-wrapped octave step contributed
  ;;          nothing once :octave stopped being a real IStep, and the
  ;;          model-step after it still ran normally)
  )
