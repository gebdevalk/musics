(ns musics.lang.vocab.algo-common
  "musics.lang's own `algo-common` vocabulary -- a mechanical bridge of
   algo/common/*.clj's own public API (farey/gate/isorhythm/pitch/
   pulse/reshape/split/transient-ops/zfilter), the small, reusable
   building blocks the rest of algo/ shares, plus a handful of genuine
   musics.lang-NATIVE words (see native-bootstrap-source below) for
   algo.common.scaling/rotate/numeric/trig -- trivial, stateless
   one-liners rewritten directly as musics.lang `::` definitions
   instead of bridged, verified against their Clojure originals'
   own docstring examples.

   Two bridged names were renamed to avoid colliding with an
   already-bridged `musics` vocabulary word of the same name but a
   genuinely DIFFERENT meaning (both real, both worth keeping,
   distinguished by name the same way the two Clojure fns are already
   distinguished by namespace -- see musics.core/times's own docstring,
   'despite the shared name'): algo.common.transient-ops/times ->
   `scale-duration` (duration multiplied by factor -- unrelated to
   musics/times, which repeats material N times), algo.common.
   reshape/invert -> `invert-around` (the whole sequence folds around
   ONE shared axis/mean, vs musics/invert's own per-part mean).
   algo.common.transient-ops/transpose is skipped entirely, not
   renamed -- it's the exact same operation as musics/transpose
   (both map core.domain.flat-domain/transpose across material), so
   bridging both would just be two names for one implementation.
   algo.common.numeric/gcd is also skipped -- musics.lang's own
   kernel-vocab already has a `gcd` word."
  (:require [algo.common.farey :as farey]
            [algo.common.gate :as gate]
            [algo.common.isorhythm :as isorhythm]
            [algo.common.pitch :as pitch]
            [algo.common.pulse :as pulse]
            [algo.common.reshape :as reshape]
            [algo.common.split :as split]
            [algo.common.transient-ops :as top]
            [algo.common.zfilter :as zfilter]
            [musics.lang.runtime :refer [push! pop-val! builtin callable->fn]]))

;; ---------------------------------------------------------------------
;; Native musics.lang words -- algo.common.scaling/rotate/numeric/trig,
;; each a small enough, stateless enough one-liner to write directly as
;; real musics.lang source instead of a Clojure bridge, and cheap to
;; verify against the ported fn's own docstring examples (see
;; test/musics_lang_test.clj's own golden-value checks). Run once
;; against a freshly-built ctx by musics.lang/make-ctx, right after the
;; base vocabularies are wired in -- see that fn's own comment.
;;
;; wave-theta is a new helper these six trig words share (not itself
;; ported from anywhere -- (2*pi*idx)/period is common to all six of
;; algo.common.trig's own formulas, just factored out here since a
;; musics.lang word can reuse an earlier one the same way a Clojure fn
;; would). rotate needs head/tail/concat (algo.common.rotate's own
;; take/drop-and-splice, spelled with real Factor's own sequence-word
;; names precisely so `tail` never collides with the STACK shuffler
;; `drop` already in kernel-vocab) and min/max/pi/sin/cos/tan/asin/
;; sign/>float (musics.lang/kernel-vocab, added alongside this work).
;; ---------------------------------------------------------------------

(def native-bootstrap-source
  "IN: algo-common

   :: clamp ( lo hi v -- v' )
     v hi min lo max ;

   :: closest-to ( n low hi -- v )
     n low - hi n - < low hi ? ;

   :: round-to ( n div -- v )
     n div rem :> r
     n r - :> lo
     lo div + :> hi
     n lo hi closest-to ;

   :: scale-range ( x inmin inmax outmin outmax -- y )
     outmax outmin - x inmin - * inmax inmin - / outmin + ;

   :: clamp-optional ( clip-lo clip-hi v -- v' )
     clip-lo
     ( clip-hi
       ( v clip-lo max clip-hi min )
       ( v clip-lo max )
       if )
     ( clip-hi
       ( v clip-hi min )
       ( v )
       if )
     if ;

   :: lcm ( a b -- c )
     a b * a b gcd / ;

   :: lcm-multiple ( ns -- c )
     ns 1 \\ lcm reduce ;

   :: rotate ( pattern i -- pattern' )
     pattern count :> n
     i n mod :> i2
     pattern i2 tail
     pattern i2 head
     concat ;

   :: wave-theta ( idx period -- theta )
     2 pi * idx * period / ;

   :: cosr ( idx amp base period -- y )
     idx period wave-theta cos amp * base + ;

   :: sinr ( idx amp base period -- y )
     idx period wave-theta sin amp * base + ;

   :: tanr ( idx amp base period -- y )
     idx period wave-theta tan amp * base + ;

   :: trianglr ( idx amp base period -- y )
     idx period wave-theta sin asin 2 pi / * amp * base + ;

   :: squarr ( idx amp base period -- y )
     idx period wave-theta sin sign amp * base + ;

   :: sawr ( idx amp base period -- y )
     idx >float period / :> ft
     ft 0.5 + floor
     ft swap -
     2 amp * *
     base + ;
   ")

(defn vocab []
  (merge
    (builtin "farey" (fn [ctx] (let [n (pop-val! ctx) x (pop-val! ctx)] (push! ctx (farey/farey x n))))
             "( x N -- approx )" "best rational approximation of x with denominator at most N")

    (builtin "gate" (fn [ctx] (let [parts (pop-val! ctx) on-reject (pop-val! ctx) select-fn (callable->fn ctx (pop-val! ctx))]
                                  (push! ctx (gate/gate select-fn on-reject parts))))
             "( select-fn on-reject parts -- parts' )" "gates parts by a predicate, replacing/dropping rejects per on-reject")
    (builtin "gate-algo" (fn [ctx] (let [params (pop-val! ctx) name (pop-val! ctx)] (push! ctx (gate/gate-algo name params))))
             "( name params -- name )" "a core.wall factory wrapping gate (params: :criterion :on-reject)")
    (builtin "lo-criterion" (fn [ctx] (push! ctx (gate/lo-criterion (pop-val! ctx)))) "( cutoff -- criterion )" "keeps only pitches at or below cutoff")
    (builtin "hi-criterion" (fn [ctx] (push! ctx (gate/hi-criterion (pop-val! ctx)))) "( cutoff -- criterion )" "keeps only pitches at or above cutoff")
    (builtin "window-criterion" (fn [ctx] (let [hi (pop-val! ctx) lo (pop-val! ctx)] (push! ctx (gate/window-criterion lo hi))))
             "( lo hi -- criterion )" "keeps only pitches within [lo hi] inclusive")
    (builtin "pitch-class-criterion" (fn [ctx] (push! ctx (gate/pitch-class-criterion (pop-val! ctx)))) "( allowed-pcs -- criterion )" "keeps only pitches whose pitch class (mod 12) is allowed")
    (builtin "interval-criterion" (fn [ctx] (push! ctx (gate/interval-criterion (pop-val! ctx)))) "( allowed-intervals -- criterion )" "keeps a note only if its melodic interval from the raw previous note is allowed")
    (builtin "probability-criterion" (fn [ctx] (push! ctx (gate/probability-criterion (pop-val! ctx)))) "( p -- criterion )" "keeps each note with probability p")

    (builtin "color-talea" (fn [ctx] (let [periods (pop-val! ctx) talea (pop-val! ctx) color (pop-val! ctx)]
                                         (push! ctx (isorhythm/color-talea color talea periods))))
             "( color talea periods -- events )" "the classic isorhythmic pairing, color and talea cycling independently")
    (builtin "color-talea-algo" (fn [ctx] (let [params (pop-val! ctx) name (pop-val! ctx)] (push! ctx (isorhythm/color-talea-algo name params))))
             "( name params -- name )" "a core.wall factory wrapping color-talea (params: :color :talea)")
    (builtin "zip-parts" (fn [ctx] (let [periods (pop-val! ctx) streams (pop-val! ctx)] (push! ctx (isorhythm/zip-parts streams periods))))
             "( streams periods -- events )" "generalizes color-talea past a fixed pitch+duration pair to any number of independently-cycling streams")

    (builtin "build-scale" (fn [ctx] (let [intervals (pop-val! ctx) root (pop-val! ctx)] (push! ctx (pitch/build-scale root intervals))))
             "( root intervals -- scale )" "root + cumulative semitone offsets -> a 0-11 pitch-class scale")
    (builtin "from-key" (fn [ctx] (let [scale-kw (pop-val! ctx) key-kw (pop-val! ctx)] (push! ctx (pitch/from-key key-kw scale-kw))))
             "( key-kw scale-kw -- scale )" "builds a 0-11 pitch-class scale from a named key+scale")
    (builtin "from-key-spec" (fn [ctx] (push! ctx (pitch/from-key-spec (pop-val! ctx)))) "( spec -- scale )" "same as from-key, from a single \"F#.major\"-style string")
    (builtin "resolve-scale" (fn [ctx] (push! ctx (pitch/resolve-scale (pop-val! ctx)))) "( scale-spec -- scale )" "normalizes a scale spec into a plain 0-11 pitch-class vector")

    (builtin "grid->pulses" (fn [ctx] (push! ctx (pulse/grid->pulses (pop-val! ctx)))) "( grid -- pulses )" "collapses a 0/1 (or weighted) grid into Pulse duration/value cells")

    (builtin "arpeggiate" (fn [ctx] (let [order-fn (callable->fn ctx (pop-val! ctx)) leaf (pop-val! ctx)]
                                        (push! ctx (reshape/arpeggiate leaf order-fn))))
             "( leaf order-fn -- parts )" "splits a chord leaf's simultaneous pitches into a sequence, ordered by order-fn")
    (builtin "chain-algo" (fn [ctx] (let [params (pop-val! ctx) name (pop-val! ctx)] (push! ctx (reshape/chain-algo name params))))
             "( name params -- name )" "a core.wall factory chaining several named wall steps (params: :steps)")
    (builtin "hocket" (fn [ctx] (push! ctx (apply reshape/hocket (pop-val! ctx)))) "( parts-seqs -- interleaved )" "interleaves two or more part-sequences, alternating single elements from each")
    (builtin "invert-around" (fn [ctx] (let [parts (pop-val! ctx) axis (pop-val! ctx)] (push! ctx (reshape/invert axis parts))))
             "( axis parts -- parts' )" "mirrors every part around ONE shared axis pitch (renamed from reshape's own `invert` -- see this ns's own header comment)")
    (builtin "retrograde" (fn [ctx] (push! ctx (reshape/retrograde (pop-val! ctx)))) "( parts -- parts' )" "reverses a sequence of parts")
    (builtin "weighted-shuffle" (fn [ctx] (let [dist-fn (callable->fn ctx (pop-val! ctx)) parts (pop-val! ctx)]
                                              (push! ctx (reshape/weighted-shuffle parts dist-fn))))
             "( parts dist-fn -- parts' )" "shuffles parts by repeatedly drawing the next output element via dist-fn")
    (builtin "weighted-shuffle-algo" (fn [ctx] (let [params (pop-val! ctx) name (pop-val! ctx)] (push! ctx (reshape/weighted-shuffle-algo name params))))
             "( name params -- name )" "a core.wall factory wrapping weighted-shuffle (params: :distribution)")

    (builtin "split" (fn [ctx] (let [n (pop-val! ctx) melody (pop-val! ctx)] (push! ctx (split/split melody n))))
             "( melody n -- voices )" "splits a melody into n faster, octave-shifted voices")
    (builtin "split-leafs" (fn [ctx] (let [n (pop-val! ctx) leafs (pop-val! ctx)] (push! ctx (split/split-leafs leafs n))))
             "( leafs n -- voices )" "split, from real Leaf/Rest/Drum records instead of [pitch duration] pairs")
    (builtin "split-leaf-voice" (fn [ctx] (let [leafs (pop-val! ctx) voice-index (pop-val! ctx) n (pop-val! ctx)]
                                              (push! ctx (split/split-leaf-voice n voice-index leafs))))
             "( n voice-index leafs -- voice )" "one layer's worth of a split-leafs canon, by voice index")

    (builtin "scale-duration" (fn [ctx] (let [material (pop-val! ctx) factor (pop-val! ctx)] (push! ctx (vec (top/times factor material)))))
             "( factor material -- material' )" "material with duration multiplied by factor (renamed from transient-ops' own `times` -- see this ns's own header comment)")
    (builtin "tuplet" (fn [ctx] (let [material (pop-val! ctx) factor (pop-val! ctx)] (push! ctx (vec (top/tuplet factor material)))))
             "( factor material -- material' )" "material with duration divided by factor")

    (builtin "z-filter" (fn [ctx] (let [xs (pop-val! ctx) a (pop-val! ctx) b (pop-val! ctx)] (push! ctx (vec (zfilter/z-filter b a xs)))))
             "( b a xs -- ys )" "the generic feedforward/feedback recurrence filter")
    (builtin "smooth" (fn [ctx] (let [xs (pop-val! ctx) alpha (pop-val! ctx)] (push! ctx (vec (zfilter/smooth alpha xs)))))
             "( alpha xs -- ys )" "one-pole smoothing: y[n] = (1-alpha)*x[n] + alpha*y[n-1]")
    (builtin "momentum" (fn [ctx] (let [xs (pop-val! ctx) beta (pop-val! ctx)] (push! ctx (vec (zfilter/momentum beta xs)))))
             "( beta xs -- ys )" "momentum/inertia: y[n] = x[n] + beta*(y[n-1] - x[n-1])")
    (builtin "memory" (fn [ctx] (let [xs (pop-val! ctx) decay (pop-val! ctx)] (push! ctx (vec (zfilter/memory decay xs)))))
             "( decay xs -- ys )" "decay/memory: y[n] = x[n] + decay*y[n-1]")
    (builtin "smooth-intervals" (fn [ctx] (let [xs (pop-val! ctx) alpha (pop-val! ctx)] (push! ctx (vec (zfilter/smooth-intervals alpha xs)))))
             "( alpha xs -- ys )" "smooths the intervals between consecutive values, not the absolute values")
    (builtin "interval-gain" (fn [ctx] (let [xs (pop-val! ctx) factor (pop-val! ctx)] (push! ctx (vec (zfilter/interval-gain factor xs)))))
             "( factor xs -- ys )" "multiplies every interval between consecutive values by factor")
    (builtin "pc-smooth" (fn [ctx] (let [xs (pop-val! ctx) alpha (pop-val! ctx)] (push! ctx (vec (zfilter/pc-smooth alpha xs)))))
             "( alpha xs -- ys )" "smooths pitch classes (mod 12) rather than absolute pitch")
    (builtin "smooth-pitch-algo" (fn [ctx] (let [params (pop-val! ctx) name (pop-val! ctx)] (push! ctx (zfilter/smooth-pitch-algo name params))))
             "( name params -- name )" "a core.wall factory wrapping smooth (params: :alpha)")))
