(ns input.algo-registry
  "Two name -> pre-existing-Clojure-fn registries, by string: one for
   `@[ name Arg... ]` (AtomicAlgo), one for `@{ name Primitive... Element...
   }` (ElementAlgo). A peer of input.grammar-parser/input.lilypond-import,
   not a sub-concern of input.reader.flat-tree-walker: this is about
   interpreting two *input-language* constructs, but their lifetime spans
   the whole session, not one parse call, so neither belongs inside the
   walker any more than grammar-parser or lilypond-import do (see
   CLAUDE.md's \"Other modules worth knowing about\" for that same
   reasoning applied to those two). input.reader.flat-tree-walker/
   walk-atomic-algo and walk-element-algo are the only readers -- each
   looks a name up in its own registry and calls the fn, nothing more.

   Deliberately not a generic plugin system -- musics text can only point
   at an algorithm that already exists as real Clojure code, never define
   one itself; the text's job is just to name it and feed it arguments.

   AtomicAlgo's Args are Data literals ([ ... ], walked into a plain seq
   of bare values) or bare Primitives (Int/Float/Ratio, walked into a
   single scalar), in whatever order the target fn's own parameter list
   expects; the registered fn must return a seq of [pitch duration]
   pairs, event order, ready to become real Leaf children -- one level
   before anything has been built into a Leaf at all.

   ElementAlgo's own args are leading bare Primitives (a split-count or
   voice-index, say), THEN the body's real Elements -- Leaf/Rest/Drum
   records (or container ids, for a nested Composite), already fully
   walked, exactly the shape a Sequence's own :children would hold.
   ElementAlgo's own musics.ebnf comment covers why only Primitive (not
   the fuller Data-including AlgoArg AtomicAlgo allows) is legal there.
   The registered fn is called positionally -- scalars first, the walked
   Element seq last -- and must return a seq of Leaf/Rest/Drum-shaped
   records, ready to splice in as-is.

   Both are plain atoms, not hardcoded maps -- same shape as
   core.conductor's action-registry (\"a parked toolbox\",
   register-action!/trigger!): register-algo!/register-element-algo!
   (and their unregister- counterparts) below let a user park their own
   fn under a new name directly from the REPL (or a required namespace's
   own code), with no walker/grammar change or recompile needed, so
   @[ myAlgo ...]/@{ myAlgo ...} work the moment they're registered, same
   session. defonce so re-evaluating this namespace (a REPL reload)
   doesn't wipe out whatever's already been registered. Each entry is
   {:fn f :doc doc} rather than a bare fn -- doc is what (algos)/
   (element-algos) show, since a Clojure arglist alone (`[color talea]`)
   can't say which params want a Data literal vs a bare Primitive, only
   the registerer knows that. Deliberately not persisted by write/load/
   reset (core.repo/musics.clj's session) -- it's runtime configuration
   for this process, not musical content or session state, same as
   action-registry isn't touched by those either."
  (:require [algo.common.isorhythm :as isorhythm]
            [algo.common.split :as split]
            [clojure.string :as str]))

(defonce atomic-algo-registry
  (atom {"colorTalea"
         {:fn  isorhythm/color-talea
          :doc "[color talea] -- color: a Data pitch cycle, e.g. [C4 D4 E4 F4 G4 A4 B4] (absolute pitches -- see CLAUDE.md's \"AtomicAlgo\" section for why). talea: a Data duration cycle, e.g. [/4. /8 /16 /4]. Returns one full isorhythmic period (lcm of the two lengths); wrap the call in \\repeat unfold N { ... } to repeat it."}}))

(defonce element-algo-registry
  (atom {"split"
         {:fn  split/split-leaf-voice
          :doc "n voiceIndex? Element... -- n: how many times to split a new voice off the current highest one -- each split-off is an octave up, twice as fast, and repeated twice (see algo.common.split's own namespace docstring). voiceIndex (optional Int, 0..n): which layer to return -- 0 the untouched original, n (the default, if omitted) the final/highest split-off. Element...: the original low/slow melody, real Leaf/Rest/Drum content, e.g. c4 d4 e2. Place one @{ split n voiceIndex ... } call per voiceIndex 0..n in its own << >> branch to play the whole texture together."}}))

(defn register-algo!
  "Park f under name (a string, matching AtomicAlgo's bare Name token),
   callable from musics text thereafter as @[ name Arg... ]. f is
   called positionally with exactly the args written in the text -- each
   Data literal walked into a plain seq of bare values (pitches as MIDI
   ints, durations as rationals), each bare Primitive into a single
   scalar -- and must return a seq of [pitch duration] pairs. doc (a
   plain string, no docstring metadata magic) is shown by (algos)/
   (algos name) -- since it describes which of f's own params expect a
   Data literal vs a bare scalar, something no Clojure arglist alone
   can say, this is worth writing even though it's optional."
  ([name f] (register-algo! name f nil))
  ([name f doc]
   (swap! atomic-algo-registry assoc name {:fn f :doc doc})
   nil))

(defn unregister-algo!
  "Forget name's parked algorithm -- @[ name ...] fails with \"Unknown
   algo\" again thereafter."
  [name]
  (swap! atomic-algo-registry dissoc name)
  nil)

(defn algos
  "List registered algorithms (AtomicAlgo/@[ ]).
   (algos)          -- every registered name with its doc's first line
   (algos \"name\")   -- name's full doc"
  ([]
   (doseq [[n {:keys [doc]}] (sort-by first @atomic-algo-registry)]
     (println (format "  %-15s  %s" n (if doc (first (str/split-lines doc)) "(no doc)"))))
   nil)
  ([name]
   (if-let [{:keys [doc]} (get @atomic-algo-registry name)]
     (println (or doc "(no doc)"))
     (println "Unknown algo:" name))))

(defn register-element-algo!
  "Park f under name (a string, matching ElementAlgo's bare Name token),
   callable from musics text thereafter as @{ name Primitive... Element...
   }. f is called positionally -- each leading bare Primitive (Int/
   Float/Ratio) as a single scalar, then the walked seq of real
   Leaf/Rest/Drum content (or container ids, for a nested Composite) as
   f's own final arg -- and must return a seq of Leaf/Rest/Drum-shaped
   records, ready to splice in as real content. doc (a plain string,
   optional) is shown by (element-algos)/(element-algos name)."
  ([name f] (register-element-algo! name f nil))
  ([name f doc]
   (swap! element-algo-registry assoc name {:fn f :doc doc})
   nil))

(defn unregister-element-algo!
  "Forget name's parked ElementAlgo -- @{ name ...} fails with \"Unknown
   element algo\" again thereafter."
  [name]
  (swap! element-algo-registry dissoc name)
  nil)

(defn element-algos
  "List registered ElementAlgos (@{ }).
   (element-algos)          -- every registered name with its doc's first line
   (element-algos \"name\")   -- name's full doc"
  ([]
   (doseq [[n {:keys [doc]}] (sort-by first @element-algo-registry)]
     (println (format "  %-15s  %s" n (if doc (first (str/split-lines doc)) "(no doc)"))))
   nil)
  ([name]
   (if-let [{:keys [doc]} (get @element-algo-registry name)]
     (println (or doc "(no doc)"))
     (println "Unknown element algo:" name))))
