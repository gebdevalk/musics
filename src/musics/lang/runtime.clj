(ns musics.lang.runtime
  "The small set of musics.lang mechanisms every vocabulary namespace
   needs -- the stack, quotations/word-references as first-class
   values, running already-compiled code, and the shared
   argument-marshaling helpers (builtin/->kw/callable->fn) -- pulled
   out of musics.lang itself specifically to break what would otherwise
   be a circular require: musics.lang itself requires each
   musics.lang.vocab.* namespace (to wire its words into make-ctx), so
   anything a vocab namespace needs back FROM musics.lang has to live
   somewhere neither one requires the other to reach -- this ns is that
   somewhere, with zero dependency on musics.lang, musics.core, or any
   musics.lang.vocab.* namespace.

   musics.lang itself requires this ns too, for its own kernel-vocab
   (dup/swap/if/each/...), which needs exactly the same quotation/
   execute-entry machinery any other vocabulary's combinators would --
   this isn't a bridge-specific concern, just where it had to live once
   more than one namespace needed it.

   Deliberately does NOT know about vocabularies (IN:/USE:/lookup-word/
   define-word!/...), the tokenizer, or the compiler (compile-forms/
   interpret-token!) -- those stay in musics.lang itself, since nothing
   outside it ever needs them.")

;; ---------------------------------------------------------------------
;; Quotations and word references
;; ---------------------------------------------------------------------

;; :env is nil until the quotation is actually instantiated (pushed) --
;; see musics.lang's own compile-forms "(" branch, which stamps in the
;; CURRENT ctx's own :env atom at push time, not compile time. This is
;; what makes a quotation a genuine lexical closure rather than just a
;; bundle of pre-compiled steps: the SAME compiled quotation-push step
;; runs on every invocation of its enclosing word, and each invocation
;; has its own fresh :env (a new atom per execute-entry call, below) --
;; the quotation value has to capture ITS OWN invocation's atom at the
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
;; Executing already-compiled code -- a word's own :steps, or a
;; quotation/word-reference value found on the stack at runtime
;; ---------------------------------------------------------------------

(defn run-steps [steps ctx] (doseq [step steps] (step ctx)))

(defn execute-entry
  "entry is {:type :primitive :fn (fn [ctx] ...)} or {:type :colon :steps
   [...] :incoming-names [...] } (:incoming-names non-nil only for a ::
   definition -- those get popped off the stack, right-to-left, into a
   fresh :env before the body runs; a plain : definition's :env starts
   empty, since its ( ... ) never binds anything). A quotation is run
   via run-steps directly against the CALLER's own ctx (same :env), not
   through execute-entry -- see run-callable below -- that's what makes
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

(defn quot-steps [v]
  (when-not (quotation? v) (throw (ex-info "expected a quotation" {:got v})))
  (:steps v))

(defn run-callable
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

;; ---------------------------------------------------------------------
;; Defining a primitive word -- shared by musics.lang's own kernel-vocab
;; and every musics.lang.vocab.* namespace alike
;; ---------------------------------------------------------------------

(defn builtin
  "effect is an optional stack-effect source STRING ('( x -- x x )'),
   purely descriptive (never checked/enforced, same as real Factor's
   own declared effects for a hand-written word) -- what `stack-effect`
   reads for a primitive. doc is an optional ONE-LINE description of
   what the word actually DOES (distinct from effect, which only
   describes shape, not meaning) -- what `word-doc`/`see` read. Every
   primitive in this project -- musics.lang's own kernel AND every
   musics.lang.vocab.* bridge namespace alike -- carries both."
  ([nm f] (builtin nm f nil nil))
  ([nm f effect] (builtin nm f effect nil))
  ([nm f effect doc] {nm {:type :primitive :fn f :effect effect :doc doc}}))

;; ---------------------------------------------------------------------
;; musics.core argument-marshaling helpers -- shared by every
;; musics.lang.vocab.* namespace that bridges to musics.core
;; ---------------------------------------------------------------------

(defn ->kw
  "String -> keyword; anything else (a keyword already, a number, ...)
   passes through unchanged -- conductor/wall ids compare with plain
   =/keyword?, so a bare string silently never matches without this."
  [x]
  (if (string? x) (keyword x) x))

(defn callable->fn
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
