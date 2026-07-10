(ns flat-domain-test
  (:require [clojure.test :refer [deftest is]]
            [input.reader.parser.grammar-parser :as gp]
            [core.domain.flat-domain :as d]))

;; ── Helpers ─────────────────────────────────────────────────

(defn- walk [text]
  (gp/parse-domain-string text))

;; ============================================================
;; print-structure
;; ============================================================

(deftest print-structure-shows-seq-brackets-and-leaf-count
  (let [{:keys [tree root-id]} (walk "[verse: c4 d4 e4]")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\[ :ROOT" out))
    (is (re-find #"\[ :verse .*\(3 leaves\)" out))))

(deftest print-structure-shows-par-brackets
  (let [{:keys [tree root-id]} (walk "{verse: [c4 d4] [e4 f4]}")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\{ :verse" out))))

(deftest print-structure-shows-iterator-and-nested-source
  (let [{:keys [tree root-id]} (walk "\\repeat unfold 2 [c4 d4]")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat unfold 2" out))
    (is (re-find #"\[ :SEQ\.\d+ .*\(2 leaves\)" out)
        "nested source renders with its own SEQ brackets, indented under \\repeat")))

(deftest print-structure-shows-volta-with-alternative
  (let [{:keys [tree root-id]} (walk "\\repeat volta 2 [c4 d4] \\alternative [e4 f4]")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat volta 2" out))
    (is (re-find #"\\alternative" out))
    (is (re-find #"(?s)\\repeat volta 2.*\\alternative.*\[ :SEQ\.\d+ .*\(2 leaves\)" out)
        "alternative appears after the main source, same order as the input text")))

(deftest print-structure-shows-measured-tremolo
  (let [{:keys [tree root-id]} (walk "\\repeat tremolo 32 [c4 d4]")
        out (with-out-str (d/print-structure tree root-id))]
    (is (re-find #"\\repeat tremolo 32" out))))
