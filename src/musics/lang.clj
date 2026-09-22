(ns musics.lang
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [musics.lang.runtime :refer [push! pop! builtin
                                          execute-entry run-callable quot-steps
                                          ->Quotation ->Wordref]]
            [musics.lang.vocab.musics :as musics-vocab]
            [musics.lang.vocab.parse :as parse-vocab]
            [musics.lang.vocab.algorithms :as algorithms-vocab]
            [musics.lang.vocab.algo-common :as algo-common-vocab]
            [musics.lang.vocab.algo-indisp :as algo-indisp-vocab]
            [musics.lang.vocab.algo-melodic :as algo-melodic-vocab]
            [musics.lang.vocab.algo-metric :as algo-metric-vocab]
            [musics.lang.vocab.algo-random :as algo-random-vocab]
            [musics.lang.vocab.algo-rhythmic :as algo-rhythmic-vocab]
            [musics.lang.vocab.algo-algoline :as algo-algoline-vocab]
            [musics.lang.vocab.algo-toolkit :as algo-toolkit-vocab])
  (:import (musics.lang.runtime Quotation Wordref))
  (:gen-class))

;; =====================================================================
;; musics-lang: a small Factor, hosted in Clojure.
;;
;; Replaces input.forth's language core (classic Forth: IF/ELSE/THEN,
;; DO/LOOP, BEGIN/UNTIL, gforth-style { a b c } locals, a compiled
;; branch-offset VM) with real Factor's own model instead: quotations as
;; first-class stack values, ordinary combinator WORDS for control flow
;; (if/when/unless/each/map/filter/reduce/bi/tri/... -- no compiled
;; branch ops at all), a vocabulary system (IN:/USE:/USING:, words land
;; wherever IN: currently points, redefining one is invisible to already-
;; compiled callers -- see "Early binding" below), and real Factor's own
;; ( vars... -- outputs... ) stack-effect/locals convention (:: NAME
;; ( ... ) ... ; binds the input names as real lexical locals; plain :
;; leaves them as documentation only -- verified directly against a
;; local real-Factor source checkout, core/locals/locals-docs.factor,
;; not guessed).
;;
;; ONE deliberate departure from both real Factor and this project's own
;; resources/mforth.lua prototype (a ~3400-line Factor kernel in Lua,
;; ported from piece by piece below): quotations are spelled with
;; Clojure's own list syntax, ( ... ), not Factor's [ ... ] -- "the list
;; syntax is interpreted as a factor quotation, a nameless function," an
;; explicit design choice, not an oversight. This makes ( ... ) mode-
;; sensitive in a way neither reference needs: right after a word's own
;; name (: NAME or :: NAME) it's read as a stack-effect declaration,
;; never compiled into the body; everywhere else it compiles to pushing
;; a quotation value. See compile-forms/read-stack-effect below.
;;
;; Case-sensitive, lowercase words throughout (dup, swap, if, each, ...;
;; IN:/USE:/USING:/TUPLE:-style uppercase-with-colon reserved for
;; parsing words, real Factor's own convention) -- NOT input.forth's own
;; upper-cased-at-tokenize-time case-insensitivity, which is a classic-
;; Forth convention this kernel deliberately drops.
;;
;; Early binding: compiling a word/quotation body resolves each name it
;; calls to its CURRENT dictionary entry ONCE, at compile time, and
;; bakes that entry directly into the compiled step -- matching
;; resources/mforth.lua's own model and the user's own explicit
;; requirement ("changes in word definitions does not change previous
;; behavior"). This is the OPPOSITE of input.forth's own dictionary,
;; which is deliberately late-bound (a :call op looks its name up fresh
;; on every execution) -- a real, deliberate semantic change from that
;; file, not a port artifact. One real consequence: naive self-
;; recursion (a : word calling its own bare name inside its own body)
;; no longer "just works" the way input.forth's late binding allowed --
;; the name isn't in any vocabulary yet while its own body is still
;; being compiled, so compiling it fails with "unknown word during
;; compile," same limitation real Factor itself has (which solves
;; recursion differently -- explicit combinators, not implicit self-
;; name-calling -- out of scope for this first pass).
;;
;; Numbers: Clojure's own numeric tower (real exact ratios, arbitrary-
;; precision integers) already covers everything resources/mforth.lua's
;; own hand-rolled interned-rational type exists to provide in Lua --
;; nothing to port, `+`/`-`/`*`/`/` and the reader's own numeric syntax
;; are used directly.
;;
;; Booleans: Factor's own model ("a single f singleton is false,
;; everything else is true") is already exactly Clojure's own nil/false-
;; vs-everything-else truthiness -- no T/F sentinel object needed, `if`/
;; `when`/etc. use Clojure's native truthy semantics directly, and
;; comparison words push real true/false, not a -1/0 flag convention.
;;
;; Scope of this pass ("core kernel first," an explicit, approved plan):
;; quotations, the combinator model, vocabularies, ::/:>/locals, the
;; #: ... ; musics-text bridge (ported near-verbatim from mforth.lua,
;; wired for real this time -- Lua can't reach musics.core, Clojure can),
;; and the full musics.core word bridge (mechanical translation of
;; input.forth's own musics-prims, lowercased, moved into its own
;; "musics" vocabulary instead of a shared flat dictionary -- with two
;; further concerns split into their own sibling vocabularies still:
;; text-to-repo staging (parse/parse-notation/s!/try-parse/parse-file/
;; >ids) into "parse", and core.wall's own per-voice-algorithm words
;; (register-factory!/build!/build-algo!/algos/assign-algo!/...) into
;; "algorithms" -- so neither crowds the same namespace as play/repo-
;; navigation words, or each other. "musics", "parse", and "algorithms"
;; are all USE:'d by "scratchpad" by default, so nothing in any of them
;; became harder to reach). Each of the three lives in its own file now
;; too (musics.lang.vocab.musics/parse/algorithms, this ns's own
;; `require`s at the top), not just its own vocabulary map in this one
;; -- the shared mechanisms a vocab file needs back FROM musics.lang
;; (the stack, quotations/word-refs as values, running already-compiled
;; code, and the builtin/->kw/callable->fn helpers) live in
;; musics.lang.runtime instead, specifically so requiring those vocab
;; namespaces from here (to wire their words into make-ctx) doesn't
;; create a circular require -- musics.lang.runtime depends on nothing
;; in this project at all. Explicitly
;; NOT ported yet (deferred to whenever something actually needs one,
;; straight from resources/mforth.lua at that point): the exact-rational
;; tower (unneeded, see above), generic word dispatch (PREDICATE:/M:/
;; GENERIC:), sets, TUPLE:/ERROR:, and the math.combinatorics/
;; math.statistics libraries. input.forth/forth.clj -- the classic-
;; Forth kernel this one was meant to eventually replace, once it
;; reached parity and the user confirmed the cutover -- has now been
;; removed entirely (2026-09-22, see doc/decisions.md): this kernel is
;; the sole hosted-DSL REPL language now, no third entry point left
;; alongside it.
;; =====================================================================

