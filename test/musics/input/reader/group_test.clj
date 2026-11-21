(ns ^:parsing musics.input.reader.group-test
  "( … ) inside a sequence: notes grouped inline, no id, own instructions."
  (:require [clojure.test :refer [deftest is testing]]
            [musics.domain :as d]
            [musics.mu.eval :as e]
            [musics.mu.material :as material]
            [musics.mu.print :as print]
            [musics.mu.store :as store]
            [musics.mu.value :as v]
            [musics.persist :as persist]
            [musics.repo :as repo]
            [musics.test-support :refer [with-fresh-session]]))

(defn- seq-of
  "The one seq text reads: [tree seq-node]."
  [text]
  (let [{:keys [tree root-id]} (v/read-domain text)
        root (get tree root-id)
        id   (first (:children root))]
    [tree (get tree id)]))

(defn- value-of [text]
  (let [r (e/eval-input text)]
    (when (:error r) (throw (ex-info (:error r) {})))
    (peek (:ok r))))

(defn- pitches [node]
  (vec (mapcat (fn [c] (if (d/container? c) (pitches c) (:pitches c))) (:children node))))

(deftest a-group-is-an-inline-child
  (with-fresh-session
    (let [[tree s] (seq-of "[a: c4 ( d e ) f ]")
          [_ g]  (:children s)]
      (is (= :GROUP (:type g)))
      (is (nil? (:id g)) "no id")
      (is (map? g) "the container itself is the child, not a keyword")
      (is (= #{} (set (filter #(= :GROUP (:type %)) (vals tree)))) "nothing of it in the store")
      (is (= [62 64] (pitches g)))
      (is (= 1 (d/duration tree s)) "as long as the notes spliced")
      (is (= 1/2 (d/duration tree g))))))

(deftest a-group-takes-no-id-and-needs-an-element
  (is (thrown? Exception (value-of "[ ( a: c d ) ]")))
  (is (thrown? Exception (value-of "[ ( ) c ]")) "an empty group is nothing")
  (is (thrown? Exception (value-of "[ [ ( c ) ] ( ] ")) "unclosed"))

(deftest groups-nest
  (with-fresh-session
    (let [[_ s] (seq-of "[ ( c ( d e ) ) f ]")
          [g] (:children s)]
      (is (= :GROUP (:type g)))
      (is (= :GROUP (:type (second (:children g)))))
      (is (= [60 62 64] (pitches g))))))

(deftest a-groups-instructions-apply-to-it-alone
  (with-fresh-session
    (let [notes (->> (material/events (value-of "[ !mf c4 ( !pp d e ) f ]"))
                     (filter #(= :note (:kind %))))]
      (is (= [76 38 38 76] (map :velocity notes))
          "pp inside the group, mf again after it"))))

(deftest a-group-keeps-the-context-of-its-sequence
  (with-fresh-session
    (let [notes (->> (material/events (value-of "[ !mf c4 ( d e ) f ]"))
                     (filter #(= :note (:kind %))))]
      (is (= [76 76 76 76] (map :velocity notes)) "the group sets nothing, so mf holds inside it"))))

(deftest a-group-signals-nothing
  (with-fresh-session
    (let [sections (->> (material/events (value-of "[ c4 ( d e ) f ]"))
                        (filter #(= :section (:kind %))))]
      (is (every? :id sections) "only containers with an id are signalled")
      (is (= #{:SEQ} (set (map :type sections)))))))

(deftest a-group-plays-like-its-notes
  (with-fresh-session
    (let [times (fn [text] (->> (material/events (value-of text))
                                (filter #(= :note (:kind %)))
                                (map (juxt :onset :t))))]
      (is (= (times "[ c4 d e f ]") (times "[ c4 ( d e ) f ]"))))))

(deftest pitch-commands-reach-into-a-group
  (with-fresh-session
    (is (= [65 67 69 70] (pitches (second (seq-of "[ transpose 5 ( c4 ( d e ) f ) ]")))))
    (testing "inversion and mirror give the pitches a flat seq gives"
      (is (= (pitches (second (seq-of "[ inversion ( c4 d e f ) ]")))
             (pitches (second (seq-of "[ inversion ( c4 ( d e ) f ) ]")))))
      (is (= (pitches (second (seq-of "[ mirror ( c4 d e f ) ]")))
             (pitches (second (seq-of "[ mirror ( c4 ( d e ) f ) ]"))))))))

(deftest order-commands-move-a-group-as-one-unit
  (with-fresh-session
    (is (= [65 62 64 60] (pitches (second (seq-of "[ retrograde ( c4 ( d e ) f ) ]"))))
        "the group moves, its notes keep their order")
    (is (= [55 58 56 60] (pitches (second (seq-of "[ retrograde-inversion ( c4 ( d e ) f ) ]"))))
        "inverted, then moved as units")))

(deftest a-tuplet-scales-a-group-inside-it
  (with-fresh-session
    (let [[tree s] (seq-of "[ 3:2( c8 ( d e ) ) ]")]
      (is (= 1/12 (d/duration tree (first (:children s))))))))

(deftest a-group-prints-and-reads-back
  (with-fresh-session
    (let [text (print/show (value-of "[ c4 ( d8 e !pp ( f g ) ) a ]"))]
      (is (= "[C4/4 ( D4/8 E4 !pp ( F4 G4 ) ) A4]" text))
      (is (= text (print/show (value-of text))) "reads back as it prints"))))

(deftest a-stored-group-survives-write-and-load
  (with-fresh-session
    (store/read! "[g: !mf c4 ( !pp d e ) f ]")
    (let [f      (java.io.File/createTempFile "group" ".edn")
          velocs #(->> (material/events (v/->Material :g @(repo/registry)))
                       (filter (fn [e] (= :note (:kind e)))) (mapv :velocity))
          before (velocs)]
      (try
        (persist/write (.getPath f))
        (store/reset-all!)
        (persist/load (.getPath f))
        (is (= [76 38 38 76] before))
        (is (= before (velocs)) "same sound after the round trip")
        (is (some #(= :GROUP (:type %)) (:children (get @(repo/registry) :g))) "still an inline group")
        (finally (.delete f))))))

(deftest words-take-a-group-as-one-element
  (with-fresh-session
    (is (= 3 (value-of "[ c4 ( d e ) f ] length")))
    (is (= "[C4/4 ( D4 E4 )]" (print/show (value-of "[ c4 ( d e ) f ] 2 take"))))
    (is (= "[F4/4 ( D4 E4 ) C4]" (print/show (value-of "[ c4 ( d e ) f ] [R]")))
        "[R] moves a group, it does not turn it round")
    (is (= "[C4/4 ( A#3 G#3 ) G3]" (print/show (value-of "[ c4 ( d e ) f ] [I]")))
        "[I] and [M] change the notes inside a group")))
