(ns domain-model-test
  "Run: lein test domain-model-test"
  (:require [clojure.test :refer [deftest is testing]]
            [core.domain.music-domain :as d]))

;; ---------- wire-context: leaf vs container ----------

(deftest wire-context-leaf-replaces-context
  (let [parent-ctx (d/context-root {:amp 0.5})
        leaf (d/leaf "c4" (d/context) 1/4 [60])]
    (is (= parent-ctx (:context (d/wire-context leaf parent-ctx)))
        "Leaf's context is replaced by parent-ctx")))

(deftest wire-context-composite-sets-parent
  (let [outer-ctx (d/context-root {:amp 0.5})
        inner (d/composite :SEQ "inner" (d/context-root {:tempo 120}))]
    (let [wired (d/wire-context inner outer-ctx)]
      (is (not= outer-ctx (:context wired)) "Composite keeps its own context")
      (is (= outer-ctx (-> wired :context :parent)) "Composite's parent is set"))))

(deftest wire-context-rest-replaces-context
  (let [parent-ctx (d/context-root {:amp 0.5})
        r (d/make-rest "r" (d/context) 1/4)]
    (is (= parent-ctx (:context (d/wire-context r parent-ctx))))))

(deftest wire-context-drum-replaces-context
  (let [parent-ctx (d/context-root {:amp 0.5})
        dm (d/drum "bd" (d/context) 1/4 36)]
    (is (= parent-ctx (:context (d/wire-context dm parent-ctx))))))

(deftest wire-context-nil-ctx-unchanged
  (let [parent-ctx (d/context-root {:amp 0.5})
        part {:id "test" :duration 1/4}]
    (is (= part (d/wire-context part parent-ctx)))))

;; ---------- Composite construction ----------

(deftest composite-empty
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "test" root)]
    (is (= :SEQ (:type c)))
    (is (= root (-> c :context :parent)) "Composite's ctx parent is root")
    (is (zero? (d/composite-count c)))
    (is (empty? (d/composite-children c)))))

(deftest composite-with-children
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "test" root
                       [(d/leaf "a" (d/context) 1/4 [60])
                        (d/leaf "b" (d/context) 1/4 [62])])]
    (is (= 2 (d/composite-count c)))
    (is (every? #(= (:context c) (:context %)) (d/composite-children c))
        "Leafs get the composite's context (replacement)")))

(deftest composite-with-nested-composite
  (let [root (d/context-root {:tempo 120})
        inner-pt-ctx (d/context-root {:amp 0.5})
        outer (d/composite :SEQ "outer" root
                           [(d/composite :SEQ "inner" inner-pt-ctx)])]
    (let [wired-inner (first (d/composite-children outer))]
      (is (not= (:context outer) (:context wired-inner))
          "Nested composite keeps its own context")
      (is (= (:context outer) (-> wired-inner :context :parent))
          "Nested composite's parent is set"))))

;; ---------- composite-append ----------

(deftest append-single-leaf
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "phrase" root)
        leaf (d/leaf "a" (d/context) 1/4 [60])]
    (is (= c (d/composite-append c leaf)) "Returns composite for chaining")
    (is (= 1 (d/composite-count c)))
    (is (= (:context c) (:context (first (d/composite-children c)))))
    (is (= root (-> (first (d/composite-children c)) :context :parent)))))

(deftest append-multiple-leaf
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "phrase" root)]
    (d/composite-append c [(d/leaf "a" (d/context) 1/4 [60])
                            (d/leaf "b" (d/context) 1/4 [62])])
    (is (= 2 (d/composite-count c)))
    (is (= ["a" "b"] (map :id (d/composite-children c))))
    (is (every? #(= (:context c) (:context %)) (d/composite-children c)))))

(deftest append-empty-vector-noop
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "phrase" root)]
    (d/composite-append c (d/leaf "a" (d/context) 1/4 [60]))
    (let [before (d/composite-count c)]
      (d/composite-append c [])
      (is (= before (d/composite-count c)) "Empty vector is no-op"))))

(deftest append-composite
  (let [root (d/context-root {:tempo 120})
        outer (d/composite :SEQ "outer" root)
        inner (d/composite :SEQ "inner" (d/context-root {:amp 0.5}))]
    (d/composite-append outer inner)
    (let [child (first (d/composite-children outer))]
      (is (not= (:context outer) (:context child)) "Keeps own context")
      (is (= (:context outer) (-> child :context :parent)) "Parent set"))))

;; ---------- composite-insert ----------

(deftest insert-at-front
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "phrase" root)]
    (d/composite-append c (d/leaf "a" (d/context) 1/4 [60]))
    (d/composite-insert c 0 (d/leaf "b" (d/context) 1/4 [62]))
    (is (= ["b" "a"] (map :id (d/composite-children c))))
    (is (every? #(= (:context c) (:context %)) (d/composite-children c)))))

(deftest insert-in-middle
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "phrase" root)]
    (d/composite-append c [(d/leaf "a" (d/context) 1/4 [60])
                            (d/leaf "c" (d/context) 1/4 [64])])
    (d/composite-insert c 1 (d/leaf "b" (d/context) 1/4 [62]))
    (is (= ["a" "b" "c"] (map :id (d/composite-children c))))))

