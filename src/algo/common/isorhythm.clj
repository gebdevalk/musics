(ns algo.common.isorhythm
  "Isorhythmic combinators -- cycling two (or more) independent sequences
   of unequal length together, medieval motet technique still used
   generatively today. Distinct from algo.common.reshape: reshape's own
   functions (invert/retrograde/arpeggiate/hocket) all operate on already-
   resolved domain material (parts/leaves produced by musics.clj/sq).
   color-talea below works one level earlier -- on bare pitch/duration
   values, before anything has been built into a Leaf at all -- so it
   lives in its own file rather than stretching reshape's documented
   scope.

   A 'color' is a repeating sequence of pitches with no rhythm of its
   own; a 'talea' is a repeating sequence of durations with no pitch of
   its own (musics.ebnf's BareDuration atom, '/4 /8 /8 /4' inside a Data
   container, is exactly this: a talea authored as pure data, independent
   of any color). The two cycle independently against each other, so the
   combined (pitch, duration) pairing only repeats once every
   lcm(count color, count talea) events -- one full isorhythmic period,
   e.g. a 7-pitch color against a 4-duration talea repeats every 28
   events, not 7 or 4."
  (:require [core.domain.flat-domain :as d]))

(defn- gcd
  [a b]
  (if (zero? b) a (recur b (mod a b))))

(defn- lcm
  [a b]
  (/ (* a b) (gcd a b)))

(defn color-talea
  "Combine a color (pitch sequence) and a talea (duration sequence) into
   the classic isorhythmic color-talea pairing: event i's pitch is
   (nth color (mod i (count color))), its duration is (nth talea (mod i
   (count talea))) -- the two cycle completely independently. Since the
   combined pairing only repeats once every full period --
   lcm(count color, count talea) events -- `periods` counts how many
   *full periods* to generate (not a raw event count), so
   (color-talea color talea 1) always covers exactly one complete
   isorhythmic cycle and (color-talea color talea n) is just n copies of
   it back to back. Returns a vector of [pitch duration] pairs, in
   event order, ready to be rendered into Leaf-shaped text/records by
   the caller (this fn never builds domain records itself -- it only
   computes the pairing)."
  ([color talea] (color-talea color talea 1))
  ([color talea periods]
   (let [color  (vec color)
         talea  (vec talea)
         cn     (count color)
         tn     (count talea)
         period (lcm cn tn)
         total  (* periods period)]
     (mapv (fn [i] [(nth color (mod i cn)) (nth talea (mod i tn))])
           (range total)))))

(defn color-talea-wall
  "A core.wall FACTORY -- (fn [color talea] -> wall-fn) -- that turns
   color-talea into a live GENERATOR instead of a transform: the wall
   fn it returns ignores the pitch/duration of whatever leaf/rest/drum
   placeholder nodes it's handed and substitutes the next step(s) of
   the isorhythmic color/talea cycle in their place instead, one output
   Leaf per placeholder, continuing across however many times this SAME
   resolved fn gets called. Any node that isn't a leaf/rest/drum (a Bar,
   an :assignment marker -- see core.async-engine's own tolerance for
   these) passes through untouched and never consumes a step.

   This is the 'engine feeds itself' side of the material-vs-performance
   question (see CLAUDE.md's 'Wall' section): pair the returned fn with
   a :count :infinite Iterator as the placeholder source
   (core.domain.flat-domain/iterator) and assign it to a voice, and that
   voice plays forever, structurally reading real (if musically inert)
   repo material the whole time -- so it stays addressable, and
   pause!/stop! govern it exactly like any other voice -- while its
   actually-sounding content is synthesized fresh every step and never
   itself committed anywhere. The placeholder's own pitch/duration never
   surfaces; only its role as a repeatable structural pulse (one tick =
   one output note, advancing the voice's clock the same as a real note
   would) does.

   register-wall! this under a name with :kind :factory, then either
   tag it inline ([name color talea] as a play/assign-algo! :algo
   argument) or install-once/configure-later via configure-wall! -- see
   core.wall's own docstring for both mechanisms. This fn only ever
   builds the factory side; registering/assigning it is the caller's
   own job, same as color-talea itself and algo.common.split/
   split-leaf-voice before it.

   color/talea can be plain Clojure literals ([60 64 67], [1/4 1/8]) OR
   real repo ids, via core.wall/configure-preset! -- its own
   resolve-config-form resolves a bare keyword against a committed '[ ]
   Data container straight to that container's own PLAIN values (a
   MIDI int per pitch, a Ratio per duration -- see
   flat_tree_walker.clj's own data-element-types checking, which
   guarantees a Data container never mixes kinds), no unwrapping of any
   kind needed on this fn's own side:
     '[ pitch C E G ]        ; committed as :myColor -> [60 64 67]
     '[ duration /4 /8 /8 /4 ] ; committed as :myTalea -> [1/4 1/8 1/8 1/4]
     (register-wall! :colorTalea color-talea-wall nil :factory)
     (configure-preset! :bright :colorTalea :myColor :myTalea)
     (play :verse :algo :bright)

   Each call to THIS factory mints its own counter atom, closed over by
   the wall fn it returns, so two voices independently resolving the
   same [name color talea] tag (two separate assign-algo! calls, two
   separate factory applications) each advance their own isorhythmic
   position, never interfering with each other's count -- the same
   'safe to share, no pooled state' property core.wall's own docstring
   already promises for an ordinary (non-generator) wall fn.

   Idempotent under core.wall's own documented double-call contract (a
   container's full sibling batch, then again per already-produced node
   singleton-wrapped -- see register-wall!'s own docstring): an output
   node already carrying ::step is passed straight through rather than
   drawn a second time, so the counter only ever advances once per
   genuinely new placeholder, not once per call."
  [color talea]
  (let [color (vec color)
        talea (vec talea)
        cn    (count color)
        tn    (count talea)
        step* (atom 0)]
    (fn [nodes _ctx-chain _voice]
      (map (fn [node]
             (cond
               (contains? node ::step) node
               (not (or (d/leaf? node) (d/rest? node) (d/drum? node))) node
               :else
               (let [i (dec (swap! step* inc))]
                 (-> (d/leaf (:id node) (:context node)
                             (nth talea (mod i tn))
                             [(nth color (mod i cn))])
                     (assoc ::step i)))))
           nodes))))
