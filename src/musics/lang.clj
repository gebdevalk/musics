(ns musics.lang
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [musics.core :as m])
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
;; "musics" vocabulary instead of a shared flat dictionary). Explicitly
;; NOT ported yet (deferred to whenever something actually needs one,
;; straight from resources/mforth.lua at that point): the exact-rational
;; tower (unneeded, see above), generic word dispatch (PREDICATE:/M:/
;; GENERIC:), sets, TUPLE:/ERROR:, and the math.combinatorics/
;; math.statistics libraries. input.forth/forth.clj itself is untouched
;; by this pass -- see its own file for the classic-Forth third entry
;; point this one is meant to eventually replace, once this one reaches
;; parity and the user confirms the cutover.
;; =====================================================================

;; ---------------------------------------------------------------------
;; Quotations and word references
;; ---------------------------------------------------------------------

;; :env is nil until the quotation is actually instantiated (pushed) --
;; see compile-forms' own "(" branch, which stamps in the CURRENT
;; ctx's own :env atom at push time, not compile time. This is what
;; makes a quotation a genuine lexical closure rather than just a bundle
;; of pre-compiled steps: the SAME compiled quotation-push step runs on
;; every invocation of its enclosing word, and each invocation has its
;; own fresh :env (a new atom per execute-entry call, see below) -- the
;; quotation value has to capture ITS OWN invocation's atom at the
;; moment it's created, not its enclosing word's static compiled body,
;; or every call to the enclosing word would share one one stale env.
(defrecord Quotation [steps disp env])
(defn quotation? [v] (instance? Quotation v))

;; A word reference (\ name) -- the entry it resolved to at the moment
;; \ read it (early-bound, same as every other name lookup in this
;; kernel), plus the name itself for display.
(defrecord Wordref [entry name])
(defn wordref? [v] (instance? Wordref v))

;; ---------------------------------------------------------------------
;; Stack
;; ---------------------------------------------------------------------

(defn push! [ctx v] (swap! (:stack ctx) conj v))

(defn pop-val! [ctx]
  (let [s @(:stack ctx)]
    (when (empty? s) (throw (ex-info "Stack underflow" {})))
    (let [v (peek s)]
      (swap! (:stack ctx) pop)
      v)))

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
;; then :vocab-imports (FROM:/RENAME:), then each :vocab-uses'd
;; vocabulary (skipping whatever :vocab-exclusions says to on that
;; particular one), then "kernel" last, always implicitly in scope,
;; exactly like real Factor's own kernel vocabulary. Ambiguity among
;; several plain USE:'d vocabularies (not FROM:'d/RENAME:'d/qualified,
;; which are already unambiguous by construction -- each names its own
;; single source) resolves "first used vocabulary wins," the same
;; simplification resources/mforth.lua's own `lookup` already makes
;; over real Factor's own ambiguity-error behavior.

(defn- qualified-lookup [ctx name]
  (when-let [idx (str/index-of name ":")]
    (let [prefix (subs name 0 idx) word (subs name (inc idx))]
      (when-let [source-vocab (get (get @(:vocab-qualifiers ctx) @(:current-vocab ctx)) prefix)]
        (get (get @(:vocabularies ctx) source-vocab) word)))))

(defn lookup-word [ctx name]
  (let [vocabs @(:vocabularies ctx)
        cur-name @(:current-vocab ctx)
        cur (get vocabs cur-name)
        imports (get @(:vocab-imports ctx) cur-name)
        exclusions (get @(:vocab-exclusions ctx) cur-name)]
    (or (qualified-lookup ctx name)
        (get cur name)
        (when-let [[src-vocab src-name] (get imports name)]
          (get (get vocabs src-vocab) src-name))
        (some (fn [used]
                (when-not (contains? (get exclusions used) name)
                  (get (get vocabs used) name)))
              (get @(:vocab-uses ctx) cur-name))
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
;; A token is a plain word string ("dup", "3/4", "(", "in:", ...) or
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
                          (recur (inc j)))))]
              (recur end (conj tokens (subs source i end))))))))))

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

(declare compile-forms execute-entry display see-text where-vocab)

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
          (recur (drop 2 toks) (conj steps (fn [ctx] (swap! (:env ctx) assoc name (pop-val! ctx))))))

        (and locals (contains? @(:names locals) t))
        (recur (rest toks) (conj steps (fn [ctx] (push! ctx (get @(:env ctx) t)))))

        :else
        (if-let [entry (lookup-word ctx t)]
          (recur (rest toks) (conj steps (fn [ctx] (execute-entry entry ctx))))
          (if (num-token? t)
            (let [n (parse-num t)]
              (recur (rest toks) (conj steps (fn [ctx] (push! ctx n)))))
            (throw (ex-info (str "unknown word during compile: " t) {}))))))))

(defn run-steps [steps ctx]
  (doseq [step steps] (step ctx)))

