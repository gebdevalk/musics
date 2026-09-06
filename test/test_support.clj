(ns test-support
  "Shared test isolation helpers -- binding-based, not reset!-based, so
   each test gets a genuinely fresh, isolated set of registries rather
   than relying on remembering to reset a shared one back to empty. See
   core.registries' own ns docstring: every var it declares is
   ^:dynamic specifically so a test can bind a fresh atom instead of
   mutating the shared one -- these two macros are that intended usage,
   applied consistently, replacing the reset!/reset-all! convention
   every existing test used instead."
  (:require [core.registries :as reg]
            [core.repo :as repo]
            [input.reader.flat-core-builder :as flat]))

(defmacro with-fresh-registries
  "Run body with every core.registries dynamic var (repo registry/
   staging/tx-counter/sid-counter, wall/preset registries, the three
   conductor tables, the adviser log) PLUS core.repo/play-tx
   (a separate var, deliberately not covered by reg/reset-all! -- see
   its own docstring) bound fresh -- genuinely isolated from whatever
   any OTHER test namespace happens to have left in the shared atoms in
   the same JVM run, not just reset back to empty.

   No :ROOT seeded -- matches core.repo/reset-all!'s own behavior
   exactly, so a test builds its own :ROOT the same way it already does
   right after (repo/reset-all!). See with-fresh-session below for the
   musics.clj-level guarantee instead."
  [& body]
  `(binding [reg/*repo-registry* (atom {})
             reg/*repo-staging* (atom {})
             reg/*repo-tx-counter* (atom 0)
             reg/*repo-sid-counter* (atom 0)
             reg/*algo-registry* (atom {})
             reg/*preset-registry* (atom {})
             reg/*distribution-registry* (atom {})
             reg/*criteria-registry* (atom {})
             reg/*conductor-action-registry* (atom {})
             reg/*conductor-schedule* (atom {})
             reg/*conductor-repeating* (atom {})
             reg/*adviser-log* (atom [])
             repo/play-tx (atom 0)]
     ~@body))

(defmacro with-fresh-session
  "Like with-fresh-registries, but ALSO seeds a real :ROOT -- the same
   recipe musics.clj/reset itself uses (flat-core-builder/empty-session)
   -- and points play-tx at it, for a test that expects the musics.clj-
   level 'a session always has :ROOT' guarantee rather than core.repo's
   own bare 'nothing exists at all' right after reset-all!."
  [& body]
  `(with-fresh-registries
     (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
     (repo/play-latest!)
     ~@body))
