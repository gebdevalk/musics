(ns musics.mu.value
  "mu's musical values: what a literal becomes on the stack, without
   entering the repo.

   The literals of one input are walked together, in order, against the
   current repo (up to each :=, after which the input reads on afresh),
   so a duration carries over and a relative pitch follows the note
   before it; each input starts fresh. A bare leaf becomes the
   leaf itself; any other literal a Material: its id and a repo holding
   it with everything it refers to. Nothing reaches musics.repo here --
   storing is R! (doc/md/mu-repl.md)."
  (:require [clojure.string :as str]
            [musics.domain.context :as c]
            [musics.input.reader.walker :as walker]
            [musics.mu.grammar :as g]))

(defrecord Material [id repo])
;; :base, assoc'd on, is the repo the literal was read against: what the
;; literal itself created or redefined is what differs from it.

(defn material? [x] (instance? Material x))

(defrecord Quotation [body source]
  ;; a quotation is a function of what it is given, so a Clojure function that
  ;; takes a fn (a fitness function, a constraint) takes '( … ) as well: the
  ;; arguments go on a stack of their own, the top of what is left is the result
  clojure.lang.IFn
  (invoke [this a]
    (peek ((requiring-resolve 'musics.mu.eval/call) [a] this)))
  (invoke [this a b]
    (peek ((requiring-resolve 'musics.mu.eval/call) [a b] this)))
  (invoke [this a b c]
    (peek ((requiring-resolve 'musics.mu.eval/call) [a b c] this)))
  (applyTo [this args]
    (peek ((requiring-resolve 'musics.mu.eval/call) (vec args) this))))


(defrecord PcSet [kind items])  ; $[047] classes, $(037) prime form, $<001110> interval vector

(defn pcset? [x] (instance? PcSet x))

(def ^:private pcset-brackets {:classes ["$[" "]"] :prime ["$(" ")"] :vector ["$<" ">"]})

(defn pcset-text
  "A pitch-class set as mu reads it back: its numbers glued together
   with T for 10 and E for 11 ($[047TE]), or apart when one is above 11
   ($<12 12 12 12 12 6>)."
  [{:keys [kind items]}]
  (let [[open close] (pcset-brackets kind)
        glyph (fn [n] (case (long n) 10 "T" 11 "E" (str n)))]
    (str open (if (every? #(<= 0 % 11) items)
                (apply str (map glyph items))
                (str/join " " items))
         close)))

(defrecord Mark [count])  ; | … ||||: a gathering mark, and -|>'s boundary
(def mark (->Mark 1))
(defn mark? [x] (instance? Mark x))

(defn quotation-record?
  "A quotation '( … ) written in mu, as opposed to a Clojure fn standing in for one."
  [x]
  (instance? Quotation x))

(defn quotation?
  "A quotation '( … ), or a Clojure fn standing in for one: it takes the
   top value and leaves its result."
  [x]
  (or (instance? Quotation x) (fn? x)))

(def ^:private leaf-tags #{:Note :Chord :ChordSymbol :Rest :Drum :MultiRest})
(def ^:private literal-tags
  (into leaf-tags #{:Sequence :Parallel :Context  :Data :repeat :RepeatBars :tuplet :shuffle :rotate :choose :alternate}))

(defn literal?
  "Is this parse node a musical literal (a leaf or a composite)?"
  [node]
  (and (vector? node) (contains? literal-tags (first node))))

(defn leaf? [x] (and (map? x) (contains? #{:PITCHED :REST :DRUM :LEAF} (:type x))))

(defn leaf-node? [node] (and (vector? node) (contains? leaf-tags (first node))))

(declare reachable)

(defn- var-map
  "The bindings that hold music as the walker's variables ({name
   {:children :context}}, what \\name splices into a literal), and the
   nodes they need in the repo. A sequence splices its children and
   replays its context, as a text variable does; a bare leaf is itself;
   any other material (a parallel, …) is one reference."
  [bindings]
  (reduce-kv
   (fn [[vm nodes] k x]
     (cond
       (leaf? x)
       [(assoc vm k {:children [x] :context (c/context)}) nodes]
       (instance? Material x)
       (let [n    (get (:repo x) (:id x))
             own  (select-keys (:repo x) (disj (reachable (:repo x) (:id x)) :ROOT))]
         [(assoc vm k (if (= :SEQ (:type n))
                        {:children (:children n) :context (:context n)}
                        {:children [(:id x)] :context (c/context)}))
          (merge nodes own)])
       :else [vm nodes]))
   [{} {}] bindings))

(defn read-literals
  "Walk the literal nodes of one input (text) together against repo and
   auto-ids, with bindings ({name value}, mu's := ) as variables. Returns
   {:values [v …] :ids [id …] :auto-ids …}, one value and the id it was
   read under per node: a leaf map for a bare leaf (its id the one-leaf
   seq parse would wrap it in), a Material for a composite. The auto-ids advance, so a later
   input never reuses an id."
  ([nodes text repo auto-ids] (read-literals nodes text repo auto-ids {}))
  ([nodes text repo auto-ids bindings]
   (if (empty? nodes)
     {:values [] :ids [] :auto-ids auto-ids}
     ;; :base stays the repo without the bound nodes: what a bound name
     ;; brings in is the literal's own, stored with it by R!
     (let [[vm bound] (var-map bindings)
           r    (walker/walk-each nodes text {:repo (merge repo bound) :auto-ids auto-ids :var-map vm})
           tree (:tree r)]
       {:values (mapv (fn [node id]
                        (cond
                          (leaf-node? node) (first (:children (get tree id)))
                          ;; a repeat is read inside a seq of its own;
                          ;; its value is the repeat, held inline there
                          (#{:repeat :RepeatBars} (first node))
                          (let [it (first (:children (get tree id)))]
                            (assoc (->Material (:id it) (assoc tree (:id it) it)) :base repo))
                          :else (assoc (->Material id tree) :base repo)))
                      nodes (:ids r))
        :ids      (:ids r)
        :auto-ids (:auto-ids r)}))))

(defrecord Ref [label get set])
;; A place that holds a value, read with @ and written with ! : label is how it is
;; written (bass/onsets), get a fn of nothing, set a fn of the new value.

(defn ref? [x] (instance? Ref x))
(defn ref-get [r] ((:get r)))
(defn ref-set! [r x] ((:set r) x))

(defn node
  "A Material's own node."
  [m]
  (get (:repo m) (:id m)))

(defn- refs
  "The ids a node refers to: keyword children, and those of inline
   children, an iterator's source and its alternative, an override
   mark's context, recursively."
  [n]
  (letfn [(of [x] (cond (keyword? x) [x]
                        (map? x)     (concat (mapcat of (:children x))
                                             (of (:source x))
                                             (of (get-in x [:params :alternative]))
                                             (when (= :override (:key x)) (of (:ref x))))
                        :else        []))]
    (of (dissoc n :id))))

(defn reachable
  "Every id reachable from id in repo, id included."
  [repo id]
  (loop [todo [id] seen #{}]
    (if-let [k (peek todo)]
      (if (seen k)
        (recur (pop todo) seen)
        (recur (into (pop todo) (refs (get repo k))) (conj seen k)))
      seen)))

(defn own-nodes
  "The nodes a Material's literal created or redefined: reachable from
   its id and different from the repo it was read against."
  [m]
  (let [repo (:repo m) base (:base m {})]
    (into {} (for [k (reachable repo (:id m))
                   :let [n (get repo k)]
                   :when (and n (not= k :ROOT) (not (identical? n (get base k))))]
               [k n]))))


(defn read-domain
  "Walk text made only of literals ([c d] {…} ^{…} …) to the domain,
   nothing stored: the walker's {:tree id->node :ids [id …] :auto-ids …},
   one id per literal. Throws when text can't be read or holds anything
   but literals."
  ([text] (read-domain text nil))
  ([text session]
   (let [tree (g/parse text)]
     (when (g/failure? tree)
       (throw (ex-info (str "can't read this:\n" (with-out-str (print tree))) {:failure tree})))
     (when-let [x (first (remove literal? (rest tree)))]
       (throw (ex-info (str "only literals here, not " (pr-str x)) {:node x})))
     (walker/walk-each (rest tree) text session))))
