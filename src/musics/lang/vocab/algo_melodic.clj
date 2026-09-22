(ns musics.lang.vocab.algo-melodic
  "musics.lang's own `algo-melodic` vocabulary -- a mechanical bridge of
   algo/melodic/*.clj's own public API: species counterpoint
   (algo.melodic.counterpoint), generative melody methods/constraint
   walks (algo.melodic.melody), and Slonimsky interpolation
   (algo.melodic.slonimsky)."
  (:require [algo.melodic.counterpoint :as cpt]
            [algo.melodic.melody :as melody]
            [algo.melodic.slonimsky :as slon]
            [musics.lang.runtime :refer [push! pop! builtin]])
  (:refer-clojure :exclude [pop!]))

(defn vocab []
  (merge
    (builtin "default-rules" (fn [ctx] (push! ctx cpt/default-rules))
             "( -- rules )" "no parallel fifths/octaves, consonance required on every beat")
    (builtin "generate" (fn [ctx] (let [voice-specs (pop! ctx) beat-durations (pop! ctx) rules (pop! ctx)
                                         pitch-range (pop! ctx) scale (pop! ctx)]
                                     (push! ctx (cpt/generate scale pitch-range rules beat-durations voice-specs))))
             "( scale pitch-range rules beat-durations voice-specs -- voices )" "species counterpoint: motivic imitation then rule enforcement")

    (builtin "c-major" (fn [ctx] (push! ctx melody/c-major)) "( -- scale )" "the C major 0-11 pitch-class scale")
    (builtin "a-minor" (fn [ctx] (push! ctx melody/a-minor)) "( -- scale )" "the A minor 0-11 pitch-class scale")
    (builtin "c-pentatonic" (fn [ctx] (push! ctx melody/c-pentatonic)) "( -- scale )" "the C major-pentatonic 0-11 pitch-class scale")
    (builtin "markov-train" (fn [ctx] (let [order (pop! ctx) melody* (pop! ctx)] (push! ctx (melody/markov-train melody* order))))
             "( melody order -- model )" "trains an order-N Markov transition model from a melody")
    (builtin "markov-generate" (fn [ctx] (let [length (pop! ctx) model (pop! ctx)] (push! ctx (melody/markov-generate model length))))
             "( model length -- melody )" "generates length notes by walking a trained Markov model")
    (builtin "lsystem-melody" (fn [ctx] (let [max-notes (pop! ctx) iterations (pop! ctx) note-map (pop! ctx) rules (pop! ctx) axiom (pop! ctx)]
                                            (push! ctx (melody/lsystem-melody axiom rules note-map iterations max-notes))))
             "( axiom rules note-map iterations max-notes -- melody )" "expands an L-system axiom into a melody via note-map")
    (builtin "grammar-generate" (fn [ctx] (let [terminals (pop! ctx) rules (pop! ctx)] (push! ctx (melody/grammar-generate rules terminals))))
             "( rules terminals -- melody )" "generates a melody from a generative grammar's rules/terminals")
    (builtin "constraint-melody" (fn [ctx] (let [constraints (pop! ctx) length (pop! ctx) scale (pop! ctx)]
                                               (push! ctx (melody/constraint-melody scale length constraints))))
             "( scale length constraints -- melody )" "a constraint-satisfaction walk over scale, length notes long")
    (builtin "modulating-melody" (fn [ctx] (let [constraints (pop! ctx) segments (pop! ctx)]
                                               (push! ctx (melody/modulating-melody segments constraints))))
             "( segments constraints -- melody )" "a melody across several scale segments in sequence, one key-changing generator")
    (builtin "max-leap-constraint" (fn [ctx] (let [max-degrees (pop! ctx) scale (pop! ctx)] (push! ctx (melody/max-leap-constraint scale max-degrees))))
             "( scale max-degrees -- constraint )" "rejects a candidate note leaping more than max-degrees scale steps")
    (builtin "no-repeat-constraint" (fn [ctx] (let [note (pop! ctx) melody* (pop! ctx)] (push! ctx (melody/no-repeat-constraint melody* note))))
             "( melody note -- ? )" "rejects note if it repeats melody's own last note")
    (builtin "direction-limit-constraint" (fn [ctx] (let [max-consecutive (pop! ctx) scale (pop! ctx)]
                                                        (push! ctx (melody/direction-limit-constraint scale max-consecutive))))
             "( scale max-consecutive -- constraint )" "rejects a note extending a same-direction run past max-consecutive")
    (builtin "cadence-constraint" (fn [ctx] (let [cadence-note (pop! ctx) target-length (pop! ctx)]
                                                (push! ctx (melody/cadence-constraint target-length cadence-note))))
             "( target-length cadence-note -- constraint )" "requires the melody's own last note to be cadence-note")

    (builtin "infrapolate" (fn [ctx] (let [insertion (pop! ctx) principal (pop! ctx)] (push! ctx (slon/infrapolate principal insertion))))
             "( principal insertion -- tones )" "insert insertion BEFORE each tone in principal")
    (builtin "interpolate" (fn [ctx] (let [insertion (pop! ctx) principal (pop! ctx)] (push! ctx (slon/interpolate principal insertion))))
             "( principal insertion -- tones )" "insert insertion BETWEEN each pair of consecutive tones in principal")
    (builtin "ultrapolate" (fn [ctx] (let [insertion (pop! ctx) principal (pop! ctx)] (push! ctx (slon/ultrapolate principal insertion))))
             "( principal insertion -- tones )" "insert insertion AFTER each tone in principal, including the last")
    (builtin "mixed-polations" (fn [ctx] (let [ultra-after-last? (pop! ctx) ultra (pop! ctx) inter (pop! ctx) infra (pop! ctx) principal (pop! ctx)]
                                             (push! ctx (slon/mixed-polations principal infra inter ultra ultra-after-last?))))
             "( principal infra inter ultra ultra-after-last? -- tones )" "the fully general Slonimsky interpolation form")
    (builtin "mixed-polations-algo" (fn [ctx] (let [params (pop! ctx) name (pop! ctx)] (push! ctx (slon/mixed-polations-algo name params))))
             "( name params -- name )" "a core.wall factory wrapping mixed-polations (params: :infra :inter :ultra :ultra-after-last?)")))