(defn execute-entry
  "entry is {:type :primitive :fn (fn [ctx] ...)} or {:type :colon :steps
   [...] :incoming-names [...] } (:incoming-names non-nil only for a ::
   definition -- those get popped off the stack, right-to-left, into a
   fresh :env before the body runs; a plain : definition's :env starts
   empty, since its ( ... ) never binds anything). A quotation is run
   via run-steps directly against the CALLER's own ctx (same :env), not
   through execute-entry -- see call/if/etc. below -- that's what makes
   a quotation close over its enclosing word's own locals."
  [entry ctx]
  (case (:type entry)
    :primitive ((:fn entry) ctx)
    :colon
    (let [env (atom {})
          ctx' (assoc ctx :env env)]
      (doseq [nm (reverse (:incoming-names entry))]
        (swap! env assoc nm (pop-val! ctx)))
      (run-steps (:steps entry) ctx'))
    (throw (ex-info "cannot execute this entry" {:entry entry}))))

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

(defn- quot-steps [v]
  (when-not (quotation? v) (throw (ex-info "expected a quotation" {:got v})))
  (:steps v))

(defn- run-callable
  "v is a Quotation (run its steps) or a Wordref (execute its entry) --
   both are what call/if/when/unless/dip/keep/bi/tri accept, matching
   real Factor's own `call` (any callable, not just a literal quotation)."
  [v ctx]
  (cond
    ;; Runs against the quotation's OWN captured :env (its defining
    ;; word's live locals at the moment it was pushed), not the
    ;; CALLER's -- what makes this a real lexical closure, not just a
    ;; bundle of steps. See Quotation's own docstring.
    (quotation? v) (run-steps (:steps v) (assoc ctx :env (:env v)))
    (wordref? v) (execute-entry (:entry v) ctx)
    :else (throw (ex-info "expected a quotation or word reference" {:got v}))))

(defn- def-prim
  "effect is an optional stack-effect source STRING ('( x -- x x )'),
   purely descriptive (never checked/enforced, same as real Factor's
   own declared effects for a hand-written word) -- what `stack-effect`/
   `see` below read for a primitive. Retrofitted onto the kernel vocab's
   own core words (this is the 'language core' `see`/`stack-effect`
   most directly serve); left nil across the much larger musics-vocab
   bridge for now -- those already have full docstrings on their own
   musics.core fn, reachable by reading that file directly, and
   retrofitting 59 more effect strings here is a separate, lower-value
   mechanical pass, not attempted in this one."
  ([nm f] (def-prim nm f nil))
  ([nm f effect] {nm {:type :primitive :fn f :effect effect}}))

(defn- kernel-vocab []
  (merge
    ;; -- literals: Factor's own t/f, spelled as Clojure's own true/false
    ;; directly (see this ns's own header comment on booleans) ---------
    (def-prim "true" (fn [ctx] (push! ctx true)) "( -- true )")
    (def-prim "false" (fn [ctx] (push! ctx false)) "( -- false )")

    ;; -- stack shufflers ---------------------------------------------
    (def-prim "dup" (fn [ctx] (let [a (pop-val! ctx)] (push! ctx a) (push! ctx a))) "( x -- x x )")
    (def-prim "drop" (fn [ctx] (pop-val! ctx)) "( x -- )")
    (def-prim "swap" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx b) (push! ctx a))) "( a b -- b a )")
    (def-prim "over" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx a) (push! ctx b) (push! ctx a))) "( a b -- a b a )")
    (def-prim "rot" (fn [ctx] (let [c (pop-val! ctx) b (pop-val! ctx) a (pop-val! ctx)]
                                 (push! ctx b) (push! ctx c) (push! ctx a))) "( a b c -- b c a )")
    (def-prim "nip" (fn [ctx] (let [b (pop-val! ctx) _a (pop-val! ctx)] (push! ctx b))) "( a b -- b )")
    (def-prim "pick" (fn [ctx] (let [c (pop-val! ctx) b (pop-val! ctx) a (pop-val! ctx)]
                                  (push! ctx a) (push! ctx b) (push! ctx c) (push! ctx a))) "( a b c -- a b c a )")
    (def-prim "2dup" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)]
                                  (push! ctx a) (push! ctx b) (push! ctx a) (push! ctx b))) "( a b -- a b a b )")
    (def-prim "clear" (fn [ctx] (reset! (:stack ctx) [])) "( ... -- )")

    ;; -- arithmetic/comparison -- Clojure's own numeric tower already
    ;; has real exact ratios/bigints, so +/-/*// need no special casing
    ;; at all (a genuine simplification over resources/mforth.lua's own
    ;; hand-rolled rational type, see this ns's own header comment).
    ;; Comparisons push real true/false, not a -1/0 flag convention --
    ;; Factor's own boolean model IS Clojure's own truthiness already.
    (def-prim "+" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (+ a b)))) "( a b -- c )")
    (def-prim "-" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (- a b)))) "( a b -- c )")
    (def-prim "*" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (* a b)))) "( a b -- c )")
    (def-prim "/" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (/ a b)))) "( a b -- c )")
    ;; Real Factor's own mod takes the sign of the DIVIDEND (confirmed:
    ;; core/math/math-docs.factor's own HELP: mod -- "the remainder
    ;; being negative if x is negative"), the OPPOSITE of Clojure's own
    ;; mod (sign of the divisor) -- Clojure's own rem is the one that
    ;; actually matches Factor's mod here, confirmed against real
    ;; Factor's own worked example too: -7 2 mod => -1.
    (def-prim "mod" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (rem a b)))) "( x y -- z )")
    (def-prim "<" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (< a b)))) "( a b -- ? )")
    (def-prim ">" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (> a b)))) "( a b -- ? )")
    (def-prim "<=" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (<= a b)))) "( a b -- ? )")
    (def-prim ">=" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (>= a b)))) "( a b -- ? )")
    (def-prim "=" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (= a b)))) "( a b -- ? )")
    (def-prim "not" (fn [ctx] (push! ctx (not (pop-val! ctx)))) "( ? -- ? )")

    ;; -- control flow: ordinary words, quotations are the payload -----
    (def-prim "call" (fn [ctx] (run-callable (pop-val! ctx) ctx)) "( ..a quot -- ..b )")
    (def-prim "execute" (fn [ctx] (run-callable (pop-val! ctx) ctx)) "( ..a word/quot -- ..b )")
    (def-prim "if" (fn [ctx] (let [false-q (pop-val! ctx) true-q (pop-val! ctx) flag (pop-val! ctx)]
                                (run-callable (if flag true-q false-q) ctx))) "( ..a ? true-quot false-quot -- ..b )")
    (def-prim "when" (fn [ctx] (let [q (pop-val! ctx) flag (pop-val! ctx)]
                                  (when flag (run-callable q ctx)))) "( ..a ? quot -- ..b )")
    (def-prim "unless" (fn [ctx] (let [q (pop-val! ctx) flag (pop-val! ctx)]
                                    (when-not flag (run-callable q ctx)))) "( ..a ? quot -- ..b )")
    (def-prim "dip" (fn [ctx] (let [q (pop-val! ctx) x (pop-val! ctx)]
                                 (run-callable q ctx) (push! ctx x))) "( ..a x quot -- ..b x )")
    (def-prim "keep" (fn [ctx] (let [q (pop-val! ctx) x (pop-val! ctx)]
                                  (push! ctx x) (run-callable q ctx) (push! ctx x))) "( ..a x quot -- ..b x )")
    (def-prim "bi" (fn [ctx] (let [q (pop-val! ctx) p (pop-val! ctx) x (pop-val! ctx)]
                                (push! ctx x) (run-callable p ctx)
                                (push! ctx x) (run-callable q ctx))) "( x p q -- )")
    (def-prim "tri" (fn [ctx] (let [r (pop-val! ctx) q (pop-val! ctx) p (pop-val! ctx) x (pop-val! ctx)]
                                 (push! ctx x) (run-callable p ctx)
                                 (push! ctx x) (run-callable q ctx)
                                 (push! ctx x) (run-callable r ctx))) "( x p q r -- )")
    (def-prim "2dip" (fn [ctx] (let [q (pop-val! ctx) y (pop-val! ctx) x (pop-val! ctx)]
                                  (run-callable q ctx) (push! ctx x) (push! ctx y))) "( ..a x y quot -- ..b x y )")
    (def-prim "3dip" (fn [ctx] (let [q (pop-val! ctx) z (pop-val! ctx) y (pop-val! ctx) x (pop-val! ctx)]
                                  (run-callable q ctx) (push! ctx x) (push! ctx y) (push! ctx z))) "( ..a x y z quot -- ..b x y z )")
    ;; curry/compose combine ALREADY-instantiated quotations' own steps
    ;; into a new one -- :env nil (no enclosing locals of its own),
    ;; correct for the common case (composing/currying self-contained
    ;; quotations); a source quotation that itself referenced outer
    ;; locals loses that closure here -- a known, narrow limitation, not
    ;; attempted in this first pass (curry/compose over a closure-
    ;; capturing quotation is a genuine edge case real Factor's own
    ;; identical words handle via its fuller `fry`/locals machinery,
    ;; out of scope here).
    (def-prim "curry" (fn [ctx] (let [q (pop-val! ctx) obj (pop-val! ctx)]
                                   (push! ctx (->Quotation
                                                (into [(fn [ctx] (push! ctx obj))] (quot-steps q))
                                                "( curried )" nil))))
             "( obj quot -- curried )")
    (def-prim "compose" (fn [ctx] (let [q2 (pop-val! ctx) q1 (pop-val! ctx)]
                                     (push! ctx (->Quotation
                                                  (into (vec (quot-steps q1)) (quot-steps q2))
                                                  "( composed )" nil))))
             "( quot1 quot2 -- composed )")
    (def-prim "loop"
      (fn [ctx] (let [q (pop-val! ctx)]
                  (loop [] (run-callable q ctx) (when (pop-val! ctx) (recur)))))
      "( pred: ( -- ? ) -- )")

    ;; -- sequence combinators -- operate on any Clojure seqable (a
    ;; vector/list/lazy-seq returned by a musics.core bridge word, e.g.
    ;; ids/leaves/children -- literal sequence-construction syntax is
    ;; explicitly deferred, see this ns's own header comment).
    (def-prim "each" (fn [ctx] (let [q (pop-val! ctx) xs (pop-val! ctx)]
                                  (doseq [x xs] (push! ctx x) (run-callable q ctx))))
             "( seq quot -- )")
    (def-prim "map" (fn [ctx] (let [q (pop-val! ctx) xs (pop-val! ctx)]
                                 (push! ctx (mapv (fn [x] (push! ctx x) (run-callable q ctx) (pop-val! ctx)) xs))))
             "( seq quot -- newseq )")
    (def-prim "filter" (fn [ctx] (let [q (pop-val! ctx) xs (pop-val! ctx)]
                                    (push! ctx (vec (filter (fn [x] (push! ctx x) (run-callable q ctx) (pop-val! ctx)) xs)))))
             "( seq quot -- subseq )")
    (def-prim "reduce" (fn [ctx] (let [q (pop-val! ctx) init (pop-val! ctx) xs (pop-val! ctx)]
                                    (push! ctx (reduce (fn [acc x] (push! ctx acc) (push! ctx x) (run-callable q ctx) (pop-val! ctx))
                                                        init xs))))
             "( seq identity quot -- result )")

    ;; -- vocabularies -----------------------------------------------------
    ;; Only reachable from a compiled body (interpret-token! special-
    ;; cases the bare top-level token before word lookup ever runs) --
    ;; these throw a clear error rather than silently misbehaving in
    ;; that position, same "parsing words are top-level-only" limitation
    ;; real Factor's own IN:/USE:/USING: have.
    (def-prim "in:" (fn [_ctx] (throw (ex-info "in: is a parsing word, only valid at the top level" {}))))
    (def-prim "use:" (fn [_ctx] (throw (ex-info "use: is a parsing word, only valid at the top level" {}))))
    (def-prim "using:" (fn [_ctx] (throw (ex-info "using: is a parsing word, only valid at the top level" {}))))
    (def-prim "from:" (fn [_ctx] (throw (ex-info "from: is a parsing word, only valid at the top level" {}))))
    (def-prim "exclude:" (fn [_ctx] (throw (ex-info "exclude: is a parsing word, only valid at the top level" {}))))
    (def-prim "rename:" (fn [_ctx] (throw (ex-info "rename: is a parsing word, only valid at the top level" {}))))
    (def-prim "qualified:" (fn [_ctx] (throw (ex-info "qualified: is a parsing word, only valid at the top level" {}))))
    (def-prim "qualified-with:" (fn [_ctx] (throw (ex-info "qualified-with: is a parsing word, only valid at the top level" {}))))
    (def-prim "forget:" (fn [_ctx] (throw (ex-info "forget: is a parsing word, only valid at the top level" {}))))

    ;; -- vocabulary introspection -- this kernel's own convenience
    ;; additions, not claimed as verified real-Factor word names.
    (def-prim "vocabs" (fn [ctx] (push! ctx (vec (sort (keys @(:vocabularies ctx)))))) "( -- names )")
    (def-prim "words" (fn [ctx] (push! ctx (vec (sort (keys (get @(:vocabularies ctx) @(:current-vocab ctx))))))) "( -- names )")
    (def-prim "vocab" (fn [ctx] (push! ctx @(:current-vocab ctx))) "( -- name )")
    (def-prim "parsing?" (fn [ctx] (push! ctx @(:parsing? ctx))) "( -- ? )")
    (def-prim "compiling?" (fn [ctx] (push! ctx @(:compiling? ctx))) "( -- ? )")
    (def-prim "interpreting?" (fn [ctx] (push! ctx (and (not @(:parsing? ctx)) (not @(:compiling? ctx))))) "( -- ? )")

    ;; -- code inspection -- see/where/stack-effect, real Factor's own
    ;; words (verified against a local real-Factor source checkout's own
    ;; basis/see/see-docs.factor, core/definitions/definitions-docs.factor,
    ;; core/effects/effects-docs.factor), all three taking a WORD
    ;; REFERENCE (\ name -- the only "a word as a value" this kernel
    ;; has, real Factor's own convention too: `\ append see`). where's
    ;; own real effect is `( defspec -- loc )`, loc a { path line# }
    ;; pair or f "if the location is not known" -- this kernel has no
    ;; file-based loading at all yet (everything arrives as typed/fed
    ;; text, see input.forth's own identical "no on-disk module loader"
    ;; note), so there's no path/line# to report; the vocabulary name is
    ;; the closest real, honest analog (found by scanning every
    ;; vocabulary for the one whose own map holds this exact entry --
    ;; one mechanism covers a :colon word and a :primitive alike, no
    ;; need to separately stamp :vocab onto every entry at definition
    ;; time), or false when the entry isn't found in any (shouldn't
    ;; happen for a \-produced wordref, kept as an honest fallback
    ;; rather than an assumption).
    (def-prim "see"
      (fn [ctx] (print (see-text (pop-val! ctx))) (flush))
      "( defspec -- )")
    (def-prim "where"
      (fn [ctx] (push! ctx (or (where-vocab ctx (pop-val! ctx)) false)))
      "( defspec -- loc )")
    (def-prim "stack-effect"
      (fn [ctx] (push! ctx (or (:effect (:entry (pop-val! ctx))) false)))
      "( word -- effect/f )")

    ;; -- print -----------------------------------------------------------
    (def-prim "." (fn [ctx] (print (display (pop-val! ctx))) (print " ") (flush)) "( value -- )")
    (def-prim ".s" (fn [ctx] (print (str/join " " (map display @(:stack ctx)))) (print " ") (flush)) "( -- )")
    (def-prim "print" (fn [ctx] (print (pop-val! ctx)) (flush)) "( str -- )")
    (def-prim "nl" (fn [_ctx] (println)) "( -- )")))

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
   primitives rather than pretending they have an ordinary : body."
  [wordref]
  (let [{:keys [entry name]} wordref
        effect (:effect entry)]
    (case (:type entry)
      :colon (str (if (:binding? entry) ":: " ": ") name
                   (when effect (str " " effect))
                   " " (:body-text entry) " ;")
      :primitive (str "PRIMITIVE: " name (when effect (str " " effect)))
      (str "unknown entry: " (pr-str entry)))))

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

;; ---------------------------------------------------------------------
;; musics.core bridge -- everything below exists only because this
;; kernel also hosts musics text. Mechanical translation of input.forth's
;; own musics-prims (same 59 words, same argument-marshaling
;; conventions -- ->kw/token->fn/callable-arg -- just lowercased and
;; moved into their own "musics" vocabulary instead of a shared flat
;; dictionary, and #: ... ; ("parsing mode," see this ns's own header
;; comment) replacing input.forth's own bare-bracket-auto-detection for
;; how musics text gets onto the stack in the first place.
;; ---------------------------------------------------------------------

(defn- ->kw
  "String -> keyword; anything else (a keyword already, a number, ...)
   passes through unchanged -- see input.forth's own identical helper
   for the full argument-marshaling rationale (conductor/wall ids
   compare with plain =/keyword?, so a bare string silently never
   matches without this)."
  [x]
  (if (string? x) (keyword x) x))

(defn- callable->fn
  "A Wordref or a real Clojure fn -> a plain Clojure fn against ctx's own
   stack (each call arg pushed, the callable run, whatever it leaves on
   top becomes the Clojure-level return value) -- for musics.core words
   that take a callback (thread, register-action!, register-factory!)."
  [ctx v]
  (cond
    (wordref? v) (fn [& args]
                   (doseq [a args] (push! ctx a))
                   (execute-entry (:entry v) ctx)
                   (when (seq @(:stack ctx)) (pop-val! ctx)))
    (ifn? v) v
    :else (throw (ex-info "expected a fn or a word reference (\\ name)" {:got v}))))

(defn- musics-vocab []
  (merge
    ;; -- parse (commits immediately) -----------------------------------
    (def-prim "parse" (fn [ctx] (push! ctx (m/parse (pop-val! ctx)))))
    ;; The one place :parsing? is genuinely true -- #: ... ; itself is
    ;; already resolved by the tokenizer (no ctx exists there, see
    ;; tokenize's own header comment on why that span has to be captured
    ;; before ordinary word-tokenization ever touches it), so this is
    ;; the first point real interpreter state is available for it.
    (def-prim "parse-notation" (fn [ctx] (try
                                            (reset! (:parsing? ctx) true)
                                            (push! ctx (m/parse (pop-val! ctx)))
                                            (finally (reset! (:parsing? ctx) false)))))
    (def-prim "s!" (fn [ctx] (push! ctx (m/s! (pop-val! ctx)))))
    (def-prim "try-parse" (fn [ctx] (push! ctx (m/try-parse (pop-val! ctx)))))
    (def-prim "parse-file" (fn [ctx] (push! ctx (m/parse-file (pop-val! ctx)))))
    (def-prim ">ids" (fn [ctx] (push! ctx (:ids (pop-val! ctx)))))

    ;; -- registry / navigation / inspection -----------------------------
    (def-prim "find" (fn [ctx] (push! ctx (m/find (->kw (pop-val! ctx))))))
    (def-prim "ids" (fn [ctx] (push! ctx (m/ids))))
    (def-prim "root-children" (fn [ctx] (push! ctx (m/root-children))))
    (def-prim "children" (fn [ctx] (push! ctx (m/children (->kw (pop-val! ctx))))))
    (def-prim "leaves" (fn [ctx] (push! ctx (m/leaves (->kw (pop-val! ctx))))))
    (def-prim "sq" (fn [ctx] (push! ctx (m/sq (->kw (pop-val! ctx))))))
    (def-prim "inspect" (fn [ctx] (m/inspect (->kw (pop-val! ctx)))))
    (def-prim "inspect-all" (fn [_ctx] (m/inspect)))
    (def-prim "ctx" (fn [ctx] (m/ctx (->kw (pop-val! ctx)))))
    (def-prim "ctx-value" (fn [ctx] (let [time (pop-val! ctx) key (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                       (push! ctx (m/ctx-value id key time)))))
    (def-prim "locate" (fn [ctx] (let [path (pop-val! ctx) id (->kw (pop-val! ctx))]
                                    (push! ctx (m/locate id path)))))
    (def-prim "describe" (fn [ctx] (push! ctx (m/describe (->kw (pop-val! ctx))))))
    (def-prim "print-structure" (fn [ctx] (m/print-structure (->kw (pop-val! ctx)))))
    (def-prim "expand" (fn [ctx] (push! ctx (m/expand (pop-val! ctx)))))

    ;; -- MIDI / playback -------------------------------------------------
    (def-prim "connect" (fn [_ctx] (m/connect)))
    (def-prim "warm-up!" (fn [_ctx] (m/warm-up!)))
    (def-prim "warm-up-n!" (fn [ctx] (let [ms (pop-val! ctx) n (pop-val! ctx)] (m/warm-up! n ms))))
    (def-prim "disconnect" (fn [_ctx] (m/disconnect)))
    (def-prim "play" (fn [ctx] (m/play (->kw (pop-val! ctx)))))
    (def-prim "play-add" (fn [ctx] (push! ctx (m/play-add (->kw (pop-val! ctx))))))
    (def-prim "play-change" (fn [ctx] (let [arg (->kw (pop-val! ctx)) path (->kw (pop-val! ctx))]
                                         (push! ctx (m/play-change path arg)))))
    (def-prim "voice-at" (fn [ctx] (push! ctx (m/voice-at (->kw (pop-val! ctx))))))
    (def-prim "play-file!" (fn [ctx] (m/play-file! (pop-val! ctx))))
    (def-prim "display" (fn [ctx] (push! ctx (m/display (->kw (pop-val! ctx))))))
    (def-prim "stop!" (fn [_ctx] (m/stop!)))
    (def-prim "pause!" (fn [_ctx] (m/pause!)))
    (def-prim "resume!" (fn [_ctx] (m/resume!)))
    (def-prim "all-notes-off" (fn [_ctx] (m/all-notes-off)))
    (def-prim "play!" (fn [ctx] (let [v (pop-val! ctx)
                                       {:keys [ids]} (if (string? v) (m/parse v) v)]
                                   (m/play (vec ids)))))
    (def-prim "p!" (fn [ctx] (m/p! (pop-val! ctx))))

    ;; -- generative transforms -------------------------------------------
    (def-prim "times" (fn [ctx] (let [material (pop-val! ctx) n (pop-val! ctx)]
                                   (push! ctx (m/times n material)))))
    (def-prim "transpose" (fn [ctx] (let [material (pop-val! ctx) semitones (pop-val! ctx)]
                                       (push! ctx (m/transpose semitones material)))))
    (def-prim "invert" (fn [ctx] (let [material (pop-val! ctx) axis (pop-val! ctx)]
                                    (push! ctx (m/invert axis material)))))
    (def-prim "invert-mean" (fn [ctx] (push! ctx (m/invert (pop-val! ctx)))))
    (def-prim "scale" (fn [ctx] (let [material (pop-val! ctx) factor (pop-val! ctx)]
                                   (push! ctx (m/scale factor material)))))
    (def-prim "reverse" (fn [ctx] (push! ctx (m/reverse (pop-val! ctx)))))
    (def-prim "shuffle" (fn [ctx] (push! ctx (m/shuffle (pop-val! ctx)))))
    (def-prim "thread" (fn [ctx] (let [material (pop-val! ctx) f (callable->fn ctx (pop-val! ctx))]
                                    (push! ctx (m/thread f material)))))
    (def-prim "active-key" (fn [ctx] (push! ctx (m/active-key (->kw (pop-val! ctx))))))
    (def-prim "tonal-transpose" (fn [ctx] (let [material (pop-val! ctx) steps (pop-val! ctx) ks (pop-val! ctx)]
                                             (push! ctx (m/tonal-transpose ks steps material)))))
    (def-prim "tonal-invert" (fn [ctx] (let [material (pop-val! ctx) axis (pop-val! ctx) ks (pop-val! ctx)]
                                          (push! ctx (m/tonal-invert ks axis material)))))
    (def-prim "snap-to-scale" (fn [ctx] (let [material (pop-val! ctx) ks (pop-val! ctx)]
                                           (push! ctx (m/snap-to-scale ks material)))))
    (def-prim "tonal-harmonize" (fn [ctx] (let [material (pop-val! ctx) steps (pop-val! ctx) ks (pop-val! ctx)]
                                             (push! ctx (m/tonal-harmonize ks steps material)))))

    ;; -- variables --------------------------------------------------------
    (def-prim "clear-vars" (fn [_ctx] (m/clear-vars)))

    ;; -- persistence --------------------------------------------------------
    (def-prim "write" (fn [ctx] (m/write (pop-val! ctx))))
    (def-prim "load" (fn [ctx] (m/load (pop-val! ctx))))
    (def-prim "ly-to-mus" (fn [ctx] (push! ctx (m/ly-to-mus (pop-val! ctx)))))

    ;; -- reset / help -------------------------------------------------------
    (def-prim "reset" (fn [_ctx] (m/reset)))
    (def-prim "help" (fn [_ctx] (m/help)))
    (def-prim "help?" (fn [ctx] (m/help (pop-val! ctx))))

    ;; -- wall (per-voice playback algorithms) --------------------------------
    (def-prim "register-factory!" (fn [ctx] (let [f (callable->fn ctx (pop-val! ctx)) nm (->kw (pop-val! ctx))]
                                               (m/register-factory! nm f))))
    (def-prim "register-factory-doc!" (fn [ctx] (let [doc (pop-val! ctx) f (callable->fn ctx (pop-val! ctx))
                                                       nm (->kw (pop-val! ctx))]
                                                   (m/register-factory! nm f doc))))
    (def-prim "unregister-factory!" (fn [ctx] (m/unregister-factory! (->kw (pop-val! ctx)))))
    (def-prim "factories" (fn [ctx] (push! ctx (m/factories))))
    (def-prim "factories?" (fn [ctx] (push! ctx (m/factories (->kw (pop-val! ctx))))))
    (def-prim "unregister-algo!" (fn [ctx] (m/unregister-algo! (->kw (pop-val! ctx)))))
    (def-prim "algos" (fn [ctx] (push! ctx (m/algos))))
    (def-prim "algos?" (fn [ctx] (push! ctx (m/algos (->kw (pop-val! ctx))))))
    (def-prim "assign-algo!" (fn [ctx] (let [nm (->kw (pop-val! ctx)) path (->kw (pop-val! ctx))]
                                          (m/assign-algo! path nm))))
    (def-prim "algo-assignments" (fn [ctx] (push! ctx (m/algo-assignments))))
    (def-prim "build!" (fn [ctx] (let [params (pop-val! ctx) factory-name (->kw (pop-val! ctx)) nm (->kw (pop-val! ctx))]
                                    (push! ctx (m/build! nm factory-name params)))))
    (def-prim "build-algo!" (fn [ctx] (let [f (callable->fn ctx (pop-val! ctx)) nm (->kw (pop-val! ctx))]
                                         (push! ctx (m/build-algo! nm f)))))

    ;; -- action registry / schedule -------------------------------------------
    (def-prim "register-action!" (fn [ctx] (let [f (callable->fn ctx (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                              (m/register-action! id f))))
    (def-prim "unregister-action!" (fn [ctx] (m/unregister-action! (->kw (pop-val! ctx)))))
    (def-prim "trigger!" (fn [ctx] (let [arg (pop-val! ctx) id (->kw (pop-val! ctx))]
                                      (push! ctx (m/trigger! id arg)))))
    (def-prim "schedule!" (fn [ctx] (let [action-id (->kw (pop-val! ctx)) phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                       (m/schedule! id phase action-id))))
    (def-prim "unschedule!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                         (m/unschedule! id phase))))
    (def-prim "scheduled" (fn [ctx] (push! ctx (m/scheduled))))
    (def-prim "scheduled?" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                        (push! ctx (m/scheduled id phase)))))
    (def-prim "scheduled-repeating" (fn [ctx] (push! ctx (m/scheduled-repeating))))
    (def-prim "scheduled-repeating?" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                                  (push! ctx (m/scheduled-repeating id phase)))))
    (def-prim "unschedule-repeating!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                                   (m/unschedule-repeating! id phase))))
    (def-prim "schedule-tx!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                          (push! ctx (m/schedule-tx! id phase)))))

    ;; -- misc / state ---------------------------------------------------
    (def-prim "music-eval" (fn [ctx] (push! ctx (m/music-eval (pop-val! ctx)))))
    (def-prim "session" (fn [ctx] (push! ctx @m/session)))
    (def-prim "receiver" (fn [ctx] (push! ctx @m/receiver)))))

;; ---------------------------------------------------------------------
;; Top level: interpret a stream of tokens
;; ---------------------------------------------------------------------

(defn interpret-token! [ctx t toks]
  (cond
    (= t ":") (compile-definition! ctx toks false)
    (= t "::") (compile-definition! ctx toks true)

    (= t "in:")
    (let [name (first toks)]
      (when-not name (throw (ex-info "in: expected a vocabulary name" {})))
      (ensure-vocab! ctx name)
      (reset! (:current-vocab ctx) name)
      (rest toks))

    (= t "use:")
    (let [name (first toks)]
      (when-not name (throw (ex-info "use: expected a vocabulary name" {})))
      (use-vocab! ctx name)
      (rest toks))

    (= t "using:")
    (loop [toks toks]
      (let [name (first toks)]
        (cond
          (nil? name) (throw (ex-info "using: expected a terminating ';'" {}))
          (= name ";") (rest toks)
          :else (do (use-vocab! ctx name) (recur (rest toks))))))

    ;; FROM: vocab => word1 word2 ... ; -- import only these specific
    ;; words, taking precedence over a plain USE:/USING: on a collision
    ;; (see this ns's own Vocabularies header comment for the confirmed
    ;; real-Factor precedence rule).
    (= t "from:")
    (let [vocab (first toks) arrow (second toks)]
      (when-not (= arrow "=>") (throw (ex-info "from: expected 'vocab => word...'" {})))
      (ensure-vocab! ctx vocab)
      (loop [toks (drop 2 toks)]
        (let [w (first toks)]
          (cond
            (nil? w) (throw (ex-info "from: expected a terminating ';'" {}))
            (= w ";") (rest toks)
            :else (do (import-word! ctx w vocab w) (recur (rest toks)))))))

    ;; EXCLUDE: vocab => word1 word2 ... ; -- import all of vocab's
    ;; words EXCEPT these.
    (= t "exclude:")
    (let [vocab (first toks) arrow (second toks)]
      (when-not (= arrow "=>") (throw (ex-info "exclude: expected 'vocab => word...'" {})))
      (use-vocab! ctx vocab)
      (loop [toks (drop 2 toks)]
        (let [w (first toks)]
          (cond
            (nil? w) (throw (ex-info "exclude: expected a terminating ';'" {}))
            (= w ";") (rest toks)
            :else (do (exclude-word! ctx vocab w) (recur (rest toks)))))))

    ;; RENAME: word vocab => new-name -- fixed 4-token form, no
    ;; terminating ';' (confirmed real Factor's own $syntax).
    (= t "rename:")
    (let [word (first toks) vocab (second toks) arrow (nth toks 2 nil) new-name (nth toks 3 nil)]
      (when-not (= arrow "=>") (throw (ex-info "rename: expected 'word vocab => new-name'" {})))
      (when-not new-name (throw (ex-info "rename: expected a new name" {})))
      (ensure-vocab! ctx vocab)
      (import-word! ctx new-name vocab word)
      (drop 4 toks))

    ;; QUALIFIED: vocab -- vocab's own words reachable as vocab:word.
    (= t "qualified:")
    (let [vocab (first toks)]
      (when-not vocab (throw (ex-info "qualified: expected a vocabulary name" {})))
      (qualify-vocab! ctx vocab vocab)
      (rest toks))

    ;; QUALIFIED-WITH: vocab prefix -- vocab's own words reachable as
    ;; prefix:word instead of vocab:word.
    (= t "qualified-with:")
    (let [vocab (first toks) prefix (second toks)]
      (when-not (and vocab prefix) (throw (ex-info "qualified-with: expected 'vocab prefix'" {})))
      (qualify-vocab! ctx prefix vocab)
      (drop 2 toks))

    ;; FORGET: word -- see forget-word!'s own docstring for the one
    ;; deliberate simplification from real Factor's own version.
    (= t "forget:")
    (let [name (first toks)]
      (when-not name (throw (ex-info "forget: expected a word name" {})))
      (forget-word! ctx name)
      (rest toks))

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

(defn make-ctx []
  (let [ctx {:stack (atom [])
             :vocabularies (atom {"kernel" (kernel-vocab)
                                   "musics" (musics-vocab)
                                   "scratchpad" {}})
             :vocab-uses (atom {"scratchpad" #{"musics"}})
             :vocab-imports (atom {})
             :vocab-exclusions (atom {})
             :vocab-qualifiers (atom {})
             :current-vocab (atom "scratchpad")
             ;; The only two real mode flags -- no separate :interpreting
             ;; flag exists at all: interpreting IS just both of these
             ;; false, not a third stored state.
             :parsing? (atom false)
             :compiling? (atom false)}]
    ctx))

(defn run-string [ctx s]
  (interpret-all! ctx (tokenize s)))

;; ---------------------------------------------------------------------
;; REPL
;; ---------------------------------------------------------------------

(defn- forth-exit! [] (throw (ex-info "musics-lang-exit" {:musics-lang/exit? true})))

(defn run-repl-loop
  "Print prompt, read a line, run-string it, print \" ok\" (or an error),
   repeat -- until EOF (Ctrl-D) or 'bye' throws the exit signal. Mirrors
   input.forth's own run-repl-loop exactly (same shape, same reasons)."
  [ctx prompt]
  (define-word! ctx "bye" {:type :primitive :fn (fn [_ctx] (forth-exit!))})
  (loop []
    (print prompt) (flush)
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
  (run-repl-loop (make-ctx) "> "))

(defn repl!
  "Drop into a nested musics-lang REPL loop from within an already-
   running Clojure REPL -- mirrors input.forth's own repl!. Shares
   core.repo/musics.core's session with the outer REPL (defonce
   singletons); only the kernel-level state (vocabularies, stack) is
   fresh per call.

   (musics.lang/repl!) from a Clojure REPL; `bye` (or Ctrl-D) to return."
  []
  (println "musics-lang REPL. Ctrl-D or `bye` to return to the Clojure REPL.")
  (run-repl-loop (make-ctx) "musics> ")
  (println "Back to the Clojure REPL.")
  nil)
