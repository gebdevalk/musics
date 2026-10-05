(ns ^:algo musics.algo.tree.builder-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [musics.algo.tree :as t]
            [musics.algo.tree.builder :as b]
            [musics.algo.tree.lib :as lib]))

(defn- plain [d] (str/replace (b/render d) "▸" ""))

(defn- build
  "Place algos one after another, each in the active slot."
  [& shorts]
  (reduce (fn [d s] (b/place d s (:active d))) (b/draft) shorts))

(deftest grows-root-to-leaves
  (let [d (build :zip :pulses->durations :euclid :cycle> :scale)]
    (is (b/complete? d))
    (is (= (t/show (lib/zip (lib/pulses->durations lib/euclid) (lib/cycle> lib/scale)))
           (t/show (b/->tree d))) "the same tree as written by hand")
    (is (= "(zip (pulses->durations (euclid)) (cycle> (scale)))" (plain d)))))

(deftest the-active-slot-moves-to-the-next-hole
  (let [d (build :zip :pulses->durations)]
    (is (= [0 0] (:active d)) "pulses->durations' child")
    (is (= :pulse (b/slot-type d [0 0])))
    (is (= :pitch (b/slot-type d [1])))
    (is (= "(zip (pulses->durations ▸①) ②)" (b/render d)))))

