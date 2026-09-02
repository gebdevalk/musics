(ns algo.common.reshape
  "Compositional reshaping recipes over already-resolved domain material
   -- typically a real Clojure seq produced by musics.clj/sq, reshaped
   further with ordinary seq functions, then handed to play.

   Distinct from core.domain.flat-domain's per-leaf transforms (transpose/
   invert/times/dotted/dynamic), which reshape one part's own fields in
   place -- transpose/times/dotted/dynamic only ever look at their own
   part. invert is the one exception once an axis isn't given: its own
   no-arg form means each part around its own individual mean (a chord
   folds around its own center, part by part), which is still a per-part
   computation, not a sequence-wide one. This namespace's own invert
   below is the sequence-wide version -- one shared axis, computed from
   every pitch across the whole sequence -- alongside retrograde/
   arpeggiate/hocket's reordering, splitting, and combining.

   weighted-shuffle/weighted-shuffle-algo (added later, alongside the
   others but a genuinely different KIND of thing) are this project's
   first composite wall-fn FACTORY -- one whose own arg is another named
   thing (a registered distribution, core.wall/distribution-fn) rather
   than a literal value, explored directly as the concrete case for
   'can algorithm composition itself be specified, not just algorithm
   parameters' (see doc/decisions.md for the fuller design discussion).

   lo-filter/hi-filter/window-filter (and their own -algo factory
   wrappers) are the audio low-pass/high-pass/band-pass analogy, gating
   PITCH instead of frequency -- ordinary parameterized factories (a
   literal cutoff/range, not a name resolved against another registry),
   unlike weighted-shuffle-algo."
  (:require [core.domain.flat-domain :as d]
            [core.wall :as wall]))

(defn invert
  "Sequence-level convenience over core.domain.flat-domain/invert: mirrors
   every part in parts around one shared axis pitch, rather than each part
   inverting around its own individual mean. With axis given, this is
   exactly (mapv (d/invert axis) parts). With no axis, the axis used is
   the rounded mean of every pitch across the WHOLE sequence, so the
   sequence folds around its own overall center as one shape -- distinct
   from d/invert's own no-arg form, which means each part around only its
   own pitches."
  ([parts]
   (let [pitches (mapcat :pitches parts)
         mean    (Math/round (double (/ (reduce + pitches) (count pitches))))]
     (invert mean parts)))
  ([axis parts]
   (mapv (d/invert axis) parts)))

(defn retrograde
  "Reverse a sequence of parts -- the classical retrograde transform, same
   idea as core.domain.context/env-reverse but applied to a sequence of
   parts rather than a single envelope. Doesn't adjust :tied flags -- a
   tie into what's now the previous note isn't un-tied or re-anchored,
   so a phrase with ties may not retrograde cleanly on its own."
  [parts]
  (vec (reverse parts)))

