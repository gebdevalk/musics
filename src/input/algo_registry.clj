(ns input.algo-registry
  "The `@'[ name Arg... ]` (AtomicAlgo) registry -- name -> a pre-
   existing Clojure fn, by string. A peer of input.grammar-parser/
   input.lilypond-import, not a sub-concern of input.reader.flat-tree-
   walker: this is about interpreting an *input-language* construct
   (AtomicAlgo), but its own lifetime spans the whole session, not one
   parse call, so it doesn't belong inside the walker any more than
   grammar-parser or lilypond-import do (see CLAUDE.md's \"Other modules
   worth knowing about\" for that same reasoning applied to those two).
   input.reader.flat-tree-walker/walk-atomic-algo is the only reader --
   it looks a name up here and calls the registered fn, nothing more.

   Deliberately not a generic plugin system -- musics text can only
   point at an algorithm that already exists as real Clojure code, never
   define one itself; the text's job is just to name it and feed it
   arguments. Each Arg walk-atomic-algo hands the fn is either a Data
   literal ('[ ... ], walked into a plain seq of bare values) or a bare
   Primitive (Int/Float/Ratio, walked into a single scalar) -- written
   in whatever order the target fn's own parameter list expects, scalars
   and sequences freely mixed (a rhythm generator's pulse/step counts
   alongside a pitch cycle, say). The registered fn is called
   positionally with exactly that arg list and must return a seq of
   [pitch duration] pairs, event order, ready to become real Leaf
   children.

   A plain atom, not a hardcoded map -- same shape as core.conductor's
   action-registry (\"a parked toolbox\", register-action!/trigger!):
   register-algo!/unregister-algo! below let a user park their own fn
   under a new name directly from the REPL (or a required namespace's
   own code), with no walker/grammar change or recompile needed, so
   @'[ myAlgo ...] works the moment it's registered, same session.
   defonce so re-evaluating this namespace (a REPL reload) doesn't wipe
   out whatever's already been registered. Each entry is {:fn f :doc
   doc} rather than a bare fn -- doc is what (algos)/(algos name) show,
   since a Clojure arglist alone (`[color talea]`) can't say which
   params want a Data literal vs a bare Primitive, only the registerer
   knows that. Deliberately not persisted by write/load/reset (core.repo/
   musics.clj's session) -- it's runtime configuration for this process,
   not musical content or session state, same as action-registry isn't
   touched by those either."
  (:require [algo.common.isorhythm :as isorhythm]
            [clojure.string :as str]))

(defonce atomic-algo-registry
  (atom {"colorTalea"
         {:fn  isorhythm/color-talea
          :doc "[color talea] -- color: a Data pitch cycle, e.g. '[C4 D4 E4 F4 G4 A4 B4] (absolute pitches -- see CLAUDE.md's \"AtomicAlgo\" section for why). talea: a Data duration cycle, e.g. '[/4. /8 /16 /4]. Returns one full isorhythmic period (lcm of the two lengths); wrap the call in \\repeat unfold N { ... } to repeat it."}}))

(defn register-algo!
  "Park f under name (a string, matching AtomicAlgo's bare Name token),
   callable from musics text thereafter as @'[ name Arg... ]. f is
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
  "Forget name's parked algorithm -- @'[ name ...] fails with \"Unknown
   algo\" again thereafter."
  [name]
  (swap! atomic-algo-registry dissoc name)
  nil)

(defn algos
  "List registered algorithms (AtomicAlgo/@'[ ]).
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
