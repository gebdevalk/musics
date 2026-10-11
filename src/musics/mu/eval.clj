(ns musics.mu.eval
  "mu's evaluator: one input is parsed, its literals read as values (all
   together, so durations and relative pitches carry over within it),
   then its items run in order on the stack (doc/mu-repl.md).

   Words live in namespaces, looked up when run (late binding): a
   redefinition changes every word that uses it. A name is found in the
   namespaces in use; in two of them it is an error until qualified
   (ns/word). An error clears the stack."
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [musics.core :as m]
            [musics.input.reader.builder :as builder]
            [musics.mu.grammar :as g]
            [musics.mu.print :as p]
            [musics.mu.value :as v]
            [musics.repo :as repo]))

;; ------------------------------------------------------------------
;; State: the stack, the namespaces, which are in use, where words go
;; ------------------------------------------------------------------

(defn fresh-state []
  {:stack [] :ns {} :use ["kernel" "tool" "music" "mine"] :in "mine" :bindings {}})

(defonce state (atom (fresh-state)))

(defn mu-error
  "Throw a mu error: its message is what the user sees."
  [& parts]
  (throw (ex-info (str/join " " parts) {:mu/error true})))

;; ------------------------------------------------------------------
;; Words
;; ------------------------------------------------------------------

(defn- leaf-name?
  "Would name, written bare, read as a leaf (a note, rest, drum)?"
  [name]
  (let [r (g/parse name)]
    (and (not (g/failure? r)) (= 1 (count (rest r))) (v/leaf-node? (second r)))))

(defn- check-name!
  "Refuse a word name that is a leaf: bare it would always
   be the note, never the word."
  [name]
  (when (leaf-name? name)
    (mu-error name "is a note (or rest or drum), so it can't be a word -- choose another name")))

(defn define!
  "Make entry the word name in namespace ns. An entry is {:fn (fn
   [stack] stack') :doc …} for a word written in Clojure, or {:body
   nodes :effect … :values …} for one defined with : … ;."
  [ns name entry]
  (swap! state assoc-in [:ns ns name] entry)
  name)

(defn word!
  "Define a mu word written in Clojure: (word! \"kernel\" \"dup\"
   \"( a -- a a )\" (fn [s] (conj s (peek s))))."
  [ns name effect f]
  (define! ns name {:fn f :effect effect}))

(defn need
  "Assert the stack holds at least n items for word."
  [stack n word]
  (when (< (count stack) n)
    (mu-error (str word ":") "needs" n (if (= 1 n) "item" "items") "on the stack, has" (count stack))))

