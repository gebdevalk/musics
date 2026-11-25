(ns ^:repl musics.mu.native-test
  "Values built without text: each native constructor gives the value the text route gives."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [musics.domain :as d]
            [musics.input.reader.builder :as builder]
            [musics.mu.eval :as e]
            [musics.mu.kernel]
            [musics.mu.library]
            [musics.mu.material :as mt]
            [musics.mu.print :as p]
            [musics.mu.tools]
            [musics.mu.value :as v]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-registries]]))

(defn- fresh [t]
  (let [saved @e/state]
    (try
      (with-fresh-registries
        (reset! e/state (assoc (e/fresh-state) :ns (select-keys (:ns saved) ["kernel" "tool" "music"])))
        (repo/commit-node! :ROOT (get (:repo (builder/empty-session)) :ROOT))
        (t))
      (finally (reset! e/state saved)))))

(use-fixtures :each fresh)

(defn- chain-shape
  "A leaf's context chain as [offset entries] for each link: the identity of a context
   differs between two reads, what it holds and where it stands does not."
  [leaf]
  (mapv (fn [[ctx off]] [off (count @(:envelopes-atom ctx))]) (:ctx-chain leaf)))

(defn- shape
  "A value without ids: its text, its duration, its pitch statistics, and each child as the
   fields that play (a leaf) or its own shape (a container), with the chains of its leaves."
  [m]
  (let [repo (:repo m)
        node (v/node m)
        leaf-shape (fn [l] [(select-keys l [:type :pitches :duration :articulation :dynamic :tied :modifiers :overrides :program :ratio])
                            (chain-shape l)])]
    {:text (p/show m)
     :type (:type node)
     :duration (d/duration repo node)
     :stats (select-keys node [:pitch-sum :pitch-n])
     :children (mapv #(if (map? %) (if (or (d/bar? %) (d/jump? %)) (select-keys % [:type :count]) (leaf-shape %)) %) (:children node))}))

(def ^:private seqs
  ["[c4 d e]" "[c4 r d8 e]" "[c4 <e g> d]" "[c4 d | e f || g]" "[c4~ c d]" "[x4\\38 x x\\36 r]"
   "[c4 d $ e DS f]" "[3:2(c8 d e) f]" "[c4\\vol:90 d-> e8.]" "[c d e f g a b]" "[!mf c4 d]"])

(deftest line-of-leaves-is-built-without-text
  (doseq [text seqs]
    (let [els (mt/elements (mt/read-text text))]
      (when (and (seq els) (every? mt/leaf? els))
        (testing text
          (is (= (shape (#'mt/line-by-text els)) (shape (#'mt/line-native els)))))))))

(deftest line-of-leaves-and-marks
  (let [leaves (mt/elements (mt/read-text "[c4 d e]"))
        bar    (first (filter d/bar? (:children (v/node (mt/read-text "[c4 | d]")))))
        els    [(first leaves) bar (second leaves)]]
    (is (= (shape (#'mt/line-by-text els)) (shape (#'mt/line-native els))))))

(deftest line-of-leaves-with-lengths-that-are-no-note-value
  (let [[a b c] (mt/elements (mt/read-text "[c4 d e]"))]
    (doseq [[da db dc] [[9/8 1/3 5/16] [2/3 3/16 1/6] [3 7/8 1/12]]]
      (let [els [(assoc a :duration da) (assoc b :duration db) (assoc c :duration dc)]]
        (testing (str [da db dc])
          (is (= (shape (#'mt/line-by-text els)) (shape (#'mt/line-native els)))))))))