(deftest insert-composite
  (let [root (d/context-root {:tempo 120})
        outer (d/composite :SEQ "outer" root)]
    (d/composite-append outer (d/leaf "a" (d/context) 1/4 [60]))
    (d/composite-insert outer 0 (d/composite :SEQ "inner"
                                             (d/context-root {:amp 0.5})))
    (is (not= (:context outer) (:context (first (d/composite-children outer))))
        "Inserted composite keeps its own context")
    (is (= (:context outer) (-> (first (d/composite-children outer)) :context :parent))
        "Parent set")))

;; ---------- composite-replace ----------

(deftest replace-returns-old
  (let [root (d/context-root {:tempo 120})
        old-leaf (d/leaf "old" (d/context) 1/4 [60])
        new-leaf (d/leaf "new" (d/context) 1/4 [62])
        c (d/composite :SEQ "test" root [old-leaf])]
    (is (= "old" (:id (d/composite-replace c 0 new-leaf))) "Returns old")
    (is (= 1 (d/composite-count c)) "Count unchanged")
    (is (= "new" (:id (first (d/composite-children c)))))
    (is (= (:context c) (:context (first (d/composite-children c)))))))

(deftest replace-composite
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "outer" root
                       [(d/leaf "a" (d/context) 1/4 [60])])]
    (is (d/leaf? (d/composite-replace c 0
                     (d/composite :SEQ "inner"
                                  (d/context-root {:amp 0.5}))))
        "Returns old leaf")
    (let [child (first (d/composite-children c))]
      (is (not= (:context c) (:context child)) "Keeps own context")
      (is (= (:context c) (-> child :context :parent)) "Parent set"))))

;; ---------- children snapshot ----------

(deftest children-snapshot-immutable
  (let [c (d/composite :SEQ "test" (d/context-root {:tempo 120}))
        snapshot (d/composite-children c)]
    (d/composite-append c (d/leaf "a" (d/context) 1/4 [60]))
    (is (zero? (count snapshot)) "Snapshot unaffected by later mutations")))

;; ---------- composite-count ----------

(deftest count-progression
  (let [root (d/context-root {:tempo 120})
        c (d/composite :SEQ "test" root)]
    (is (zero? (d/composite-count c)))
    (d/composite-append c (d/leaf "a" (d/context) 1/4 [60]))
    (is (= 1 (d/composite-count c)))
    (d/composite-append c [(d/leaf "b" (d/context) 1/4 [62])
                            (d/leaf "c" (d/context) 1/4 [64])])
    (is (= 3 (d/composite-count c)))))

;; ---------- make-score ----------

(deftest score-root-only
  (let [score (d/make-score (d/context-root {:tempo 120}))]
    (is (= :SCORE (:type score)))
    (is (zero? (d/composite-count score)))))

(deftest score-with-part
  (let [score (d/make-score (d/context-root {:tempo 120})
                            (d/leaf "c4" (d/context) 1/4 [60]))]
    (let [child (first (d/composite-children score))]
      (is (= (:context score) (:context child))
          "Leaf gets score's context (replacement)")
      (is (= (-> score :context :parent) (-> child :context :parent))
          "Leaf reaches same root as score"))))

(deftest score-with-composite
  (let [score (d/make-score (d/context-root {:tempo 120})
                            (d/composite :SEQ "phrase"
                                         (d/context-root {:amp 0.5})))]
    (let [child (first (d/composite-children score))]
      (is (not= (:context score) (:context child)) "Keeps own context")
      (is (= (:context score) (-> child :context :parent)) "Parent set"))))

;; ---------- Deeper parent chain ----------

(deftest leaf-under-nested-composite
  "Leaf wired at inner-construction time keeps that context even after
   inner is later wired into outer. Children are NOT re-wired when
   their parent is re-parented."
  (let [root (d/context-root {:tempo 120})
        inner-root (d/context-root {:amp 0.5})
        leaf (d/leaf "c4" (d/context) 1/4 [60])
        inner (d/composite :SEQ "inner" inner-root [leaf])
        outer (d/composite :SEQ "outer" root [inner])]
    (let [outer-child (first (d/composite-children outer))  ;; inner after reparenting
          inner-child (first (d/composite-children outer-child))]  ;; leaf
      ;; leaf keeps its original context (wired at inner-construction time)
      ;; leaf's context parent is inner-root (the original parent of inner)
      (is (= inner-root (-> inner-child :context :parent))
          "Leaf's context parent is the inner-root, not outer's context")
      ;; after outer construction, inner's context parent = outer's context
      (is (= (:context outer) (-> outer-child :context :parent))
          "Inner composite's parent is the outer composite's context"))))

(deftest context-tree-after-multiple-appends
  "Notes wired at section-append-time keep that context even after
   section is later appended into score. Children are NOT re-wired
   when their parent is re-parented."
  (let [root (d/context-root {:tempo 120})
        score (d/composite :SCORE "piece" root)
        section (d/composite :SEQ "verse" (d/context-root {:amp 0.6}))
        ;; save section's context BEFORE wiring into score
        section-ctx-original (:context section)
        na (d/leaf "c4" (d/context) 1/4 [60])
        nb (d/leaf "d4" (d/context) 1/4 [62])]
    (d/composite-append section [na nb])
    (d/composite-append score section)
    (let [sec (first (d/composite-children score))
          notes (d/composite-children sec)]
      ;; section's context parent = score's context
      (is (= (:context score) (-> sec :context :parent))
          "Section's parent is score's context")
      ;; notes keep the original section context (wired at append-time)
      (is (= section-ctx-original (:context (first notes)))
          "First note's context is section's original context")
      (is (= section-ctx-original (:context (second notes)))
          "Second note's context is section's original context"))))
