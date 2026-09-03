(ns algo.melodic.slonimsky
  "Nicolas Slonimsky's own interpolation techniques, from his 1947
   Thesaurus of Scales and Melodic Patterns -- ported from a real design
   email (emails/messages/algorithm/Slonimsky interpolation, 2026-04-28)
   that had already worked out clean, correct code for every named
   variant. A STATIC generator (whole-sequence-at-once, like most of
   algo.rithmic/algo.melodic -- see the project's own live-vs-static
   survey), not a per-voice wall-fn: these build a fixed melodic pattern
   from principal tones plus insertion material, not something that
   makes sense to re-derive fresh on every repeat cycle the way a
   generator like color-talea does.

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
