(ns musics.lang.vocab.algo
  "musics.lang's own `algo` vocabulary -- core.wall's per-voice
   playback-algorithm bridge (register-factory!/build!/build-algo!/
   algos/assign-algo!/... plus registered/algo-fn/apply-algo/
   chain-algo!/retune!), split out of `musics.lang.vocab.musics` so
   factory/algo/assignment introspection words don't crowd the same
   namespace as play/repo-navigation words -- see musics.lang's own ns
   docstring. `scratchpad` USEs it by default, same as `musics`/
   `parse`, so every word here stays reachable unqualified at the top
   level -- see musics.lang/make-ctx.

   This vocab is ALSO the tree root for the whole algo-* family
   (make-ctx's own :vocab-uses has `algo` USE: all 8 of
   algo-common/algo-indisp/algo-melodic/algo-metric/algo-random/
   algo-rhythmic/algo-algoline/algo-toolkit) -- transitive USE:
   resolution (musics.lang's own use-vocab-lookup) means anything that
   USEs `algo` sees every one of those too, automatically. Nothing
   about being a container changes this vocab's own words above -- a
   vocab can define real words of its own AND USE: a whole sub-tree of
   others at the same time."
  (:require [musics.core :as m]
            [musics.lang.runtime :refer [push! pop! builtin ->kw callable->fn]]))

(defn vocab []
  (merge
    (builtin "register-factory!" (fn [ctx] (let [f (callable->fn ctx (pop! ctx)) nm (->kw (pop! ctx))]
                                               (m/register-factory! nm f)))
             "( name f -- )" "permanently registers an algorithm factory")
    (builtin "register-factory-doc!" (fn [ctx] (let [doc (pop! ctx) f (callable->fn ctx (pop! ctx))
                                                       nm (->kw (pop! ctx))]
                                                   (m/register-factory! nm f doc)))
             "( name f doc -- )" "register-factory!, plus a doc string")
    (builtin "unregister-factory!" (fn [ctx] (m/unregister-factory! (->kw (pop! ctx)))) "( name -- )" "removes a registered factory")
    (builtin "factories" (fn [ctx] (push! ctx (m/factories))) "( -- )" "prints every registered factory's own name")
    (builtin "factories?" (fn [ctx] (push! ctx (m/factories (->kw (pop! ctx))))) "( name -- )" "prints one factory's own detail")
    (builtin "unregister-algo!" (fn [ctx] (m/unregister-algo! (->kw (pop! ctx)))) "( name -- )" "removes a built algorithm")
    (builtin "algos" (fn [ctx] (push! ctx (m/algos))) "( -- )" "prints every built algorithm's own name")
    (builtin "algos?" (fn [ctx] (push! ctx (m/algos (->kw (pop! ctx))))) "( name -- )" "prints one built algorithm's own detail")
    (builtin "assign-algo!" (fn [ctx] (let [nm (->kw (pop! ctx)) path (->kw (pop! ctx))]
                                          (m/assign-algo! path nm)))
             "( path name -- )" "prepares a track's own NEXT mint to use an algorithm")
    (builtin "algo-assignments" (fn [ctx] (push! ctx (m/algo-assignments))) "( -- )" "prints every prepared path -> algorithm assignment")
    (builtin "build!" (fn [ctx] (let [params (pop! ctx) factory-name (->kw (pop! ctx)) nm (->kw (pop! ctx))]
                                    (push! ctx (m/build! nm factory-name params))))
             "( name factory-name params -- fn )" "applies a factory's own params, storing the result under name")
    (builtin "build-algo!" (fn [ctx] (let [f (callable->fn ctx (pop! ctx)) nm (->kw (pop! ctx))]
                                         (push! ctx (m/build-algo! nm f))))
             "( name f -- fn )" "stores an already-built wall fn directly, no factory involved")

    ;; -- introspection + composition over already-built algos ------------
    (builtin "registered" (fn [ctx] (push! ctx (m/registered)))
             "( -- map )" "the full built-algo registry, including :factory-name/:params/:chain recipes")
    (builtin "registered?" (fn [ctx] (push! ctx (m/registered (->kw (pop! ctx)))))
             "( name -- entry/nil )" "one built algo's own full entry")
    (builtin "algo-fn" (fn [ctx] (push! ctx (m/algo-fn (->kw (pop! ctx)))))
             "( name -- fn/nil )" "the actual resolved wall fn for a built algo name, fresh")
    (builtin "apply-algo" (fn [ctx] (let [nodes (pop! ctx) voice (pop! ctx) ctxchain (pop! ctx) f (pop! ctx)]
                                        (push! ctx (m/apply-algo f ctxchain voice nodes))))
             "( slot-fn ctxchain voice nodes -- nodes' )" "runs nodes through an already-resolved wall fn (nil is a no-op)")
    (builtin "chain-algo!" (fn [ctx] (let [names (pop! ctx) nm (->kw (pop! ctx))]
                                         (push! ctx (m/chain-algo! nm names))))
             "( name names -- name )" "builds a new algo that runs each of names' own algos in sequence, each link independently hot-swappable")
    (builtin "retune!" (fn [ctx] (let [v (pop! ctx) k (->kw (pop! ctx)) nm (->kw (pop! ctx))]
                                     (push! ctx (m/retune! nm k v))))
             "( name key value -- name )" "rebuilds an already-built algo with just one param changed, everything else kept")))
