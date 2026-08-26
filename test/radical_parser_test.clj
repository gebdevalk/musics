(ns ^:parsing radical-parser-test
  "Cross-checks radical.ebnf + input.reader.radical-tree-walker against
   musics.ebnf + input.reader.flat-tree-walker for equivalent input --
   not just 'radical's own pipeline runs without throwing', but that the
   two pipelines produce the SAME resulting domain structure for
   musically-equivalent text, container by container, leaf by leaf.

   node-summary strips :id/:context/:ctx-chain (both pipelines mint
   their own ids independently, and a Context's own :envelopes-atom
   never = another instance's even with identical content -- atom
   identity, not value equality) and recursively resolves :children/
   :source through the tree map, so what's actually compared is the
   musically meaningful shape: container types, leaf pitches/durations/
   articulation/dynamic/modifiers/tied, Iterator type/params."
  (:require [clojure.test :refer [deftest is testing]]
            [input.grammar-parser :as gp]
            [core.domain.context :as c]))

(defn- resolve-id [tree x]
  (if (keyword? x) (get tree x) x))

(defn- node-summary [tree node]
  (let [node (resolve-id tree node)]
    (if (map? node)
      (cond-> (dissoc node :id :context :ctx-chain)
        (contains? node :children) (update :children #(mapv (partial node-summary tree) %))
        (contains? node :source)   (update :source (partial node-summary tree))
        ;; walk-repeat's own :alternative param holds a nested container
        ;; (see walk-repeat/walk-tremolo) -- recurse into it too, same as
        ;; :children/:source, so its own leaves' :context/:ctx-chain atoms
        ;; get stripped rather than compared by (never-equal) identity.
        (get-in node [:params :alternative])
        (update-in [:params :alternative] (partial node-summary tree)))
      node)))

(defn- musics-summary [text id]
  (let [{:keys [tree]} (gp/parse-domain-string text)]
    (node-summary tree id)))

(defn- radical-summary [text id]
  (let [{:keys [tree]} (gp/parse-domain-string-radical text)]
    (node-summary tree id)))

(defn- same? [musics-text radical-text]
  (= (musics-summary musics-text :verse)
     (radical-summary radical-text :verse)))

;; ── Containers ──────────────────────────────────────────────

(deftest plain-sequence
  (is (same? "{verse: c4 d4 e4}" "[verse: c4 d4 e4]")))

(deftest parallel-group
  (is (same? "{verse: << {c4 d4} {e4 f4} >>}"
             "[verse: #{[c4 d4] [e4 f4]}]")))

(deftest data-container
  (is (same? "{verse: [C4 D4 E4]}" "[verse: '[C4 D4 E4]]")))

;; ── Commands ────────────────────────────────────────────────

(deftest times-command
  (is (same? "{verse: \\times 2/3 {c8 d8 e8}}"
             "[verse: (times 2/3 [c8 d8 e8])]")))

(deftest tuplet-command
  (is (same? "{verse: \\tuplet 3/2 {c8 d8 e8}}"
             "[verse: (tuplet 3/2 [c8 d8 e8])]")))

(deftest transpose-command
  (is (same? "{verse: \\transpose c g {c4 d4}}"
             "[verse: (transpose c g [c4 d4])]")))

(deftest repeat-unfold
  (is (same? "{verse: \\repeat unfold 2 {c4 d4}}"
             "[verse: (repeat unfold 2 [c4 d4])]")))

(deftest repeat-volta-with-alternative
  (is (same? "{verse: \\repeat volta 2 {c4 d4} \\alternative {e4}}"
             "[verse: (repeat volta 2 [c4 d4] (alternative [e4]))]")))

(deftest repeat-tremolo
  (testing "musics.ebnf's separate \\repeat tremolo rule vs. radical.ebnf's
            own repeat rule with repeat-type \"tremolo\" -- walk-repeat
            absorbs what used to be walk-tremolo, see that fn's docstring"
    (is (same? "{verse: \\repeat tremolo 8 {c4 d4}}"
               "[verse: (repeat tremolo 8 [c4 d4])]"))))

(deftest grace-variants
  (doseq [[musics-kw radical-kw] [["grace" "grace"]
                                  ["acciaccatura" "acciaccatura"]
                                  ["appoggiatura" "appoggiatura"]
                                  ["slashedGrace" "slashedGrace"]
                                  ["afterGrace" "afterGrace"]]]
    (is (same? (str "{verse: \\" musics-kw " c8 d4}")
               (str "[verse: (" radical-kw " c8 d4)]"))
        (str musics-kw " grace variant"))))

;; ── Comments (different syntax, must have zero effect on the tree) ──

(deftest comments-are-discarded
  (is (same? "{verse: c4 % a line comment\n d4}"
             "[verse: c4 ; a line comment\n d4]"))
  (is (same? "{verse: c4 %{ a block comment %} d4}"
             "[verse: c4 %{ a block comment %} d4]")))

;; ── Slur (identical syntax in both grammars) ─────────────────

(deftest slur-marks
  (is (same? "{verse: c4( d4 e4)}" "[verse: c4( d4 e4)]")))

;; ── Variables ───────────────────────────────────────────────

(deftest vardef-varref
  (is (same? "myvar = {c4 d4} {verse: \\myvar}"
             "myvar = [c4 d4] [verse: \\myvar]")))

;; ── Context blocks: compare sampled context values, not raw
;;    :envelopes-atom content (never = across independent parses) ──

(deftest context-block-and-reference
  (testing "{ctx: !tempo:120} referenced from a sibling sequence resolves
            to the same tempo value under both grammars"
    (let [{m-tree :tree} (gp/parse-domain-string "^{ctx: !tempo:120} {verse: :ctx c4}")
          {r-tree :tree} (gp/parse-domain-string-radical "{ctx: !tempo:120} [verse: :ctx c4]")
          m-ctx (:context (get m-tree :verse))
          r-ctx (:context (get r-tree :verse))]
      (is (= 120 (c/ctx-value-chain [m-ctx] :Tempo 0)))
      (is (= 120 (c/ctx-value-chain [r-ctx] :Tempo 0))))))
