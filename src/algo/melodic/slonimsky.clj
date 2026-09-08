(ns algo.melodic.slonimsky
  "Nicolas Slonimsky's own interpolation techniques, from his 1947
   Thesaurus of Scales and Melodic Patterns -- ported from a real design
   email (emails/messages/algorithm/Slonimsky interpolation, 2026-04-28)
   that had already worked out clean, correct code for every named
   variant. A STATIC generator (whole-sequence-at-once, like most of
   algo.rhythmic/algo.melodic -- see the project's own live-vs-static
   survey): mixed-polations builds a fixed melodic pattern from
   principal tones plus insertion material in one pass, nothing
   stateful to carry across calls the way a generator like color-talea
   does.

   That's still a genuine per-voice wall-fn shape, though -- a plain
   TRANSFORM (reshape whatever nodes you're handed), not a GENERATOR
   (synthesize fresh content ignoring them) -- so mixed-polations-algo
   below wraps it exactly like algo.common.reshape's own
   weighted-shuffle-algo does: on an ordinary container it runs once;
   on a repeat's own body it re-runs fresh on every pass, weaving the
   SAME configured infra/inter/ultra material around whatever the
   voice's real material is each cycle.

   Slonimsky's own three basic operations, all relative to a sequence
   of PRINCIPAL TONES (the melodic skeleton):
     infrapolation  -- insert a pattern BEFORE each principal tone
     ultrapolation  -- insert a pattern AFTER each principal tone
     interpolation  -- insert a pattern BETWEEN consecutive principal tones
   mixed-polations below is the fully general form -- infra/inter/ultra
   are each independently optional (nil/empty = disabled), and
   infrapolate/ultrapolate/interpolate are thin convenience wrappers
   over it using Slonimsky's own vocabulary, matching this project's
   usual 'one general fn + named convenience wrappers' shape (compare
   algo.common.gate's own lo-criterion/hi-criterion/window-criterion,
   each a thin wrapper choosing one predicate shape)."
  )

(defn mixed-polations
  "The fully general form -- infra/inter/ultra are each independently
   optional (nil or empty disables that layer): for each principal tone
   p, in order, emit infra (if any), then p itself, then ultra (if any,
   unless p is the LAST tone and ultra-after-last? is false), then inter
   (if any, but never after the last tone -- there's nothing left to
   interpolate TOWARD). Slonimsky's own 'most flexible general function,'
   ported verbatim from the source email's own mixed_polations."
  ([principal] (mixed-polations principal nil nil nil false))
  ([principal infra inter ultra] (mixed-polations principal infra inter ultra false))
  ([principal infra inter ultra ultra-after-last?]
   (let [infra (or infra [])
         inter (or inter [])
         ultra (or ultra [])
         n     (count principal)]
     (vec
       (mapcat
         (fn [i p]
           (concat
             infra
             [p]
             (when (or ultra-after-last? (< i (dec n))) ultra)
             (when (< i (dec n)) inter)))
         (range)
         principal)))))

(defn infrapolate
  "Insert insertion BEFORE each tone in principal."
  [principal insertion]
  (mixed-polations principal insertion nil nil false))

(defn ultrapolate
  "Insert insertion AFTER each tone in principal (including the last,
   matching Slonimsky's own 'ultrapolation' as a standalone operation --
   contrast mixed-polations' own default of stopping after the last
   tone when used as part of a MIXED form)."
  [principal insertion]
  (mixed-polations principal nil nil insertion true))

(defn interpolate
  "Insert insertion BETWEEN each pair of consecutive tones in principal.
   A principal of fewer than 2 tones passes through unchanged -- nothing
   to interpolate between."
  [principal insertion]
  (if (< (count principal) 2)
    (vec principal)
    (mixed-polations principal nil insertion nil false)))

(defn mixed-polations-algo
  "A core.wall FACTORY -- (fn [infra inter ultra] -> wall-fn), or (fn
   [infra inter ultra ultra-after-last?] -> wall-fn) -- turning
   mixed-polations into a live per-voice TRANSFORM: the wall-fn it
   returns treats whatever nodes it's handed as Slonimsky's own
   PRINCIPAL TONES and weaves infra/inter/ultra material around them
   exactly as mixed-polations already does, nil/empty disabling any
   given layer same as there. A plain reshape, not a generator (contrast
   algo.common.isorhythm/color-talea-algo) -- infra/inter/ultra must
   already be real material (a seq of Leaf/Rest/Drum maps, e.g. via sq,
   or a literal), resolved BEFORE this factory ever sees them --
   core.wall/configure-preset!'s own resolve-config-form is the
   mechanism for that, same division of labor color-talea-algo already
   uses (this file stays free of any core.wall/core.repo dependency of
   its own either way, same as color-talea/color-talea-algo's own
   split between isorhythm.clj's plain data fn and whatever wires it up).

   register-algo! this under a name with :kind :factory, then tag it
   inline ([name infra inter ultra] as a play/assign-algo! :algo
   argument) or install-once/configure-later via configure-algo! -- see
   core.wall's own docstring for both mechanisms:
     (register-algo! :slonimsky mixed-polations-algo nil :factory)
     (play (repeat unfold 4 :verse) :algo [:slonimsky nil [:turn] nil])
   Because a repeat's own body is re-visited fresh, and its wall-fn
   re-invoked fresh, on EVERY pass (see weighted-shuffle-algo's own
   docstring for the confirmed-live mechanism), the SAME insertion
   material gets rewoven around whatever :verse's own material is on
   each cycle, with zero extra plumbing."
  ([infra inter ultra] (mixed-polations-algo infra inter ultra false))
  ([infra inter ultra ultra-after-last?]
   (fn [nodes _ctx-chain _voice]
     (mixed-polations nodes infra inter ultra ultra-after-last?))))
