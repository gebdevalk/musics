(ns musics.lang.vocab.parse
  "musics.lang's own `parse` vocabulary -- text-to-repo staging
   (parse/parse-notation/s!/try-parse/parse-file/>ids), split out of
   `musics.lang.vocab.musics` for the same reason that one exists
   separately from musics.lang's own kernel-vocab in the first place:
   see musics.lang's own ns docstring.

   `parse-notation` in particular MUST stay reachable unqualified from
   `scratchpad` -- it's the literal target word the tokenizer emits for
   `#: ... ;` (see musics.lang's own tokenize docstring), looked up by
   bare name like any other token, not a special-cased dispatch that
   could reach into a specific vocabulary on its own -- see
   musics.lang/make-ctx's own :vocab-uses."
  (:require [musics.core :as m]
            [musics.lang.runtime :refer [push! pop! builtin]]))

(defn vocab []
  (merge
    (builtin "parse" (fn [ctx] (push! ctx (m/parse (pop! ctx)))) "( text -- {:ids ids} )" "parses and commits musics text into the repo")
    ;; The one place :parsing? is genuinely true -- #: ... ; itself is
    ;; already resolved by the tokenizer (no ctx exists there, see
    ;; musics.lang's own tokenize header comment on why that span has
    ;; to be captured before ordinary word-tokenization ever touches
    ;; it), so this is the first point real interpreter state is
    ;; available for it.
    (builtin "parse-notation" (fn [ctx] (try
                                            (reset! (:parsing? ctx) true)
                                            (push! ctx (m/parse (pop! ctx)))
                                            (finally (reset! (:parsing? ctx) false))))
             "( text -- {:ids ids} )" "#: ... ;'s own target word -- same as parse, run with parsing? true")
    (builtin "s!" (fn [ctx] (push! ctx (m/s! (pop! ctx)))) "( text -- {:ids ids} )" "musics.core/parse's own short name")
    (builtin "try-parse" (fn [ctx] (push! ctx (m/try-parse (pop! ctx)))) "( text -- {:ids ids}/f )" "like parse, but f instead of throwing on a bad parse")
    (builtin "parse-file" (fn [ctx] (push! ctx (m/parse-file (pop! ctx)))) "( path -- {:ids ids} )" "reads and parses a .mus file")
    (builtin ">ids" (fn [ctx] (push! ctx (:ids (pop! ctx)))) "( {:ids ids} -- ids )" "pulls the ids out of a parse result")))