(defn arpeggiate
  "Split a chord leaf's simultaneous pitches into a sequence of single-
   note leaves, splitting the original duration evenly across them --
   turns a chord into a run. Pitches are sorted ascending by default;
   pass order-fn (e.g. (comp reverse sort) for descending) to change the
   order. A no-op (returns [leaf]) for a leaf with fewer than 2 pitches,
   or any part with no :pitches at all (rest/drum/container)."
  ([leaf] (arpeggiate leaf sort))
  ([leaf order-fn]
   (let [pitches (order-fn (:pitches leaf))
         n       (count pitches)]
     (if (< n 2)
       [leaf]
       (let [dur (/ (:duration leaf) n)]
         (mapv #(assoc leaf :pitches [%] :duration dur) pitches))))))

(defn hocket
  "Interleave two or more part-sequences into one, alternating single
   elements from each in turn -- the medieval hocket technique: a single
   melodic line split across voices, one note/group at a time. A thin
   named wrapper over interleave -- the value here is the name, not new
   logic."
  [& parts-seqs]
  (apply interleave parts-seqs))

(defn weighted-shuffle
  "Shuffle parts by repeatedly drawing the NEXT output element from
   whatever's still remaining, at index (dist-fn 0 n) -- n the CURRENT
   remaining count, floored and clamped into [0, n-1] -- rather than
   assigning each element an independent sort key. That more obvious-
   looking construction was tried first and rejected once actually
   checked: ranks of i.i.d. continuous draws are uniform over
   permutations no matter their marginal distribution, so 'sort by an
   independent draw per element' silently makes dist-fn's own shape
   irrelevant to the result -- confirmed with a live probe (a 20000-
   trial average-displacement comparison) before writing this, not
   just reasoned about; uniform and algo.random/lo-emph came back
   statistically indistinguishable (1.9456 vs 1.9395) under that
   construction.

   This one actually responds to dist-fn's shape, confirmed the same
   way: with dist-fn = algo.random/uniform, draws are unbiased over the
   remaining pool at every step -- pick-random-without-replacement,
   which reduces to the SAME permutation distribution plain Fisher-
   Yates/clojure.core/shuffle produces (1.9422 vs 1.9449 in the same
   probe -- confirmed, not assumed). With algo.random/lo-emph (peaked
   toward the LOW end of a range), draws cluster near 0 -- i.e. near
   the FRONT of whatever's still remaining -- so elements tend to keep
   close to their ORIGINAL relative order: a weaker, order-preserving
   shuffle (1.2626 average displacement vs uniform's 1.9422 in the same
   n=6 probe). hi-emph is the mirror image, biased toward picking from
   near the END of what's remaining each step -- a stronger, more
   reversal-leaning reorder."
  [parts dist-fn]
  (loop [remaining (vec parts) result []]
    (if (empty? remaining)
      result
      (let [n   (count remaining)
            idx (-> (dist-fn 0 n) Math/floor long (max 0) (min (dec n)))]
        (recur (into (subvec remaining 0 idx) (subvec remaining (inc idx) n))
               (conj result (nth remaining idx)))))))

(defn weighted-shuffle-algo
  "A core.wall FACTORY -- (fn [dist-name] -> wall-fn) -- resolving
   dist-name against core.wall/distribution-fn (register it there
   first, e.g. (register-distribution! :lo-emph algo.random/lo-emph))
   and building a wall-fn that reorders whatever nodes it's handed via
   weighted-shuffle above. This project's first composite wall-fn
   factory whose own arg names ANOTHER registered thing -- a
   distribution, not a literal value -- the concrete case explored for
   whether algorithm COMPOSITION itself, not just parameters, is worth
   specifying declaratively (see doc/decisions.md).

   An unregistered dist-name is checked and handled HERE, eagerly, at
   factory-application time -- not left to fail lazily the first time
   the returned wall-fn actually runs, deep inside a live voice's own
   go-block, where a thrown exception silently kills the voice instead
   of surfacing (confirmed elsewhere in this project, see
   validate-ids!'s own docstring) -- so an unregistered name degrades
   to identity (no shuffling) with a console warning immediately,
   same 'degrade and warn, never throw from inside a live voice' policy
   core.wall/apply-factory already has for its own failure cases.

   register-algo! this under a name with :kind :factory, then tag it
   inline ([name dist-name] as a play/assign-algo! :algo argument) --
   see core.wall's own docstring for the mechanism:
     (register-distribution! :lo-emph algo.random/lo-emph)
     (register-algo! :weightedShuffle weighted-shuffle-algo nil :factory)
     (play (repeat unfold 4 [c4 d4 e4 f4]) :algo [:weightedShuffle :lo-emph])
   Because a repeat's own body is re-visited fresh, and its wall-fn re-
   invoked fresh, on EVERY pass (core.async-engine's play-node container
   branch calls resolve-algo on raw-children on every single visit, no
   caching -- confirmed live, not assumed), this reshuffles anew each
   cycle with zero extra plumbing -- the whole point of the original
   'repeat n times, reshuffled every cycle, weighted by lo-emph' case
   this factory was built to answer."
  [dist-name]
  (if-let [dist-fn (wall/distribution-fn dist-name)]
    (fn [nodes _ctx-chain _voice] (weighted-shuffle nodes dist-fn))
    (do (println "algo.common.reshape: no distribution registered as" dist-name
                  "-- falling back to identity")
        (fn [nodes _ctx-chain _voice] nodes))))

;; ============================================================
;; Pitch-range gates: lo-filter/hi-filter/window-filter -- the audio
;; low-pass/high-pass/band-pass analogy, gating PITCH instead of
;; frequency. Ordinary parameterized factories (a literal cutoff/range,
;; not a name resolved against another registry the way weighted-
;; shuffle-algo's distribution arg is) -- ANY cutoff/range works, no
;; registration step needed beyond register-algo! itself, so these
;; don't touch core.wall's distribution registry at all.
;; ============================================================

(defn pitch-filter
  "Gate parts (a seq of Leaf/Rest/Drum/container/etc -- the same shape a
   wall-fn always receives) by pred, a MIDI-pitch predicate (int ->
   boolean). Only Leaf parts are affected -- Rest passes through
   untouched (nothing to filter), Drum passes through untouched (its
   :program identifies an instrument/sound, not a pitch, so a pitch
   predicate doesn't meaningfully apply), and any container/Bar/
   :assignment/etc. passes through too, same tolerance every wall-fn in
   this project already has for a shape it doesn't specifically act on.

   A chord Leaf is filtered PITCH BY PITCH, not kept/dropped wholesale
   -- (pitch-filter #(<= % 67) [chord-with-pitches-60-72-64]) keeps
   [60 64], drops 72, same as a real filter gates each frequency
   component of a signal independently rather than an all-or-nothing
   decision per note. A Leaf whose pitches ALL fail is DROPPED from the
   result entirely, not rested -- a genuine filter removes what doesn't
   pass, same as clojure.core/filter itself; the returned seq can be
   SHORTER than parts, and downstream timing/repeat-cycle length
   shrinks accordingly (confirmed safe at every stage a dropped Leaf
   can reach: play-leaves guards on (seq xs), resolve-ornaments is a
   plain mapcat -- both true no-ops on an empty seq, whether the drop
   happens at a container's own batch call or at one leaf's own
   singleton re-dispatch)."
  [pred parts]
  (into []
        (keep (fn [part]
                (if (d/leaf? part)
                  (let [kept (filterv pred (:pitches part))]
                    (when (seq kept) (assoc part :pitches kept)))
                  part)))
        parts))

(defn lo-filter
  "Keep only pitches at or below cutoff -- everything above is dropped
   (or, in a chord, dropped from just that chord). The audio low-pass
   analogy: passes LOW, gates out HIGH. See pitch-filter."
  [parts cutoff]
  (pitch-filter #(<= % cutoff) parts))

(defn hi-filter
  "Keep only pitches at or above cutoff -- the audio high-pass analogy:
   passes HIGH, gates out LOW. See pitch-filter."
  [parts cutoff]
  (pitch-filter #(>= % cutoff) parts))

(defn window-filter
  "Keep only pitches within [lo hi] inclusive -- the audio band-pass
   analogy. See pitch-filter."
  [parts lo hi]
  (pitch-filter #(<= lo % hi) parts))

(defn lo-filter-algo
  "A core.wall FACTORY -- (fn [cutoff] -> wall-fn) -- wrapping lo-filter
   as a per-voice playback algorithm:
     (register-algo! :loFilter lo-filter-algo nil :factory)
     (play :verse :algo [:loFilter 67])"
  [cutoff]
  (fn [nodes _ctx-chain _voice] (lo-filter nodes cutoff)))

(defn hi-filter-algo
  "A core.wall FACTORY -- (fn [cutoff] -> wall-fn) -- wrapping hi-filter
   as a per-voice playback algorithm. See lo-filter-algo's own
   docstring for the registration/use pattern."
  [cutoff]
  (fn [nodes _ctx-chain _voice] (hi-filter nodes cutoff)))

(defn window-filter-algo
  "A core.wall FACTORY -- (fn [lo hi] -> wall-fn) -- wrapping
   window-filter as a per-voice playback algorithm:
     (register-algo! :windowFilter window-filter-algo nil :factory)
     (play :verse :algo [:windowFilter 60 72])"
  [lo hi]
  (fn [nodes _ctx-chain _voice] (window-filter nodes lo hi)))

;; ============================================================
;; chain-algo -- composing several NAMED algos into one, "prepare and
;; perform" via plain Clojure data (a vector of Name specs), not text.
;; The concrete answer to "a flexible, simple way to compose algorithms
;; declaratively, without needing a grammar": configure-preset! is
;; already the PREPARE step (a named, ready-to-perform instance);
;; assign-algo!/[Form :algo Name] is already PERFORM; chain-algo is the
;; one missing piece -- something to prepare FROM that's richer than a
;; single factory's own args.
;; ============================================================

(defn chain-algo
  "A core.wall FACTORY -- (fn [& specs] -> wall-fn) -- composing several
   named algos into ONE wall-fn, threading nodes through each spec IN
   ORDER: spec1's own resolved algo runs first, its OUTPUT becomes
   spec2's own input, and so on. Each spec is the SAME Name shape
   assign-algo! already accepts -- a bare registered name, or [name
   arg...] to apply a registered FACTORY inline -- resolved via
   core.wall/resolve-name, the EXACT SAME resolution assign-algo!
   itself uses (moved there from core.async-engine specifically so a
   caller outside the engine, like this one, could reach it without
   core.wall needing to depend on the engine -- see resolve-name's own
   docstring). An unregistered/mistyped spec degrades that ONE step to
   identity (resolve-name's own console warning), same 'degrade and
   warn, never throw from inside a live voice' policy every other
   composite resolution in this project already has -- the REST of the
   chain still runs; one bad step doesn't break the whole thing.

     (register-algo! :chain chain-algo nil :factory)
     (play :verse :algo [:chain [:loFilter 67] [:weightedShuffle :lo-emph]])

   -- or PREPARE it as a reusable, named instance via configure-preset!:

     (configure-preset! :morning :chain [:loFilter 67] [:weightedShuffle :lo-emph])
     (play :verse :algo :morning)

   Both confirmed live. The configure-preset! path has one real, narrow
   caveat worth knowing: configure-preset!'s own args are resolved
   against COMMITTED REPO MATERIAL first (core.wall/resolve-config-form
   -- a bare keyword there means 'look this up as a repo id', not 'an
   algo name'). A spec's own leading keyword (:loFilter, :weightedShuffle)
   only survives that step UNCHANGED because it happens not to also name
   a real, committed repo id -- if it did, configure-preset! would
   silently substitute that container's own children in its place
   instead. The DIRECT inline [Form :algo [:chain ...]] tag has no such
   ambiguity at all (assign-algo!'s own Name argument is never run
   through resolve-config-form) -- prefer it when in doubt, or when a
   spec's own name might collide with something you've also committed
   to the repo."
  [& specs]
  (let [resolved (mapv wall/resolve-name specs)]
    (fn [nodes ctx-chain voice]
      (reduce (fn [ns algo-fn] (algo-fn ns ctx-chain voice)) nodes resolved))))

;; ============================================================
;; Three more small gates -- pitch-class/interval/probability -- ported
;; from the same source email cluster as lo-filter/hi-filter/window-
;; filter (emails/messages/algorithm/More filters, 2026-03-11). A
;; rejected part is DROPPED from the result, same as pitch-filter
;; itself and the Python originals both do -- an earlier version of
;; this file instead rested a rejected part to keep timing/sequence
;; length unchanged; reverted (2026-09-02, per direct user feedback: a
;; filter must remove what doesn't pass, not mute it).
;; ============================================================

(defn pitch-class-filter
  "Keep only pitches whose pitch CLASS (mod 12) is in allowed-pcs --
   e.g. constrain a melody to a scale's own pitch classes regardless of
   octave. Reuses pitch-filter's own chord-aware, drop-on-all-fail
   machinery directly (a chord is gated pitch by pitch, same as
   lo-filter/hi-filter/window-filter already do)."
  [parts allowed-pcs]
  (let [allowed (set (map #(mod % 12) allowed-pcs))]
    (pitch-filter #(contains? allowed (mod % 12)) parts)))

(defn interval-filter
  "Keep a Leaf part only if its OWN first pitch's melodic interval from
   the immediately PRECEDING part's own pitch (the raw previous part in
   parts, not the last part that actually survived filtering -- matches
   the source's own semantics exactly: an excluded part still counts as
   'the previous one' for the NEXT part's own interval check) is in
   allowed-intervals. The very first Leaf is always kept -- there's no
   previous interval to check yet. A rejected Leaf is DROPPED from the
   result, not rested -- the result can be shorter than parts. Non-Leaf
   parts (Rest/Drum/container/etc.) pass through untouched and don't
   reset what counts as 'previous.'"
  [parts allowed-intervals]
  (let [allowed (set allowed-intervals)]
    (loop [remaining (seq parts) prev-pitch nil first? true out []]
      (if (empty? remaining)
        out
        (let [part (first remaining)]
          (if (d/leaf? part)
            (let [p (first (:pitches part))
                  keep? (or first? (contains? allowed (- p prev-pitch)))]
              (recur (rest remaining) p false (if keep? (conj out part) out)))
            (recur (rest remaining) prev-pitch first? (conj out part))))))))

(defn probability-filter
  "Keep each Leaf part with probability p (a Bernoulli coin flip per
   part, independent of pitch -- a chord is kept or dropped as a whole,
   not gated pitch by pitch, since the coin flip has nothing to do with
   pitch value at all). A rejected Leaf is DROPPED from the result, not
   rested. Non-Leaf parts always pass through untouched."
  [parts p]
  (into []
        (keep (fn [part]
                (cond
                  (not (d/leaf? part)) part
                  (< (rand) p)         part
                  :else                nil)))
        parts))

(defn pitch-class-filter-algo
  "A core.wall FACTORY -- (fn [allowed-pcs] -> wall-fn) -- wrapping
   pitch-class-filter as a per-voice playback algorithm:
     (register-algo! :pcFilter pitch-class-filter-algo nil :factory)
     (play :verse :algo [:pcFilter [0 2 4 5 7 9 11]])   ; C major only"
  [allowed-pcs]
  (fn [nodes _ctx-chain _voice] (pitch-class-filter nodes allowed-pcs)))

(defn interval-filter-algo
  "A core.wall FACTORY -- (fn [allowed-intervals] -> wall-fn) -- wrapping
   interval-filter as a per-voice playback algorithm:
     (register-algo! :intervalFilter interval-filter-algo nil :factory)
     (play :verse :algo [:intervalFilter [1 2]])   ; stepwise motion only"
  [allowed-intervals]
  (fn [nodes _ctx-chain _voice] (interval-filter nodes allowed-intervals)))

(defn probability-filter-algo
  "A core.wall FACTORY -- (fn [p] -> wall-fn) -- wrapping
   probability-filter as a per-voice playback algorithm:
     (register-algo! :probFilter probability-filter-algo nil :factory)
     (play :verse :algo [:probFilter 0.5])"
  [p]
  (fn [nodes _ctx-chain _voice] (probability-filter nodes p)))