;; ---------------------------------------------------------------------
;; Vocabularies -- IN:/USE:/USING:/FROM:/EXCLUDE:/RENAME:/QUALIFIED:/
;; QUALIFIED-WITH:/FORGET:, word lookup, definition. Exact syntax and
;; precedence verified against a local real-Factor source checkout
;; (core/syntax/syntax-docs.factor's own HELP: entries) rather than
;; assumed -- FROM:/EXCLUDE:/RENAME: use "vocab => words... ;", RENAME:
;; is "word vocab => new-name" (no trailing ; -- fixed 4-token form),
;; QUALIFIED:/QUALIFIED-WITH: are single-line too ("vocab" / "vocab
;; prefix"), and FROM:/RENAME: explicitly take precedence over a plain
;; USE:/USING: on a name collision (confirmed, not assumed -- the docs'
;; own worked example: FROM: binary-search => search ; picks that
;; vocab's own search over one also reachable via a plain USING:).
;;
;; ctx's own:
;;   :vocabularies      {vocab-name -> {word-name -> entry}}
;;   :vocab-uses        {vocab-name -> #{used-vocab-name}}       (USE:/USING:)
;;   :vocab-imports     {vocab-name -> {local-name -> [source-vocab source-name]}}
;;                      (FROM:/RENAME: -- named, one-at-a-time imports)
;;   :vocab-exclusions  {vocab-name -> {used-vocab-name -> #{excluded-name}}}
;;                      (EXCLUDE: -- a normal USE:, minus specific names)
;;   :vocab-qualifiers  {vocab-name -> {prefix -> source-vocab}}
;;                      (QUALIFIED:/QUALIFIED-WITH: -- prefix:word access)
;;   :current-vocab     (atom of the vocab name new definitions land in)
;;
;; Lookup order: an explicit prefix:word (a registered QUALIFIED:/
;; QUALIFIED-WITH: prefix) resolves directly and unambiguously, checked
;; first since it's the most specific form and can't collide with
;; anything else by construction; otherwise, current vocab's own words,
;; then :vocab-imports (FROM:/RENAME:), then :vocab-uses (see
;; use-vocab-lookup below), then "kernel" last, always implicitly in
;; scope, exactly like real Factor's own kernel vocabulary.
;;
;; :vocab-uses is walked TRANSITIVELY, not just one hop -- a vocab your
;; current vocab USEs, USEs in turn, is visible too, any number of
;; levels deep, cycle-safely (see use-vocab-lookup's own docstring).
;; This is what makes a vocab usable as a real CONTAINER for a whole
;; sub-tree of others: `algorithms` (make-ctx, below) USEs all 8
;; algo-* vocabs, so anything that USEs `algorithms` -- `scratchpad`,
;; or any other vocab -- sees every one of them too, with nothing to
;; wire up per sub-vocab. The edges are declared top-down (a container
;; USEs its own children), but word VISIBILITY resolves bottom-up along
;; those same edges -- a word only exists where it's actually defined,
;; and every vocab above that in the USE: chain just inherits
;; visibility into it, the same relationship an `import`/`:require`
;; graph has anywhere else. Ambiguity among several plain USE:'d
;; vocabularies at the same hop (not FROM:'d/RENAME:'d/qualified, which
;; are already unambiguous by construction -- each names its own single
;; source) resolves "first one this walk visits wins," the same
;; simplification resources/mforth.lua's own `lookup` already makes
;; over real Factor's own ambiguity-error behavior.

(defn- qualified-lookup [ctx name]
  (when-let [idx (str/index-of name ":")]
    (let [prefix (subs name 0 idx) word (subs name (inc idx))]
      (when-let [source-vocab (get (get @(:vocab-qualifiers ctx) @(:current-vocab ctx)) prefix)]
        (get (get @(:vocabularies ctx) source-vocab) word)))))

(defn- use-vocab-lookup
  "Walks the USE:/USING: graph reachable from start-vocab, TRANSITIVELY
   (a vocab USEd by a vocab you USE is visible too, any number of hops
   deep) and cycle-safely (never re-descends into a vocab already
   visited in this one walk, so a mutual USE: between two vocabs -- or
   any longer cycle -- resolves instead of looping forever; this is
   what makes a vocab genuinely usable as a CONTAINER for a whole
   sub-tree of other vocabs: USE: it once, transitively see everything
   it itself USEs, with nothing further to wire up by hand). Depth-
   first via a plain vector-as-stack (peek/pop off the end) -- order
   only matters for which vocab wins an ambiguous name, already a
   'whatever this walk visits first' guarantee before this change too
   (see this ns's own Vocabularies header comment).

   Each hop's own EXCLUDE: still applies to just that ONE edge -- the
   vocab that declared the exclusion narrows only ITS OWN view of the
   vocab it's about to descend into, not the whole subtree beyond it,
   same scope EXCLUDE: already had before transitivity existed at all:
   excluding foo when reaching B doesn't stop C (USEd by B) from still
   surfacing its own foo, if C has one and nothing excluded C's foo
   specifically."
  [ctx name start-vocab]
  (let [vocabs @(:vocabularies ctx)
        all-uses @(:vocab-uses ctx)
        all-exclusions @(:vocab-exclusions ctx)]
    (loop [queue (mapv (fn [v] [start-vocab v]) (get all-uses start-vocab))
           visited #{start-vocab}]
      (when-let [[parent vocab-name] (peek queue)]
        (let [queue (pop queue)]
          (if (contains? visited vocab-name)
            (recur queue visited)
            (let [visited (conj visited vocab-name)
                  excluded? (contains? (get-in all-exclusions [parent vocab-name]) name)
                  found (when-not excluded? (get (get vocabs vocab-name) name))]
              (or found
                  (recur (into queue (mapv (fn [v] [vocab-name v]) (get all-uses vocab-name)))
                         visited)))))))))

(defn lookup-word [ctx name]
  (let [vocabs @(:vocabularies ctx)
        cur-name @(:current-vocab ctx)
        cur (get vocabs cur-name)
        imports (get @(:vocab-imports ctx) cur-name)]
    (or (qualified-lookup ctx name)
        (get cur name)
        (when-let [[src-vocab src-name] (get imports name)]
          (get (get vocabs src-vocab) src-name))
        (use-vocab-lookup ctx name cur-name)
        (get (get vocabs "kernel") name))))

(defn define-word! [ctx name entry]
  (swap! (:vocabularies ctx) update @(:current-vocab ctx) assoc name entry))

(defn forget-word!
  "Removes name from the CURRENT vocab's own map only -- real Factor's
   own FORGET: searches for whichever vocabulary a word actually lives
   in; this kernel's own deliberate simplification only reaches the
   common case (forgetting something defined in the vocab you're
   currently in), not a global search across every vocabulary. Existing
   already-compiled callers keep working regardless -- early binding
   already captured the entry directly, matching real Factor's own
   documented behavior ('existing definitions... will continue to
   work')."
  [ctx name]
  (swap! (:vocabularies ctx) update @(:current-vocab ctx) dissoc name))

(defn ensure-vocab! [ctx name]
  (swap! (:vocabularies ctx) update name #(or % {}))
  (swap! (:vocab-uses ctx) update name #(or % #{})))

(defn use-vocab! [ctx name]
  (ensure-vocab! ctx @(:current-vocab ctx))
  (swap! (:vocab-uses ctx) update @(:current-vocab ctx) (fnil conj #{}) name))

(defn import-word!
  "FROM:/RENAME:'s own shared mechanism -- makes source-name (from
   source-vocab) reachable under local-name in the CURRENT vocab."
  [ctx local-name source-vocab source-name]
  (swap! (:vocab-imports ctx) assoc-in [@(:current-vocab ctx) local-name] [source-vocab source-name]))

(defn exclude-word!
  "EXCLUDE:'s own mechanism -- source-vocab is used normally (see
   use-vocab!), but excluded-name is skipped when resolving a name
   through THAT particular used vocabulary specifically."
  [ctx source-vocab excluded-name]
  (swap! (:vocab-exclusions ctx) update-in [@(:current-vocab ctx) source-vocab] (fnil conj #{}) excluded-name))

(defn qualify-vocab!
  "QUALIFIED:/QUALIFIED-WITH:'s own shared mechanism -- source-vocab's
   words become reachable as prefix:word from the current vocab."
  [ctx prefix source-vocab]
  (ensure-vocab! ctx source-vocab)
  (swap! (:vocab-qualifiers ctx) assoc-in [@(:current-vocab ctx) prefix] source-vocab))

;; ---------------------------------------------------------------------
;; Tokenizer
;; ---------------------------------------------------------------------
;; A token is a plain word string ("dup", "3/4", "(", "IN:", ...) or
;; [:str "..."] (a real Factor string literal, or the captured text of
;; a #: ... ; musics-text span -- both push a plain Clojure string the
;; same way). Unlike input.forth's own tokenizer, "(" is NOT special-
;; cased here at all -- it's just an ordinary whitespace-delimited word,
;; ending up a one-character token the same way "[" already needs
;; whitespace around it in real Factor -- the COMPILER (compile-forms
;; below), not the tokenizer, decides what "(" means based on where it
;; appears.

(declare scan-hash-colon scan-quoted-string)

(defn- scan-balanced-close
  "source's own char at bracket-i is an opening [ or { -- returns the
   index just past its matching closer, [ ]/{ } depth-tracked together
   (one counter, same technique scan-hash-colon already uses below --
   nothing here needs cross-checking WHICH bracket kind closes which,
   only where the matching one ends), tolerant of \"...\" strings so a
   bracket inside a string literal can't miscount."
  [^String source bracket-i len]
  (loop [j (inc bracket-i) depth (long 1)]
    (cond
      (>= j len) (throw (ex-info "Unterminated [ / { / #{ literal" {:start bracket-i}))
      (= (.charAt source j) \")
      (let [[_ next-j] (scan-quoted-string source (inc j) len)]
        (recur next-j depth))
      (#{\[ \{} (.charAt source j)) (recur (inc j) (inc depth))
      (#{\] \}} (.charAt source j))
      (if (= depth 1) (inc j) (recur (inc j) (dec depth)))
      :else (recur (inc j) depth))))

(defn- scan-quoted-string
  "s starts right after an opening \" -- returns [text next-i], text
   unescaped (\\\" \\\\ \\n \\t)."
  [^String s i len]
  (loop [j i parts []]
    (if (>= j len)
      (throw (ex-info "Unterminated string literal" {}))
      (let [c (.charAt s j)]
        (cond
          (= c \")
          [(apply str parts) (inc j)]

          (= c \\)
          (let [nc (.charAt s (inc j))]
            (recur (+ j 2) (conj parts (case nc \n \newline \t \tab nc))))

          :else
          (recur (inc j) (conj parts c)))))))

(defn tokenize [^String source]
  (let [len (long (count source))]
    (loop [i (long 0) tokens []]
      (if (>= i len)
        tokens
        (let [c (.charAt source i)]
          (cond
            (Character/isWhitespace c)
            (recur (inc i) tokens)

            (= c \")
            (let [[text next-i] (scan-quoted-string source (inc i) len)]
              (recur (long next-i) (conj tokens [:str text])))

            ;; #: ... ; -- musics-notation sugar for "..." parse-notation
            ;; -- ported from resources/mforth.lua near-verbatim (this
            ;; piece is already correct and tested-by-construction
            ;; there): depth-tracked over musics.ebnf's own bracket
            ;; characters ([ ] { } ( ) '[ -- a single depth counter
            ;; suffices, since nothing here needs to verify BRACKET
            ;; MATCHING, only find the terminating top-level ; -- and a
            ;; two-char opener like ^{/'[ needs no special-casing, its
            ;; own bracket character alone still increments/decrements
            ;; depth correctly, the leading ^/' is just inert captured
            ;; text), tolerant of "..." StringLits (no escapes, scanned
            ;; to the next raw ") and %{ ... %} block comments
            ;; (non-greedy, first %} closes it -- both checked directly
            ;; against musics.ebnf, not assumed).
            (and (= c \#) (< (inc i) len) (= (.charAt source (inc i)) \:))
            (let [[text next-i] (scan-hash-colon source (+ i 2) len i)]
              (recur (long next-i) (conj tokens [:str text] "parse-notation")))

            ;; Literal Clojure data -- vectors [ ], maps/sets { }/#{ },
            ;; read via clojure.edn (real reader syntax, but deliberately
            ;; not the full Clojure reader -- no eval, no arbitrary
            ;; reader macros, just the data subset the ns docstring's
            ;; own "data types are Clojure's" calls for: numbers,
            ;; strings, keywords, booleans, nil, vectors, lists, maps,
            ;; sets). A NESTED literal (a vector of vectors, a map whose
            ;; value is a set, ...) needs no special handling at all --
            ;; scan-balanced-close finds the OUTER closer by depth alone,
            ;; and edn/read-string parses everything inside it in one
            ;; shot, recursively, on its own. Emitted as [:lit v], a
            ;; token shape compile-forms/interpret-token! already handle
            ;; identically to [:str v] (both are just "push this already-
            ;; built value" -- see their own shared "(vector? t)" branch).
            (= c \[)
            (let [end (scan-balanced-close source i len)]
              (recur (long end) (conj tokens [:lit (edn/read-string (subs source i end))])))

            (= c \{)
            (let [end (scan-balanced-close source i len)]
              (recur (long end) (conj tokens [:lit (edn/read-string (subs source i end))])))

            (and (= c \#) (< (inc i) len) (= (.charAt source (inc i)) \{))
            (let [end (scan-balanced-close source (inc i) len)]
              (recur (long end) (conj tokens [:lit (edn/read-string (subs source i end))])))

            :else
            (let [end (long (loop [j i]
                        (if (or (>= j len) (Character/isWhitespace (.charAt source j)))
                          j
                          (recur (inc j)))))
                  text (subs source i end)]
              ;; Keyword literals (:a, :some-thing) -- one more piece of
              ;; "data types are Clojure's," read the same [:lit v] way
              ;; [ ]/{ }/#{ } already are. Deliberately excludes "::"
              ;; (the :: word-definition form) and ":>" (bind a new
              ;; local) -- this kernel's own two genuine two-character
              ;; words that also happen to start with ':'.
              (if (and (> (count text) 1) (= (first text) \:)
                       (not (#{"::" ":>"} text)))
                (recur end (conj tokens [:lit (keyword (subs text 1))]))
                (recur end (conj tokens text))))))))))

(defn- scan-hash-colon
  "source at start-i is right after '#:' -- returns [text next-i], text
   the captured span up to (not including) the terminating top-level ';'."
  [^String source start-i len open-i]
  (loop [j start-i depth (long 0)]
    (cond
      (>= j len) (throw (ex-info "Unterminated #: ... ; block" {:start open-i}))

      (= (.charAt source j) \")
      (let [end (str/index-of source "\"" (inc j))]
        (when-not end (throw (ex-info "Unterminated string inside #: ... ; block" {:start open-i})))
        (recur (inc (long end)) depth))

      (and (= (.charAt source j) \%) (< (inc j) len) (= (.charAt source (inc j)) \{))
      (let [end (str/index-of source "%}" (+ j 2))]
        (recur (long (if end (+ end 2) len)) depth))

      (#{\[ \{ \(} (.charAt source j))
      (recur (inc j) (inc depth))

      (#{\] \} \)} (.charAt source j))
      (recur (inc j) (dec depth))

      (and (= (.charAt source j) \;) (zero? depth))
      [(subs source start-i j) (inc j)]

      :else
      (recur (inc j) depth))))

;; ---------------------------------------------------------------------
;; Reader/compiler
;; ---------------------------------------------------------------------
;; compile-forms is the one shared recursive compiler, used for a word's
;; own body (stopping at ";"), a quotation's own body (stopping at the
;; matching ")"), and to compile-and-push a bare top-level "(" the same
;; way. `locals` (nil outside a ::-locals-bearing definition) threads
;; through nested quotation bodies too -- a quotation sees its enclosing
;; word's own locals, matching real Factor's own lexical-closure
;; semantics (confirmed: core/locals/locals-docs.factor's own account of
;; :: NAME ( ... ) ... ; and [| ... ]).

(defn- num-token? [^String t]
  (re-matches #"[+-]?\d+(/\d+)?|[+-]?\d+\.\d+([eE][+-]?\d+)?" t))

(defn- parse-num [^String t]
  (cond
    (str/includes? t "/") (let [[n d] (str/split t #"/")]
                             (/ (bigint n) (bigint d)))
    (or (str/includes? t ".") (str/includes? t "e") (str/includes? t "E"))
    (Double/parseDouble t)
    :else (let [n (bigint t)]
            (if (<= Long/MIN_VALUE n Long/MAX_VALUE) (long n) n))))

(declare compile-forms display see-text where-vocab)

(defn- token-text
  "One raw token (a plain word string, or [:str s]) -> its own re-typable
   source text -- a string token re-quoted via pr-str, same convention
   `display` uses for a real string value."
  [t]
  (if (vector? t) (pr-str (second t)) t))

(defn- quotation-disp
  "body-toks -- the RAW tokens between a quotation's own ( and its
   matching ) (not yet compiled/interpreted) -- joined back into real,
   re-readable Factor source, e.g. \"( 1 + )\", not a generic
   placeholder: real Factor's own printing philosophy is to print almost
   any object back as valid, re-readable source (confirmed against
   resources/mforth.lua's own identical tokens_to_text/make_quotation
   pairing), and this project's own musics.lang follows it for
   quotations too -- what .` / `.s` actually show has to look like what
   was typed to build it, not a stand-in. A nested quotation's own ( )
   need no special handling here -- they're still just plain tokens in
   this same flat slice, so they come through verbatim."
  [body-toks]
  (if (empty? body-toks)
    "( )"
    (str "( " (str/join " " (map token-text body-toks)) " )")))

(defn- read-stack-effect
  "toks starts right after ': name'/'::  name' -- if the next token is
   '(', reads a real Factor stack effect ( in... -- out... ), returns
   [input-names effect-text remaining-toks] -- input-names for ::'s own
   locals-binding (the ONLY thing that ever consumed this before),
   effect-text the full reconstructed '( ... )' source (same
   token-rejoining idea as quotation-disp/token-text, kept here too so
   `see`/`stack-effect` have real text to show, not just the names a
   plain : never even binds). Otherwise returns [nil nil toks] unchanged
   -- a stack effect is always optional, same as real Factor."
  [toks]
  (if (and (seq toks) (= (first toks) "("))
    (loop [toks (rest toks) input-names [] side :in body-toks []]
      (let [t (first toks)]
        (cond
          (nil? t) (throw (ex-info "Unterminated stack effect (" {}))
          (= t ")") [input-names (str "( " (str/join " " body-toks) " )") (rest toks)]
          (= t "--") (recur (rest toks) input-names :out (conj body-toks t))
          (= side :in) (recur (rest toks) (conj input-names t) side (conj body-toks t))
          :else (recur (rest toks) input-names side (conj body-toks t)))))
    [nil nil toks]))

(defn- compile-forms
  "toks a seq of remaining tokens; stop-set a set of word tokens that end
   this span (consumed, not included in the result). locals (nil, or
   {:names #{...}} threaded from an enclosing ::  definition) makes a
   bare local name compile to a read step instead of a word call, and
   lets :> add a new one, visible for the rest of THIS body and any
   quotation nested inside it. Returns [steps remaining-toks]."
  [ctx toks stop-set locals]
  (loop [toks toks steps []]
    (let [t (first toks)]
      (cond
        (nil? t) [steps toks]

        (and (string? t) (contains? stop-set t)) [steps (rest toks)]

        (vector? t) ; [:str s] or [:lit v] -- either way, just push it
        (let [s (second t)]
          (recur (rest toks) (conj steps (fn [ctx] (push! ctx s)))))

        (= t "(")
        (let [[q-steps rest-toks] (compile-forms ctx (rest toks) #{")"} locals)
              ;; rest-toks sits right after the matching ")" -- the
              ;; consumed span, minus that closing token itself, is the
              ;; quotation's own real body text.
              consumed (- (count (rest toks)) (count rest-toks))
              body-toks (take (dec consumed) (rest toks))
              q (->Quotation q-steps (quotation-disp body-toks) nil)]
          ;; :env is stamped at RUN time (this step's own ctx), not
          ;; compile time -- see Quotation's own docstring for why.
          (recur rest-toks (conj steps (fn [ctx] (push! ctx (assoc q :env (:env ctx)))))))

        (= t "\\")
        (let [name (second toks)
              _ (when-not name (throw (ex-info "\\ expects a word name" {})))
              entry (lookup-word ctx name)]
          (when-not entry (throw (ex-info (str "unknown word after \\: " name) {})))
          (let [wr (->Wordref entry name)]
            (recur (drop 2 toks) (conj steps (fn [ctx] (push! ctx wr))))))

        (= t ":>")
        (let [name (second toks)]
          (when-not (and locals name) (throw (ex-info ":> used outside a ::  definition" {})))
          (swap! (:names locals) conj name)
          (recur (drop 2 toks) (conj steps (fn [ctx] (swap! (:env ctx) assoc name (pop! ctx))))))

        (and locals (contains? @(:names locals) t))
        (recur (rest toks) (conj steps (fn [ctx] (push! ctx (get @(:env ctx) t)))))

        :else
        (if-let [entry (lookup-word ctx t)]
          (recur (rest toks) (conj steps (fn [ctx] (execute-entry entry ctx))))
          (if (num-token? t)
            (let [n (parse-num t)]
              (recur (rest toks) (conj steps (fn [ctx] (push! ctx n)))))
            (throw (ex-info (str "unknown word during compile: " t) {}))))))))

(defn- compile-definition!
  "toks starts right after ':'/'::'. Reads the name, an optional stack
   effect (locals-binding only when binding? is true, i.e. for ::), the
   body up to ';', and defines the word in ctx's own current vocabulary.
   Returns the remaining toks. :compiling? is genuinely true (real
   interpreter state, not just a naming convention) for exactly the
   span where the body itself is being compiled -- reset in a finally
   so a compile error still leaves it false, never stuck on. The entry
   also carries :effect (the stack-effect source text, always kept now
   even for a plain : that never binds it) and :body-text (the body's
   own reconstructed source, same token-rejoining idea quotation-disp
   already uses for a quotation's own body) -- what `see`/`stack-effect`
   below actually read."
  [ctx toks binding?]
  (let [name (first toks)
        _ (when-not name (throw (ex-info "expected a name after :/::" {})))
        [effect-names effect-text toks] (read-stack-effect (rest toks))
        locals (when binding? {:names (atom (set effect-names)) :env nil})
        body-start toks
        [steps toks] (try
                       (reset! (:compiling? ctx) true)
                       (compile-forms ctx toks #{";"} locals)
                       (finally (reset! (:compiling? ctx) false)))
        consumed (- (count body-start) (count toks))
        body-toks (take (dec consumed) body-start)]
    (define-word! ctx name {:type :colon :steps steps
                             :incoming-names (when binding? effect-names)
                             :binding? binding?
                             :effect effect-text
                             :body-text (str/join " " (map token-text body-toks))})
    toks))

;; ---------------------------------------------------------------------
;; Combinators -- Factor's actual control-flow model: no special syntax
;; at all, just ordinary words consuming quotations as plain stack
;; values. This is what lets input.forth's whole "branch ops using
;; offsets, tiny index-based VM" design be dropped outright, not patched
;; -- quotations-as-values make it unnecessary. Ported from
;; resources/mforth.lua's own identically-named/identically-shaped
;; combinators (its own "── Combinators ──" section).
;; ---------------------------------------------------------------------

(defn- gcd*
  "Clojure has no built-in gcd -- a plain Euclidean algorithm, always
   non-negative, same convention most languages' own integer gcd uses."
  [a b]
  (if (zero? b) (abs (long a)) (recur b (mod a b))))

(defn- kernel-vocab []
  (merge
    ;; -- literals: Factor's own t/f, spelled as Clojure's own true/false
    ;; directly (see this ns's own header comment on booleans) ---------
    (builtin "true" (fn [ctx] (push! ctx true)) "( -- true )" "pushes the true singleton")
    (builtin "false" (fn [ctx] (push! ctx false)) "( -- false )" "pushes the false singleton -- the only falsy value")
    (builtin "nil" (fn [ctx] (push! ctx nil)) "( -- nil )" "pushes nil -- falsy same as false, but a distinct 'genuinely absent' value")

    ;; -- stack shufflers ---------------------------------------------
    (builtin "dup" (fn [ctx] (let [a (pop! ctx)] (push! ctx a) (push! ctx a))) "( x -- x x )" "duplicates the top of the stack")
    (builtin "drop" (fn [ctx] (pop! ctx)) "( x -- )" "discards the top of the stack")
    (builtin "swap" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx b) (push! ctx a))) "( a b -- b a )" "swaps the top two stack items")
    (builtin "over" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx a) (push! ctx b) (push! ctx a))) "( a b -- a b a )" "copies the second item to the top")
    (builtin "rot" (fn [ctx] (let [c (pop! ctx) b (pop! ctx) a (pop! ctx)]
                                 (push! ctx b) (push! ctx c) (push! ctx a))) "( a b c -- b c a )" "rotates the top three items left")
    (builtin "nip" (fn [ctx] (let [b (pop! ctx) _a (pop! ctx)] (push! ctx b))) "( a b -- b )" "discards the second item, keeping the top")
    (builtin "pick" (fn [ctx] (let [c (pop! ctx) b (pop! ctx) a (pop! ctx)]
                                  (push! ctx a) (push! ctx b) (push! ctx c) (push! ctx a))) "( a b c -- a b c a )" "copies the third item to the top")
    (builtin "2dup" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)]
                                  (push! ctx a) (push! ctx b) (push! ctx a) (push! ctx b))) "( a b -- a b a b )" "duplicates the top two items as a pair")
    (builtin "clear" (fn [ctx] (reset! (:stack ctx) [])) "( ... -- )" "empties the entire stack")

    ;; -- arithmetic/comparison -- Clojure's own numeric tower already
    ;; has real exact ratios/bigints, so +/-/*// need no special casing
    ;; at all (a genuine simplification over resources/mforth.lua's own
    ;; hand-rolled rational type, see this ns's own header comment).
    ;; Comparisons push real true/false, not a -1/0 flag convention --
    ;; Factor's own boolean model IS Clojure's own truthiness already.
    (builtin "+" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (+ a b)))) "( a b -- c )" "adds two numbers")
    (builtin "-" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (- a b)))) "( a b -- c )" "subtracts b from a")
    (builtin "*" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (* a b)))) "( a b -- c )" "multiplies two numbers")
    (builtin "/" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (/ a b)))) "( a b -- c )" "divides a by b, exact for integers/ratios")
    ;; mod/rem/floor/neg/abs/gcd all behave exactly as Clojure's own
    ;; built-ins do -- a deliberate choice, not real Factor's own
    ;; convention (real Factor's own mod actually takes the sign of the
    ;; DIVIDEND, the opposite of Clojure's; this kernel used to match
    ;; that, but "data types are Clojure's" now extends to arithmetic
    ;; behavior too, so mod/rem below are Clojure's own, unmodified).
    (builtin "mod" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (mod a b)))) "( x y -- z )" "remainder of x/y, sign of y (Clojure's own mod)")
    (builtin "rem" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (rem a b)))) "( x y -- z )" "remainder of x/y, sign of x (Clojure's own rem)")
    (builtin "/mod" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (quot a b)) (push! ctx (rem a b)))) "( x y -- q r )" "truncated quotient and remainder together, consistent with each other")
    (builtin "neg" (fn [ctx] (push! ctx (- (pop! ctx)))) "( x -- -x )" "negates a number")
    (builtin "abs" (fn [ctx] (push! ctx (abs (pop! ctx)))) "( x -- |x| )" "absolute value")
    (builtin "gcd" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (gcd* a b)))) "( a b -- c )" "greatest common divisor")
    (builtin "floor" (fn [ctx] (push! ctx (long (Math/floor (double (pop! ctx)))))) "( x -- y )" "largest integer not greater than x")
    (builtin "min" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (min a b)))) "( a b -- c )" "the smaller of two numbers")
    (builtin "max" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (max a b)))) "( a b -- c )" "the larger of two numbers")
    ;; -- trig/general math -- generic enough to belong in the kernel
    ;; itself, not any one bridge vocabulary (added alongside min/max
    ;; above specifically so algo-common's own native cosr/sinr/tanr/...
    ;; words have real primitives to build on).
    (builtin "pi" (fn [ctx] (push! ctx Math/PI)) "( -- x )" "the constant pi")
    (builtin "sin" (fn [ctx] (push! ctx (Math/sin (double (pop! ctx))))) "( x -- y )" "sine of x radians")
    (builtin "cos" (fn [ctx] (push! ctx (Math/cos (double (pop! ctx))))) "( x -- y )" "cosine of x radians")
    (builtin "tan" (fn [ctx] (push! ctx (Math/tan (double (pop! ctx))))) "( x -- y )" "tangent of x radians")
    (builtin "asin" (fn [ctx] (push! ctx (Math/asin (double (pop! ctx))))) "( x -- y )" "arcsine of x, in radians")
    (builtin "sign" (fn [ctx] (push! ctx (Math/signum (double (pop! ctx))))) "( x -- s )" "-1.0/0.0/1.0 by the sign of x")
    (builtin ">float" (fn [ctx] (push! ctx (double (pop! ctx)))) "( x -- y )" "x forced to a double, e.g. before dividing two integers and wanting a float result")
    (builtin "<" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (< a b)))) "( a b -- ? )" "true if a is less than b")
    (builtin ">" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (> a b)))) "( a b -- ? )" "true if a is greater than b")
    (builtin "<=" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (<= a b)))) "( a b -- ? )" "true if a is less than or equal to b")
    (builtin ">=" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (>= a b)))) "( a b -- ? )" "true if a is greater than or equal to b")
    (builtin "=" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (= a b)))) "( a b -- ? )" "true if a and b are equal")
    (builtin "not" (fn [ctx] (push! ctx (not (pop! ctx)))) "( ? -- ? )" "flips true/false")

    ;; -- control flow: ordinary words, quotations are the payload -----
    (builtin "call" (fn [ctx] (run-callable (pop! ctx) ctx)) "( ..a quot -- ..b )" "runs a quotation")
    (builtin "execute" (fn [ctx] (run-callable (pop! ctx) ctx)) "( ..a word/quot -- ..b )" "runs a word reference or quotation")
    (builtin "if" (fn [ctx] (let [false-q (pop! ctx) true-q (pop! ctx) flag (pop! ctx)]
                                (run-callable (if flag true-q false-q) ctx))) "( ..a ? true-quot false-quot -- ..b )" "runs one quotation or the other, by a boolean")
    ;; ? ( ? true false -- true/false ): confirmed real Factor kernel
    ;; word -- the ternary-if sibling of if above, picking between two
    ;; plain VALUES instead of running one of two quotations. Both
    ;; values are already on the stack either way (Clojure/Factor are
    ;; both eager here), so unlike `if` this never needs a callable at
    ;; all -- just a plain three-arg select.
    (builtin "?" (fn [ctx] (let [f (pop! ctx) t (pop! ctx) flag (pop! ctx)]
                             (push! ctx (if flag t f)))) "( ? true false -- true/false )" "picks one of two plain values by a boolean, no quotations involved")
    (builtin "when" (fn [ctx] (let [q (pop! ctx) flag (pop! ctx)]
                                  (when flag (run-callable q ctx)))) "( ..a ? quot -- ..b )" "runs the quotation only if the flag is true")
    (builtin "unless" (fn [ctx] (let [q (pop! ctx) flag (pop! ctx)]
                                    (when-not flag (run-callable q ctx)))) "( ..a ? quot -- ..b )" "runs the quotation only if the flag is false")
    (builtin "dip" (fn [ctx] (let [q (pop! ctx) x (pop! ctx)]
                                 (run-callable q ctx) (push! ctx x))) "( ..a x quot -- ..b x )" "runs the quotation with x removed, then restores x on top")
    (builtin "keep" (fn [ctx] (let [q (pop! ctx) x (pop! ctx)]
                                  (push! ctx x) (run-callable q ctx) (push! ctx x))) "( ..a x quot -- ..b x )" "runs the quotation on x, then restores the original x after")
    (builtin "bi" (fn [ctx] (let [q (pop! ctx) p (pop! ctx) x (pop! ctx)]
                                (push! ctx x) (run-callable p ctx)
                                (push! ctx x) (run-callable q ctx))) "( x p q -- )" "applies p, then q, each to the same original x")
    (builtin "tri" (fn [ctx] (let [r (pop! ctx) q (pop! ctx) p (pop! ctx) x (pop! ctx)]
                                 (push! ctx x) (run-callable p ctx)
                                 (push! ctx x) (run-callable q ctx)
                                 (push! ctx x) (run-callable r ctx))) "( x p q r -- )" "applies p, q, then r, each to the same original x")
    (builtin "2dip" (fn [ctx] (let [q (pop! ctx) y (pop! ctx) x (pop! ctx)]
                                  (run-callable q ctx) (push! ctx x) (push! ctx y))) "( ..a x y quot -- ..b x y )" "runs the quotation with x y removed, then restores them")
    (builtin "3dip" (fn [ctx] (let [q (pop! ctx) z (pop! ctx) y (pop! ctx) x (pop! ctx)]
                                  (run-callable q ctx) (push! ctx x) (push! ctx y) (push! ctx z))) "( ..a x y z quot -- ..b x y z )" "runs the quotation with x y z removed, then restores them")
    ;; curry/compose combine ALREADY-instantiated quotations' own steps
    ;; into a new one -- :env nil (no enclosing locals of its own),
    ;; correct for the common case (composing/currying self-contained
    ;; quotations); a source quotation that itself referenced outer
    ;; locals loses that closure here -- a known, narrow limitation, not
    ;; attempted in this first pass (curry/compose over a closure-
    ;; capturing quotation is a genuine edge case real Factor's own
    ;; identical words handle via its fuller `fry`/locals machinery,
    ;; out of scope here).
    (builtin "curry" (fn [ctx] (let [q (pop! ctx) obj (pop! ctx)]
                                   (push! ctx (->Quotation
                                                (into [(fn [ctx] (push! ctx obj))] (quot-steps q))
                                                "( curried )" nil))))
             "( obj quot -- curried )" "builds a new quotation that pushes obj, then runs quot")
    (builtin "compose" (fn [ctx] (let [q2 (pop! ctx) q1 (pop! ctx)]
                                     (push! ctx (->Quotation
                                                  (into (vec (quot-steps q1)) (quot-steps q2))
                                                  "( composed )" nil))))
             "( quot1 quot2 -- composed )" "builds a new quotation that runs quot1 then quot2")
    (builtin "loop"
      (fn [ctx] (let [q (pop! ctx)]
                  (loop [] (run-callable q ctx) (when (pop! ctx) (recur)))))
      "( pred: ( -- ? ) -- )" "runs pred repeatedly until it leaves false on the stack")

    ;; -- sequence combinators -- operate on any Clojure seqable: a
    ;; literal [ ]/{ }/#{ } (see this ns's own header comment), or a
    ;; vector/list/lazy-seq returned by a musics.core bridge word (e.g.
    ;; ids/leaves/children).
    (builtin "each" (fn [ctx] (let [q (pop! ctx) xs (pop! ctx)]
                                  (doseq [x xs] (push! ctx x) (run-callable q ctx))))
             "( seq quot -- )" "runs quot once per element, for side effects")
    (builtin "map" (fn [ctx] (let [q (pop! ctx) xs (pop! ctx)]
                                 (push! ctx (mapv (fn [x] (push! ctx x) (run-callable q ctx) (pop! ctx)) xs))))
             "( seq quot -- newseq )" "builds a new sequence by running quot on each element")
    (builtin "filter" (fn [ctx] (let [q (pop! ctx) xs (pop! ctx)]
                                    (push! ctx (vec (filter (fn [x] (push! ctx x) (run-callable q ctx) (pop! ctx)) xs)))))
             "( seq quot -- subseq )" "keeps only the elements quot leaves true for")
    (builtin "reduce" (fn [ctx] (let [q (pop! ctx) init (pop! ctx) xs (pop! ctx)]
                                    (push! ctx (reduce (fn [acc x] (push! ctx acc) (push! ctx x) (run-callable q ctx) (pop! ctx))
                                                        init xs))))
             "( seq identity quot -- result )" "folds the sequence down to one value with quot")

    ;; -- sequence/assoc accessors -- direct, thin wrappers over
    ;; Clojure's own core fns, Clojure's own arg order (collection
    ;; first) rather than Factor's own collection-last convention --
    ;; "data types are Clojure's" extends to how they're USED here too,
    ;; not just how they're spelled.
    (builtin "nth" (fn [ctx] (let [n (pop! ctx) coll (pop! ctx)] (push! ctx (nth coll n nil)))) "( coll n -- elt/nil )" "the nth element, 0-indexed")
    (builtin "get" (fn [ctx] (let [k (pop! ctx) m (pop! ctx)] (push! ctx (get m k)))) "( map key -- value/nil )" "looks a key up in a map")
    (builtin "assoc" (fn [ctx] (let [v (pop! ctx) k (pop! ctx) m (pop! ctx)] (push! ctx (assoc m k v)))) "( map key value -- map' )" "a new map with key set to value")
    (builtin "conj" (fn [ctx] (let [x (pop! ctx) coll (pop! ctx)] (push! ctx (conj coll x)))) "( coll x -- coll' )" "a new collection with x added")
    (builtin "first" (fn [ctx] (push! ctx (first (pop! ctx)))) "( coll -- x/nil )" "the first element")
    ;; real Factor's own name for this too (confirmed: the original
    ;; mforth.lua-flavored course this project's own doc/musics-
    ;; course.txt was adapted from already documented "rest drops the
    ;; first element" against real Factor) -- and Clojure's own core fn
    ;; name besides, so no naming decision was actually needed here.
    (builtin "rest" (fn [ctx] (push! ctx (vec (rest (pop! ctx))))) "( coll -- coll' )" "every element except the first")
    (builtin "count" (fn [ctx] (push! ctx (count (pop! ctx)))) "( coll -- n )" "how many elements")
    ;; head/tail, not take/drop -- real Factor's own naming, chosen
    ;; specifically so a sequence word never collides with the stack
    ;; shuffler `drop` above (a genuinely different `drop`, discarding
    ;; the whole top-of-stack value rather than n elements of a coll).
    (builtin "head" (fn [ctx] (let [n (pop! ctx) coll (pop! ctx)] (push! ctx (vec (take n coll))))) "( coll n -- coll' )" "the first n elements")
    (builtin "tail" (fn [ctx] (let [n (pop! ctx) coll (pop! ctx)] (push! ctx (vec (drop n coll))))) "( coll n -- coll' )" "every element after the first n")
    (builtin "concat" (fn [ctx] (let [b (pop! ctx) a (pop! ctx)] (push! ctx (vec (concat a b))))) "( coll1 coll2 -- coll3 )" "coll1's elements followed by coll2's")

    ;; -- vocabularies -----------------------------------------------------
    ;; Only reachable from a compiled body (interpret-token! special-
    ;; cases the bare top-level token before word lookup ever runs) --
    ;; these throw a clear error rather than silently misbehaving in
    ;; that position, same "parsing words are top-level-only" limitation
    ;; real Factor's own IN:/USE:/USING: have.
    (builtin "IN:" (fn [_ctx] (throw (ex-info "IN: is a parsing word, only valid at the top level" {})))
             nil "sets which vocabulary new definitions land in")
    (builtin "USE:" (fn [_ctx] (throw (ex-info "USE: is a parsing word, only valid at the top level" {})))
             nil "brings one whole vocabulary's words into scope")
    (builtin "USING:" (fn [_ctx] (throw (ex-info "USING: is a parsing word, only valid at the top level" {})))
             nil "USE: for several vocabularies at once, ended by ;")
    (builtin "FROM:" (fn [_ctx] (throw (ex-info "FROM: is a parsing word, only valid at the top level" {})))
             nil "imports only the named words from one vocabulary")
    (builtin "EXCLUDE:" (fn [_ctx] (throw (ex-info "EXCLUDE: is a parsing word, only valid at the top level" {})))
             nil "imports a whole vocabulary except the named words")
    (builtin "RENAME:" (fn [_ctx] (throw (ex-info "RENAME: is a parsing word, only valid at the top level" {})))
             nil "imports one word from a vocabulary under a new name")
    (builtin "QUALIFIED:" (fn [_ctx] (throw (ex-info "QUALIFIED: is a parsing word, only valid at the top level" {})))
             nil "makes a vocabulary reachable as vocab:word")
    (builtin "QUALIFIED-WITH:" (fn [_ctx] (throw (ex-info "QUALIFIED-WITH: is a parsing word, only valid at the top level" {})))
             nil "QUALIFIED: with a custom prefix instead of the vocab's own name")
    (builtin "FORGET:" (fn [_ctx] (throw (ex-info "FORGET: is a parsing word, only valid at the top level" {})))
             nil "removes a word from the current vocabulary")
    (builtin "HELP:" (fn [_ctx] (throw (ex-info "HELP: is a parsing word, only valid at the top level" {})))
             nil "attaches a one-line description to an already-defined word")

    ;; -- vocabulary introspection -- this kernel's own convenience
    ;; additions, not claimed as verified real-Factor word names.
    (builtin "vocabs" (fn [ctx] (push! ctx (vec (sort (keys @(:vocabularies ctx)))))) "( -- names )" "lists every known vocabulary's own name")
    (builtin "words" (fn [ctx] (push! ctx (vec (sort (keys (get @(:vocabularies ctx) @(:current-vocab ctx))))))) "( -- names )" "lists the current vocabulary's own word names")
    (builtin "vocab" (fn [ctx] (push! ctx @(:current-vocab ctx))) "( -- name )" "pushes the current vocabulary's own name")
    (builtin "parsing?" (fn [ctx] (push! ctx @(:parsing? ctx))) "( -- ? )" "true while a #: ... ; musics-text span is being parsed")
    (builtin "compiling?" (fn [ctx] (push! ctx @(:compiling? ctx))) "( -- ? )" "true while a : or :: word's own body is being compiled")
    (builtin "interpreting?" (fn [ctx] (push! ctx (and (not @(:parsing? ctx)) (not @(:compiling? ctx))))) "( -- ? )" "true whenever neither compiling? nor parsing? is")

    ;; -- code inspection -- see/where/stack-effect, real Factor's own
    ;; words (verified against a local real-Factor source checkout's own
    ;; basis/see/see-docs.factor, core/definitions/definitions-docs.factor,
    ;; core/effects/effects-docs.factor), all three taking a WORD
    ;; REFERENCE (\ name -- the only "a word as a value" this kernel
    ;; has, real Factor's own convention too: `\ append see`). where's
    ;; own real effect is `( defspec -- loc )`, loc a { path line# }
    ;; pair or f "if the location is not known" -- this kernel has no
    ;; file-based loading at all yet (everything arrives as typed/fed
    ;; text, no on-disk module loader), so there's no path/line# to
    ;; report; the vocabulary name is
    ;; the closest real, honest analog (found by scanning every
    ;; vocabulary for the one whose own map holds this exact entry --
    ;; one mechanism covers a :colon word and a :primitive alike, no
    ;; need to separately stamp :vocab onto every entry at definition
    ;; time), or false when the entry isn't found in any (shouldn't
    ;; happen for a \-produced wordref, kept as an honest fallback
    ;; rather than an assumption).
    (builtin "see"
      (fn [ctx] (print (see-text (pop! ctx))) (flush))
      "( defspec -- )" "prints a word's own reconstructed definition and doc")
    (builtin "where"
      (fn [ctx] (push! ctx (or (where-vocab ctx (pop! ctx)) false)))
      "( defspec -- loc )" "reports which vocabulary a word is defined in")
    (builtin "stack-effect"
      (fn [ctx] (push! ctx (or (:effect (:entry (pop! ctx))) false)))
      "( word -- effect/f )" "a word's own declared stack effect, or f if none was given")
    (builtin "word-doc"
      (fn [ctx] (push! ctx (or (:doc (:entry (pop! ctx))) false)))
      "( word -- doc/f )"
      "the one-line description HELP: attached, or f if none was given")

    ;; -- print -----------------------------------------------------------
    (builtin "." (fn [ctx] (print (display (pop! ctx))) (print " ") (flush)) "( value -- )" "prints one value as its own re-readable source")
    (builtin ".s" (fn [ctx] (print (str/join " " (map display @(:stack ctx)))) (print " ") (flush)) "( -- )" "prints the whole stack, without touching it")
    (builtin "print" (fn [ctx] (print (pop! ctx)) (flush)) "( str -- )" "prints a string's own raw content, no quotes")
    (builtin "nl" (fn [_ctx] (println)) "( -- )" "prints a newline")))

;; A real multimethod, not a growing cond -- deliberately, since real
;; Factor's own printing IS class-based generic dispatch (each class
;; registers its own `M: class pprint* ...`), and this is the same
;; shape here: dispatch on the Clojure type directly, one defmethod per
;; type that needs its own rendering. This is also the natural, already-
;; idiomatic home for a future TUPLE:'s own T{ class slot v ... } print
;; form and GENERIC:/M:'s own user-defined dispatch -- both are this
;; same "one behavior, many classes" shape, just applied to arbitrary
;; user words instead of only to printing.
(defmulti display
  "Real Factor's own pprint philosophy: print back almost any object as
   valid, re-readable source -- a string prints QUOTED, a vector/map/set
   prints in Clojure's own native syntax (already exactly what pr-str
   gives -- the :default case), a quotation prints its own reconstructed
   source (see :disp on Quotation)."
  type)

(defmethod display Quotation [v] (:disp v))
(defmethod display Wordref [v] (str "\\ " (:name v)))
(defmethod display :default [v] (pr-str v))

;; ---------------------------------------------------------------------
;; Code inspection -- see/where/stack-effect's own shared helpers (the
;; three kernel-vocab words themselves are defined inline above, right
;; next to the rest of the kernel; these two just need to exist before
;; that def-prim block runs, per the forward-declare at this file's own
;; top).
;; ---------------------------------------------------------------------

(defn see-text
  "wordref -> the reconstructed real Factor `: name ( effect ) body ;`
   source for a :colon entry (:: instead of : when it binds locals),
   or an honest `PRIMITIVE: name ( effect )` for a :primitive one --
   there's no body source to show for those (they're Clojure fns, not
   compiled from musics-lang text at all), matching real Factor's own
   distinct PRIMITIVE: declaration syntax for its own genuine VM
   primitives rather than pretending they have an ordinary : body. A
   :doc, if present, is shown on its OWN leading line, deliberately NOT
   folded into the reconstructed definition text itself -- this kernel
   has no verified comment syntax of its own to spell it with, so
   dressing it up as if it were part of the re-readable source would be
   a claim this file can't actually back up (unlike everything else
   see-text reconstructs, which really is valid input)."
  [wordref]
  (let [{:keys [entry name]} wordref
        effect (:effect entry)
        doc (:doc entry)
        definition
        (case (:type entry)
          :colon (str (if (:binding? entry) ":: " ": ") name
                       (when effect (str " " effect))
                       " " (:body-text entry) " ;")
          :primitive (str "PRIMITIVE: " name (when effect (str " " effect)))
          (str "unknown entry: " (pr-str entry)))]
    (if doc (str name " -- " doc "\n" definition) definition)))

(defn where-vocab
  "wordref -> the name of the vocabulary whose own word map holds this
   EXACT entry (structural =, not identity -- entries are plain maps/
   records), or nil if none does. This is the one mechanism that works
   uniformly for a :colon word (defined through define-word!, so always
   findable) and a :primitive (merged into its vocab's map wholesale at
   ctx-construction time, never individually stamped with its own
   :vocab) alike -- see this ns's own `where` word for why a vocabulary
   name is what stands in for real Factor's own { path line# } here."
  [ctx wordref]
  (some (fn [[vname vmap]]
          (when (some #(= % (:entry wordref)) (vals vmap)) vname))
        @(:vocabularies ctx)))

(defn set-word-doc!
  "HELP:'s own mechanism -- amends an ALREADY-defined word's own entry
   with a one-line description, found via where-vocab (the one
   mechanism that already locates either kind of entry uniformly, see
   its own docstring) and updated in place, same simplified spirit as
   real Factor's own separate HELP: block (documentation is amended
   after the fact, not required at definition time) but a single string
   instead of a full $values/$description/$examples structure."
  [ctx name doc]
  (let [entry (lookup-word ctx name)]
    (when-not entry (throw (ex-info (str "HELP: unknown word: " name) {})))
    (if-let [vocab (where-vocab ctx (->Wordref entry name))]
      (swap! (:vocabularies ctx) update-in [vocab name] assoc :doc doc)
      (throw (ex-info (str "HELP: cannot locate a vocabulary for " name) {})))))

;; ---------------------------------------------------------------------
;; The musics.core bridge -- musics.lang.vocab.musics/vocab,
;; musics.lang.vocab.parse/vocab, musics.lang.vocab.algorithms/vocab
;; (required above) -- exists only because this kernel also hosts
;; musics text. Mechanical translation of input.forth's own
;; musics-prims (same 59 words, same argument-marshaling conventions --
;; ->kw/callable->fn, see musics.lang.runtime) -- just lowercased and
;; split across "musics"/"parse"/"algorithms", one file each, instead
;; of one shared flat dictionary -- and #: ... ; ("parsing mode," see
;; this ns's own header comment) replacing input.forth's own
;; bare-bracket-auto-detection for how musics text gets onto the stack
;; in the first place. See make-ctx below for how the three are wired
;; in and made reachable unqualified from `scratchpad` by default.
;; ---------------------------------------------------------------------

;; ---------------------------------------------------------------------
;; Top level: interpret a stream of tokens
;; ---------------------------------------------------------------------

(defn interpret-token! [ctx t toks]
  (cond
    (= t ":") (compile-definition! ctx toks false)
    (= t "::") (compile-definition! ctx toks true)

    (= t "IN:")
    (let [name (first toks)]
      (when-not name (throw (ex-info "IN: expected a vocabulary name" {})))
      (ensure-vocab! ctx name)
      (reset! (:current-vocab ctx) name)
      (rest toks))

    (= t "USE:")
    (let [name (first toks)]
      (when-not name (throw (ex-info "USE: expected a vocabulary name" {})))
      (use-vocab! ctx name)
      (rest toks))

    (= t "USING:")
    (loop [toks toks]
      (let [name (first toks)]
        (cond
          (nil? name) (throw (ex-info "USING: expected a terminating ';'" {}))
          (= name ";") (rest toks)
          :else (do (use-vocab! ctx name) (recur (rest toks))))))

    ;; FROM: vocab => word1 word2 ... ; -- import only these specific
    ;; words, taking precedence over a plain USE:/USING: on a collision
    ;; (see this ns's own Vocabularies header comment for the confirmed
    ;; real-Factor precedence rule).
    (= t "FROM:")
    (let [vocab (first toks) arrow (second toks)]
      (when-not (= arrow "=>") (throw (ex-info "FROM: expected 'vocab => word...'" {})))
      (ensure-vocab! ctx vocab)
      (loop [toks (drop 2 toks)]
        (let [w (first toks)]
          (cond
            (nil? w) (throw (ex-info "FROM: expected a terminating ';'" {}))
            (= w ";") (rest toks)
            :else (do (import-word! ctx w vocab w) (recur (rest toks)))))))

    ;; EXCLUDE: vocab => word1 word2 ... ; -- import all of vocab's
    ;; words EXCEPT these.
    (= t "EXCLUDE:")
    (let [vocab (first toks) arrow (second toks)]
      (when-not (= arrow "=>") (throw (ex-info "EXCLUDE: expected 'vocab => word...'" {})))
      (use-vocab! ctx vocab)
      (loop [toks (drop 2 toks)]
        (let [w (first toks)]
          (cond
            (nil? w) (throw (ex-info "EXCLUDE: expected a terminating ';'" {}))
            (= w ";") (rest toks)
            :else (do (exclude-word! ctx vocab w) (recur (rest toks)))))))

    ;; RENAME: word vocab => new-name -- fixed 4-token form, no
    ;; terminating ';' (confirmed real Factor's own $syntax).
    (= t "RENAME:")
    (let [word (first toks) vocab (second toks) arrow (nth toks 2 nil) new-name (nth toks 3 nil)]
      (when-not (= arrow "=>") (throw (ex-info "RENAME: expected 'word vocab => new-name'" {})))
      (when-not new-name (throw (ex-info "RENAME: expected a new name" {})))
      (ensure-vocab! ctx vocab)
      (import-word! ctx new-name vocab word)
      (drop 4 toks))

    ;; QUALIFIED: vocab -- vocab's own words reachable as vocab:word.
    (= t "QUALIFIED:")
    (let [vocab (first toks)]
      (when-not vocab (throw (ex-info "QUALIFIED: expected a vocabulary name" {})))
      (qualify-vocab! ctx vocab vocab)
      (rest toks))

    ;; QUALIFIED-WITH: vocab prefix -- vocab's own words reachable as
    ;; prefix:word instead of vocab:word.
    (= t "QUALIFIED-WITH:")
    (let [vocab (first toks) prefix (second toks)]
      (when-not (and vocab prefix) (throw (ex-info "QUALIFIED-WITH: expected 'vocab prefix'" {})))
      (qualify-vocab! ctx prefix vocab)
      (drop 2 toks))

    ;; FORGET: word -- see forget-word!'s own docstring for the one
    ;; deliberate simplification from real Factor's own version.
    (= t "FORGET:")
    (let [name (first toks)]
      (when-not name (throw (ex-info "FORGET: expected a word name" {})))
      (forget-word! ctx name)
      (rest toks))

    ;; HELP: name "one-line description" -- see set-word-doc!'s own
    ;; docstring for how this simplifies real Factor's own fuller HELP:
    ;; block. No terminating ';' -- fixed 2-token form, same shape
    ;; RENAME:/QUALIFIED-WITH: already have.
    (= t "HELP:")
    (let [name (first toks)
          doc-tok (second toks)]
      (when-not (and name (vector? doc-tok) (= (first doc-tok) :str))
        (throw (ex-info "HELP: expected 'name \"description\"'" {})))
      (set-word-doc! ctx name (second doc-tok))
      (drop 2 toks))

    (= t "\\")
    (let [name (first toks)
          entry (lookup-word ctx name)]
      (when-not entry (throw (ex-info (str "unknown word after \\: " name) {})))
      (push! ctx (->Wordref entry name))
      (rest toks))

    (= t "(")
    (let [[steps rest-toks] (compile-forms ctx toks #{")"} nil)
          consumed (- (count toks) (count rest-toks))
          body-toks (take (dec consumed) toks)]
      (push! ctx (->Quotation steps (quotation-disp body-toks) (:env ctx)))
      rest-toks)

    (vector? t) (do (push! ctx (second t)) toks) ; [:str s] or [:lit v]

    :else
    (if-let [entry (lookup-word ctx t)]
      (do (execute-entry entry ctx) toks)
      (if (num-token? t)
        (do (push! ctx (parse-num t)) toks)
        (throw (ex-info (str "unknown word: " t) {}))))))

(defn interpret-all! [ctx toks]
  (loop [toks toks]
    (when (seq toks)
      (recur (interpret-token! ctx (first toks) (rest toks))))))

(defn run-string [ctx s]
  (interpret-all! ctx (tokenize s)))

(defn make-ctx []
  (let [ctx {:stack (atom [])
             :vocabularies (atom {"kernel" (kernel-vocab)
                                   "musics" (musics-vocab/vocab)
                                   "parse" (parse-vocab/vocab)
                                   "algorithms" (algorithms-vocab/vocab)
                                   "algo-common" (algo-common-vocab/vocab)
                                   "algo-indisp" (algo-indisp-vocab/vocab)
                                   "algo-melodic" (algo-melodic-vocab/vocab)
                                   "algo-metric" (algo-metric-vocab/vocab)
                                   "algo-random" (algo-random-vocab/vocab)
                                   "algo-rhythmic" (algo-rhythmic-vocab/vocab)
                                   "algo-algoline" (algo-algoline-vocab/vocab)
                                   "algo-toolkit" (algo-toolkit-vocab/vocab)
                                   "scratchpad" {}})
             ;; `algorithms` is itself the tree root for the whole
             ;; algo-* family now -- it USEs all 8, so anything that
             ;; USEs `algorithms` (scratchpad, or any other vocab) sees
             ;; every one of them TRANSITIVELY (use-vocab-lookup, above),
             ;; with nothing to wire up per sub-vocab individually.
             ;; `algorithms` still has its own real words too
             ;; (register-factory!/build!/chain-algo!/...) -- nothing
             ;; about being a container stops a vocab from also
             ;; defining words of its own.
             :vocab-uses (atom {"scratchpad" #{"musics" "parse" "algorithms"}
                                 "algorithms" #{"algo-common" "algo-indisp" "algo-melodic" "algo-metric"
                                                "algo-random" "algo-rhythmic" "algo-algoline" "algo-toolkit"}})
             :vocab-imports (atom {})
             :vocab-exclusions (atom {})
             :vocab-qualifiers (atom {})
             :current-vocab (atom "scratchpad")
             ;; The only two real mode flags -- no separate :interpreting
             ;; flag exists at all: interpreting IS just both of these
             ;; false, not a third stored state.
             :parsing? (atom false)
             :compiling? (atom false)}]
    ;; algo-common's own native words (clamp/rotate/lcm/the six trig
    ;; fns/...) are real musics.lang SOURCE, not Clojure primitives --
    ;; compiled into the "algo-common" vocab right here, the one point
    ;; a fresh ctx already has every kernel/bridge word (min/max/pi/
    ;; sin/.../head/tail/concat included) available to compile against.
    ;; :current-vocab is reset back to "scratchpad" afterward -- running
    ;; this source switches it to "algo-common" (via its own leading
    ;; IN:), same as any other IN:-bearing text would, and a fresh ctx
    ;; must still start in "scratchpad".
    (run-string ctx algo-common-vocab/native-bootstrap-source)
    (reset! (:current-vocab ctx) "scratchpad")
    ctx))

;; ---------------------------------------------------------------------
;; REPL
;; ---------------------------------------------------------------------

(defn- forth-exit! [] (throw (ex-info "musics-lang-exit" {:musics-lang/exit? true})))

(defn prompt-text
  "vocab<depth> -- real Factor's own listener prompt shape: the current
   vocabulary's own name, then the stack's own depth in angle brackets,
   recomputed fresh every line (both change as you go)."
  [ctx]
  (str @(:current-vocab ctx) "<" (count @(:stack ctx)) ">"))

(defn run-repl-loop
  "Print prompt, read a line, run-string it, print \" ok\" (or an error),
   repeat -- until EOF (Ctrl-D) or 'bye' throws the exit signal. Mirrors
   input.forth's own run-repl-loop's overall shape, but the prompt
   itself is now live (see prompt-text), not the fixed string
   input.forth's own version always prints."
  [ctx]
  (define-word! ctx "bye" {:type :primitive :fn (fn [_ctx] (forth-exit!))})
  (loop []
    (print (prompt-text ctx)) (print " ") (flush)
    (let [line (read-line)]
      (when line
        (let [continue?
              (try
                (run-string ctx line)
                (println " ok")
                true
                (catch clojure.lang.ExceptionInfo e
                  (if (:musics-lang/exit? (ex-data e))
                    false
                    (do (println "Error:" (.getMessage e)) true)))
                (catch Exception e
                  (println "Error:" (.getMessage e))
                  true))]
          (when continue? (recur)))))))

(defn -main [& _]
  (println "musics-lang. Ctrl-D or `bye` to exit.")
  (run-repl-loop (make-ctx)))

(defn repl!
  "Drop into a nested musics-lang REPL loop from within an already-
   running Clojure REPL -- mirrors input.forth's own repl!. Shares
   core.repo/musics.core's session with the outer REPL (defonce
   singletons); only the kernel-level state (vocabularies, stack) is
   fresh per call.

   (musics.lang/repl!) from a Clojure REPL; `bye` (or Ctrl-D) to return."
  []
  (println "musics-lang REPL. Ctrl-D or `bye` to return to the Clojure REPL.")
  (run-repl-loop (make-ctx))
  (println "Back to the Clojure REPL.")
  nil)
