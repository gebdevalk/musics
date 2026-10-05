(ns musics.test-support
  "Shared test isolation helpers -- binding-based, not reset!-based, so
   each test gets a genuinely fresh, isolated set of registries rather
   than relying on remembering to reset a shared one back to empty. See
   musics.registries' own ns docstring: every var it declares is
   ^:dynamic specifically so a test can bind a fresh atom instead of
   mutating the shared one -- these two macros are that intended usage,
   applied consistently, replacing the reset!/reset-all! convention
   every existing test used instead."
  (:require [musics.registries :as reg]
            [musics.repo :as repo]
            [musics.input.reader.builder :as flat]))

(defmacro with-fresh-registries
  "Run body with every musics.registries dynamic var (the repo registry,
   wall's algo registry, the three conductor tables, the
   activity log) bound fresh -- genuinely isolated from whatever any
   OTHER test namespace happens to have left in the shared atoms in the
   same JVM run, not just reset back to empty.

   No :ROOT seeded -- matches musics.repo/reset-all!'s own behavior
   exactly, so a test builds its own :ROOT the same way it already does
   right after (repo/reset-all!). See with-fresh-session below for the
   musics.core-level guarantee instead."
  [& body]
  `(binding [reg/*repo-registry* (atom {})
             reg/*algo-registry* (atom {})
             reg/*conductor-action-registry* (atom {})
             reg/*conductor-schedule* (atom {})
             reg/*conductor-repeating* (atom {})
             reg/*activity-log* (atom [])]
     ~@body))

(defmacro with-fresh-session
  "Like with-fresh-registries, but ALSO seeds a real :ROOT -- the same
   recipe musics.core/reset itself uses (builder/empty-session)
   -- for a test that expects the musics.core-level 'a session always
   has :ROOT' guarantee rather than musics.repo's own bare 'nothing exists
   at all' right after reset-all!."
  [& body]
  `(with-fresh-registries
     (repo/commit-node! :ROOT (get (:repo (flat/empty-session)) :ROOT))
     ~@body))
