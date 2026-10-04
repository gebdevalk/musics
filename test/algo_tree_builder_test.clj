(ns ^:algo algo-tree-builder-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [algo.tree :as t]
            [algo.tree.builder :as b]
            [algo.tree.lib :as lib]))

(defn- plain [d] (str/replace (b/render d) "▸" ""))

(defn- build
  "Place algos one after another, each in the active slot."
  [& shorts]
  (reduce (fn [d s] (b/place d s (:active d))) (b/draft) shorts))

(deftest grows-root-to-leaves
  (let [d (build :notes :gate :euclid :cycled :scale)]
    (is (b/complete? d))
    (is (= (t/show (lib/notes (lib/gate lib/euclid (lib/cycled lib/scale))))
           (t/show (b/->tree d))) "the same tree as written by hand")
    (is (= "(notes (gate (euclid) (cycled (scale))))" (plain d)))))

(deftest the-active-slot-moves-to-the-next-hole
  (let [d (build :notes :gate)]
    (is (= [0 0] (:active d)) "gate's first child")
    (is (= :pulse (b/slot-type d [0 0])))
    (is (= :pitch (b/slot-type d [0 1])))
    (is (= "(notes (gate ▸① ②))" (b/render d)))))

(deftest only-fitting-algos-may-be-placed
  (let [d (build :notes :gate)]
    (is (b/fits? d [0 0] :euclid))
    (is (not (b/fits? d [0 0] :scale)) "pitches don't fit a grid slot")
    (is (b/fits? d [0 1] :cycled) "a :same algo fits by its first input")
    (is (thrown-with-msg? Exception #"wants :pulse" (b/place d :scale [0 0])))
    (let [fit (->> (b/categories d) (filter #(= "rhythmic" (:name %))) first :fit)]
      (is (pos? fit))
      (is (every? :fits? (filter :fits? (b/algos-in d "rhythmic")))))
    (is (not-any? :fits? (b/algos-in d "output")) "nothing in output makes a grid")))

(deftest replace-remove-undo
  (let [d  (build :notes :gate :euclid :cycled :scale)
        d2 (b/place d :cantor [0 0])]
    (is (= "(notes (gate (cantor) (cycled (scale))))" (plain d2)) "a node replaced")
    (let [d3 (b/remove d2 [0 1])]
      (is (= "(notes (gate (cantor) ▸①))" (b/render d3)))
      (is (= (b/render d2) (b/render (b/undo d3))) "undo")
      (is (= (b/render d) (b/render (b/undo (b/undo d3))))))))

(deftest literals-and-params-as-children
  (let [d (-> (build :notes :gate :euclid)
              (as-> d (b/place-literal d [60 64 67] (:active d))))]
    (is (b/complete? d))
    (is (= "(notes (gate (euclid) [60 64 67]))" (plain d)))
    (is (= 3 (count (remove nil? (map :pitches (t/run (b/->tree d) {})))))))
  (let [d (-> (build :transpose) (as-> d (b/place-literal d :nodes (:active d))))]
    (is (= "(transpose :nodes)" (plain d)))))

(deftest edits-an-existing-tree
  (let [tree (lib/notes (lib/gate (lib/euclid :as :bass) (lib/cycled lib/scale)))
        d    (b/draft tree)]
    (is (b/complete? d))
    (is (= (t/show tree) (t/show (b/->tree d))) "round trip, :as kept")))

(deftest finalize-returns-tree-and-tctx
  (let [d (build :notes :gate :euclid :cycled :scale)
        s (b/settings-for d nil)
        _ (t/setp! s :k 5)
        [tree tctx] (b/finalize d s)]
    (is (= 5 (get-in @tctx [:params :k])) "settings made along the way are kept")
    (is (seq (t/run tree tctx)))))

(deftest the-repl-twin-builds-the-same-tree
  (let [;; numbers are positions in the listings as they stand at each step
        idx  (fn [d c short] (inc (.indexOf ^java.util.List (mapv :short (b/algos-in d c)) short)))
        cidx (fn [d c] (inc (.indexOf ^java.util.List (mapv :name (b/categories d)) c)))
        d0 (b/draft) d1 (b/place d0 :notes []) d2 (b/place d1 :gate [0])
        d3 (b/place d2 :euclid [0 0]) d4 (b/place d3 :cycled [0 1])
        input (str/join "\n" [(cidx d0 "output") (idx d0 "output" :notes)
                              (cidx d1 "shape") (idx d1 "shape" :gate)
                              (cidx d2 "rhythmic") (idx d2 "rhythmic" :euclid)
                              (cidx d3 "shape") (idx d3 "shape" :cycled)
                              (cidx d4 "sources") (idx d4 "sources" :scale)
                              "k :k 5" "f"])
        [tree tctx] (with-in-str input (binding [*out* (java.io.StringWriter.)] (b/repl-build (b/draft) nil)))]
    (is (= '(notes (gate (euclid) (cycled (scale)))) (t/show tree)))
    (is (= 5 (get-in @tctx [:params :k])))))

(deftest the-repl-twin-cancels
  (is (nil? (with-in-str "q" (binding [*out* (java.io.StringWriter.)] (b/repl-build (b/draft) nil))))))

(deftest settings-survive-an-incomplete-moment
  ;; set :k, undo the last step (tree incomplete), redo it: :k stays
  (let [idx  (fn [d c short] (inc (.indexOf ^java.util.List (mapv :short (b/algos-in d c)) short)))
        cidx (fn [d c] (inc (.indexOf ^java.util.List (mapv :name (b/categories d)) c)))
        d4 (reduce (fn [d s] (b/place d s (:active d))) (b/draft) [:notes :gate :euclid :cycled])
        input (str/join "\n" ["k :k 5" "u" (cidx d4 "sources") (idx d4 "sources" :scale) "f"])
        start (b/place d4 :scale (:active d4))
        [_ tctx] (with-in-str input (binding [*out* (java.io.StringWriter.)] (b/repl-build start nil)))]
    (is (= 5 (get-in @tctx [:params :k])))))

(deftest undo-and-redo
  (let [d  (build :notes :gate :euclid :cycled :scale)
        u3 (-> d b/undo b/undo b/undo)]
    (is (= "(notes (gate ▸① ②))" (b/render u3)) "three steps back")
    (is (b/can-redo? u3))
    (is (= (plain d) (plain (-> u3 b/redo b/redo b/redo))) "and forward again")
    (is (not (b/can-redo? (-> u3 b/redo b/redo b/redo))))
    (is (= (plain d) (plain (-> u3 b/redo b/redo b/redo b/redo))) "redo past the end does nothing")
    (let [branched (b/place (b/undo d) :scale (:active (b/undo d)))]
      (is (not (b/can-redo? branched)) "a new edit clears redo"))
    (is (= (plain (b/draft)) (plain (b/undo (b/draft)))) "undo on an empty draft does nothing")
    (is (b/can-undo? (b/remove d [0 1])) "remove is undoable")
    (is (= "(notes (gate (euclid) ①))" (plain (b/redo (b/undo (b/remove d [0 1])))))
        "... and redo removes again")))

(deftest the-repl-twin-undoes-and-redoes
  (let [start (build :notes :gate :euclid :cycled :scale)
        [tree _] (with-in-str "u\nu\ny\ny\nf"
                   (binding [*out* (java.io.StringWriter.)] (b/repl-build start nil)))]
    (is (= '(notes (gate (euclid) (cycled (scale)))) (t/show tree)))))

(deftest a-hole-under-a-same-node-takes-the-slots-type
  ;; cycled is :in [:any] :out :same -- in gate's pitches slot, its own
  ;; child has to give pitches too
  (let [d (build :notes :gate :euclid :cycled)]
    (is (= :pitch (b/slot-type d [0 1 0])))
    (is (b/fits? d [0 1 0] :scale))
    (is (not (b/fits? d [0 1 0] :euclid)) "a grid source no longer slips in")
    (is (thrown-with-msg? Exception #"euclid gives :pulse, this slot wants :pitch"
                          (b/place d :euclid [0 1 0])))))
