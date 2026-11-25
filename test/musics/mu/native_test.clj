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
   differs between two reads, what it holds and where it stands does not. The last link,
   :ROOT's, holds bare values and no points, so where it stands is left out."
  [leaf]
  (let [links (mapv (fn [[ctx off]] [off (count @(:envelopes-atom ctx))]) (:ctx-chain leaf))]
    (if (seq links) (conj (pop links) [:root (second (peek links))]) links)))

(defn- norm
  "x without ids and identities: a leaf as the fields that play and its chain, a container as
   its type, its flags, a name it was given, and its children, ids followed in repo."
  [repo x]
  (cond
    (keyword? x) (norm repo (get repo x))
    (and (map? x) (or (d/bar? x) (d/jump? x))) (select-keys x [:type :count])
    (and (map? x) (#{:PITCHED :REST :DRUM :LEAF} (:type x)))
    [(select-keys x [:type :pitches :duration :articulation :dynamic :tied :modifiers :overrides :program :ratio])
     (chain-shape x)]
    (d/iterator? x) {:type (:type x)
                     :params (select-keys (:params x) [:count :repeat-type])
                     :stats (select-keys x [:pitch-sum :pitch-n])
                     :source (norm repo (:source x))
                     :alternative (some->> (get-in x [:params :alternative]) (norm repo))}
    (map? x) {:type (:type x)
              :name (when (and (:id x) (not (p/auto-id? (:id x))) (not= :GROUP (:type x))) (:id x))
              :flags (select-keys x [:shuffle :rotate :choose :alternate :weights])
              :stats (select-keys x [:pitch-sum :pitch-n])
              :children (mapv #(norm repo %) (:children x))}
    :else x))

(defn- shape
  "A value without ids: its text, its duration, and its nodes as norm gives them."
  [m]
  {:text (p/show m)
   :duration (d/duration (:repo m) (v/node m))
   :tree (norm (:repo m) (v/node m))})

(def ^:private seqs
  ["[c4 d e]" "[c4 r d8 e]" "[c4 <e g> d]" "[c4 d | e f || g]" "[c4~ c d]" "[x4\\38 x x\\36 r]"
   "[c4 d $ e DS f]" "[3:2[c8 d e] f]" "[c4\\vol:90 d-> e8.]" "[c d e f g a b]" "[!mf c4 d]"])

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

(defn- value [text] (mt/read-text text))

(deftest par-of-leaves-and-plain-seqs-is-built-without-text
  (doseq [parts [["[c4 d e]" "[f g a]"]
                 ["[c4 d e]" "[c4 d e]" "[c4 d e]"]
                 ["[c4 d | e f]" "[g2 a]"]
                 ["[c4 [d e] f]" "[g a]"]
                 ["{[c d] [e f]}" "[g a b]"]
                 ["[c4 r d8 <e g>]" "[x4\\38 x]"]
                 ["[c4]"]]]
    (testing (pr-str parts)
      (let [xs (mapv value parts)]
        (is (= (shape (#'mt/par-by-text xs nil)) (shape (#'mt/par-native xs nil))))))))

(deftest par-of-a-leaf-and-seqs
  (let [leaf (first (mt/elements (value "[c4 d]")))
        xs   [leaf (value "[e f]")]]
    (is (= (shape (#'mt/par-by-text xs nil)) (shape (#'mt/par-native xs nil))))))

(deftest par-of-keeps-the-ids-of-its-parts-apart
  (let [xs [(value "[c4 [d e]]") (value "[f [g a]]")]
        m  (#'mt/par-native xs nil)
        ids (keys (v/own-nodes m))]
    (is (= (count ids) (count (set ids))))
    (is (= 5 (count ids)) "two parts, each a seq holding a seq, and the parallel")
    (is (= (p/show (#'mt/par-by-text xs nil)) (p/show m)) "and it reads as the text route's does")))

(deftest par-of-falls-back-to-text-for-what-it-does-not-build
  (let [xs [(value "[!mf c4 d]") (value "[e f]")]]
    (is (not (#'mt/simple? (first xs))) "an instruction")
    (is (= (p/show (#'mt/par-by-text xs nil)) (p/show (mt/par-of xs))))))

(deftest line-of-leaves-and-plain-seqs-and-parallels
  (doseq [parts [["[c4 d]" "[e f]"]
                 ["[c4 d]" "[c4 d]" "[c4 d]"]
                 ["[c4 [d e]]" "[f [g a]]"]
                 ["{[c d] [e f]}" "[g a]"]
                 ["[c4 d | e f]" "[g2 a]"]
                 ["[c4 d e]"]]]
    (testing (pr-str parts)
      (let [xs (mapv value parts)]
        (is (= (shape (#'mt/line-by-text xs)) (shape (#'mt/line-native xs)))))))
  (testing "leaves among them"
    (let [[a b] (mt/elements (value "[c4 d]"))
          xs    [a (value "[e f]") b (value "{[g a] [b c']}")]]
      (is (= (shape (#'mt/line-by-text xs)) (shape (#'mt/line-native xs)))))))

(deftest line-keeps-the-ids-of-its-elements-apart
  (let [xs [(value "[c4 [d e]]") (value "[f [g a]]")]
        m  (#'mt/line-native xs)
        ids (keys (v/own-nodes m))]
    (is (= (count ids) (count (set ids))))
    (is (= (p/show (#'mt/line-by-text xs)) (p/show m)))))

(deftest a-repeat-is-built-without-text
  (doseq [[type n body alt] [[nil 2 "[c4 d]" nil]
                             [nil 3 "[c4 d e f]" nil]
                             [nil :infinite "[c4 d]" nil]
                             [nil 2 "[c4 d]" "[e]"]
                             [nil 2 "[c4 d]" "[[e] [f]]"]
                             [nil 2 "[c4 [d e] f]" "[g a]"]
                             ["tremolo" 8 "[c16 d]" nil]
                             [nil 2 "[c4 d | e f]" nil]]]
    (testing (pr-str [type n body alt])
      (let [b (value body)
            a (some-> alt value)]
        (is (= (shape (#'mt/read-repeat-by-text type n b a)) (shape (#'mt/repeat-native type n b a))))))))

(deftest a-group-is-built-without-text
  (doseq [text ["( c4 d e )" "( c4 d | e )" "( c4 r <e g> )" "( x4\\38 x )"]]
    (testing text
      (let [els (vec (mt/elements (value (str "[" (subs text 2 (- (count text) 2)) "]"))))
            by-text (mt/read-text text)
            native  (mt/group-of (let [m (value (str "[" (subs text 2 (- (count text) 2)) "]"))]
                                   (vec (remove nil? (map #(if (map? %) % nil) (:children (v/node m)))))))]
        (is (= (shape by-text) (shape native)))
        (is (seq els))))))

(deftest a-line-of-groups-is-built-without-text
  (let [g (fn [t] (mt/read-text t))
        xs [(g "( c4 d )") (g "( e f g )") (g "( a )")]]
    (is (= (shape (#'mt/line-by-text xs)) (shape (#'mt/line-native xs))))
    (is (= "[( C4/4 D4 ) ( E4 F4 G4 ) ( A3 )]" (p/show (mt/line xs))))))

(deftest a-seq-marked-to-be-ordered-anew-is-built-without-text
  (doseq [[flags text prefix] [[{:shuffle true} "[c4 d e f]" "shuffle "]
                               [{:rotate 1} "[c4 d e]" "rotate 1 "]
                               [{:rotate -2} "[c4 d e f g]" "rotate -2 "]
                               [{:choose true} "[c4 d e]" "choose "]
                               [{:choose true :weights [3 1]} "[[c d] [e f]]" "choose 3,1 "]
                               [{:alternate true} "[c4 d | e f]" "alternate "]
                               [{:shuffle true} "[c4 [d e] f]" "shuffle "]]]
    (testing (str prefix text)
      (is (= (shape (value (str prefix text))) (shape (mt/marked-seq (value text) flags)))))))

(deftest a-repeat-with-an-ending-that-is-not-plain-goes-through-text
  (is (not (#'mt/simple? (value "[!mf e]"))))
  (is (= (p/show (#'mt/read-repeat-by-text nil 2 (value "[c d]") (value "[!mf e]")))
         (p/show (#'mt/read-repeat nil 2 (value "[c d]") (value "[!mf e]"))))))
