(ns ^:domain flat-domain-test
  (:require [clojure.test :refer [deftest is]]
            [input.grammar-parser :as gp]
            [core.domain.flat-domain :as d]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- walk [text]
  (gp/parse-domain-string text))

(defn- fixture
  "Load a DSL fixture from test/resources/musics -- keeps escape-heavy
   backslash/quote-laden input (\\repeat, \\alternative, embedded strings)
   out of Clojure string literals."
  [name]
  (walk (slurp (str "test/resources/musics/" name))))

;; ============================================================
;; fold-node -- generic scaffold, exercised independently of describe/
;; freeze with a small toy handler-map
;; ============================================================

(deftest fold-node-applies-a-toy-algebra-through-real-repo-resolution
  (let [n1     (d/leaf :n1 nil 1/4 [60])
        r1     (d/rest* :r1 nil 1/4)
        inner  {:type :SEQ :id :inner :context nil :children [n1]}
        repo   {:inner inner}
        root   {:type :SEQ :id :s :context nil :children [:inner r1]}
        count-handlers {:container (fn [_ folded] (reduce + (map :result folded)))
                        :leaf      (fn [_] 1)
                        :rest      (fn [_] 1)}]
    (is (= 2 (d/fold-node root count-handlers
                          :resolve-ref (fn [id] (get repo id))))
        "1 leaf inside :inner + 1 rest at the top level")))

(deftest fold-node-default-resolve-ref-leaves-a-keyword-child-as-ref
  (let [root     {:type :SEQ :id :s :context nil :children [:elsewhere]}
        kinds    (atom [])
        handlers {:container (fn [_ folded] (swap! kinds into (map :kind folded)))}]
    (d/fold-node root handlers)
    (is (= [:ref] @kinds) "identity resolve-ref never resolves -- distinct from a failed lookup")))

(deftest fold-node-reports-a-real-failed-lookup-as-missing
  (let [root     {:type :SEQ :id :s :context nil :children [:elsewhere]}
        kinds    (atom [])
        handlers {:container (fn [_ folded] (swap! kinds into (map :kind folded)))
                  :missing   (fn [raw] raw)}]
    (d/fold-node root handlers :resolve-ref (constantly nil))
    (is (= [:missing] @kinds))))

(deftest fold-node-returns-nil-for-a-nil-node
  (is (nil? (d/fold-node nil {:container (fn [_ _] :should-not-run)}))))

;; ============================================================
;; print-structure
;; ============================================================

(deftest print-structure-shows-seq-brackets-and-leaf-count
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4 e4}")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\{ :ROOT" out))
    (is (re-find #"\{ :verse .*\(3 leaves\)" out))))

(deftest print-structure-shows-par-brackets
  (let [{:keys [tree root-id]} (walk "<<verse: {c4 d4} {e4 f4}>>")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"<< :verse" out))))

(deftest print-structure-shows-iterator-and-nested-source
  (let [{:keys [tree root-id]} (fixture "repeat-unfold.mus")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat unfold 2" out))
    (is (re-find #"\{ :s\d+ .*\(2 leaves\)" out)
        "nested source renders with its own SEQ brackets, indented under \\repeat")))

(deftest print-structure-shows-volta-with-alternative
  (let [{:keys [tree root-id]} (fixture "repeat-volta-alternative.mus")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat volta 2" out))
    (is (re-find #"\\alternative" out))
    (is (re-find #"(?s)\\repeat volta 2.*\\alternative.*\{ :s\d+ .*\(2 leaves\)" out)
        "alternative appears after the main source, same order as the input text")))

(deftest print-structure-shows-measured-tremolo
  (let [{:keys [tree root-id]} (fixture "repeat-tremolo.mus")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat tremolo 32" out))))

(deftest print-structure-reports-a-dangling-reference-instead-of-crashing
  ;; A reference to an id not parsed yet (or ever) resolves to nil via
  ;; repo -- describe-node used to let that nil ride into :children,
  ;; which then NPE'd in print-structure's (pos? (:leaf-count node)).
  (let [{:keys [tree root-id]} (walk "{song: :verse}")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\?\? :verse  \(unresolved\)" out))))

(deftest print-structure-reports-an-unresolvable-root-id-instead-of-crashing
  ;; root-id itself not existing in repo hit the exact same (pos? nil)
  ;; NPE at the top level, before ever reaching a container/child.
  (let [{:keys [tree]} (walk "{verse: c4 d4}")
        out (with-out-str (d/print-structure tree :nope))]
    (is (re-find #"\?\? :nope  \(unresolved\)" out))))

(deftest print-structure-shows-data-brackets-and-counts-plain-values-as-leaves
  ;; Data holds plain Int/Float/etc values, not Leaf/Rest/Drum records --
  ;; describe-node used to call itself on these (since they're neither
  ;; leaf?/rest?/drum? nor container?/iterator?), get nil back, and let
  ;; that nil ride into :children the same way a dangling reference did.
  ;; Also covers the :DATA/:ATOMIC_ALGO closing bracket, which the
  ;; grammar closes with a bare ']', not \"]'\".
  (let [{:keys [tree root-id]} (walk "'[1 2 3]")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"'\[ :d\d+ .*\(3 leaves\)" out))
    (is (not (re-find #"]'" out)) "Data closes with a bare ], not ]'")))

(deftest print-structure-shows-unit-brackets-and-preserves-a-given-id
  (let [{:keys [tree root-id]} (walk "{verse: [grp: c4 d4] e4}")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\[ :grp" out) "Unit renders with a [ bracket and keeps its explicit id")
    (is (re-find #"\(2 leaves\)" out))))
