(ns resolve-test
  (:require [clojure.test :refer [deftest is]]
            [input.grammar-parser :as gp]
            [core.domain.flat-domain :as d]
            [core.domain.resolve :as r]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- walk [text]
  (gp/parse-domain-string text))

(defn- fixture
  "Load a DSL fixture from test/resources/musics -- keeps escape-heavy
   backslash input (\\repeat etc.) out of Clojure string literals."
  [name]
  (walk (slurp (str "test/resources/musics/" name))))

;; ============================================================
;; locate
;; ============================================================

(deftest locate-finds-a-leaf-through-nested-containers
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4 e4}")
        {:keys [part ctx-chain path]} (r/locate tree root-id [0 1])]
    (is (d/leaf? part) "path lands on the 2nd leaf of :verse")
    (is (= [62] (:pitches part)))
    (is (= [0 1] path))
    (is (= 2 (count ctx-chain))
        "chain = verse's own context, ROOT's context -- ROOT's context appears
         exactly once, not duplicated by a separately-supplied root-ctx")))

(deftest locate-descends-into-a-container-by-index
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4}")
        {:keys [part path]} (r/locate tree root-id [0])]
    (is (d/container? part))
    (is (= :verse (:id part)))
    (is (= [0] path))))

(deftest locate-returns-nil-for-out-of-range-index
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4}")]
    (is (nil? (r/locate tree root-id [5])))))

(deftest locate-returns-nil-past-a-leaf
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4}")]
    (is (nil? (r/locate tree root-id [0 0 0]))
        "path continues after already reaching a leaf")))

(deftest locate-lands-on-the-iterator-itself
  (let [{:keys [tree root-id]} (fixture "repeat-unfold.mus")
        {:keys [part]} (r/locate tree root-id [0])]
    (is (d/iterator? part) "path [0] selects the Iterator among ROOT's children")))

(deftest locate-descends-into-iterator-source
  (let [{:keys [tree root-id]} (fixture "repeat-unfold.mus")
        {:keys [part]} (r/locate tree root-id [0 0])]
    (is (d/container? part) "one more path segment steps past the Iterator into its :source")
    (is (= 2 (count (:children part))))))

(deftest locate-selects-a-child-by-id-without-knowing-its-position
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4} {chorus: g4 a4}")
        {:keys [part]} (r/locate tree root-id [:chorus])]
    (is (d/container? part))
    (is (= :chorus (:id part)))))

(deftest locate-mixes-id-and-index-selectors
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4} {chorus: g4 a4}")
        {:keys [part]} (r/locate tree root-id [:chorus 1])]
    (is (d/leaf? part))
    (is (= [69] (:pitches part)) "chorus's 2nd leaf (a4)")))

(deftest locate-returns-nil-for-unmatched-id
  (let [{:keys [tree root-id]} (walk "{verse: c4 d4}")]
    (is (nil? (r/locate tree root-id [:bogus])))))

;; ============================================================
;; Unit (context-less container)
;; ============================================================

(deftest unit-contributes-no-context-of-its-own-to-the-chain
  ;; A leaf directly in :verse and a leaf inside a Unit nested in :verse
  ;; should see the exact same ctx-chain -- the Unit is structurally
  ;; present (it's a real container, addressable by index) but contributes
  ;; nothing to the chain, unlike every other composite type.
  ;; Path [0] is :verse itself (a top-level named Sequence); [0 0] is the
  ;; Unit, [0 0 0] a leaf inside it; [0 1] is e4, verse's other child.
  (let [{:keys [tree root-id]} (walk "{verse: (grp: c4 d4) e4}")
        {chain-in-unit :ctx-chain}   (r/locate tree root-id [0 0 0])
        {chain-sibling :ctx-chain}   (r/locate tree root-id [0 1])]
    (is (= 2 (count chain-in-unit)) "ROOT's context + verse's context, no third for the Unit")
    (is (= chain-in-unit chain-sibling)
        "same chain whether the leaf is inside the Unit or a direct sibling of it")))

(deftest unit-is-a-real-addressable-container
  (let [{:keys [tree root-id]} (walk "{verse: (grp: c4 d4) e4}")
        {:keys [part]} (r/locate tree root-id [0 0])]
    (is (d/container? part))
    (is (= :grp (:id part)))
    (is (= :UNIT (:type part)))))
