(ns ^:parsing musics.mu.value-test
  "Literals become values without entering the repo: one input walked
   together (duration and relative pitch carry over), each input fresh."
  (:require [clojure.test :refer [deftest is testing]]
            [musics.domain :as d]
            [musics.input.reader.builder :as builder]
            [musics.mu.grammar :as g]
            [musics.mu.value :as v]))

(defn- empty-repo [] (:repo (builder/empty-session)))

(defn- values [text]
  (let [nodes (filterv v/literal? (rest (g/parse text)))]
    (:values (v/read-literals nodes text (empty-repo) {}))))

(defn- pitch+dur [leaf] [(:pitches leaf) (:duration leaf)])

(deftest a-sequence-literal
  (let [[m] (values "[c4 d e]")]
    (is (v/material? m))
    (is (= :SEQ (:type (v/node m))))
    (is (= [[[60] 1/4] [[62] 1/4] [[64] 1/4]]
           (mapv pitch+dur (d/children (:repo m) (v/node m)))))))

(deftest loose-leaves-carry-over-within-an-input
  (testing "duration carries over, relative pitch follows the previous note"
    (is (= [[[60] 1/4] [[62] 1/4] [[64] 1/4]] (mapv pitch+dur (values "c4 d e"))))
    (is (= [[[72] 1/8] [[74] 1/8]] (mapv pitch+dur (values "C5/8 d")))))
  (testing "a bare leaf is the leaf itself"
    (is (every? d/leaf? (values "c4 d e"))))
  (testing "each input starts fresh"
    (is (= [[[62] 1/4]] (mapv pitch+dur (values "d"))))))

(deftest other-literals
  (is (= [[57 60 64] 1/4] (pitch+dur (first (values "<a c e>4")))) "a chord")
  (is (d/rest? (first (values "r8"))))
  (let [[m] (values "{[c d] [e f]}")]
    (is (= :PAR (:type (v/node m)))))
  (let [[m] (values "^{ !ff }")]
    (is (= :CONTEXT (:type (v/node m))) "an unnamed context still gets an id"))
  (let [[m] (values "[motif: g a]")]
    (is (= :motif (:id m)) "the literal's own id")))

(deftest nothing-enters-the-repo
  (let [repo (empty-repo)
        text "[c4 d e]"
        nodes (filterv v/literal? (rest (g/parse text)))
        {:keys [values auto-ids]} (v/read-literals nodes text repo {})]
    (is (= (set (keys repo)) #{:ROOT}) "the given repo is untouched (a value)")
    (is (contains? (:repo (first values)) (:id (first values))))
    (is (pos? (get auto-ids :SEQ 0)) "the auto ids advance")))
