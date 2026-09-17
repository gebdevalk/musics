(ns ^:repl show-algos-test
  "algo-ns-syms/algo-category/algo-tree/show-algos (musics.clj) -- the
   algo/ browsing catalog, built live off ns-publics/docstrings rather
   than a hand-maintained list. Coverage focuses on the real logic
   (path -> namespace-symbol conversion, category derivation for both
   the nested algo/<subdir>/<file>.clj shape and the bare algo/<file>.clj
   shape) -- not a full enumeration of every algo/ file, which would
   just duplicate the audit-summary.txt findings in test form and go
   stale the same way a hand-maintained doc table would."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [musics.core :as m]))

(deftest algo-ns-syms-finds-every-real-algo-namespace
  (let [syms (#'m/algo-ns-syms)]
    (is (seq syms) "genuinely finds something, not an empty classpath miss")
    (is (every? #(str/starts-with? (str %) "algo.") syms)
        "every symbol is under the algo. namespace root")
    (is (= syms (sort syms)) "returned pre-sorted, not in filesystem-walk order")
    (is (some #{'algo.rhythmic.rhythm} syms)
        "a real, nested algo/<subdir>/<file>.clj namespace is found")
    (is (some #{'algo.random} syms)
        "a bare top-level algo/<file>.clj namespace (no subdirectory of
         its own) is found too, not just ones nested under a subdir")
    (is (some #{'algo.common.transient-ops} syms)
        "an underscored filename (transient_ops.clj) correctly becomes
         a hyphenated namespace segment (transient-ops), same as
         Clojure's own file<->namespace convention")))

(deftest algo-category-handles-both-namespace-shapes
  (is (= "rhythmic" (#'m/algo-category 'algo.rhythmic.rhythm))
      "a nested namespace's category is its own subdirectory")
  (is (= "random" (#'m/algo-category 'algo.random))
      "a bare top-level namespace (no subdirectory) is still its own
       category, grouping naturally alongside algo/random/'s own
       namespaces (algo.random.henon, etc.)")
  (is (= "random" (#'m/algo-category 'algo.random.henon))
      "a nested namespace under the SAME name as the bare one above
       lands in the identical category, as intended"))

(deftest algo-tree-has-every-known-category-and-a-real-documented-algo
  (let [tree (#'m/algo-tree)]
    (is (= #{"common" "indisp" "melodic" "metric" "random" "rhythmic" "toolkit" "algoline"}
           (set (keys tree)))
        "every algo/ subdirectory is represented as its own category --
         toolkit and algoline (algo/toolkit.clj, algo/algoline.clj,
         bare files with no subdirectory of their own to group into,
         same shape as algo/random.clj before algo/random/ existed
         alongside it) are real, deliberate categories of their own,
         not an oversight. (algo.dimensions used to be a third such
         category -- removed 2026-09-17 once its one real finding had
         shipped and nothing else depended on it; see
         doc/algorithms.md's own note for the fuller history.)")
    (is (= "Returns random integer between lo (inclusive) and hi (exclusive)"
           (get-in tree ["random" "int-range"]))
        "a real, known algo's full docstring is reachable by
         [category name] -- euclidean-rhythm used to be this fixture's
         own one-line-docstring example, until its docstring genuinely
         grew multi-line (2026-09-14, documenting a real bug fix);
         int-range fills the same 'known short one-liner' role now")
    (is (nil? (get-in tree ["rhythmic" "lindenmayer-rhythm*private-helper-that-does-not-exist"]))
        "an unknown name resolves to nil, not an error")))

(deftest show-algos-prints-a-category-header-and-known-algos
  (let [printed (with-out-str (m/show-algos))]
    (is (re-find #"--- rhythmic ---" printed) "category headers appear")
    (is (re-find #"euclidean-rhythm" printed) "known algo names appear")))

(deftest show-algos-one-category-prints-only-that-category
  (let [printed (with-out-str (m/show-algos "metric"))]
    (is (re-find #"--- metric ---" printed))
    (is (not (re-find #"--- rhythmic ---" printed))
        "a different category's own header does NOT leak into a
         single-category call")))

(deftest show-algos-two-args-prints-the-full-documentation
  (let [printed (with-out-str (m/show-algos "random" "int-range"))]
    (is (= "Returns random integer between lo (inclusive) and hi (exclusive)\n" printed)
        "the full doc for a genuinely one-line docstring"))
  (let [printed (with-out-str (m/show-algos "common" "chain-algo"))]
    (is (> (count (str/split-lines printed)) 5)
        "and the FULL multi-line docstring for one that has several
         lines, not just its first line the way the compact category
         listing above does")
    (is (re-find #"register-factory! :chain chain-algo" printed)
        "content from well past the first line is genuinely present")))

(deftest show-algos-degrades-clearly-for-an-unknown-category-or-name
  (is (re-find #"Unknown category" (with-out-str (m/show-algos "not-a-real-category"))))
  (is (re-find #"Unknown algo" (with-out-str (m/show-algos "rhythmic" "not-a-real-algo")))))