(deftest only-fitting-algos-may-be-placed
  (let [d (build :zip :pulses->durations)]
    (is (b/fits? d [0 0] :euclid))
    (is (not (b/fits? d [0 0] :scale)) "pitches don't fit a grid slot")
    (is (b/fits? d [1] :cycle>) "a :same algo fits by its first input")
    (is (thrown-with-msg? Exception #"wants :pulse" (b/place d :scale [0 0])))
    (let [fit (->> (b/categories d) (filter #(= "rhythmic" (:name %))) first :fit)]
      (is (pos? fit))
      (is (every? :fits? (filter :fits? (b/algos-in d "rhythmic")))))
    (is (not-any? :fits? (b/algos-in d "output")) "nothing in output makes a grid")))

(deftest replace-remove-undo
  (let [d  (build :zip :pulses->durations :euclid :cycle> :scale)
        d2 (b/place d :cantor [0 0])]
    (is (= "(zip (pulses->durations (cantor)) (cycle> (scale)))" (plain d2)) "a node replaced")
    (let [d3 (b/remove d2 [1])]
      (is (= "(zip (pulses->durations (cantor)) ▸①)" (b/render d3)))
      (is (= (b/render d2) (b/render (b/undo d3))) "undo")
      (is (= (b/render d) (b/render (b/undo (b/undo d3))))))))

(deftest literals-and-params-as-children
  (let [d (-> (build :zip :pulses->durations :euclid)
              (as-> d (b/place-literal d [60 64 67] (:active d))))]
    (is (b/complete? d))
    (is (= "(zip (pulses->durations (euclid)) [60 64 67])" (plain d)))
    (is (= 3 (count (t/run (b/->tree d) {})))))
  (let [d (-> (build :transpose) (as-> d (b/place-literal d :nodes (:active d))))]
    (is (= "(transpose :nodes)" (plain d)))))

(deftest edits-an-existing-tree
  (let [tree (lib/zip (lib/pulses->durations (lib/euclid :as :bass)) (lib/cycle> lib/scale))
        d    (b/draft tree)]
    (is (b/complete? d))
    (is (= (t/show tree) (t/show (b/->tree d))) "round trip, :as kept")))

(deftest finalize-returns-tree-and-tctx
  (let [d (build :zip :pulses->durations :euclid :cycle> :scale)
        s (b/settings-for d nil)
        _ (t/setp! s :k 5)
        [tree tctx] (b/finalize d s)]
    (is (= 5 (get-in @tctx [:params :k])) "settings made along the way are kept")
    (is (seq (t/run tree tctx)))))

(deftest the-repl-twin-builds-the-same-tree
  (let [;; numbers are positions in the listings as they stand at each step
        idx  (fn [d c short] (inc (.indexOf ^java.util.List (mapv :short (b/algos-in d c)) short)))
        cidx (fn [d c] (inc (.indexOf ^java.util.List (mapv :name (b/categories d)) c)))
        d0 (b/draft) d1 (b/place d0 :zip []) d2 (b/place d1 :pulses->durations [0])
        d3 (b/place d2 :euclid [0 0]) d4 (b/place d3 :cycle> [1])
        input (str/join "\n" [(cidx d0 "output") (idx d0 "output" :zip)
                              (cidx d1 "bridge") (idx d1 "bridge" :pulses->durations)
                              (cidx d2 "rhythmic") (idx d2 "rhythmic" :euclid)
                              (cidx d3 "tool") (idx d3 "tool" :cycle>)
                              (cidx d4 "sources") (idx d4 "sources" :scale)
                              "k :k 5" "f"])
        [tree tctx] (with-in-str input (binding [*out* (java.io.StringWriter.)] (b/repl-build (b/draft) nil)))]
    (is (= '(zip (pulses->durations (euclid)) (cycle> (scale))) (t/show tree)))
    (is (= 5 (get-in @tctx [:params :k])))))

(deftest the-repl-twin-cancels
  (is (nil? (with-in-str "q" (binding [*out* (java.io.StringWriter.)] (b/repl-build (b/draft) nil))))))

(deftest settings-survive-an-incomplete-moment
  ;; set :k, undo the last step (tree incomplete), redo it: :k stays
  (let [idx  (fn [d c short] (inc (.indexOf ^java.util.List (mapv :short (b/algos-in d c)) short)))
        cidx (fn [d c] (inc (.indexOf ^java.util.List (mapv :name (b/categories d)) c)))
        d4 (reduce (fn [d s] (b/place d s (:active d))) (b/draft) [:zip :pulses->durations :euclid :cycle>])
        input (str/join "\n" ["k :k 5" "u" (cidx d4 "sources") (idx d4 "sources" :scale) "f"])
        start (b/place d4 :scale (:active d4))
        [_ tctx] (with-in-str input (binding [*out* (java.io.StringWriter.)] (b/repl-build start nil)))]
    (is (= 5 (get-in @tctx [:params :k])))))

(deftest undo-and-redo
  (let [d  (build :zip :pulses->durations :euclid :cycle> :scale)
        u3 (-> d b/undo b/undo b/undo)]
    (is (= "(zip (pulses->durations ▸①) ②)" (b/render u3)) "three steps back")
    (is (b/can-redo? u3))
    (is (= (plain d) (plain (-> u3 b/redo b/redo b/redo))) "and forward again")
    (is (not (b/can-redo? (-> u3 b/redo b/redo b/redo))))
    (is (= (plain d) (plain (-> u3 b/redo b/redo b/redo b/redo))) "redo past the end does nothing")
    (let [branched (b/place (b/undo d) :scale (:active (b/undo d)))]
      (is (not (b/can-redo? branched)) "a new edit clears redo"))
    (is (= (plain (b/draft)) (plain (b/undo (b/draft)))) "undo on an empty draft does nothing")
    (is (b/can-undo? (b/remove d [1])) "remove is undoable")
    (is (= "(zip (pulses->durations (euclid)) ①)" (plain (b/redo (b/undo (b/remove d [1])))))
        "... and redo removes again")))

(deftest the-repl-twin-undoes-and-redoes
  (let [start (build :zip :pulses->durations :euclid :cycle> :scale)
        [tree _] (with-in-str "u\nu\ny\ny\nf"
                   (binding [*out* (java.io.StringWriter.)] (b/repl-build start nil)))]
    (is (= '(zip (pulses->durations (euclid)) (cycle> (scale))) (t/show tree)))))

(deftest a-hole-under-a-same-node-takes-the-slots-type
  ;; cycle> is :in [:any] :out :same -- in zip's pitches slot, its own
  ;; child has to give pitches too
  (let [d (build :zip :pulses->durations :euclid :cycle>)]
    (is (= :pitch (b/slot-type d [1 0])))
    (is (b/fits? d [1 0] :scale))
    (is (not (b/fits? d [1 0] :euclid)) "a grid source no longer slips in")
    (is (thrown-with-msg? Exception #"euclid gives :pulse, this slot wants :pitch"
                          (b/place d :euclid [1 0])))))