(defn to-mark
  "[below above]: stack split at its nearest | mark, the mark dropped --
   what a gathering word takes. An error if there is none."
  [stack word]
  (let [i (some #(when (v/mark? (nth stack %)) %) (range (dec (count stack)) -1 -1))]
    (when-not i (mu-error (str word ":") "needs a | mark below what it gathers, as | c d e" word))
    [(subvec stack 0 i) (subvec stack (inc i))]))

(defn lookup
  "The entry a name refers to: ns/word directly, else the one in the
   namespaces in use; nil if none, an error if several."
  [st name]
  (if-let [[_ ns w] (re-matches #"([^/]+)/(.+)" name)]
    (get-in st [:ns ns w])
    (let [found (keep (fn [ns] (when-let [e (get-in st [:ns ns name])] [ns e])) (:use st))]
      (case (count found)
        0 nil
        1 (second (first found))
        (mu-error name "is ambiguous, in" (str/join " and " (map first found))
                  "-- write" (str/join " or " (map #(str (first %) "/" name) found)))))))

;; ------------------------------------------------------------------
;; Running items
;; ------------------------------------------------------------------

(declare run-items)

(defn- number-value [tag text]
  (case tag
    :SignedInt   (Long/parseLong (str/replace text #"^\+" ""))
    :SignedFloat (Double/parseDouble text)
    :SignedRatio (let [[n d] (str/split (str/replace text #"^\+" "") #"/")]
                   (/ (Long/parseLong n) (Long/parseLong d)))))

(defn run-word
  "Run the word called name on stack."
  [stack name]
  (let [entry (or (lookup @state name) (mu-error "unknown word:" name))]
    (if-let [f (:fn entry)]
      (f stack)
      (let [ins    (get-in entry [:effect :in])
            _      (need stack (count ins) name)
            n      (- (count stack) (count ins))
            locals (zipmap ins (subvec stack n))]
        (run-items (subvec stack 0 n) (:body entry) {:locals locals :values (:values entry)})))))

(defn call
  "Run a quotation on stack."
  [stack q]
  (when-not (v/quotation? q) (mu-error "call: expected a quotation '( … ), got" (p/show q)))
  (run-items stack (:body q) (:env q)))

(defn- node-name [node]
  (let [x (second node)]
    (if (vector? x) (second x) x)))

(defn- run-item
  "Run one parse node on stack, in env ({:locals :values}): values maps a
   literal node to the value it was read as."
  [stack node env]
  (case (first node)
    (:SignedInt :SignedFloat :SignedRatio) (conj stack (number-value (first node) (second node)))
    :StringLit (conj stack (second node))
    :Mark      (conj stack v/mark)
    :IdName    (conj stack (keyword (second node)))
    :Local     (let [n (second node)]
                 (if (contains? (:locals env) n)
                   (conj stack (get (:locals env) n))
                   (mu-error (str "$" n) "is not a local here")))
    :Quotation (conj stack (assoc (v/->Quotation (vec (rest node)) (:source (meta node)))
                                  :env env))
    :LooseInstruction
    (mu-error "an instruction needs a container: write [" (str (:source (meta node)) " …]")
              "or give a context, ^{" (str (:source (meta node))) "}")
    :QuotedName (conj stack (symbol (node-name node)))
    :VarRef    (let [n (node-name node) b (:bindings @state)]
                 (if (contains? b n)
                   (conj stack (get b n))
                   (mu-error (str "\\" n ":") "nothing is bound to" n "-- bind it with" (str "'" n) "value =")))
    :Definition (let [[_ name-node & more] node
                      [effect body] (if (= :StackEffect (ffirst more)) [(first more) (rest more)] [nil more])
                      ins  (some->> effect rest (some #(when (= :EffectIn (first %)) %)) rest vec)
                      outs (some->> effect rest (some #(when (= :EffectOut (first %)) %)) rest vec)]
                  (check-name! (second name-node))
                  (define! (:in @state) (second name-node)
                           {:body (vec body) :effect (when effect {:in ins :out outs})
                            :values (:values env) :source (:source (meta node))})
                  stack)
    :Word (run-word stack (node-name node))
    ;; a literal: the value it was read as, with the input
    (if-let [i (:mu/index (meta node))]
      (conj stack (nth (:values env) i))
      (mu-error "can't run" (pr-str (first node))))))

(defn run-items [stack nodes env]
  (reduce (fn [s node] (run-item s node env)) stack nodes))

;; ------------------------------------------------------------------
;; One input
;; ------------------------------------------------------------------

(defn- annotate
  "The parse tree with each node's source text in its metadata (for
   quotations, definitions and messages), and each musical literal --
   not descended into -- numbered in reading order (:mu/index). Returns
   [tree literals]: the annotated tree and its literal nodes, in order."
  [tree text]
  (let [lits (atom [])
        src  (fn [x] (if-let [[s e] (insta/span x)] (vary-meta x assoc :source (subs text s e)) x))
        walk (fn walk [x]
               (cond
                 (v/literal? x) (let [x' (vary-meta (src x) assoc :mu/index (count @lits))]
                                  (swap! lits conj x')
                                  x')
                 (and (vector? x) (keyword? (first x)))
                 (src (with-meta (into [(first x)] (map walk (rest x))) (meta x)))
                 :else x))
        tree' (walk tree)]
    [tree' @lits]))

(defn- refs-in
  "The names \\name refers to inside the literal nodes."
  [lits]
  (set (for [l lits x (tree-seq vector? seq l)
             :when (and (vector? x) (= :VarRef (first x)))]
         (second (second x)))))

(defn- check-refs!
  "Refuse a \\name in a literal that this same input binds (its literals
   are read before = runs), or that holds no music."
  [tree lits]
  (let [refs  (refs-in lits)
        bound (set (for [x (tree-seq vector? seq tree)
                         :when (and (vector? x) (= :QuotedName (first x)))]
                     (node-name x)))
        b     (:bindings @state)]
    (doseq [n refs]
      (cond
        (bound n) (mu-error (str "\\" n ":") "is bound in this input; its literals are read before = runs,"
                            "so use it in the next input")
        (not (contains? b n)) (mu-error (str "\\" n ":") "nothing is bound to" n "-- bind it with" (str "'" n) "value =")
        (not (or (v/material? (b n)) (v/leaf? (b n))))
        (mu-error (str "\\" n ":") "holds" (p/show (b n)) "-- only music goes in a literal")))))

(defn current-repo
  "What is stored now, as literals are read against it: with a :ROOT
   even before anything is stored."
  []
  (let [current (into {} @(repo/registry))]
    (if (:ROOT current) current (merge (:repo (builder/empty-session)) current))))

(defn read-input
  "Parse text: {:items [...] :values [value …]} (values indexed by each
   literal's :mu/index), or {:incomplete true}, or {:error message}."
  [text]
  (let [tree (g/parse text)]
    (cond
      (and (g/failure? tree) (g/incomplete? text)) {:incomplete true}
      (g/failure? tree) {:error (str "can't read this:\n" (str/trim (with-out-str (print tree))))}
      :else
      (let [[tree' lits] (annotate tree text)
            _       (check-refs! tree' lits)
            current (current-repo)
            {:keys [values auto-ids]} (v/read-literals lits text current (:auto-ids @m/session) (:bindings @state))]
        (swap! m/session assoc :auto-ids auto-ids)
        {:items (vec (rest tree')) :values values}))))

(defn eval-input
  "Evaluate one input. Returns {:ok stack}, {:incomplete true}, or
   {:error message} -- after which the stack is empty. Nothing is
   printed: as in Forth, only words that print do."
  [text]
  (let [r (try (read-input text) (catch Exception e {:error (.getMessage e)}))]
    (cond
      (:incomplete r) r
      (:error r)      (do (swap! state assoc :stack []) r)
      :else
      (try
        (let [s (run-items (:stack @state) (:items r) {:locals {} :values (:values r)})]
          (swap! state assoc :stack s)
          {:ok s})
        (catch clojure.lang.ExceptionInfo e
          (when (:mu/bye (ex-data e)) (throw e))
          (swap! state assoc :stack [])
          {:error (.getMessage e)})
        (catch Exception e
          (swap! state assoc :stack [])
          {:error (str (.getSimpleName (class e)) ": " (.getMessage e))})))))
