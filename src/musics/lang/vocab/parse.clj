(ns musics.lang.vocab.parse
  "musics.lang's own `parse` vocabulary -- text-to-repo staging
   (parse/parse-notation/s!/try-parse/parse-file), split out of
   `musics.lang.vocab.musics` for the same reason that one exists
   separately from musics.lang's own kernel-vocab in the first place:
   see musics.lang's own ns docstring.

   `parse-notation` in particular MUST stay reachable unqualified from
   `scratchpad` -- it's the literal target word the tokenizer emits for
   `#: ... ;` (see musics.lang's own tokenize docstring), looked up by
   bare name like any other token, not a special-cased dispatch that
   could reach into a specific vocabulary on its own -- see
   musics.lang/make-ctx's own :vocab-uses.

   parse/parse-notation/s!/parse-file push every id a parse touched --
   top-level AND nested, in structural/written order (musics.core/
   parse's own :all-ids, see its docstring) -- as separate stack items,
   one id per slot, not a single {:ids ids} map. `[a: c4] [b: d4]`
   inside a Parallel leaves BOTH :a and :b on the stack alongside the
   Parallel's own id, not just the Parallel's -- and several bare
   top-level leaves (already separate ids of their own, see
   flat-tree-walker/wrap-bare-leaf) each get their own stack slot the
   same way. A failed parse still pushes exactly one value, `nil` (same
   as `try-parse` on failure), never zero -- so a caller never has to
   guess how many slots to `drop`/inspect before it knows whether the
   parse itself failed."
  (:require [musics.core :as m]
            [musics.lang.runtime :refer [push! pop! builtin]])
  (:refer-clojure :exclude [pop!]))

(defn- push-parse-result!
  "result is m/parse's own return value ({:ids ids :all-ids all-ids},
   or nil on a failed parse). Pushes every id in :all-ids as its own
   stack item, in order -- or, on failure, a single nil (never zero
   values either way)."
  [ctx result]
  (if result
    (doseq [id (:all-ids result)] (push! ctx id))
    (push! ctx nil)))

(defn vocab []
  (merge
    (builtin "parse" (fn [ctx] (push-parse-result! ctx (m/parse (pop! ctx)))) "( text -- id* )" "parses and commits musics text, pushing every id it touched (top-level and nested) individually")
    ;; The one place :parsing? is genuinely true -- #: ... ; itself is
    ;; already resolved by the tokenizer (no ctx exists there, see
    ;; musics.lang's own tokenize header comment on why that span has
    ;; to be captured before ordinary word-tokenization ever touches
    ;; it), so this is the first point real interpreter state is
    ;; available for it.
    (builtin "parse-notation" (fn [ctx] (try
                                            (reset! (:parsing? ctx) true)
                                            (push-parse-result! ctx (m/parse (pop! ctx)))
                                            (finally (reset! (:parsing? ctx) false))))
             "( text -- id* )" "#: ... ;'s own target word -- same as parse, run with parsing? true")
    (builtin "s!" (fn [ctx] (push-parse-result! ctx (m/s! (pop! ctx)))) "( text -- id* )" "musics.core/parse's own short name")
    (builtin "try-parse" (fn [ctx] (push! ctx (m/try-parse (pop! ctx)))) "( text -- tree/f )" "parses only, no commit -- pushes the raw instaparse tree (grammar debugging) or f on failure")
    (builtin "parse-file" (fn [ctx] (push-parse-result! ctx (m/parse-file (pop! ctx)))) "( path -- id* )" "reads and parses a .mus file, pushing every id it touched individually")))
