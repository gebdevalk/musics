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
   own declared effects for a hand-written word) -- what `stack-effect`
   reads for a primitive. doc is an optional ONE-LINE description of
   what the word actually DOES (distinct from effect, which only
   describes shape, not meaning) -- what `word-doc`/`see` read. Every
   primitive in this file -- kernel AND the musics.core bridge alike --
   now carries both."
  ([nm f] (def-prim nm f nil nil))
  ([nm f effect] (def-prim nm f effect nil))
  ([nm f effect doc] {nm {:type :primitive :fn f :effect effect :doc doc}}))

(defn- gcd*
  "Clojure has no built-in gcd -- a plain Euclidean algorithm, always
   non-negative, same convention most languages' own integer gcd uses."
  [a b]
  (if (zero? b) (abs (long a)) (recur b (mod a b))))

(defn- kernel-vocab []
  (merge
    ;; -- literals: Factor's own t/f, spelled as Clojure's own true/false
    ;; directly (see this ns's own header comment on booleans) ---------
    (def-prim "true" (fn [ctx] (push! ctx true)) "( -- true )" "pushes the true singleton")
    (def-prim "false" (fn [ctx] (push! ctx false)) "( -- false )" "pushes the false singleton -- the only falsy value")

    ;; -- stack shufflers ---------------------------------------------
    (def-prim "dup" (fn [ctx] (let [a (pop-val! ctx)] (push! ctx a) (push! ctx a))) "( x -- x x )" "duplicates the top of the stack")
    (def-prim "drop" (fn [ctx] (pop-val! ctx)) "( x -- )" "discards the top of the stack")
    (def-prim "swap" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx b) (push! ctx a))) "( a b -- b a )" "swaps the top two stack items")
    (def-prim "over" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx a) (push! ctx b) (push! ctx a))) "( a b -- a b a )" "copies the second item to the top")
    (def-prim "rot" (fn [ctx] (let [c (pop-val! ctx) b (pop-val! ctx) a (pop-val! ctx)]
                                 (push! ctx b) (push! ctx c) (push! ctx a))) "( a b c -- b c a )" "rotates the top three items left")
    (def-prim "nip" (fn [ctx] (let [b (pop-val! ctx) _a (pop-val! ctx)] (push! ctx b))) "( a b -- b )" "discards the second item, keeping the top")
    (def-prim "pick" (fn [ctx] (let [c (pop-val! ctx) b (pop-val! ctx) a (pop-val! ctx)]
                                  (push! ctx a) (push! ctx b) (push! ctx c) (push! ctx a))) "( a b c -- a b c a )" "copies the third item to the top")
    (def-prim "2dup" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)]
                                  (push! ctx a) (push! ctx b) (push! ctx a) (push! ctx b))) "( a b -- a b a b )" "duplicates the top two items as a pair")
    (def-prim "clear" (fn [ctx] (reset! (:stack ctx) [])) "( ... -- )" "empties the entire stack")

    ;; -- arithmetic/comparison -- Clojure's own numeric tower already
    ;; has real exact ratios/bigints, so +/-/*// need no special casing
    ;; at all (a genuine simplification over resources/mforth.lua's own
    ;; hand-rolled rational type, see this ns's own header comment).
    ;; Comparisons push real true/false, not a -1/0 flag convention --
    ;; Factor's own boolean model IS Clojure's own truthiness already.
    (def-prim "+" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (+ a b)))) "( a b -- c )" "adds two numbers")
    (def-prim "-" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (- a b)))) "( a b -- c )" "subtracts b from a")
    (def-prim "*" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (* a b)))) "( a b -- c )" "multiplies two numbers")
    (def-prim "/" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (/ a b)))) "( a b -- c )" "divides a by b, exact for integers/ratios")
    ;; mod/rem/floor/neg/abs/gcd all behave exactly as Clojure's own
    ;; built-ins do -- a deliberate choice, not real Factor's own
    ;; convention (real Factor's own mod actually takes the sign of the
    ;; DIVIDEND, the opposite of Clojure's; this kernel used to match
    ;; that, but "data types are Clojure's" now extends to arithmetic
    ;; behavior too, so mod/rem below are Clojure's own, unmodified).
    (def-prim "mod" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (mod a b)))) "( x y -- z )" "remainder of x/y, sign of y (Clojure's own mod)")
    (def-prim "rem" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (rem a b)))) "( x y -- z )" "remainder of x/y, sign of x (Clojure's own rem)")
    (def-prim "/mod" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (quot a b)) (push! ctx (rem a b)))) "( x y -- q r )" "truncated quotient and remainder together, consistent with each other")
    (def-prim "neg" (fn [ctx] (push! ctx (- (pop-val! ctx)))) "( x -- -x )" "negates a number")
    (def-prim "abs" (fn [ctx] (push! ctx (abs (pop-val! ctx)))) "( x -- |x| )" "absolute value")
    (def-prim "gcd" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (gcd* a b)))) "( a b -- c )" "greatest common divisor")
    (def-prim "floor" (fn [ctx] (push! ctx (long (Math/floor (double (pop-val! ctx)))))) "( x -- y )" "largest integer not greater than x")
    (def-prim "<" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (< a b)))) "( a b -- ? )" "true if a is less than b")
    (def-prim ">" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (> a b)))) "( a b -- ? )" "true if a is greater than b")
    (def-prim "<=" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (<= a b)))) "( a b -- ? )" "true if a is less than or equal to b")
    (def-prim ">=" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (>= a b)))) "( a b -- ? )" "true if a is greater than or equal to b")
    (def-prim "=" (fn [ctx] (let [b (pop-val! ctx) a (pop-val! ctx)] (push! ctx (= a b)))) "( a b -- ? )" "true if a and b are equal")
    (def-prim "not" (fn [ctx] (push! ctx (not (pop-val! ctx)))) "( ? -- ? )" "flips true/false")

    ;; -- control flow: ordinary words, quotations are the payload -----
    (def-prim "call" (fn [ctx] (run-callable (pop-val! ctx) ctx)) "( ..a quot -- ..b )" "runs a quotation")
    (def-prim "execute" (fn [ctx] (run-callable (pop-val! ctx) ctx)) "( ..a word/quot -- ..b )" "runs a word reference or quotation")
    (def-prim "if" (fn [ctx] (let [false-q (pop-val! ctx) true-q (pop-val! ctx) flag (pop-val! ctx)]
                                (run-callable (if flag true-q false-q) ctx))) "( ..a ? true-quot false-quot -- ..b )" "runs one quotation or the other, by a boolean")
    (def-prim "when" (fn [ctx] (let [q (pop-val! ctx) flag (pop-val! ctx)]
                                  (when flag (run-callable q ctx)))) "( ..a ? quot -- ..b )" "runs the quotation only if the flag is true")
    (def-prim "unless" (fn [ctx] (let [q (pop-val! ctx) flag (pop-val! ctx)]
                                    (when-not flag (run-callable q ctx)))) "( ..a ? quot -- ..b )" "runs the quotation only if the flag is false")
    (def-prim "dip" (fn [ctx] (let [q (pop-val! ctx) x (pop-val! ctx)]
                                 (run-callable q ctx) (push! ctx x))) "( ..a x quot -- ..b x )" "runs the quotation with x removed, then restores x on top")
    (def-prim "keep" (fn [ctx] (let [q (pop-val! ctx) x (pop-val! ctx)]
                                  (push! ctx x) (run-callable q ctx) (push! ctx x))) "( ..a x quot -- ..b x )" "runs the quotation on x, then restores the original x after")
    (def-prim "bi" (fn [ctx] (let [q (pop-val! ctx) p (pop-val! ctx) x (pop-val! ctx)]
                                (push! ctx x) (run-callable p ctx)
                                (push! ctx x) (run-callable q ctx))) "( x p q -- )" "applies p, then q, each to the same original x")
    (def-prim "tri" (fn [ctx] (let [r (pop-val! ctx) q (pop-val! ctx) p (pop-val! ctx) x (pop-val! ctx)]
                                 (push! ctx x) (run-callable p ctx)
                                 (push! ctx x) (run-callable q ctx)
                                 (push! ctx x) (run-callable r ctx))) "( x p q r -- )" "applies p, q, then r, each to the same original x")
    (def-prim "2dip" (fn [ctx] (let [q (pop-val! ctx) y (pop-val! ctx) x (pop-val! ctx)]
                                  (run-callable q ctx) (push! ctx x) (push! ctx y))) "( ..a x y quot -- ..b x y )" "runs the quotation with x y removed, then restores them")
    (def-prim "3dip" (fn [ctx] (let [q (pop-val! ctx) z (pop-val! ctx) y (pop-val! ctx) x (pop-val! ctx)]
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
    (def-prim "curry" (fn [ctx] (let [q (pop-val! ctx) obj (pop-val! ctx)]
                                   (push! ctx (->Quotation
                                                (into [(fn [ctx] (push! ctx obj))] (quot-steps q))
                                                "( curried )" nil))))
             "( obj quot -- curried )" "builds a new quotation that pushes obj, then runs quot")
    (def-prim "compose" (fn [ctx] (let [q2 (pop-val! ctx) q1 (pop-val! ctx)]
                                     (push! ctx (->Quotation
                                                  (into (vec (quot-steps q1)) (quot-steps q2))
                                                  "( composed )" nil))))
             "( quot1 quot2 -- composed )" "builds a new quotation that runs quot1 then quot2")
    (def-prim "loop"
      (fn [ctx] (let [q (pop-val! ctx)]
                  (loop [] (run-callable q ctx) (when (pop-val! ctx) (recur)))))
      "( pred: ( -- ? ) -- )" "runs pred repeatedly until it leaves false on the stack")

    ;; -- sequence combinators -- operate on any Clojure seqable: a
    ;; literal [ ]/{ }/#{ } (see this ns's own header comment), or a
    ;; vector/list/lazy-seq returned by a musics.core bridge word (e.g.
    ;; ids/leaves/children).
    (def-prim "each" (fn [ctx] (let [q (pop-val! ctx) xs (pop-val! ctx)]
                                  (doseq [x xs] (push! ctx x) (run-callable q ctx))))
             "( seq quot -- )" "runs quot once per element, for side effects")
    (def-prim "map" (fn [ctx] (let [q (pop-val! ctx) xs (pop-val! ctx)]
                                 (push! ctx (mapv (fn [x] (push! ctx x) (run-callable q ctx) (pop-val! ctx)) xs))))
             "( seq quot -- newseq )" "builds a new sequence by running quot on each element")
    (def-prim "filter" (fn [ctx] (let [q (pop-val! ctx) xs (pop-val! ctx)]
                                    (push! ctx (vec (filter (fn [x] (push! ctx x) (run-callable q ctx) (pop-val! ctx)) xs)))))
             "( seq quot -- subseq )" "keeps only the elements quot leaves true for")
    (def-prim "reduce" (fn [ctx] (let [q (pop-val! ctx) init (pop-val! ctx) xs (pop-val! ctx)]
                                    (push! ctx (reduce (fn [acc x] (push! ctx acc) (push! ctx x) (run-callable q ctx) (pop-val! ctx))
                                                        init xs))))
             "( seq identity quot -- result )" "folds the sequence down to one value with quot")

    ;; -- sequence/assoc accessors -- direct, thin wrappers over
    ;; Clojure's own core fns, Clojure's own arg order (collection
    ;; first) rather than Factor's own collection-last convention --
    ;; "data types are Clojure's" extends to how they're USED here too,
    ;; not just how they're spelled.
    (def-prim "nth" (fn [ctx] (let [n (pop-val! ctx) coll (pop-val! ctx)] (push! ctx (nth coll n nil)))) "( coll n -- elt/nil )" "the nth element, 0-indexed")
    (def-prim "get" (fn [ctx] (let [k (pop-val! ctx) m (pop-val! ctx)] (push! ctx (get m k)))) "( map key -- value/nil )" "looks a key up in a map")
    (def-prim "assoc" (fn [ctx] (let [v (pop-val! ctx) k (pop-val! ctx) m (pop-val! ctx)] (push! ctx (assoc m k v)))) "( map key value -- map' )" "a new map with key set to value")
    (def-prim "conj" (fn [ctx] (let [x (pop-val! ctx) coll (pop-val! ctx)] (push! ctx (conj coll x)))) "( coll x -- coll' )" "a new collection with x added")
    (def-prim "first" (fn [ctx] (push! ctx (first (pop-val! ctx)))) "( coll -- x/nil )" "the first element")
    (def-prim "count" (fn [ctx] (push! ctx (count (pop-val! ctx)))) "( coll -- n )" "how many elements")

    ;; -- vocabularies -----------------------------------------------------
    ;; Only reachable from a compiled body (interpret-token! special-
    ;; cases the bare top-level token before word lookup ever runs) --
    ;; these throw a clear error rather than silently misbehaving in
    ;; that position, same "parsing words are top-level-only" limitation
    ;; real Factor's own IN:/USE:/USING: have.
    (def-prim "IN:" (fn [_ctx] (throw (ex-info "IN: is a parsing word, only valid at the top level" {})))
             nil "sets which vocabulary new definitions land in")
    (def-prim "USE:" (fn [_ctx] (throw (ex-info "USE: is a parsing word, only valid at the top level" {})))
             nil "brings one whole vocabulary's words into scope")
    (def-prim "USING:" (fn [_ctx] (throw (ex-info "USING: is a parsing word, only valid at the top level" {})))
             nil "USE: for several vocabularies at once, ended by ;")
    (def-prim "FROM:" (fn [_ctx] (throw (ex-info "FROM: is a parsing word, only valid at the top level" {})))
             nil "imports only the named words from one vocabulary")
    (def-prim "EXCLUDE:" (fn [_ctx] (throw (ex-info "EXCLUDE: is a parsing word, only valid at the top level" {})))
             nil "imports a whole vocabulary except the named words")
    (def-prim "RENAME:" (fn [_ctx] (throw (ex-info "RENAME: is a parsing word, only valid at the top level" {})))
             nil "imports one word from a vocabulary under a new name")
    (def-prim "QUALIFIED:" (fn [_ctx] (throw (ex-info "QUALIFIED: is a parsing word, only valid at the top level" {})))
             nil "makes a vocabulary reachable as vocab:word")
    (def-prim "QUALIFIED-WITH:" (fn [_ctx] (throw (ex-info "QUALIFIED-WITH: is a parsing word, only valid at the top level" {})))
             nil "QUALIFIED: with a custom prefix instead of the vocab's own name")
    (def-prim "FORGET:" (fn [_ctx] (throw (ex-info "FORGET: is a parsing word, only valid at the top level" {})))
             nil "removes a word from the current vocabulary")
    (def-prim "HELP:" (fn [_ctx] (throw (ex-info "HELP: is a parsing word, only valid at the top level" {})))
             nil "attaches a one-line description to an already-defined word")

    ;; -- vocabulary introspection -- this kernel's own convenience
    ;; additions, not claimed as verified real-Factor word names.
    (def-prim "vocabs" (fn [ctx] (push! ctx (vec (sort (keys @(:vocabularies ctx)))))) "( -- names )" "lists every known vocabulary's own name")
    (def-prim "words" (fn [ctx] (push! ctx (vec (sort (keys (get @(:vocabularies ctx) @(:current-vocab ctx))))))) "( -- names )" "lists the current vocabulary's own word names")
    (def-prim "vocab" (fn [ctx] (push! ctx @(:current-vocab ctx))) "( -- name )" "pushes the current vocabulary's own name")
    (def-prim "parsing?" (fn [ctx] (push! ctx @(:parsing? ctx))) "( -- ? )" "true while a #: ... ; musics-text span is being parsed")
    (def-prim "compiling?" (fn [ctx] (push! ctx @(:compiling? ctx))) "( -- ? )" "true while a : or :: word's own body is being compiled")
    (def-prim "interpreting?" (fn [ctx] (push! ctx (and (not @(:parsing? ctx)) (not @(:compiling? ctx))))) "( -- ? )" "true whenever neither compiling? nor parsing? is")

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
      "( defspec -- )" "prints a word's own reconstructed definition and doc")
    (def-prim "where"
      (fn [ctx] (push! ctx (or (where-vocab ctx (pop-val! ctx)) false)))
      "( defspec -- loc )" "reports which vocabulary a word is defined in")
    (def-prim "stack-effect"
      (fn [ctx] (push! ctx (or (:effect (:entry (pop-val! ctx))) false)))
      "( word -- effect/f )" "a word's own declared stack effect, or f if none was given")
    (def-prim "word-doc"
      (fn [ctx] (push! ctx (or (:doc (:entry (pop-val! ctx))) false)))
      "( word -- doc/f )"
      "the one-line description HELP: attached, or f if none was given")

    ;; -- print -----------------------------------------------------------
    (def-prim "." (fn [ctx] (print (display (pop-val! ctx))) (print " ") (flush)) "( value -- )" "prints one value as its own re-readable source")
    (def-prim ".s" (fn [ctx] (print (str/join " " (map display @(:stack ctx)))) (print " ") (flush)) "( -- )" "prints the whole stack, without touching it")
    (def-prim "print" (fn [ctx] (print (pop-val! ctx)) (flush)) "( str -- )" "prints a string's own raw content, no quotes")
    (def-prim "nl" (fn [_ctx] (println)) "( -- )" "prints a newline")))

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
    (def-prim "parse" (fn [ctx] (push! ctx (m/parse (pop-val! ctx)))) "( text -- {:ids ids} )" "parses and commits musics text into the repo")
    ;; The one place :parsing? is genuinely true -- #: ... ; itself is
    ;; already resolved by the tokenizer (no ctx exists there, see
    ;; tokenize's own header comment on why that span has to be captured
    ;; before ordinary word-tokenization ever touches it), so this is
    ;; the first point real interpreter state is available for it.
    (def-prim "parse-notation" (fn [ctx] (try
                                            (reset! (:parsing? ctx) true)
                                            (push! ctx (m/parse (pop-val! ctx)))
                                            (finally (reset! (:parsing? ctx) false))))
             "( text -- {:ids ids} )" "#: ... ;'s own target word -- same as parse, run with parsing? true")
    (def-prim "s!" (fn [ctx] (push! ctx (m/s! (pop-val! ctx)))) "( text -- {:ids ids} )" "musics.core/parse's own short name")
    (def-prim "try-parse" (fn [ctx] (push! ctx (m/try-parse (pop-val! ctx)))) "( text -- {:ids ids}/f )" "like parse, but f instead of throwing on a bad parse")
    (def-prim "parse-file" (fn [ctx] (push! ctx (m/parse-file (pop-val! ctx)))) "( path -- {:ids ids} )" "reads and parses a .mus file")
    (def-prim ">ids" (fn [ctx] (push! ctx (:ids (pop-val! ctx)))) "( {:ids ids} -- ids )" "pulls the ids out of a parse result")

    ;; -- registry / navigation / inspection -----------------------------
    (def-prim "find" (fn [ctx] (push! ctx (m/find (->kw (pop-val! ctx))))) "( id -- node/f )" "looks up a node by id in the repo")
    (def-prim "ids" (fn [ctx] (push! ctx (m/ids))) "( -- ids )" "every id currently in the repo")
    (def-prim "root-children" (fn [ctx] (push! ctx (m/root-children))) "( -- ids )" "the top-level parts directly under :ROOT")
    (def-prim "children" (fn [ctx] (push! ctx (m/children (->kw (pop-val! ctx))))) "( id -- children )" "a container's own immediate children")
    (def-prim "leaves" (fn [ctx] (push! ctx (m/leaves (->kw (pop-val! ctx))))) "( id -- leaves )" "every leaf note/rest under an id, in order")
    (def-prim "sq" (fn [ctx] (push! ctx (m/sq (->kw (pop-val! ctx))))) "( id -- seq )" "a container's own children as a bare, playable seq")
    (def-prim "inspect" (fn [ctx] (m/inspect (->kw (pop-val! ctx)))) "( id -- )" "prints a node's own structure")
    (def-prim "inspect-all" (fn [_ctx] (m/inspect)) "( -- )" "prints a session-wide node-count overview")
    (def-prim "ctx" (fn [ctx] (m/ctx (->kw (pop-val! ctx)))) "( id -- )" "prints a part's own context chain")
    (def-prim "ctx-value" (fn [ctx] (let [time (pop-val! ctx) key (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                       (push! ctx (m/ctx-value id key time))))
             "( id key time -- value )" "samples one context key's own value at a given time")
    (def-prim "locate" (fn [ctx] (let [path (pop-val! ctx) id (->kw (pop-val! ctx))]
                                    (push! ctx (m/locate id path))))
             "( id path -- node )" "navigates from id along an explicit selector path")
    (def-prim "describe" (fn [ctx] (push! ctx (m/describe (->kw (pop-val! ctx))))) "( id -- str )" "a human-readable description of a node")
    (def-prim "print-structure" (fn [ctx] (m/print-structure (->kw (pop-val! ctx)))) "( id -- )" "prints a node's own full tree structure")
    (def-prim "expand" (fn [ctx] (push! ctx (m/expand (pop-val! ctx)))) "( leaf -- path )" "finds where a real leaf value sits in the repo tree")

    ;; -- MIDI / playback -------------------------------------------------
    (def-prim "connect" (fn [_ctx] (m/connect)) "( -- )" "opens the Fluidsynth MIDI connection")
    (def-prim "warm-up!" (fn [_ctx] (m/warm-up!)) "( -- )" "sends a silent note to wake the synth up")
    (def-prim "warm-up-n!" (fn [ctx] (let [ms (pop-val! ctx) n (pop-val! ctx)] (m/warm-up! n ms))) "( n ms -- )" "warm-up!, n times, ms apart")
    (def-prim "disconnect" (fn [_ctx] (m/disconnect)) "( -- )" "closes the MIDI connection")
    (def-prim "play" (fn [ctx] (m/play (->kw (pop-val! ctx)))) "( id -- )" "flushes every voice, then plays id")
    (def-prim "play-add" (fn [ctx] (push! ctx (m/play-add (->kw (pop-val! ctx))))) "( id -- path )" "plays id alongside whatever's already playing")
    (def-prim "play-change" (fn [ctx] (let [arg (->kw (pop-val! ctx)) path (->kw (pop-val! ctx))]
                                         (push! ctx (m/play-change path arg))))
             "( path id -- path )" "replaces one already-playing track's own material")
    (def-prim "voice-at" (fn [ctx] (push! ctx (m/voice-at (->kw (pop-val! ctx))))) "( path -- voice )" "the live voice map at a given track path")
    (def-prim "play-file!" (fn [ctx] (m/play-file! (pop-val! ctx))) "( path -- )" "parses, commits, and plays a .mus file in one step")
    (def-prim "display" (fn [ctx] (push! ctx (m/display (->kw (pop-val! ctx))))) "( id -- )" "a synchronous, silent preview of what play would do")
    (def-prim "stop!" (fn [_ctx] (m/stop!)) "( -- )" "stops every voice, sending note-off promptly")
    (def-prim "pause!" (fn [_ctx] (m/pause!)) "( -- )" "freezes playback in place, no retrigger on resume")
    (def-prim "resume!" (fn [_ctx] (m/resume!)) "( -- )" "resumes playback after pause!")
    (def-prim "all-notes-off" (fn [_ctx] (m/all-notes-off)) "( -- )" "sends an immediate all-notes-off panic message")
    (def-prim "play!" (fn [ctx] (let [v (pop-val! ctx)
                                       {:keys [ids]} (if (string? v) (m/parse v) v)]
                                   (m/play (vec ids))))
             "( text/{:ids ids} -- )" "parses (if needed), commits, and plays in one step")
    (def-prim "p!" (fn [ctx] (m/p! (pop-val! ctx))) "( text -- )" "musics.core/play!'s own short name")

    ;; -- generative transforms -------------------------------------------
    (def-prim "times" (fn [ctx] (let [material (pop-val! ctx) n (pop-val! ctx)]
                                   (push! ctx (m/times n material))))
             "( n material -- material' )" "repeats material n times")
    (def-prim "transpose" (fn [ctx] (let [material (pop-val! ctx) semitones (pop-val! ctx)]
                                       (push! ctx (m/transpose semitones material))))
             "( semitones material -- material' )" "shifts every pitch by a fixed number of semitones")
    (def-prim "invert" (fn [ctx] (let [material (pop-val! ctx) axis (pop-val! ctx)]
                                    (push! ctx (m/invert axis material))))
             "( axis material -- material' )" "mirrors every pitch around an axis")
    (def-prim "invert-mean" (fn [ctx] (push! ctx (m/invert (pop-val! ctx)))) "( material -- material' )" "invert, axis defaulted to the material's own mean pitch")
    (def-prim "scale" (fn [ctx] (let [material (pop-val! ctx) factor (pop-val! ctx)]
                                   (push! ctx (m/scale factor material))))
             "( factor material -- material' )" "scales every duration by a fixed factor")
    (def-prim "reverse" (fn [ctx] (push! ctx (m/reverse (pop-val! ctx)))) "( material -- material' )" "reverses material's own order")
    (def-prim "shuffle" (fn [ctx] (push! ctx (m/shuffle (pop-val! ctx)))) "( material -- material' )" "randomly reorders material")
    (def-prim "thread" (fn [ctx] (let [material (pop-val! ctx) f (callable->fn ctx (pop-val! ctx))]
                                    (push! ctx (m/thread f material))))
             "( f material -- material' )" "applies f to every element of material")
    (def-prim "active-key" (fn [ctx] (push! ctx (m/active-key (->kw (pop-val! ctx))))) "( id -- ks )" "the key currently in scope for a part")
    (def-prim "tonal-transpose" (fn [ctx] (let [material (pop-val! ctx) steps (pop-val! ctx) ks (pop-val! ctx)]
                                             (push! ctx (m/tonal-transpose ks steps material))))
             "( ks steps material -- material' )" "transposes by scale steps within a key, not raw semitones")
    (def-prim "tonal-invert" (fn [ctx] (let [material (pop-val! ctx) axis (pop-val! ctx) ks (pop-val! ctx)]
                                          (push! ctx (m/tonal-invert ks axis material))))
             "( ks axis material -- material' )" "invert, staying diatonic to a key")
    (def-prim "snap-to-scale" (fn [ctx] (let [material (pop-val! ctx) ks (pop-val! ctx)]
                                           (push! ctx (m/snap-to-scale ks material))))
             "( ks material -- material' )" "moves every pitch to the nearest note in a key's own scale")
    (def-prim "tonal-harmonize" (fn [ctx] (let [material (pop-val! ctx) steps (pop-val! ctx) ks (pop-val! ctx)]
                                             (push! ctx (m/tonal-harmonize ks steps material))))
             "( ks steps material -- material' )" "adds a diatonic harmony voice, steps above")

    ;; -- variables --------------------------------------------------------
    (def-prim "clear-vars" (fn [_ctx] (m/clear-vars)) "( -- )" "clears every \\name-referenceable variable")

    ;; -- persistence --------------------------------------------------------
    (def-prim "write" (fn [ctx] (m/write (pop-val! ctx))) "( path -- )" "saves the whole current repo to a file")
    (def-prim "load" (fn [ctx] (m/load (pop-val! ctx))) "( path -- )" "replaces the current repo with a saved file's own content")
    (def-prim "ly-to-mus" (fn [ctx] (push! ctx (m/ly-to-mus (pop-val! ctx)))) "( path -- mus-path )" "converts a LilyPond file to a sibling .mus file")

    ;; -- reset / help -------------------------------------------------------
    (def-prim "reset" (fn [_ctx] (m/reset)) "( -- )" "wipes the repo entirely, re-bootstraps a fresh :ROOT")
    (def-prim "help" (fn [_ctx] (m/help)) "( -- )" "prints musics.core's own full context-key help table")
    (def-prim "help?" (fn [ctx] (m/help (pop-val! ctx))) "( key -- )" "prints musics.core's own help for one context key")

    ;; -- wall (per-voice playback algorithms) --------------------------------
    (def-prim "register-factory!" (fn [ctx] (let [f (callable->fn ctx (pop-val! ctx)) nm (->kw (pop-val! ctx))]
                                               (m/register-factory! nm f)))
             "( name f -- )" "permanently registers an algorithm factory")
    (def-prim "register-factory-doc!" (fn [ctx] (let [doc (pop-val! ctx) f (callable->fn ctx (pop-val! ctx))
                                                       nm (->kw (pop-val! ctx))]
                                                   (m/register-factory! nm f doc)))
             "( name f doc -- )" "register-factory!, plus a doc string")
    (def-prim "unregister-factory!" (fn [ctx] (m/unregister-factory! (->kw (pop-val! ctx)))) "( name -- )" "removes a registered factory")
    (def-prim "factories" (fn [ctx] (push! ctx (m/factories))) "( -- )" "prints every registered factory's own name")
    (def-prim "factories?" (fn [ctx] (push! ctx (m/factories (->kw (pop-val! ctx))))) "( name -- )" "prints one factory's own detail")
    (def-prim "unregister-algo!" (fn [ctx] (m/unregister-algo! (->kw (pop-val! ctx)))) "( name -- )" "removes a built algorithm")
    (def-prim "algos" (fn [ctx] (push! ctx (m/algos))) "( -- )" "prints every built algorithm's own name")
    (def-prim "algos?" (fn [ctx] (push! ctx (m/algos (->kw (pop-val! ctx))))) "( name -- )" "prints one built algorithm's own detail")
    (def-prim "assign-algo!" (fn [ctx] (let [nm (->kw (pop-val! ctx)) path (->kw (pop-val! ctx))]
                                          (m/assign-algo! path nm)))
             "( path name -- )" "prepares a track's own NEXT mint to use an algorithm")
    (def-prim "algo-assignments" (fn [ctx] (push! ctx (m/algo-assignments))) "( -- )" "prints every prepared path -> algorithm assignment")
    (def-prim "build!" (fn [ctx] (let [params (pop-val! ctx) factory-name (->kw (pop-val! ctx)) nm (->kw (pop-val! ctx))]
                                    (push! ctx (m/build! nm factory-name params))))
             "( name factory-name params -- fn )" "applies a factory's own params, storing the result under name")
    (def-prim "build-algo!" (fn [ctx] (let [f (callable->fn ctx (pop-val! ctx)) nm (->kw (pop-val! ctx))]
                                         (push! ctx (m/build-algo! nm f))))
             "( name f -- fn )" "stores an already-built wall fn directly, no factory involved")

    ;; -- action registry / schedule -------------------------------------------
    (def-prim "register-action!" (fn [ctx] (let [f (callable->fn ctx (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                              (m/register-action! id f)))
             "( id f -- )" "parks a callable action under id, for trigger!/schedule!")
    (def-prim "unregister-action!" (fn [ctx] (m/unregister-action! (->kw (pop-val! ctx)))) "( id -- )" "removes a registered action")
    (def-prim "trigger!" (fn [ctx] (let [arg (pop-val! ctx) id (->kw (pop-val! ctx))]
                                      (push! ctx (m/trigger! id arg))))
             "( id arg -- result )" "runs a registered action directly, right now")
    (def-prim "schedule!" (fn [ctx] (let [action-id (->kw (pop-val! ctx)) phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                       (m/schedule! id phase action-id)))
             "( id phase action-id -- )" "arms a one-shot action for the next [id phase] boundary")
    (def-prim "unschedule!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                         (m/unschedule! id phase)))
             "( id phase -- )" "cancels a scheduled one-shot action")
    (def-prim "scheduled" (fn [ctx] (push! ctx (m/scheduled))) "( -- )" "prints every pending one-shot schedule entry")
    (def-prim "scheduled?" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                        (push! ctx (m/scheduled id phase))))
             "( id phase -- entry/f )" "checks one specific one-shot schedule slot")
    (def-prim "scheduled-repeating" (fn [ctx] (push! ctx (m/scheduled-repeating))) "( -- )" "prints every pending repeating (schedule-tx!) entry")
    (def-prim "scheduled-repeating?" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                                  (push! ctx (m/scheduled-repeating id phase))))
             "( id phase -- entry/f )" "checks one specific repeating schedule slot")
    (def-prim "unschedule-repeating!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                                   (m/unschedule-repeating! id phase)))
             "( id phase -- )" "cancels a repeating schedule-tx! entry")
    (def-prim "schedule-tx!" (fn [ctx] (let [phase (->kw (pop-val! ctx)) id (->kw (pop-val! ctx))]
                                          (push! ctx (m/schedule-tx! id phase))))
             "( id phase -- )" "redirects one voice's own :view to whatever's newly committed, next boundary")

    ;; -- misc / state ---------------------------------------------------
    (def-prim "music-eval" (fn [ctx] (push! ctx (m/music-eval (pop-val! ctx)))) "( text -- result )" "mu!'s own :eval hook, callable directly")
    (def-prim "session" (fn [ctx] (push! ctx @m/session)) "( -- session )" "the current {:auto-ids :var-map} session map")
    (def-prim "receiver" (fn [ctx] (push! ctx @m/receiver)) "( -- receiver/nil )" "the current MIDI output receiver, if connected")))

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
